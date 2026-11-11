package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyPluginCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = TestFileAccessProvider()
    private val root: File get() = File(tmp.root, "plugins")

    private fun writeManifest(id: String, json: String) {
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, PluginManifestParser.FILE_NAME).writeText(json)
    }

    /** 旧版随包内置清单：用 builtinScenario 指向宿主内置场景。 */
    private fun legacyManifest(id: String) = """
        {"id":"$id","name":"旧内置","version":"1.0.0","apiVersion":1,
         "capabilities":{"provision":{"scenario":"${id.substringAfterLast('.')}","builtinScenario":"x"}}}
    """.trimIndent()

    /** 新格式清单：安装脚本随包。 */
    private fun scriptManifest(id: String) = """
        {"id":"$id","name":"新插件","version":"1.1.0","apiVersion":1,
         "capabilities":{"provision":{"scenario":"x","script":"install.sh"}}}
    """.trimIndent()

    private fun reaServer() = McpServerConfig(
        name = "rea",
        command = "npx",
        args = listOf("-y", "rea-agents@6.5.0", "mcp")
    )

    private fun otherServer() = McpServerConfig(name = "custom", command = "python3", args = listOf("a.py"))

    @Test
    fun `旧内置插件目录与对应 MCP 条目一并清理`() = runTest {
        writeManifest(PluginIds.REA, legacyManifest(PluginIds.REA))
        val mcp = mockk<McpConfigRepository>(relaxed = true)
        coEvery { mcp.getGlobalServers() } returns listOf(reaServer(), otherServer())

        val removed = LegacyPluginCleanup.run(provider, mcp, root.absolutePath)

        assertTrue(removed.contains(PluginIds.REA))
        assertFalse(File(root, PluginIds.REA).exists())
        coVerify {
            mcp.setGlobalServers(match { servers -> servers.map { it.name } == listOf("custom") })
        }
    }

    @Test
    fun `新格式插件目录保留且 MCP 原样不动`() = runTest {
        writeManifest(PluginIds.REA, scriptManifest(PluginIds.REA))
        val mcp = mockk<McpConfigRepository>(relaxed = true)
        coEvery { mcp.getGlobalServers() } returns listOf(reaServer())

        LegacyPluginCleanup.run(provider, mcp, root.absolutePath)

        assertTrue(File(root, PluginIds.REA).exists())
        coVerify(exactly = 0) { mcp.setGlobalServers(any()) }
    }

    @Test
    fun `目录里有多余文件时整体跳过不动`() = runTest {
        writeManifest(PluginIds.MULTILLM, legacyManifest(PluginIds.MULTILLM))
        File(root, PluginIds.MULTILLM).resolve("notes.txt").writeText("用户自己的东西")
        val mcp = mockk<McpConfigRepository>(relaxed = true)
        coEvery { mcp.getGlobalServers() } returns emptyList()

        LegacyPluginCleanup.run(provider, mcp, root.absolutePath)

        assertTrue(File(root, PluginIds.MULTILLM).exists())
    }

    @Test
    fun `同名但命令不同的 MCP 条目不算残留`() = runTest {
        val mcp = mockk<McpConfigRepository>(relaxed = true)
        val userOwned = McpServerConfig(name = "rea", command = "my-own-tool", args = listOf("serve"))
        coEvery { mcp.getGlobalServers() } returns listOf(userOwned)

        LegacyPluginCleanup.run(provider, mcp, root.absolutePath)

        coVerify(exactly = 0) { mcp.setGlobalServers(any()) }
    }
}
