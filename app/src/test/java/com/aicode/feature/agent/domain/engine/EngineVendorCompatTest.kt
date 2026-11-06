package com.aicode.feature.agent.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EngineVendorCompatTest {

    @Test
    fun `openai 型主流厂商 baseUrl 按 host 命中端点表`() {
        // 用户 OpenAI 型供应商常见的 OpenAI 兼容 baseUrl，host 命中即可换算
        val cases = mapOf(
            "https://open.bigmodel.cn/api/paas/v4" to "https://open.bigmodel.cn/api/anthropic",
            "https://api.z.ai/api/paas/v4" to "https://api.z.ai/api/anthropic",
            "https://api.deepseek.com/v1" to "https://api.deepseek.com/anthropic",
            "https://dashscope.aliyuncs.com/compatible-mode/v1" to "https://dashscope.aliyuncs.com/apps/anthropic",
            "https://api.moonshot.cn/v1" to "https://api.moonshot.cn/anthropic",
            "https://api.minimax.chat/v1" to null, // 旧域名不在表内，未命中
        )
        cases.forEach { (baseUrl, expected) ->
            assertEquals(expected, EngineVendorCompat.routeForHost(baseUrl)?.baseUrl)
        }
    }

    @Test
    fun `命中厂商路由自带档位模型`() {
        val route = EngineVendorCompat.routeForHost("https://api.deepseek.com/v1")
        assertNotNull(route)
        assertNotNull(route!!.defaultModel)
        assertNotNull(route.fastModel)
    }

    @Test
    fun `未知厂商与非法 baseUrl 返回 null`() {
        assertNull(EngineVendorCompat.routeForHost("https://my-proxy.example.com/v1"))
        assertNull(EngineVendorCompat.routeForHost("not a url"))
        assertNull(EngineVendorCompat.routeForHost(""))
    }

    @Test
    fun `同一厂商多线路端点 host 互不覆盖`() {
        val cn = EngineVendorCompat.routeForHost("https://open.bigmodel.cn/api/paas/v4")
        val intl = EngineVendorCompat.routeForHost("https://api.z.ai/api/paas/v4")
        assertNotNull(cn)
        assertNotNull(intl)
        assertEquals("glm", cn!!.vendorId)
        assertEquals("glm-intl", intl!!.vendorId)
        assertNotEquals(cn.baseUrl, intl.baseUrl)
    }
}
