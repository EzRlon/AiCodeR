package com.aicode.feature.agent.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codex `-c` 参数值的 TOML 转义。
 *
 * 这是三个引擎参数拼接里最容易出错的一环：Codex 不给 `--mcp-config`，MCP 只能靠
 * `-c key=value` 内联挂载，**一个字符没转义对，整条参数解析失败**，表现为
 * 「MCP 没挂上」——从现象几乎无法反推回转义问题，所以值得单独钉住。
 */
class CliTomlTest {

    @Test
    fun `普通字符串加引号`() {
        assertEquals("\"hello\"", CliToml.string("hello"))
    }

    @Test
    fun `路径原样保留`() {
        assertEquals(
            "\"/root/.aicode/bridge/aicode-bridge.mjs\"",
            CliToml.string("/root/.aicode/bridge/aicode-bridge.mjs")
        )
    }

    @Test
    fun `双引号被转义`() {
        assertEquals("\"say \\\"hi\\\"\"", CliToml.string("say \"hi\""))
    }

    @Test
    fun `反斜杠被转义`() {
        assertEquals("\"C:\\\\path\"", CliToml.string("C:\\path"))
    }

    @Test
    fun `换行被转义`() {
        assertEquals("\"line1\\nline2\"", CliToml.string("line1\nline2"))
    }

    @Test
    fun `回车被转义`() {
        assertEquals("\"a\\rb\"", CliToml.string("a\rb"))
    }

    @Test
    fun `反斜杠先于其它字符处理，不会双重转义`() {
        // 输入「\n」是两个字符（反斜杠 + n），不是换行符。
        // 若先转义换行再转义反斜杠，会把结果里的 \" 再转一遍，产出错误内容。
        assertEquals("\"\\\\n\"", CliToml.string("\\n"))
    }

    @Test
    fun `引号与反斜杠组合`() {
        assertEquals("\"a\\\\\\\"b\"", CliToml.string("a\\\"b"))
    }

    @Test
    fun `中文内容原样保留`() {
        assertEquals("\"中转站\"", CliToml.string("中转站"))
    }

    @Test
    fun `空字符串产出空引号对`() {
        assertEquals("\"\"", CliToml.string(""))
    }

    @Test
    fun `URL 中的冒号斜杠不需转义`() {
        assertEquals(
            "\"https://relay.example.com/v1\"",
            CliToml.string("https://relay.example.com/v1")
        )
    }

    @Test
    fun `整条 -c 参数拼接形态正确`() {
        // 模拟实际用法：mcp_servers.x.command="值"
        val line = "mcp_servers.aicode_bridge.command=${CliToml.string("node")}"

        assertEquals("mcp_servers.aicode_bridge.command=\"node\"", line)
    }
}
