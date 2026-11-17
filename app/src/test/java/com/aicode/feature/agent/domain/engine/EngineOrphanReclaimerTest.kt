package com.aicode.feature.agent.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动孤儿进程回收的校验与杀进程顺序。
 *
 * 这是会向真实 PID 发信号的路径，单测全部走假 [ProcessProbe]：
 * 重点保证「不误杀」（PID 复用 / 非 proot / 当前进程记录）与「后代先于根」。
 */
class EngineOrphanReclaimerTest {

    private val prootPath = "/data/app/~~x/com.aicode-1/lib/arm64/libproot.so"

    @Test
    fun `回收时后代先于根收到 SIGTERM，随后兜底 SIGKILL`() {
        val probe = FakeProbe(
            stats = mutableMapOf(
                100 to ProcStat(100, 1, 555, 'S'),
                101 to ProcStat(101, 100, 556, 'S'),
                102 to ProcStat(102, 101, 557, 'S')
            ),
            cmdlines = mutableMapOf(100 to listOf(prootPath, "-r", "/data/rootfs"))
        )
        val result = EngineOrphanReclaimer(probe, graceMs = 0)
            .reclaim(mapOf("sid-1" to record(100, 555)), currentRunId = "new-run")

        assertEquals(ReclaimResult.KILLED, result.single().outcome)
        val sigtermOrder = probe.signals.filter { it.second == EngineOrphanReclaimer.SIGTERM }.map { it.first }
        assertEquals(listOf(102, 101, 100), sigtermOrder)
        // 假 probe 不响应信号，宽限后仍存活 → 三个都收到 SIGKILL 兜底
        assertEquals(3, probe.signals.count { it.second == EngineOrphanReclaimer.SIGKILL })
    }

    @Test
    fun `PID 被复用（启动时间不一致）不杀`() {
        val probe = FakeProbe(
            stats = mutableMapOf(100 to ProcStat(100, 1, 999, 'S')),
            cmdlines = mutableMapOf(100 to listOf(prootPath))
        )
        val result = EngineOrphanReclaimer(probe, graceMs = 0)
            .reclaim(mapOf("sid-1" to record(100, 555)), currentRunId = "new-run")

        assertEquals(ReclaimResult.PID_REUSED, result.single().outcome)
        assertTrue(probe.signals.isEmpty())
    }

    @Test
    fun `非 proot 进程不杀`() {
        val probe = FakeProbe(
            stats = mutableMapOf(100 to ProcStat(100, 1, 555, 'S')),
            cmdlines = mutableMapOf(100 to listOf("/system/bin/sh", "-c", "sleep 999"))
        )
        val result = EngineOrphanReclaimer(probe, graceMs = 0)
            .reclaim(mapOf("sid-1" to record(100, 555)), currentRunId = "new-run")

        assertEquals(ReclaimResult.NOT_PROOT, result.single().outcome)
        assertTrue(probe.signals.isEmpty())
    }

    @Test
    fun `已退出或僵尸进程不杀`() {
        val probe = FakeProbe(
            stats = mutableMapOf(100 to ProcStat(100, 1, 555, 'Z')),
            cmdlines = mutableMapOf(100 to listOf(prootPath))
        )
        val reclaimer = EngineOrphanReclaimer(probe, graceMs = 0)

        assertEquals(
            ReclaimResult.ALREADY_EXITED,
            reclaimer.reclaim(mapOf("sid-1" to record(100, 555)), "new-run").single().outcome
        )
        assertEquals(
            ReclaimResult.ALREADY_EXITED,
            reclaimer.reclaim(mapOf("sid-2" to record(999, 1)), "new-run").single().outcome
        )
        assertTrue(probe.signals.isEmpty())
    }

    @Test
    fun `当前进程 runId 的记录一律跳过`() {
        val probe = FakeProbe(
            stats = mutableMapOf(100 to ProcStat(100, 1, 555, 'S')),
            cmdlines = mutableMapOf(100 to listOf(prootPath))
        )
        val result = EngineOrphanReclaimer(probe, graceMs = 0)
            .reclaim(
                mapOf("sid-1" to record(100, 555, runId = "this-run")),
                currentRunId = "this-run"
            )

        assertEquals(ReclaimResult.CURRENT_PROCESS, result.single().outcome)
        assertTrue(probe.signals.isEmpty())
    }

