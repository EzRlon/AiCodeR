package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 冷启动孤儿进程回收的底层件。设计见 `ai-config/notes/lmk-orphan-reclaim-design-2026-11-15.md`。
 *
 * 为什么需要：App 被 LMK 时来不及执行 finally，`proot → claude/codex/opencode` 整棵树会被 init
 * 收养并继续跑；下次打开 App 又起一份，同一会话两个进程并行施工。这里把 spawn 到的 PID 落盘，
 * 冷启动按 PID + `/proc` 启动时间双校验后回收。
 *
 * 安全原则：**宁可漏杀，不可错杀**。PID 复用、非 proot 进程、当前进程自己登记的记录一律不动。
 */

/** 一条落盘的引擎进程记录；`startTicks` 是 `/proc/<pid>/stat` 第 22 字段，用于识别 PID 复用。 */
@Serializable
internal data class EngineProcessRecord(
    val pid: Int,
    val startTicks: Long,
    val engine: String? = null,
    /** 写入该记录的 App 进程标识：冷启动只回收其它 runId 的记录，避免误杀本进程新起的回合。 */
    val runId: String,
    val createdAt: Long
)

/** `/proc/<pid>/stat` 里回收需要的字段。 */
internal data class ProcStat(
    val pid: Int,
    val ppid: Int,
    val startTicks: Long,
    /** 单字符状态；`Z` 为僵尸（已退出，等待父进程收割）。 */
    val state: Char
)

/** `/proc` 访问与信号通道的抽象：生产实现读真实 /proc，单测注入假实现。 */
internal interface ProcessProbe {
    fun stat(pid: Int): ProcStat?

    fun cmdline(pid: Int): List<String>

    /** 进程环境变量（NUL 分隔后的非空项）；用于按 `PERMISSION_BRIDGE_SESSION` 精确认领子进程。 */
    fun environ(pid: Int): List<String>

    /** `/proc/<pid>` 是否存在：用来区分「进程已退出」与「stat 读失败（瞬时错误）」。 */
    fun exists(pid: Int): Boolean

    fun allPids(): List<Int>

    fun signal(pid: Int, signal: Int)
}

/**
 * 解析 `/proc/<pid>/stat` 单行内容。
 *
 * 第 2 字段 `comm` 可能含空格与括号，必须按**最后一个** `)` 切分；切分后第一个字段是状态字符
 * （第 3 字段），故 ppid（第 4 字段）在 index 1、starttime（第 22 字段）在 index 19。
 */
internal fun parseProcStat(raw: String): ProcStat? {
    val close = raw.lastIndexOf(')')
    if (close <= 0) return null
    val pid = raw.substring(0, close).trim().substringBefore(' ').toIntOrNull() ?: return null
    val fields = raw.substring(close + 1).trim().split(' ')
    if (fields.size < 20) return null
    return ProcStat(
        pid = pid,
        ppid = fields[1].toIntOrNull() ?: return null,
        startTicks = fields[19].toLongOrNull() ?: return null,
        state = fields[0].firstOrNull() ?: '?'
    )
}

/** 生产实现：直接读 `/proc`；信号走 `android.os.Process.sendSignal`（API 24+）。 */
internal class ProcFsProbe : ProcessProbe {

    override fun stat(pid: Int): ProcStat? = runCatching {
        parseProcStat(File("/proc/$pid/stat").readText())
    }.getOrNull()

