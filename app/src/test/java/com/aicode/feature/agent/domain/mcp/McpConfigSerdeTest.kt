package com.aicode.feature.agent.domain.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** McpJsonCodec：工具裁剪集合（enabledTools 白名单 / disabledTools）序列化与解析的回归防护。 */
class McpConfigSerdeTest {

    @Test
    fun emptyEnabledTools_isOmittedFromJson() {
        val json = McpJsonCodec.serialize(
            listOf(McpServerConfig(name = "svc", command = "npx", args = listOf("-y", "x")))
        )
        assertFalse("空白名单不应写入 JSON（保持既有行为与最小体积）", json.contains("enabledTools"))
        assertFalse("空禁用集同样不应写入", json.contains("disabledTools"))
    }

    @Test
    fun enabledTools_roundTrip_preservesBothSets() {
        val original = McpServerConfig(
            name = "rea",
            command = "npx",
            args = listOf("-y", "rea-agents@6.5.0", "mcp"),
            env = mapOf("REA_JADX_MCP_JAR" to "/opt/rea/jadx-headless-mcp-0.7.1-all.jar"),
            enabled = true,
            disabledTools = setOf("disabled_one"),
            enabledTools = setOf("binary_session", "inspect_android_method")
        )
        val parsed = McpJsonCodec.parse(McpJsonCodec.serialize(listOf(original)))
        assertEquals(1, parsed.size)
        val s = parsed.single()
        assertEquals("rea", s.name)
        assertTrue(s.enabled)
        assertEquals(setOf("binary_session", "inspect_android_method"), s.enabledTools)
        assertEquals(setOf("disabled_one"), s.disabledTools)
        assertEquals("/opt/rea/jadx-headless-mcp-0.7.1-all.jar", s.env["REA_JADX_MCP_JAR"])
    }

    @Test
    fun legacyConfig_withoutEnabledTools_parsesToEmptySet() {
        val legacy = """{"mcpServers":{"old":{"command":"npx","enabled":true}}}"""
        val parsed = McpJsonCodec.parse(legacy)
        assertEquals(1, parsed.size)
        assertTrue("旧配置缺字段应默认空集（不限制）", parsed.single().enabledTools.isEmpty())
    }

    @Test
    fun whitelistFilter_semantics() {
        // 与 McpManager 注册过滤一致的语义：白名单非空→仅名单内；空→不限制；再叠加 disabledTools。
        data class T(val name: String)
        val all = listOf(T("a"), T("b"), T("c"))
        val whitelist = setOf("a", "c")
        val disabled = setOf("c")
        val effective = all.filter { (whitelist.isEmpty() || it.name in whitelist) && it.name !in disabled }
        assertEquals(listOf("a"), effective)
        val noWhitelist = all.filter { (emptySet<String>().isEmpty() || it.name in emptySet<String>()) && it.name !in disabled }
        assertEquals(listOf("a", "b"), noWhitelist)
    }
}