    @Test
    fun `SIGTERM 后已退出则不再 SIGKILL`() {
        val probe = FakeProbe(
            stats = mutableMapOf(100 to ProcStat(100, 1, 555, 'S')),
            cmdlines = mutableMapOf(100 to listOf(prootPath)),
            dieOnSigterm = true
        )
        EngineOrphanReclaimer(probe, graceMs = 0)
            .reclaim(mapOf("sid-1" to record(100, 555)), currentRunId = "new-run")

        assertEquals(1, probe.signals.count { it.second == EngineOrphanReclaimer.SIGTERM })
        assertTrue(probe.signals.none { it.second == EngineOrphanReclaimer.SIGKILL })
    }

    @Test
    fun `宽限后 PID 被复用则不 SIGKILL（qa P1-1）`() {
        val probe = FakeProbe(
            stats = mutableMapOf(100 to ProcStat(100, 1, 555, 'S')),
            cmdlines = mutableMapOf(100 to listOf(prootPath)),
            // 根进程收到 SIGTERM 后退出，PID 100 立刻被同 uid 新进程复用（启动时间不同）
            onSigterm = { stats -> stats[100] = ProcStat(100, 1, 777, 'S') }
        )
        EngineOrphanReclaimer(probe, graceMs = 0)
            .reclaim(mapOf("sid-1" to record(100, 555)), currentRunId = "new-run")

        assertTrue(probe.signals.none { it.second == EngineOrphanReclaimer.SIGKILL })
    }

    @Test
    fun `proc stat 解析支持 comm 含空格与括号`() {
        val spaced = parseProcStat(statLine(123, "node worker", 'S', 1, 998877))
        assertEquals(123, spaced?.pid)
        assertEquals(1, spaced?.ppid)
        assertEquals(998877L, spaced?.startTicks)
        assertEquals('S', spaced?.state)

        val paren = parseProcStat(statLine(456, "a)b", 'R', 99, 1234))
        assertEquals(456, paren?.pid)
        assertEquals(99, paren?.ppid)
        assertEquals(1234L, paren?.startTicks)
    }

    @Test
    fun `proc stat 解析对残缺内容返回 null`() {
        assertEquals(null, parseProcStat(""))
        assertEquals(null, parseProcStat("123 (no-close"))
        assertEquals(null, parseProcStat("123 (comm) S 1 2"))
    }

    /** 按真实 stat 字段序拼一行：state 为第 3 字段、ppid 第 4、starttime 第 22。 */
    private fun statLine(pid: Int, comm: String, state: Char, ppid: Int, startTicks: Long): String {
        val rest = mutableListOf(state.toString(), ppid.toString())
        while (rest.size < 19) rest += "0"
        rest += startTicks.toString()
        return "$pid ($comm) ${rest.joinToString(" ")}"
    }

    private fun record(pid: Int, startTicks: Long, runId: String = "old-run") =
        EngineProcessRecord(pid = pid, startTicks = startTicks, runId = runId, createdAt = 0L)

    /** 假 probe：默认不响应信号；可配置 SIGTERM 后退出或模拟 PID 复用。 */
    private class FakeProbe(
        private val stats: MutableMap<Int, ProcStat>,
        private val cmdlines: Map<Int, List<String>>,
        private val dieOnSigterm: Boolean = false,
        private val onSigterm: (MutableMap<Int, ProcStat>) -> Unit = {}
    ) : ProcessProbe {
        val signals = mutableListOf<Pair<Int, Int>>()

        override fun stat(pid: Int): ProcStat? = stats[pid]

        override fun cmdline(pid: Int): List<String> = cmdlines[pid].orEmpty()

        override fun environ(pid: Int): List<String> = emptyList()

        override fun exists(pid: Int): Boolean = stats.containsKey(pid)

        override fun allPids(): List<Int> = stats.keys.toList()

        override fun signal(pid: Int, signal: Int) {
            signals += pid to signal
            if (signal == EngineOrphanReclaimer.SIGTERM) {
                if (dieOnSigterm) stats.remove(pid)
                onSigterm(stats)
            }
        }
    }
}