    override fun cmdline(pid: Int): List<String> = runCatching {
        File("/proc/$pid/cmdline").readBytes()
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    override fun environ(pid: Int): List<String> = runCatching {
        File("/proc/$pid/environ").readBytes()
            .toString(Charsets.UTF_8)
            .split('\u0000')
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    override fun exists(pid: Int): Boolean = File("/proc/$pid").exists()

    override fun allPids(): List<Int> =
        File("/proc").list()?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    override fun signal(pid: Int, signal: Int) {
        runCatching { android.os.Process.sendSignal(pid, signal) }
            .onFailure { FileLogger.w(TAG, "发信号失败: pid=$pid sig=$signal", it) }
    }

    private companion object {
        const val TAG = "ProcFsProbe"
    }
}

/** `engine-processes.json` 的读写：原子替换，损坏时降级为空（下次重新登记）。 */
internal class EngineProcessFile(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): Map<String, EngineProcessRecord> = runCatching {
        if (!file.exists() || file.length() == 0L) emptyMap()
        else json.decodeFromString<Map<String, EngineProcessRecord>>(file.readText())
    }.onFailure { FileLogger.w(TAG, "读取引擎进程记录失败，按空处理", it) }
        .getOrDefault(emptyMap())

    @Synchronized
    fun put(sessionId: String, record: EngineProcessRecord) {
        write(load() + (sessionId to record))
    }

    /**
     * 删除会话记录；[expectedPid] 非空时只在记录仍属于该 PID 时删除。
     * 删除是异步排队的，若期间同会话已拉起新进程并写入新记录，无条件删会把新记录抹掉。
     */
    @Synchronized
    fun remove(sessionId: String, expectedPid: Int? = null) {
        val current = load()
        val stored = current[sessionId] ?: return
        if (expectedPid != null && stored.pid != expectedPid) return
        write(current - sessionId)
    }

    /** 原子地只保留满足 [predicate] 的记录；避免“先 load 再写回”期间丢并发新写入的记录。 */
    @Synchronized
    fun retainWhere(predicate: (sessionId: String, record: EngineProcessRecord) -> Boolean) {
        val current = load()
        val keep = current.filter { (sessionId, record) -> predicate(sessionId, record) }
        if (keep.size != current.size) write(keep)
    }

    private fun write(map: Map<String, EngineProcessRecord>) {
        runCatching {
            if (map.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(map))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }.onFailure { FileLogger.w(TAG, "写入引擎进程记录失败", it) }
    }

    private companion object {
        const val TAG = "EngineProcessFile"
    }
}

/** 单条记录的回收结论，供日志与单测断言。 */
internal data class ReclaimResult(
    val sessionId: String,
    val pid: Int,
    val outcome: String
) {
    companion object {
        const val KILLED = "killed"
        const val ALREADY_EXITED = "already-exited"
        const val PID_REUSED = "pid-reused"
        const val NOT_PROOT = "not-proot"
        const val CURRENT_PROCESS = "current-process"

        /** `/proc/<pid>` 还在但 stat 读失败（瞬时权限/资源错误）：保留记录，下次重试。 */
        const val UNREADABLE = "stat-unreadable"
    }
}

/**
 * 纯回收逻辑：校验 → 后序 SIGTERM → 宽限 → 二次扫描 SIGKILL。
 *
 * [graceMs] 为 SIGTERM 后等待进程自行退出的宽限期；单测传 0 避免真等待。
 */
internal class EngineOrphanReclaimer(
    private val probe: ProcessProbe,
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val sleep: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }
) {

    /**
     * 批量回收：一次 `/proc` 全表快照共享给所有记录，所有树统一 SIGTERM、只等**一次**宽限、
     * 再统一 SIGKILL（此前每条记录各扫一遍全表、各等 800ms，冷启动耗时线性叠加）。
     *
     * 宽限后的 SIGKILL 只对第一轮快照里「startTicks 未变且非僵尸」的 PID 发，避免根退出后
     * PID 被同 uid 复用导致误杀（qa P1-1）。
     */
    fun reclaim(
        records: Map<String, EngineProcessRecord>,
        currentRunId: String
    ): List<ReclaimResult> {
        if (records.isEmpty()) return emptyList()
        val results = mutableListOf<ReclaimResult>()
        // 一次全表快照（含僵尸：僵尸要能如实判为已退出）
        val snapshot = probe.allPids().mapNotNull { probe.stat(it) }
        val byPid = snapshot.associateBy { it.pid }
        val alive = snapshot.filter { it.state != 'Z' }
        val byParent = alive.groupBy { it.ppid }

        data class Plan(val sessionId: String, val tree: List<ProcStat>)
        val plans = mutableListOf<Plan>()

        for ((sessionId, record) in records) {
            if (record.runId == currentRunId) {
                results += ReclaimResult(sessionId, record.pid, ReclaimResult.CURRENT_PROCESS)
                continue
            }
            val stat = byPid[record.pid]
            if (stat == null) {
                // /proc 目录还在但 stat 读不到 = 瞬时错误，保留记录下次重试；目录没了才是真退出。
                val outcome = if (probe.exists(record.pid)) ReclaimResult.UNREADABLE else ReclaimResult.ALREADY_EXITED
                results += ReclaimResult(sessionId, record.pid, outcome)
                continue
            }
            if (stat.state == 'Z') {
                results += ReclaimResult(sessionId, record.pid, ReclaimResult.ALREADY_EXITED)
                continue
            }
            // starttime 与登记值不一致 = 原进程已退出且 PID 被复用，绝不能碰。
            if (stat.startTicks != record.startTicks) {
                results += ReclaimResult(sessionId, record.pid, ReclaimResult.PID_REUSED)
                continue
            }
            if (!probe.cmdline(record.pid).firstOrNull().orEmpty().let(::isProotExecutable)) {
                results += ReclaimResult(sessionId, record.pid, ReclaimResult.NOT_PROOT)
                continue
            }
            val tree = liveTree(record.pid, alive, byParent)
            if (tree.isEmpty()) {
                results += ReclaimResult(sessionId, record.pid, ReclaimResult.ALREADY_EXITED)
                continue
            }
            plans += Plan(sessionId, tree)
        }

        if (plans.isNotEmpty()) {
            // 后代先退（叶→根）：先 SIGKILL proot 会让 tracee 成为孤儿，--kill-on-exit 也无从执行。
            plans.forEach { plan -> plan.tree.asReversed().forEach { probe.signal(it.pid, SIGTERM) } }
            sleep(graceMs)
            plans.forEach { plan ->
                plan.tree.forEach { before ->
                    val now = probe.stat(before.pid) ?: return@forEach
                    if (now.state != 'Z' && now.startTicks == before.startTicks) {
                        probe.signal(before.pid, SIGKILL)
                    }
                }
                results += ReclaimResult(plan.sessionId, plan.tree.first().pid, ReclaimResult.KILLED)
            }
        }
        return results
    }

    /** 以 [rootPid] 为根的存活进程快照（根在前、后序遍历顺序）；僵尸与非存活进程已剔除。 */
    private fun liveTree(rootPid: Int, alive: List<ProcStat>, byParent: Map<Int, List<ProcStat>>): List<ProcStat> {
        // 根必须是存活的那一个：根已退出时后代会被 init 收养，无法再证明同源，按「宁可漏杀」放弃。
        val root = alive.firstOrNull { it.pid == rootPid } ?: return emptyList()
        val ordered = mutableListOf(root)
        val seen = mutableSetOf(rootPid)
        val queue = ArrayDeque<Int>().apply { add(rootPid) }
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            byParent[parent].orEmpty().forEach { child ->
                if (seen.add(child.pid)) {
                    ordered += child
                    queue += child.pid
                }
            }
        }
        return ordered
    }

    private fun isProotExecutable(arg: String) = isProotExecutableArg(arg)

    companion object {
        private const val DEFAULT_GRACE_MS = 800L
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}

