package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置原生插件清单的静态校验（纯 JVM，工作目录 = 模块根 app/）。
 *
 * 这些清单随 App 分发，写坏了不会崩溃，只会在插件页变成一个「问题目录」，
 * 或者被静默扫不到。这里在打包前把契约错误拦下来。
 */
class BuiltinPluginsAssetsTest {

    private val pluginsDir = File("src/main/assets/builtin-plugins")

    private fun pluginDirs(): List<File> =
        pluginsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun `每个内置插件清单可解析且目录名等于 id`() {
        assertTrue("找不到内置插件目录 ${pluginsDir.absolutePath}", pluginsDir.isDirectory)
        val dirs = pluginDirs()
        assertTrue("内置插件目录为空", dirs.isNotEmpty())

        for (dir in dirs) {
            val file = File(dir, PluginManifestParser.FILE_NAME)
            assertTrue("${dir.name} 缺少 ${PluginManifestParser.FILE_NAME}", file.isFile)
            val result = PluginManifestParser.parse(file.readText())
            assertTrue(
                "${dir.name} 清单不合法: ${(result as? PluginManifestResult.Invalid)?.errors}",
                result is PluginManifestResult.Valid
            )
            val manifest = (result as PluginManifestResult.Valid).manifest
            assertEquals("${dir.name} 的 id 应与目录名一致", dir.name, manifest.id)
        }
    }

    @Test
    fun `内置插件必须声明内置场景入口`() {
        for (dir in pluginDirs()) {
            val manifest = (
                PluginManifestParser.parse(File(dir, PluginManifestParser.FILE_NAME).readText())
                    as PluginManifestResult.Valid
                ).manifest
            val provision = manifest.capabilities.provision
            assertNotNull("${dir.name} 缺少 provision", provision)
            val scenario = provision?.builtinScenario
            assertNotNull("${dir.name} 应使用 builtinScenario", scenario)
            assertTrue(
                "${dir.name} 的场景必须在白名单内",
                scenario != null && scenario in PluginManifestParser.BUILTIN_SCENARIOS
            )
        }
    }
}
