package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可分发的插件包（`data/plugins/<id>/`）静态校验。
 *
 * 这些目录**不再随 APK 下发**，而是打成 zip 由用户「从文件安装」。但它们仍是官方插件，
 * 清单或安装脚本写坏了只会在用户设备上暴露，所以在这里把契约错误拦下来：
 * 目录名 = id、清单可解析、声明的安装脚本确实存在、脚本依赖的随包资源也在。
 *
 * 工作目录是模块根 `app/`，仓库根 = `..`。
 */
class PluginPackagesTest {

    private val packagesDir = File("../data/plugins")

    private fun packageDirs(): List<File> =
        packagesDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun `每个插件包清单可解析且目录名等于 id`() {
        assertTrue("找不到插件包目录 ${packagesDir.absolutePath}", packagesDir.isDirectory)
        val dirs = packageDirs()
        assertTrue("插件包目录为空", dirs.isNotEmpty())

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
    fun `声明了环境准备就必须带上安装脚本`() {
        for (dir in packageDirs()) {
            val manifest = (
                PluginManifestParser.parse(File(dir, PluginManifestParser.FILE_NAME).readText())
                    as PluginManifestResult.Valid
                ).manifest
            val provision = manifest.capabilities.provision ?: continue
            val script = File(dir, provision.script)
            assertTrue(
                "${dir.name} 声明了 provision.script=${provision.script}，但包里没有这个文件",
                script.isFile
            )
        }
    }

    @Test
    fun `顾问团包必须随包携带服务端源码`() {
        // install.sh 会把 server/ 复制到 ~/.aicode/multi-llm/；源码不在包里，装完也起不来。
        val server = File("../data/plugins/com.aicode.multillm/server/server.py")
        assertTrue("顾问团包缺少 server/server.py", server.isFile)
        val install = File("../data/plugins/com.aicode.multillm/install.sh")
        assertNotNull(install.takeIf { it.isFile })
    }

    @Test
    fun `插件包不再使用宿主内置场景字段`() {
        // builtinScenario 已从清单 API 移除：出现该字段说明包还是旧格式，装上去会拿不到安装入口。
        for (dir in packageDirs()) {
            val text = File(dir, PluginManifestParser.FILE_NAME).readText()
            assertTrue(
                "${dir.name} 仍在用 builtinScenario（已废弃，应改为包内 install.sh）",
                !text.contains("builtinScenario")
            )
        }
    }
}
