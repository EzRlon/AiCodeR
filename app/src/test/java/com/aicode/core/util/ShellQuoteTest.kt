package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单引号转义的唯一实现。
 *
 * 回归点：此前四处拷贝，其中 ShellArgs 那份把内嵌单引号错写成 `'"'"'`——
 * 关闭单引号后落成双引号字面量，拼出的命令与预期不同。这类安全相关代码只允许一份。
 */
class ShellQuoteTest {

    @Test
    fun 安全字符不加引号() {
        assertEquals("src/main.kt", shellQuote("src/main.kt"))
        assertEquals("-n", shellQuote("-n"))
        assertEquals("a_b@c:d=e+f,g%h-i", shellQuote("a_b@c:d=e+f,g%h-i"))
    }

    @Test
    fun 空格与特殊字符整体包单引号() {
        assertEquals("'a b'", shellQuote("a b"))
        assertEquals("'a|b'", shellQuote("a|b"))
        assertEquals("'$HOME'", shellQuote("$HOME"))
        assertEquals("'`id`'", shellQuote("`id`"))
        assertEquals("'*'", shellQuote("*"))
    }

    @Test
    fun 内嵌单引号关闭转义重开() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun 空串返回一对单引号() {
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun 换行与制表符也被引号包住() {
        assertEquals("'a\nb'", shellQuote("a\nb"))
        assertEquals("'a\tb'", shellQuote("a\tb"))
    }
}