/**
 * App 的 proot 以 jniLib 形式存在（`…/lib/arm64/libproot.so`）；部分环境也可能直接叫 `proot`。
 * 只匹配 cmdline 首参，避免把参数里的字符串当可执行文件。
 */
internal fun isProotExecutableArg(arg: String): Boolean {
    val name = arg.substringAfterLast('/')
    return name.startsWith("libproot") || name == "proot"
}

/**
 * 认领「刚 spawn 的 CLI proot 子进程 PID」。
 *
 * Android 公共 SDK 的 `Process` 没有 `pid()`，所以从 `/proc` 反查：proot 是本 App 进程的直接
 * 子进程，三桥 spawn 时都注入 `PERMISSION_BRIDGE_SESSION=<sessionId>`，优先按它精确匹配。
 *
 * 兜底策略刻意保守（宁可漏杀不可错杀）：
 * - 只在「同引擎 + 未认领 + 候选唯一」时采用，避免并发首回合认成别的会话的 proot；
 * - 已认领的 PID 绝不重复认领（否则会把旧 pid 记到新回合上，冷启动漏杀）；
 * - 拿不到就返回 null（不落盘），下次冷启动按真实性校验兜底。
 */
internal class CliChildClaimer(
    private val probe: ProcessProbe,
    private val selfPid: () -> Int,
    private val claimedPids: () -> Set<Int> = { emptySet() }
) {

    fun claim(sessionId: String, engine: String?): Int? {
        val myPid = selfPid()
        val marker = "PERMISSION_BRIDGE_SESSION=$sessionId"
        val candidates = probe.allPids().asSequence()
            .filter { it != myPid }
            .filter { probe.stat(it)?.ppid == myPid }
            .filter { pid ->
                val argv = probe.cmdline(pid)
                isProotExecutableArg(argv.firstOrNull().orEmpty()) &&
                    (engine == null || isEngineInvocation(argv, engine))
            }
            .toList()
        val matched = candidates.filter { pid -> probe.environ(pid).any { it == marker } }
            .filter { it !in claimedPids() }
        matched.maxByOrNull { probe.stat(it)?.startTicks ?: 0L }?.let { return it }
        val unclaimed = candidates.filter { it !in claimedPids() }
        if (unclaimed.size != 1) return null
        return unclaimed.single()
    }

    /** cmdline 形如 `… /bin/sh -c 'exec "$0" "$@"' <program> …`；取 program 位精确比对引擎名。 */
    fun isEngineInvocation(argv: List<String>, engine: String): Boolean {
        val shellAt = argv.indexOf("/bin/sh")
        return shellAt >= 0 && argv.getOrNull(shellAt + 3) == engine
    }
}
