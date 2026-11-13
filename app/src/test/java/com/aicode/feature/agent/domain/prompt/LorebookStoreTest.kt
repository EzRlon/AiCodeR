package com.aicode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 世界书解析、触发词召回与渲染。 */
class LorebookStoreTest {

    private fun tempRoot(): File = Files.createTempDirectory("lorebook-test").toFile()

    private fun write(root: File, name: String, text: String) {
        val dir = File(root, LorebookStore.DIR).apply { mkdirs() }
        File(dir, "$name.md").writeText(text)
    }

    @Test
    fun `解析 frontmatter 与正文`() {
        val root = tempRoot()
        write(
            root, "张三",
            """
            ---
            keywords: 张三, 剑士
            priority: 10
            ---
            张三，三十岁，佩剑「霜落」。
            """.trimIndent()
        )
        val entries = LorebookStore.load(root.absolutePath)
        assertEquals(1, entries.size)
        val e = entries.first()
        assertEquals("张三", e.name)
        assertEquals(listOf("张三", "剑士"), e.keywords)
        assertEquals(10, e.priority)
        assertTrue(e.content.startsWith("张三，三十岁"))
    }

    @Test
    fun `无 frontmatter 视为常驻条目`() {
        val root = tempRoot()
        write(root, "世界观", "这是一个剑与魔法的世界。")
        val entries = LorebookStore.load(root.absolutePath)
        assertTrue(entries.single().keywords.isEmpty())
        // 常驻条目即使输入无关也应召回
        assertEquals(1, LorebookStore.recall(entries, "随便说点什么").size)
    }

    @Test
    fun `触发词命中才召回且大小写不敏感`() {
        val root = tempRoot()
        write(root, "骑士", "---\nkeywords: Knight\n---\n骑士团设定。")
        val entries = LorebookStore.load(root.absolutePath)
        assertTrue(LorebookStore.recall(entries, "讲讲 knight 的故事").isNotEmpty())
        assertTrue(LorebookStore.recall(entries, "完全无关的话").isEmpty())
    }

    @Test
    fun `停用条目即使命中也不召回`() {
        val root = tempRoot()
        write(root, "禁用", "---\nkeywords: 龙\nenabled: false\n---\n龙的设定。")
        val entries = LorebookStore.load(root.absolutePath)
        assertTrue(LorebookStore.recall(entries, "龙").isEmpty())
    }

    @Test
    fun `按优先级排序并渲染`() {
        val root = tempRoot()
        write(root, "普通", "---\nkeywords: 城\npriority: 1\n---\n普通城。")
        write(root, "重要", "---\nkeywords: 城\npriority: 9\n---\n重要城。")
        val hits = LorebookStore.recall(LorebookStore.load(root.absolutePath), "城")
        assertEquals(listOf("重要", "普通"), hits.map { it.name })
        val rendered = LorebookStore.render(hits)!!
        assertTrue(rendered.indexOf("重要") < rendered.indexOf("普通"))
    }

    @Test
    fun `无条目时渲染为 null`() {
        assertNull(LorebookStore.render(emptyList()))
    }
}
