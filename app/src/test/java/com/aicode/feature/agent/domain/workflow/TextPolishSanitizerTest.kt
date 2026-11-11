package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 润色结果清理：剥掉模型常见的外层装饰，但绝不能动正文。
 */
class TextPolishSanitizerTest {

    @Test
    fun `普通结果原样返回`() {
        assertEquals(
            "登录入口点击后没有响应，请帮忙排查原因。",
            sanitizePolishedText("  登录入口点击后没有响应，请帮忙排查原因。 \n")
        )
    }

    @Test
    fun `剥掉整体代码围栏`() {
        val raw = "```text\n请把这个函数改短一些。\n```"

        assertEquals("请把这个函数改短一些。", sanitizePolishedText(raw))
    }

    @Test
    fun `剥掉润色后前缀`() {
        assertEquals("请修复登录无响应的问题。", sanitizePolishedText("润色后：请修复登录无响应的问题。"))
    }

    @Test
    fun `剥掉整段包裹的引号`() {
        assertEquals("请修复登录问题。", sanitizePolishedText("\"请修复登录问题。\""))
        assertEquals("请修复登录问题。", sanitizePolishedText("“请修复登录问题。”"))
    }

    @Test
    fun `正文里的引号与代码不动`() {
        val text = "把 `config.json` 里的 \"timeout\" 改成 30 秒"
        assertEquals(text, sanitizePolishedText(text))
    }

    @Test
    fun `空输出返回 null`() {
        assertNull(sanitizePolishedText(""))
        assertNull(sanitizePolishedText("   \n  "))
        assertNull(sanitizePolishedText("```\n\n```"))
    }
}
