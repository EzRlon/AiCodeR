package com.aicode.feature.agent.domain.engine

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外部引擎 CLI 进程的按会话登记表。
 *
 * 为什么需要它：桥里的 `process.destroy()` 挂在协程完成回调上，而 CLI 回合常阻塞在
 * `readLine()`；用户点停止或 Activity 销毁时协程可能迟迟不完成，旧 `claude --resume`
 * 进程会继续活着。紧接着队列又拉起下一轮，同一会话就出现两个 CLI 进程并行施工。
 *
 * 这里提供两条回收通道：
 * 1. **进程内**：按 sessionId 立即杀，关闭三路流解除读阻塞，先 SIGTERM，1.5 秒后仍在则
 *    `destroyForcibly`；
 * 2. **跨进程（冷启动）**：spawn 成功即把 `pid + /proc 启动时间 + runId` 落盘到
 *    `filesDir/engine-processes.json`；App 被 LMK 后的下一次启动由
 *    [reclaimOrphansOnColdStart] 校验后回收整棵 proot 进程树（见 [EngineOrphanReclaimer]）。
 */
@Singleton
class EngineProcessRegistry @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "EngineProcessRegistry"
        const val FORCE_KILL_DELAY_MS = 1500L
        const val PERSIST_FILE = "engine-processes.json"
    }

    private val processes = ConcurrentHashMap<String, Process>()

    /** 每份 `/proc` 都按需读取；不在 Android 上时（单测）由 [EngineOrphanReclaimer] 的假实现替代。 */
    private val probe: ProcessProbe = ProcFsProbe()

    private val recordFile = EngineProcessFile(File(context.filesDir, PERSIST_FILE))

    /** 当前 App 进程标识：落盘记录带上它，冷启动只回收别的进程留下的记录，避免竞态误杀。 */
    private val runId = UUID.randomUUID().toString()

    private val reclaimer = EngineOrphanReclaimer(probe)

    private val reclaimed = AtomicBoolean(false)

    /** 落盘写走单线程：kill/unregister 可能从主线程触发，避免主线程 IO。 */
    private val persistence = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "engine-pid-persist").apply { isDaemon = true }
    }

    /** 会话 → 已认领的宿主 PID；unregister/kill 用它精确删除记录。 */
    private val sessionPids = ConcurrentHashMap<String, Int>()

    /** 已归属某个会话的 proot 子进程 PID，兜底认领时排除，避免抢别的会话的进程。 */
    private val claimedPids = ConcurrentHashMap.newKeySet<Int>()

    /** 登记刚拉起的 CLI 进程；同会话若已有旧进程，先杀掉再登记，绝不并存。 */
    fun register(sessionId: String, process: Process, engine: String? = null) {
        // 冷启动回收可能尚未跑完（Application 里是异步的）：先同步回收**同会话**的旧记录，
        // 否则本次 register 覆盖它后，旧 proot 的 PID 就永久失去线索（qa P2-6）。
        // 只处理同会话那条：无旧记录时零开销，避免把全量回收的宽限 sleep 压到首个回合的 spawn 上（optimizer P1-1）。
        reclaimPreviousForSession(sessionId)
        val previous = processes.put(sessionId, process)
        if (previous != null && previous !== process) {
            FileLogger.w(TAG, "同会话存在旧 CLI 进程，先回收: sid=$sessionId")
            // 旧进程的 PID 还挂在 sessionPids/claimedPids 上：先摘掉，避免接替回合把它当「未认领」抢回来。
            sessionPids.remove(sessionId)?.let { claimedPids.remove(it) }
            killProcess(previous, sessionId)
        }
        persist(sessionId, engine)
    }

    /** 回合正常收尾时移除登记；只移除自己那一个，避免误删接替回合的进程。 */
    fun unregister(sessionId: String, process: Process) {
        if (processes.remove(sessionId, process)) {
            forgetPersisted(sessionId)
        }
    }

    /** 立即终止指定会话的 CLI 进程（停止按钮 / Activity 销毁）。 */
    fun kill(sessionId: String) {
        processes.remove(sessionId)?.let { process ->
            forgetPersisted(sessionId)
            killProcess(process, sessionId)
        }
    }

    /**
     * 只终止「确实是这一个」的 CLI 进程（回合完成回调用）。
     *
     * 完成回调可能晚于接替回合的登记，按对象身份比较，避免旧回合结束把新进程杀掉（qa P2-6）。
     */
    fun kill(sessionId: String, expectedProcess: Process) {
        if (processes.remove(sessionId, expectedProcess)) {
            forgetPersisted(sessionId)
            killProcess(expectedProcess, sessionId)
        }
    }

    /**
     * 删除该会话的落盘记录。
     *
     * 认领不到 PID 时**不删**：留一条旧记录，冷启动会按 startTicks 判为已退出/被复用，
     * 代价只是多查一次 `/proc`；而无条件删可能抹掉接替回合刚写入的新记录（qa P1-4）。
     */
    private fun forgetPersisted(sessionId: String) {
        val pid = sessionPids.remove(sessionId)
        if (pid == null) {
            FileLogger.w(TAG, "未认领会话 PID，保留落盘记录待冷启动校验: sid=$sessionId")
            return
        }
        claimedPids.remove(pid)
        persistence.execute { recordFile.remove(sessionId, pid) }
    }

    /**
     * 该会话是否仍有 CLI 进程存活（含回合已结束但后台子代理仍在跑的残留进程）。
     *
     * 队列消费用它做闸门：旧进程没退就绝不起新回合，否则同会话两份 `--resume` 并行，
     * 引擎会报「另一个进程正在处理同一内容」。
     */
    fun isActive(sessionId: String): Boolean =
        processes[sessionId]?.isAlive == true

    /** 终止所有登记进程（App 退出/停止全部会话）。 */
    fun killAll() {
        processes.keys.toList().forEach { kill(it) }
    }

    /**
     * 冷启动回收 App 被 LMK 后遗留的孤儿 CLI 进程树。
     *
     * 只执行一次；只动「其它 App 进程 runId」的记录，且必须通过 PID + `/proc` 启动时间 + cmdline
     * 三重校验，宁可漏杀不可错杀。阻塞在 IO 线程执行（含 800ms 宽限），调用方放在后台协程。
     */
    internal fun reclaimOrphansOnColdStart(): List<ReclaimResult> {
        if (!reclaimed.compareAndSet(false, true)) return emptyList()
        val records = recordFile.load()
        if (records.isEmpty()) return emptyList()
        val results = try {
            reclaimer.reclaim(records, runId)
        } catch (t: Throwable) {
            // 回收本身失败时保留全部记录，下次冷启动再试；不能把安全网一起删掉。
            FileLogger.w(TAG, "冷启动回收孤儿进程失败，保留记录待下次", t)
            return emptyList()
        }
        // 删除「已确认终态」的老记录；NOT_PROOT / UNREADABLE 可能只是 /proc 瞬时读取失败，保留待下次重试。
        val retrySessions = results.asSequence()
            .filter { it.outcome == ReclaimResult.NOT_PROOT || it.outcome == ReclaimResult.UNREADABLE }
            .map { it.sessionId }
            .toSet()
        recordFile.retainWhere { sessionId, record ->
            record.runId == runId || sessionId in retrySessions
        }
        results.forEach { result ->
            if (result.outcome == ReclaimResult.KILLED) {
                FileLogger.w(TAG, "已回收孤儿 CLI 进程: sid=${result.sessionId} pid=${result.pid}")
            } else {
                FileLogger.i(TAG, "跳过孤儿记录: sid=${result.sessionId} pid=${result.pid} ${result.outcome}")
            }
        }
        return results
    }

    /**
     * 只同步回收「同会话」遗留的旧记录；其它会话交给冷启动全量回收。
     *
     * register 在 IO 上下文调用，无旧记录时零开销。有旧记录时最多等一次 800ms 宽限——
     * 这正是上次进程被 LMK 的现场，值得在拉起同会话新进程前先清干净。
     */
    private fun reclaimPreviousForSession(sessionId: String) {
        val previous = recordFile.load()[sessionId] ?: return
        if (previous.runId == runId) return
        val results = runCatching { reclaimer.reclaim(mapOf(sessionId to previous), runId) }
            .onFailure { FileLogger.w(TAG, "同步回收同会话旧进程失败: sid=$sessionId", it) }
            .getOrNull() ?: return
        // NOT_PROOT / UNREADABLE 可能只是 /proc 瞬时读失败：保留记录，留给冷启动全量回收重试。
        if (results.all { it.outcome != ReclaimResult.NOT_PROOT && it.outcome != ReclaimResult.UNREADABLE }) {
            recordFile.retainWhere { sid, record -> sid != sessionId || record.runId == runId }
        }
        results.forEach {
            FileLogger.i(TAG, "同会话旧进程处理: sid=$sessionId pid=${it.pid} ${it.outcome}")
        }
    }

    private fun persist(sessionId: String, engine: String?) {
        val pid = resolveChildPid(sessionId, engine)
        if (pid == null) {
            FileLogger.w(TAG, "未定位到 CLI 子进程 PID，跳过落盘: sid=$sessionId")
            return
        }
        // 同会话接替回合：旧 PID 要从未认领集合里摘掉，否则 claimedPids 只增不减（optimizer P2-4）。
        val previousPid = sessionPids.put(sessionId, pid)
        if (previousPid != null && previousPid != pid) claimedPids.remove(previousPid)
        claimedPids.add(pid)
        val stat = probe.stat(pid)
        // stat.pid 必须与目标一致、且不能是僵尸：极窄窗口内进程退出 + PID 复用会把别人的
        // starttime 记成我们的记录，冷启动可能误杀无关进程（qa P2-4）。
        if (stat == null || stat.pid != pid || stat.state == 'Z') {
            FileLogger.w(TAG, "读不到有效 /proc/$pid/stat，跳过落盘: sid=$sessionId")
            return
        }
        // register 本身在 IO 上下文（桥的 withContext(Dispatchers.IO)）里，同步写保证进程存活期
        // 一定有记录——异步写会让「刚 spawn 就被 LMK」的窗口漏杀。
        recordFile.put(
            sessionId,
            EngineProcessRecord(
                pid = pid,
                startTicks = stat.startTicks,
                engine = engine,
                runId = runId,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 定位刚 spawn 的 proot 子进程 PID。
     *
     * Android 公共 SDK 的 `java.lang.Process` **没有** `pid()`（javap android.jar 与
     * `api-versions.xml` 均无该方法，运行时实现类是包私有），反射不可靠（qa P0-1）。
     * 改从 `/proc` 认领：proot 是本 App 进程的直接子进程，且三桥 spawn 时都注入了
     * `PERMISSION_BRIDGE_SESSION=<sessionId>`，按环境变量精确匹配，多会话并发也不会认错。
     * 万一 environ 读不到（瞬时权限/竞态），退化为「本进程下尚未认领、启动时间最新的 proot 子进程」。
     */
    private fun resolveChildPid(sessionId: String, engine: String?): Int? {
        val myPid = android.os.Process.myPid()
        val marker = "PERMISSION_BRIDGE_SESSION=$sessionId"
        // 一次全表快照：按 ppid 分组，避免每个候选/兜底排序都重复 stat（optimizer P2-1）。
        val snapshot = probe.allPids().mapNotNull { probe.stat(it) }
        val children = snapshot
            .filter { it.pid != myPid && it.ppid == myPid && it.state != 'Z' }
            .associateBy { it.pid }
        val candidates = children.keys.filter { pid ->
            val argv = probe.cmdline(pid)
            isProotExecutableArg(argv.firstOrNull().orEmpty()) &&
                (engine == null || isEngineInvocation(argv, engine))
        }
        // 同会话替换回合时新旧进程可能短暂共存：只认「尚未归属任何会话」的候选，绝不重复认领旧 PID。
        // environ 读不到（hidepid/SELinux）时 matched 为空，退化为「未认领 + 引擎命令匹配 + 启动时间最新」；
        // 仍拿不到就返回 null（不落盘），由下次冷启动按真实性校验兜底，避免记错 pid 后漏杀。
        val matched = candidates.filter { pid -> probe.environ(pid).any { it == marker } }
        val picked = matched.asSequence()
            .filter { it !in claimedPids }
            .maxByOrNull { children[it]?.startTicks ?: 0L }
            ?: candidates.asSequence()
                .filter { it !in claimedPids }
                .maxByOrNull { children[it]?.startTicks ?: 0L }
        if (picked == null) {
            FileLogger.w(
                TAG,
                "未认领到可归属的引擎子进程: sid=$sessionId engine=$engine candidates=${
                    candidates.joinToString(",") { "$it:${probe.cmdline(it).getOrNull(3) ?: "?"}" }
                }"
            )
        }
        return picked
    }

    /** cmdline 形如 `… /bin/sh -c 'exec "$0" "$@"' <program> …`；取 program 位精确比对引擎名。 */
    private fun isEngineInvocation(argv: List<String>, engine: String): Boolean {
        val shellAt = argv.indexOf("/bin/sh")
        return shellAt >= 0 && argv.getOrNull(shellAt + 3) == engine
    }

    private fun killProcess(process: Process, sessionId: String) {
        // 先关流：桥的 stdout/stderr 读取协程正阻塞在 readLine，destroy 仅给 proot 发信号
        // 不一定能立刻解除阻塞；关流能让读端马上返回，回合协程才能进入 finally。
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.destroy() }
            .onFailure { FileLogger.w(TAG, "终止 CLI 进程失败: sid=$sessionId", it) }
        Thread {
            runCatching {
                Thread.sleep(FORCE_KILL_DELAY_MS)
                if (process.isAlive) {
                    FileLogger.w(TAG, "CLI 进程 ${FORCE_KILL_DELAY_MS}ms 未退出，强制终止: sid=$sessionId")
                    process.destroyForcibly()
                }
            }
        }.apply {
            name = "engine-kill-$sessionId"
            isDaemon = true
        }.start()
    }
}
