package com.aicode.feature.settings.domain.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈日志脱敏：日志会公开到 Issue，密钥形态必须被抹掉。
 */
class LogExcerptSanitizerTest {

    @Test
    fun `sk 开头的密钥被抹掉`() {
        val out = LogExcerptSanitizer.sanitize("调用失败 key=sk-ant-api03-abcdefghijklmnop")

        assertFalse(out.contains("abcdefghijklmnop"))
        assertTrue(out.contains("sk-***"))
    }

    @Test
    fun `赋值形式的 token 与 password 只保留键名`() {
        val out = LogExcerptSanitizer.sanitize(
            "api_key=abcd1234\ntoken: efgh5678\npassword = \"ijkl9012\""
        )

        assertFalse(out.contains("abcd1234"))
        assertFalse(out.contains("efgh5678"))
        assertFalse(out.contains("ijkl9012"))
        assertTrue(out.contains("api_key="))
        assertTrue(out.contains("token:"))
    }

    @Test
    fun `Bearer 令牌被抹掉`() {
        val out = LogExcerptSanitizer.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")

        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun `普通日志内容保持原样`() {
        val line = "2026-11-15 12:00:00 INFO 启动完成，耗时 320ms"
        val out = LogExcerptSanitizer.sanitize(line)

        assertTrue(out.contains(line))
    }

    @Test
    fun `只保留末尾指定行数`() {
        val raw = (1..50).joinToString("\n") { "line-$it" }
        val out = LogExcerptSanitizer.sanitize(raw, maxLines = 10)

        assertFalse(out.contains("line-40\n"))
        assertTrue(out.contains("line-41"))
        assertTrue(out.contains("line-50"))
    }

    @Test
    fun `字符上限从末尾截取`() {
        val raw = "A".repeat(100) + "B".repeat(100)
        val out = LogExcerptSanitizer.sanitize(raw, maxChars = 50)

        assertTrue(out.length <= 50)
        assertTrue(out.all { it == 'B' })
    }
}
