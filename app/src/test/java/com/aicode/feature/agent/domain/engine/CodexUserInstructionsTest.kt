package com.aicode.feature.agent.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 `~/.codex/config.toml` 里 developer_instructions 的解析。
 *
 * 这些用例钉住两个曾经的真实缺陷：
 * - 键不存在（但文件里出现过该词，如注释）时不能返回 present=true，否则调用方跳过全部注入；
 * - 多行串不能解析成空串，否则会反向覆盖用户指令。
 */
class CodexUserInstructionsTest {

    @Test
    fun `没有该键时 present 为 false`() {
        val result = CodexUserInstructions.parse("model = \"gpt-5\"\napproval_policy = \"never\"\n")

        assertEquals(false, result.present)
        assertNull(result.text)
    }

    @Test
    fun `词只出现在注释里不算配置了该键`() {
        val result = CodexUserInstructions.parse("# developer_instructions = \"旧指令\"\nmodel = \"gpt-5\"\n")

        assertEquals(false, result.present)
        assertNull(result.text)
    }

    @Test
    fun `单行基本字符串正常解析`() {
        val result = CodexUserInstructions.parse("developer_instructions = \"用中文回答\"\n")

        assertTrue(result.present)
        assertEquals("用中文回答", result.text)
    }

    @Test
    fun `缩进与尾部注释不影响解析`() {
        val result = CodexUserInstructions.parse("  developer_instructions = \"hi\"  # 用户注释\n")

        assertTrue(result.present)
        assertEquals("hi", result.text)
    }

    @Test
    fun `转义按 TOML 规则一次解码`() {
        // 原文：a<换行>b<反斜杠>c"d<制表>
        val result = CodexUserInstructions.parse(
            "developer_instructions = \"a\\nb\\\\c\\\"d\\t\"\n"
        )

        assertEquals("a\nb\\c\"d\t", result.text)
    }

    @Test
    fun `字面量反斜杠加 t 不会被解成制表符`() {
        // 旧实现先 replace(\n) 再 replace(\\\\)，C:\\temp 这类路径会变成「反斜杠 + 制表符」
        val result = CodexUserInstructions.parse("developer_instructions = \"C:\\\\temp\"\n")

        assertEquals("C:\\temp", result.text)
    }

    @Test
    fun `unicode 转义正常解码`() {
        val result = CodexUserInstructions.parse("developer_instructions = \"\\u4e2d\\u6587\"\n")

        assertEquals("中文", result.text)
    }

    @Test
    fun `多行基本字符串标记为不可解析`() {
        val result = CodexUserInstructions.parse(
            "developer_instructions = \"\"\"第一行\n第二行\"\"\"\n"
        )

        assertTrue(result.present)
        assertNull(result.text)
    }

    @Test
    fun `字面量字符串标记为不可解析`() {
        val result = CodexUserInstructions.parse("developer_instructions = 'C:\\path\\x'\n")

        assertTrue(result.present)
        assertNull(result.text)
    }

    @Test
    fun `非法转义标记为不可解析`() {
        val result = CodexUserInstructions.parse("developer_instructions = \"bad\\xescape\"\n")

        assertTrue(result.present)
        assertNull(result.text)
    }

    @Test
    fun `非字符串值标记为不可解析`() {
        val result = CodexUserInstructions.parse("developer_instructions = [\"a\", \"b\"]\n")

        assertTrue(result.present)
        assertNull(result.text)
    }

    @Test
    fun `键名相近的其它键不受影响`() {
        val result = CodexUserInstructions.parse("my_developer_instructions = \"x\"\n")

        assertEquals(false, result.present)
        assertNull(result.text)
    }
}
