package com.aicode.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestParserTest {

    /**
     * 用 buildString 拼 JSON，而不是在 raw string 里嵌套 raw string——
     * 后者 Kotlin 解析不了（模板表达式里再开 `"""` 会炸语法）。
     */
    private fun manifest(
        id: String = "com.aicode.demo",
        apiVersion: Int = 1,
        permissions: List<String> = emptyList(),
        capabilities: String? = null,
        slots: String? = null,
        extra: String? = null
    ): String = buildString {
        append("{\"id\":").append(quote(id))
        append(",\"name\":").append(quote("示例插件"))
        append(",\"version\":\"1.0.0\"")
        append(",\"apiVersion\":").append(apiVersion)
        if (permissions.isNotEmpty()) {
            append(",\"permissions\":[")
            permissions.forEachIndexed { index, p ->
                if (index > 0) append(",")
                append(quote(p))
            }
            append("]")
        }
        capabilities?.let { append(",\"capabilities\":{").append(it).append("}") }
        slots?.let { append(",\"slots\":[").append(it).append("]") }
        extra?.let { append(",").append(it) }
        append("}")
    }

    private fun quote(value: String) = "\"" + value + "\""

    private fun valid(json: String) =
        (PluginManifestParser.parse(json) as PluginManifestResult.Valid).manifest

    private fun errorsOf(json: String) =
        (PluginManifestParser.parse(json) as PluginManifestResult.Invalid).errors

    @Test
    fun `最简清单可解析`() {
        val m = valid(manifest())
        assertEquals("com.aicode.demo", m.id)
        assertEquals(1, m.apiVersion)
        assertTrue(m.grantedPermissions.isEmpty())
    }

    @Test
    fun `未知字段被忽略以保证前向兼容`() {
        val m = valid(manifest(extra = """"futureField": {"a": 1}"""))
        assertEquals("示例插件", m.name)
    }

    @Test
    fun `坏 JSON 报错而不是抛异常`() {
        assertTrue(errorsOf("{ not json").first().startsWith("清单不是合法 JSON"))
    }

    @Test
    fun `id 必须为反向域名`() {
        assertTrue(errorsOf(manifest(id = "Demo")).any { it.startsWith("id 非法") })
        assertTrue(errorsOf(manifest(id = "demo")).any { it.startsWith("id 非法") })
    }

    @Test
    fun `apiVersion 不兼容时拒绝`() {
        assertTrue(errorsOf(manifest(apiVersion = 2)).any { it.contains("API v2") })
    }

    @Test
    fun `未知权限视为清单非法`() {
        val errors = errorsOf(manifest(permissions = listOf("network", "root.everything")))
        assertTrue(errors.any { it == "未知权限: root.everything" })
    }

    @Test
    fun `权限重复报错`() {
        assertTrue(
            errorsOf(manifest(permissions = listOf("network", "network"))).any { it.contains("重复") }
        )
    }

    @Test
    fun `assets 目标越界被拒`() {
        val json = manifest(
            capabilities = """"assets": [{"from": "server", "to": "/etc/passwd"}]"""
        )
        assertTrue(errorsOf(json).any { it.startsWith("assets.to 必须落在") })
    }

    @Test
    fun `assets 来源路径穿越被拒`() {
        val json = manifest(
            capabilities = """"assets": [{"from": "../outside", "to": "~/.aicode/demo"}]"""
        )
        assertTrue(errorsOf(json).any { it.startsWith("assets.from") })
    }

    @Test
    fun `合法 assets 通过`() {
        val json = manifest(
            capabilities = """"assets": [{"from": "server", "to": "~/.aicode/demo/server"}]"""
        )
        assertEquals(1, valid(json).capabilities.assets.size)
    }

    @Test
    fun `stdio MCP 缺 command 被拒`() {
        val json = manifest(capabilities = """"mcp": [{"name": "demo"}]""")
        assertTrue(errorsOf(json).any { it.contains("必须提供 command") })
    }

    @Test
    fun `http MCP 有 url 通过`() {
        val json = manifest(
            capabilities = """"mcp": [{"name": "demo", "transport": "http", "url": "https://x/y"}]"""
        )
        assertEquals("demo", valid(json).capabilities.mcp.single().name)
    }

    @Test
    fun `MCP transport 未知被拒`() {
        val json = manifest(
            capabilities = """"mcp": [{"name": "demo", "transport": "grpc", "url": "https://x"}]"""
        )
        assertTrue(errorsOf(json).any { it.contains("不支持的 MCP transport") })
    }

    @Test
    fun `hook 脚本必须为相对路径`() {
        val json = manifest(
            capabilities = """"hooks": [{"event": "PreToolUse", "script": "/bin/sh"}]"""
        )
        assertTrue(errorsOf(json).any { it.startsWith("hook.script") })
    }

    @Test
    fun `hook 超时必须为正`() {
        val json = manifest(
            capabilities = """"hooks": [{"event": "PreToolUse", "script": "h.sh", "timeoutMs": 0}]"""
        )
        assertTrue(errorsOf(json).any { it.contains("timeoutMs") })
    }

    @Test
    fun `append 插槽不应带锚点`() {
        val json = manifest(
            slots = """{"slot": "sidebar.item", "anchor": "标注中心", "position": "append"}"""
        )
        assertTrue(errorsOf(json).any { it.contains("不应再指定 anchor") })
    }

    @Test
    fun `after 插槽必须带锚点`() {
        val json = manifest(slots = """{"slot": "sidebar.item", "position": "after"}""")
        assertTrue(errorsOf(json).any { it.contains("必须指定 anchor") })
    }

    @Test
    fun `未知插槽被拒`() {
        val json = manifest(slots = """{"slot": "somewhere.else", "position": "append"}""")
        assertTrue(errorsOf(json).any { it.startsWith("未知插槽") })
    }

    @Test
    fun `合法插槽可解析`() {
        val json = manifest(
            slots = """{"slot": "sidebar.item", "anchor": "标注中心", "position": "after"}"""
        )
        val slot = valid(json).slots.single()
        assertEquals(PluginSlot.SIDEBAR_ITEM.id, slot.slot)
        assertEquals(SlotPosition.AFTER.id, slot.position)
    }
}
