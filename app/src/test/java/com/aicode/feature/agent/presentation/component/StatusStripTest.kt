package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.domain.container.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏状态条的组装与格式化：钱位优先真实余额、没有才回落估算（用户 2026-11-11 定的 C 方案）。
 */
class StatusStripTest {

    private val labels = StatusStripLabels(
        context = "上下文",
        balance = "余额",
        estimate = "估算",
        cache = "缓存",
        sshConnecting = "SSH：连接中",
        sshFailed = "SSH：连接失败",
        sshDisconnected = "SSH：未连接"
    )

    @Test
    fun `有真实余额时显示余额而不是估算`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 34_000,
            compactionLineTokens = 838_000,
            balanceText = "\$18.42",
            estimatedCostUsd = 0.87,
            cacheHitRate = null
        )

        val money = segments.first { it.label == "余额" }
        assertEquals("\$18.42", money.value)
        assertTrue("不该同时出现估算段", segments.none { it.label == "估算" })
    }

    @Test
    fun `没有余额时回落到估算花费`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 34_000,
            compactionLineTokens = 838_000,
            balanceText = null,
            estimatedCostUsd = 0.87,
            cacheHitRate = null
        )

        val money = segments.first { it.label == "估算" }
        assertEquals("\$0.87", money.value)
    }

    @Test
    fun `上下文段带压缩线与百分比`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 34_000,
            compactionLineTokens = 838_000,
            balanceText = null,
            estimatedCostUsd = null,
            cacheHitRate = null
        )

        assertEquals(1, segments.size)
        // 2026-11-18 用户反馈：双数字像一长串，上下文段只留百分比。
        assertEquals("4%", segments.single().value)
    }

    @Test
    fun `压缩线未知时不显示上下文段`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 34_000,
            compactionLineTokens = 0,
            balanceText = null,
            estimatedCostUsd = null,
            cacheHitRate = null
        )

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `连接异常时第一段用告警色`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 0,
            compactionLineTokens = 0,
            balanceText = null,
            estimatedCostUsd = null,
            cacheHitRate = null,
            connectionState = ConnectionState.FAILED
        )

        assertEquals(StripTone.ALERT, segments.first().tone)
        assertEquals("SSH：连接失败", segments.first().value)
    }

    @Test
    fun `已连接的远程连接不占位`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 0,
            compactionLineTokens = 0,
            balanceText = null,
            estimatedCostUsd = null,
            cacheHitRate = null,
            connectionState = ConnectionState.CONNECTED
        )

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `缓存命中率只保留整数百分比`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 0,
            compactionLineTokens = 1_000_000,
            balanceText = null,
            estimatedCostUsd = null,
            cacheHitRate = 0.623f
        )

        assertEquals("62%", segments.first { it.label == "缓存" }.value)
    }

    @Test
    fun `token 缩写去掉多余的零点`() {
        assertEquals("500", formatStripTokens(500))
        assertEquals("34k", formatStripTokens(34_000))
        assertEquals("838k", formatStripTokens(838_000))
        assertEquals("1.2M", formatStripTokens(1_200_000))
    }

    @Test
    fun `估算金额不到一分钱时显示小于一分`() {
        assertEquals("<\$0.01", formatStripUsd(0.001))
        assertEquals("\$0.87", formatStripUsd(0.87))
    }

    @Test
    fun `读屏摘要包含各段标签`() {
        val segments = buildStatusStripSegments(
            labels = labels,
            contextTokens = 34_000,
            compactionLineTokens = 838_000,
            balanceText = "\$18.42",
            estimatedCostUsd = 0.87,
            cacheHitRate = null
        )

        val summary = statusStripSummary(segments)
        assertTrue(summary.contains("上下文 4%"))
        assertTrue(summary.contains("余额 \$18.42"))
        assertNull("没有估算段时不该出现在摘要里", summary.indexOf("估算").takeIf { it >= 0 })
    }
}
