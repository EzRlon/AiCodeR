package com.aicode.feature.agent.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 改动账本自动补录的路径提取：只认写操作，读操作与坏参数一律不记。 */
class ChangedFileCollectorTest {

    @Test
    fun `写文件工具能取出路径`() {
        assertEquals(
            "~/workspace/app/Main.kt",
            ChangedFileCollector.extractPath("writeFile", """{"path":"~/workspace/app/Main.kt","content":"x"}""")
        )
        assertEquals(
            "src/a.kt",
            ChangedFileCollector.extractPath("editFile", """{"path":"src/a.kt","old":"a","new":"b"}""")
        )
    }

    @Test
    fun `读类工具不记入改动`() {
        assertNull(ChangedFileCollector.extractPath("readFile", """{"path":"src/a.kt"}"""))
        assertNull(ChangedFileCollector.extractPath("search", """{"path":"src/a.kt"}"""))
        assertNull(ChangedFileCollector.extractPath("Bash", """{"path":"src/a.kt"}"""))
        assertFalse(ChangedFileCollector.isWriteTool("readFile"))
        assertTrue(ChangedFileCollector.isWriteTool("writeFile"))
    }

    @Test
    fun `参数坏掉或缺少路径时返回 null 而不抛异常`() {
        assertNull(ChangedFileCollector.extractPath("writeFile", null))
        assertNull(ChangedFileCollector.extractPath("writeFile", ""))
        assertNull(ChangedFileCollector.extractPath("writeFile", "{not json"))
        assertNull(ChangedFileCollector.extractPath("writeFile", """{"content":"x"}"""))
        assertNull(ChangedFileCollector.extractPath("writeFile", """{"path":"   "}"""))
    }

    @Test
    fun `超长路径按内容误填处理，不记入`() {
        val huge = "a".repeat(1000)
        assertNull(ChangedFileCollector.extractPath("writeFile", """{"path":"$huge"}"""))
    }

    @Test
    fun `多文件补丁可取出 paths 列表`() {
        val paths = ChangedFileCollector.extractPaths("applyPatch", """{"paths":["a.kt","b.kt"]}""")
        assertEquals(listOf("a.kt", "b.kt"), paths)
    }
}
