package com.aicode.feature.memorygraph

import com.aicode.feature.memorygraph.domain.ConclusionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConclusionParserTest {

    @Test
    fun `编号段标题被识别为段落起点`() {
        val summary = """
            5. 关键决定与理由：压缩改用九段式摘要
            6. 关键数据：AgentDatabase.kt:27
        """.trimIndent()

        val result = ConclusionParser.parse(summary)

        assertEquals(1, result.size)
        assertEquals("定案", result[0].label)
        assertTrue(result[0].text.contains("九段式摘要"))
    }

    @Test
    fun `正文提到段名不当作标题，不被提前切段`() {
        val summary = """
            5. 关键决定与理由：
            - 沿用九段式摘要
            - 之前的关键决定与理由已过期，本次重新评估
            - 压缩窗口按模型上下文自适应
        """.trimIndent()

        val result = ConclusionParser.parse(summary)

        assertEquals(3, result.size)
        assertTrue(result.all { it.label == "定案" })
        assertTrue(result.any { it.text.contains("重新评估") })
        assertTrue(result.any { it.text.contains("自适应") })
    }

    @Test
    fun `粗体与无编号标题都能识别`() {
        val summary = """
            **教训与坑**
            - ShellQuote 四份转义规则不一致即命令注入
            未完成：
            - 巨型文件拆分待立项
        """.trimIndent()

        val result = ConclusionParser.parse(summary)

        assertEquals(listOf("坑", "待续"), result.map { it.label })
    }
}
