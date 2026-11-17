package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.testutil.TestFileAccessProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = TestFileAccessProvider()
    private val pluginsRoot: File get() = File(tmp.root, "plugins")
    private val stateStore: PluginStateStore get() = PluginStateStore(provider, pluginsRoot.absolutePath)
    private val registry: PluginRegistry
        get() = PluginRegistry(provider, pluginsRoot.absolutePath, stateStore)

    private fun writePlugin(dirName: String, json: String) {
        val dir = File(pluginsRoot, dirName)
        dir.mkdirs()
        File(dir, PluginManifestParser.FILE_NAME).writeText(json)
    }

    private fun manifestJson(id: String, extra: String = "") = """
        {"id":"$id","name":"示例","version":"1.0.0","apiVersion":1$extra}
    """.trimIndent()

    @Test
    fun `根目录不存在时返回空结果`() {
        val result = registry.scan()
        assertTrue(result.plugins.isEmpty())
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `正常插件被扫出且默认未启用`() {
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))
        val plugin = registry.scan().plugins.single()
        assertEquals("com.aicode.demo", plugin.id)
        assertFalse(plugin.enabled)
    }

    @Test
    fun `目录名与 id 不一致进入问题列表`() {
        writePlugin("wrong-dir", manifestJson("com.aicode.demo"))
        val result = registry.scan()
        assertTrue(result.plugins.isEmpty())
        assertEquals("wrong-dir", result.problems.single().dirName)
        assertTrue(result.problems.single().errors.first().contains("不一致"))
    }

    @Test
    fun `坏清单进入问题列表且不影响其它插件`() {
        writePlugin("com.aicode.broken", "{ 坏 json")
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))

        val result = registry.scan()
        assertEquals(listOf("com.aicode.demo"), result.plugins.map { it.id })
        assertEquals(listOf("com.aicode.broken"), result.problems.map { it.dirName })
    }

    @Test
    fun `同一 id 不能被两个目录同时声明`() {
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))
        writePlugin("com.aicode.demo2", manifestJson("com.aicode.demo"))

        val result = registry.scan()
        assertEquals(listOf("com.aicode.demo"), result.plugins.map { it.id })
        assertEquals(listOf("com.aicode.demo2"), result.problems.map { it.dirName })
    }

    @Test
    fun `隐藏目录与非目录文件被忽略`() {
        File(pluginsRoot, ".hidden").mkdirs()
        pluginsRoot.mkdirs()
        File(pluginsRoot, "README.md").writeText("x")
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))

        val result = registry.scan()
        assertEquals(1, result.plugins.size)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `未同意免责不允许启用`() {
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))
        assertFalse(registry.setEnabled("com.aicode.demo", true))
        assertFalse(registry.scan().plugins.single().enabled)
    }

    @Test
    fun `同意免责后可启用与禁用`() {
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))
        registry.recordConsent("com.aicode.demo", version = "1.0.0", atMillis = 42L)

        assertTrue(registry.setEnabled("com.aicode.demo", true))
        assertTrue(registry.scan().plugins.single().enabled)
        assertEquals(42L, registry.scan().plugins.single().state.consentAt)

        assertTrue(registry.setEnabled("com.aicode.demo", false))
        assertFalse(registry.scan().plugins.single().enabled)
    }

    @Test
    fun `未安装的插件无法启用`() {
        pluginsRoot.mkdirs()
        assertFalse(registry.setEnabled("com.aicode.nothere", true))
    }

    @Test
    fun `recordConsent 不覆盖首次安装时间`() {
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))
        registry.recordConsent("com.aicode.demo", version = "1.0.0", atMillis = 10L)
        registry.recordConsent("com.aicode.demo", version = "1.1.0", atMillis = 99L)

        val state = registry.scan().plugins.single().state
        assertEquals(10L, state.installedAt)
        assertEquals(99L, state.consentAt)
        assertEquals("1.1.0", state.version)
    }

    @Test
    fun `enabledPlugins 只返回启用项`() {
        writePlugin("com.aicode.a", manifestJson("com.aicode.a"))
        writePlugin("com.aicode.b", manifestJson("com.aicode.b"))
        registry.recordConsent("com.aicode.a", "1.0.0", 1L)
        registry.recordConsent("com.aicode.b", "1.0.0", 1L)
        registry.setEnabled("com.aicode.b", true)

        assertEquals(listOf("com.aicode.b"), registry.enabledPlugins().map { it.id })
    }

    @Test
    fun `扫描经 FileAccessProvider 以容器路径访问`() {
        // 回归：曾经直接用 java.io.File 扫本地目录，远程 SSH 模式下列表恒空。
        val inner = TestFileAccessProvider()
        val listed = mutableListOf<String>()
        val provider = object : FileAccessProvider by inner {
            override fun listFiles(path: String): List<FileEntry> {
                listed += path
                return inner.listFiles(path)
            }
        }
        val registry = PluginRegistry(
            provider,
            "~/.aicode/plugins",
            PluginStateStore(provider, "~/.aicode/plugins")
        )

        registry.scan()
        assertEquals(listOf("~/.aicode/plugins"), listed)
    }

    @Test
    fun `卸载清状态后不再残留启用标记`() {
        writePlugin("com.aicode.demo", manifestJson("com.aicode.demo"))
        registry.recordConsent("com.aicode.demo", "1.0.0", 1L)
        registry.setEnabled("com.aicode.demo", true)

        registry.forget("com.aicode.demo")
        assertFalse(registry.scan().plugins.single().enabled)
        assertTrue(stateStore.load().isEmpty())
    }
}
