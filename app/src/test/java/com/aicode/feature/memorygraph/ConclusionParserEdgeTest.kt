package com.aicode.feature.memorygraph

import com.aicode.feature.memorygraph.domain.ConclusionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 段标题判定改锚定行首后的边界用例：这些是压缩摘要里真实出现过的写法，
 * 任何一条被排除都会让结论丢失或标签错位。
 */
class ConclusionParserEdgeTest {

    @Test
    fun `带编号冒号缩进的标准九段式标题`() {
        val summary = """
            1. 任务与背景：用户要评估两天内的未提交改动
            2. 已完成：记忆与技能入库
            5. 关键决定与理由：data 目录不能裁
            7. 教训与坑：Kotlin 块注释可嵌套
            9. 下一步：重出正式包
        """.trimIndent()

        val result = ConclusionParser.parse(summary)

        assertEquals(listOf("定案", "坑"), result.map { it.label }.distinct())
    }

    @Test
    fun `行首直接是段名的正文被当作标题、内容丢弃（已知边界）`() {
        // 已知残留边界：行首就是段名、前面既无编号也无列表符号时，仍会被当作标题行。
        // 与修改前的区别：旧逻辑会把它当段标题并把后续行归到错标签；现在只丢这一行、
        // 不会错标后续行。真实压缩摘要的正文都以列表符号起行（见上一个用例），不触发。
        val summary = "关键决定与理由已过期，本次重新评估"

        val result = ConclusionParser.parse(summary)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `列表符号起行的正文不会误切段`() {
        val summary = """
            5. 关键决定与理由：
            - 沿用九段式摘要
            - 关键决定与理由这一节之前写过，已过期
        """.trimIndent()

        val result = ConclusionParser.parse(summary)

        assertEquals(2, result.size)
        assertTrue(result.all { it.label == "定案" })
    }

    @Test
    fun `全角括号编号与顿号编号都能识别`() {
        val summary = """
            （5）关键决定与理由：选了 A
            7、教训与坑：不要裁 data
        """.trimIndent()

        val result = ConclusionParser.parse(summary)

        assertEquals(listOf("定案", "坑"), result.map { it.label })
    }
}
