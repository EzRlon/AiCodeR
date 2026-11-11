package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨道「卡住」判定的边界：120 秒整必须触发、119.9 秒不能触发、时钟回拨不能显示负数。
 * 这些边界写错的表现是「明明还在跑却报警」或「真卡住了却一直不报」，都很难靠真机发现。
 */
class TaskRailStallTest {

    @Test
    fun `未到阈值不告警`() {
        val state = railStallState(STALL_WARN_MS - 100L)

        assertFalse(state.stalled)
        assertEquals(119, state.idleSeconds)
    }

    @Test
    fun `刚好到阈值就告警`() {
        val state = railStallState(STALL_WARN_MS)

        assertTrue(state.stalled)
        assertEquals(120, state.idleSeconds)
    }

    @Test
    fun `超过阈值持续告警`() {
        val state = railStallState(STALL_WARN_MS * 3)

        assertTrue(state.stalled)
        assertEquals(360, state.idleSeconds)
    }

    @Test
    fun `时钟回拨按零处理`() {
        val state = railStallState(-5_000L)

        assertFalse(state.stalled)
        assertEquals(0, state.idleSeconds)
    }

    @Test
    fun `零活动时间不告警`() {
        assertFalse(railStallState(0L).stalled)
    }
}
