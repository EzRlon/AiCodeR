package com.aicode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [reasoningAuditOf] 契约：把「用户所选」与「实际发出」摊成落库用的 (wire, clampedFrom)。
 *
 * 关键回归点是不能误报降级：未开思考、无档位模型、预算直传等形态都不该被当成「被降级」。
 */
class ReasoningAuditTest {

    @Test
    fun 档位一致时视为直传未降级() {
        val audit = reasoningAuditOf("high", "high")
        assertEquals("high", audit.wire)
        assertNull(audit.clampedFrom)
    }

    @Test
    fun 发生降级时记录用户原本所选() {
        val audit = reasoningAuditOf("max", "high")
        assertEquals("high", audit.wire)
        assertEquals("max", audit.clampedFrom)
    }

    @Test
    fun 关闭被降成最低档也算降级() {
        val audit = reasoningAuditOf("none", "low")
        assertEquals("low", audit.wire)
        assertEquals("none", audit.clampedFrom)
    }

    @Test
    fun 预算形态不判定降级() {
        val audit = reasoningAuditOf("high", "budget:8192")
        assertEquals("budget:8192", audit.wire)
        assertNull("预算直传没有可比档位，不应记成降级", audit.clampedFrom)
    }

    @Test
    fun 任一侧缺失都不判定降级() {
        assertNull(reasoningAuditOf(null, "high").clampedFrom)
        assertNull(reasoningAuditOf("high", null).clampedFrom)
        assertNull(reasoningAuditOf(null, null).wire)
    }
}
