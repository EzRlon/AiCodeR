package com.aicode.feature.settings.domain.service

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 余额探测的解析层。网络调用没法在单测里跑，但每种协议的**取值与降级**必须钉死：
 * 结构不符、余额为负、额度为 0，都要回落成 null（由调用方显示估算），绝不显示错数字。
 */
class ProviderBalanceProbeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `OpenRouter 余额等于总额度减已用`() {
        val body = """{"data":{"total_credits":10.0,"total_usage":3.5}}"""

        assertEquals("\$6.50", parseOpenRouterCredits(body, json))
    }

    @Test
    fun `OpenRouter 没有额度字段时返回 null`() {
        assertNull(parseOpenRouterCredits("""{"data":{}}""", json))
        assertNull(parseOpenRouterCredits("""{"data":{"total_credits":1,"total_usage":2}}""", json))
    }

    @Test
    fun `DeepSeek 人民币余额带货币符号`() {
        val body = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00","granted_balance":"0.00","topped_up_balance":"110.00"}
            ]}
        """.trimIndent()

        assertEquals("¥110.00", parseDeepSeekBalance(body, json))
    }

    @Test
    fun `DeepSeek 美元余额原样带美元符`() {
        val body = """{"balance_infos":[{"currency":"USD","total_balance":"7.5"}]}"""

        assertEquals("\$7.5", parseDeepSeekBalance(body, json))
    }

    @Test
    fun `OpenAI 兼容网关的额度与用量`() {
        assertEquals(100.0, parseHardLimitUsd("""{"hard_limit_usd":100}""", json) ?: -1.0, 0.0001)
        assertEquals(12345.0, parseTotalUsageCents("""{"total_usage":12345}""", json) ?: -1.0, 0.0001)
        // 额度为 0 通常是「无限额度/占位」，不能拿来相减
        assertNull(parseHardLimitUsd("""{"hard_limit_usd":0}""", json))
    }

    @Test
    fun `one-api 配额按五十万分之一美元换算`() {
        val body = """{"data":{"quota":1000000,"used_quota":0}}"""

        assertEquals("\$2.00", parseOneApiQuota(body, json))
    }

    @Test
    fun `结构不符时统一返回 null`() {
        assertNull(parseOneApiQuota("""{"data":null}""", json))
        assertNull(parseDeepSeekBalance("not json", json))
        assertNull(parseTotalUsageCents("{}", json))
    }

    @Test
    fun `金额格式化不到一分钱时显示小于一分`() {
        assertEquals("<\$0.01", formatUsd(0.001))
        assertEquals("\$0.00", formatUsd(0.0))
        assertEquals("\$12.35", formatUsd(12.345))
    }

    @Test
    fun `未知币种追加在后而不是硬套符号`() {
        assertEquals("88.00 EUR", formatAmountWithCurrency("88.00", "EUR"))
        assertNull(formatAmountWithCurrency("  ", "CNY"))
    }
}
