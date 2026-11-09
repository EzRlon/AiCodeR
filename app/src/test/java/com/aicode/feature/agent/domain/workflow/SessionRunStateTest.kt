package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话运行状态判定规则。
 *
 * 这组测试钉住的是「消息被吞」那个致命 bug 的机制：旧 job 收尾时，
 * 如果把它误判为「已被新 job 接管」，就会跳过队列消费，用户的消息永远发不出去、
 * 且没有任何提示。规则一旦被改坏，这里必须红。
 */
class SessionRunStateTest {

    // ── isOwnJob ─────────────────────────────────────────────────

    @Test
    fun `map 里是我自己时判定为本人`() {
        val self = Any()

        assertTrue(SessionRunState.isOwnJob(registered = self, self = self))
    }

    @Test
    fun `map 里是别的 job 时判定为已被接管`() {
        val oldJob = Any()
        val newJob = Any()

        assertFalse(
            "旧 job 收尾时若 map 已指向新 job，应识别为「已被接管」",
            SessionRunState.isOwnJob(registered = newJob, self = oldJob)
        )
    }

    @Test
    fun `map 为空时不算本人`() {
        assertFalse(SessionRunState.isOwnJob(registered = null, self = Any()))
    }

    @Test
    fun `self 为空时不算本人`() {
        assertFalse(SessionRunState.isOwnJob(registered = Any(), self = null))
    }

    @Test
    fun `用等值但不相同的对象不能误判为本人`() {
        // 钉住「按引用判定」这个语义：值相等的两个不同对象不算同一个持有者。
        // 注意实际调用方传的是 kotlinx.coroutines.Job（接口，未重写 equals，
        // 因此 `==` 与 `===` 行为相同）；这条用例是为了以后换成别的类型时不踩坑。
        val a = Wrapper(1)
        val b = Wrapper(1)

        assertFalse(
            "必须按引用判定：值相等的两个不同对象不代表是同一个持有者",
            SessionRunState.isOwnJob(registered = a, self = b)
        )
    }

    // ── shouldConsumeQueue ───────────────────────────────────────

    @Test
    fun `是自己且队列非空时消费`() {
        assertTrue(SessionRunState.shouldConsumeQueue(isOwnJob = true, queuedCount = 1))
    }

    @Test
    fun `不是自己时不消费`() {
        assertFalse(
            "已被新 job 接管还去消费，会让同一会话并行跑两个回合",
            SessionRunState.shouldConsumeQueue(isOwnJob = false, queuedCount = 3)
        )
    }

    @Test
    fun `队列为空时不消费`() {
        assertFalse(SessionRunState.shouldConsumeQueue(isOwnJob = true, queuedCount = 0))
    }

    @Test
    fun `暂停会话不消费队列`() {
        assertFalse(
            "暂停的会话要保留待发请求，交接后由用户在新会话重新发起",
            SessionRunState.shouldConsumeQueue(isOwnJob = true, queuedCount = 2, paused = true)
        )
    }

    @Test
    fun `斜杠命令执行期不消费`() {
        assertFalse(
            "命令内联跑、不注册 sessionJobs，此时消费会与命令并行",
            SessionRunState.shouldConsumeQueue(isOwnJob = true, queuedCount = 1, commandRunning = true)
        )
    }

    // ── isRunning ────────────────────────────────────────────────

    @Test
    fun `会话 job 活跃时算运行中`() {
        assertTrue(
            SessionRunState.isRunning(
                hasActiveSessionJob = true,
                hasActiveCommandJob = false,
                sessionInRunningCommands = false
            )
        )
    }

    @Test
    fun `命令 job 活跃时也算运行中`() {
        assertTrue(
            "只看会话 job 会让 /compress 期间的新消息并行执行",
            SessionRunState.isRunning(
                hasActiveSessionJob = false,
                hasActiveCommandJob = true,
                sessionInRunningCommands = false
            )
        )
    }

    @Test
    fun `命令占用集合命中时也算运行中`() {
        assertTrue(
            SessionRunState.isRunning(
                hasActiveSessionJob = false,
                hasActiveCommandJob = false,
                sessionInRunningCommands = true
            )
        )
    }

    @Test
    fun `三者都不成立时才算空闲`() {
        assertFalse(
            "三种占用只要漏判一种，就会出现同一会话两个执行者",
            SessionRunState.isRunning(
                hasActiveSessionJob = false,
                hasActiveCommandJob = false,
                sessionInRunningCommands = false
            )
        )
    }
}

/** 用于验证「按引用判定」的辅助类型。 */
private class Wrapper(val value: Int)
