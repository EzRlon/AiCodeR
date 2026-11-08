package com.aicode.feature.plugin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PluginStateStore(tmp.root)

    @Test
    fun `文件不存在时为空状态`() {
        assertTrue(store().load().isEmpty())
        assertNull(store().stateOf("com.aicode.demo"))
    }

    @Test
    fun `写入后可读回且字段完整`() {
        val s = store()
        s.update("com.aicode.demo") { it.copy(enabled = true, consentAt = 100L, version = "1.0.0") }

        val reloaded = PluginStateStore(tmp.root).stateOf("com.aicode.demo")!!
        assertTrue(reloaded.enabled)
        assertEquals(100L, reloaded.consentAt)
        assertEquals("1.0.0", reloaded.version)
    }

    @Test
    fun `多次更新只改目标字段`() {
        val s = store()
        s.update("com.aicode.demo") { it.copy(consentAt = 1L) }
        s.update("com.aicode.demo") { it.copy(enabled = true) }

        val state = s.stateOf("com.aicode.demo")!!
        assertEquals(1L, state.consentAt)
        assertTrue(state.enabled)
    }

    @Test
    fun `ownedFiles 去重后落盘`() {
        val s = store()
        s.update("com.aicode.demo") { it.copy(ownedFiles = listOf("skills/a", "skills/a", "skills/b")) }
        assertEquals(listOf("skills/a", "skills/b"), s.stateOf("com.aicode.demo")!!.ownedFiles)
    }

    @Test
    fun `remove 清掉条目`() {
        val s = store()
        s.update("com.aicode.demo") { it.copy(enabled = true) }
        s.remove("com.aicode.demo")
        assertTrue(s.load().isEmpty())
    }

    @Test
    fun `状态文件损坏时按空状态处理且不抛异常`() {
        File(tmp.root, PluginStateStore.FILE_NAME).writeText("{ 这不是 json")
        val state = store().load()
        assertTrue(state.isEmpty())
    }

    @Test
    fun `未知字段不影响读取以保证前向兼容`() {
        File(tmp.root, PluginStateStore.FILE_NAME).writeText(
            """{"plugins":{"com.aicode.demo":{"enabled":true,"futureFlag":1}}}"""
        )
        assertTrue(store().stateOf("com.aicode.demo")!!.enabled)
    }

    @Test
    fun `落盘后不残留临时文件`() {
        store().update("com.aicode.demo") { it.copy(enabled = true) }
        assertFalse(File(tmp.root, PluginStateStore.FILE_NAME + ".tmp").exists())
    }
}
