package com.aicode.feature.agent.domain.tool.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * findLineEndingTolerant 加首字符快判后的等价性护栏：
 * 新实现必须与「逐起点全量比对」的旧语义给出完全相同的匹配区间。
 */
class EditFileToolLineEndingScanTest {

    /** 旧实现（改动前）：每个起点都直接进内层逐字符比对。 */
    private fun scanLegacy(content: String, pattern: String): List<Pair<Int, Int>> {
        val matches = ArrayList<Pair<Int, Int>>()
        val n = content.length
        val m = pattern.length
        if (m == 0 || m > n) return matches
        var i = 0
        outer@ while (i <= n - m) {
            var ci = i
            var pj = 0
            while (pj < m) {
                val pc = pattern[pj]
                if (pc == '\n') {
                    if (ci + 1 < n && content[ci] == '\r' && content[ci + 1] == '\n') {
                        ci += 2
                    } else if (ci < n && content[ci] == '\n') {
                        ci += 1
                    } else {
                        i++
                        continue@outer
                    }
                } else if (ci < n && content[ci] == pc) {
                    ci += 1
                } else {
                    i++
                    continue@outer
                }
                pj++
            }
            matches.add(i to ci)
            i = ci
        }
        return matches
    }

    /** 新实现：多一步首字符快判（pattern 首字符为换行时跳过）。 */
    private fun scanCurrent(content: String, pattern: String): List<Pair<Int, Int>> {
        val matches = ArrayList<Pair<Int, Int>>()
        val n = content.length
        val m = pattern.length
        if (m == 0 || m > n) return matches
        var i = 0
        outer@ while (i <= n - m) {
            if (content[i] != pattern[0] && pattern[0] != '\n') {
                i++
                continue@outer
            }
            var ci = i
            var pj = 0
            while (pj < m) {
                val pc = pattern[pj]
                if (pc == '\n') {
                    if (ci + 1 < n && content[ci] == '\r' && content[ci + 1] == '\n') {
                        ci += 2
                    } else if (ci < n && content[ci] == '\n') {
                        ci += 1
                    } else {
                        i++
                        continue@outer
                    }
                } else if (ci < n && content[ci] == pc) {
                    ci += 1
                } else {
                    i++
                    continue@outer
                }
                pj++
            }
            matches.add(i to ci)
            i = ci
        }
        return matches
    }

    private fun assertSame(content: String, pattern: String) {
        assertEquals(
            "content=${content.replace("\r", "\\r").replace("\n", "\\n")} pattern=${pattern.replace("\r", "\\r").replace("\n", "\\n")}",
            scanLegacy(content, pattern),
            scanCurrent(content, pattern)
        )
    }

    @Test
    fun `LF 内容与普通模式等价`() {
        assertSame("a\nb\nc\n", "b")
        assertSame("a\nb\nc\n", "b\nc")
        assertSame("aaa", "aa")
        assertSame("abcabc", "abc")
    }

    @Test
    fun `CRLF 内容与 LF 模式等价（换行容忍）`() {
        assertSame("a\r\nb\r\nc\r\n", "b\nc")
        assertSame("a\r\nb\r\n", "a\nb")
        assertSame("x\r\ny", "x\ny")
    }

    @Test
    fun `模式以换行开头时走慢路径且等价`() {
        assertSame("a\nb", "\nb")
        assertSame("a\r\nb", "\nb")
        assertSame("\n\n", "\n\n")
    }

    @Test
    fun `不匹配的长模式等价（快判主收益场景）`() {
        val content = "x".repeat(2000) + "\n" + "y".repeat(2000)
        assertSame(content, "z" + "y".repeat(100))
        assertSame(content, "y".repeat(50) + "z")
    }

    @Test
    fun `模式含回车字符时等价`() {
        // 调用方只把 \r\n 归一为 \n，单独的 \r 理论上可能残留在 old_string 里。
        assertSame("a\rb", "\rb")
        assertSame("a\r\nb", "a\rb")
        assertSame("ab", "a\rb")
    }

    @Test
    fun `连续与重叠边界等价`() {
        assertSame("abab", "ab")
        assertSame("aaaa", "aa")
        assertSame("", "a")
        assertSame("a", "")
    }
}
