package com.aicode.feature.agent.domain.tool.container

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * terminal 工具的两个参数解析函数：模型既可能传标准 JSON 数组，也可能传逗号分隔字符串。
 */
class BackgroundTerminalToolsParsingTest {

    @Test
    fun parseTabIds_readsJsonArray() {
        val element = JsonArray(listOf(JsonPrimitive("term-1"), JsonPrimitive("term-2")))
        assertEquals(listOf("term-1", "term-2"), parseTabIds(element))
    }

    @Test
    fun parseTabIds_acceptsDelimitedString() {
        assertEquals(listOf("term-1", "term-2", "term-3"), parseTabIds(JsonPrimitive("term-1, term-2; term-3")))
    }

    @Test
    fun parseTabIds_trimsAndDropsBlanks() {
        assertEquals(listOf("term-1"), parseTabIds(JsonPrimitive("  term-1 ,  ")))
    }

    @Test
    fun parseTabIds_returnsEmptyForMissingArg() {
        assertEquals(emptyList<String>(), parseTabIds(null))
    }

    @Test
    fun filterOutputLines_keepsMatchingLinesOnly() {
        val output = "line one\nERROR: boom\nline three\r\nERROR: again"
        assertEquals(
            listOf("ERROR: boom", "ERROR: again"),
            filterOutputLines(output, Regex("ERROR"))
        )
    }

    @Test
    fun filterOutputLines_supportsCaseInsensitiveFlag() {
        val output = "Error: a\nok\nERROR: b"
        assertEquals(2, filterOutputLines(output, Regex("(?i)error")).size)
    }

    @Test
    fun filterOutputLines_returnsEmptyWhenNoMatch() {
        assertEquals(emptyList<String>(), filterOutputLines("a\nb", Regex("zzz")))
    }
}
