package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件包安装 / 卸载的行为边界。
 *
 * 重点在「坏包不能落盘」：解压在宿主进程里做，一个 `../` 就能写到 App 私有目录之外，
 * 因此路径校验、半装清理、重名保护这三条必须有单测钉住。
 */
class PluginInstallerTest {

    private val fs = InstallerMemoryFileAccess()
    private val root = "~/.aicode/plugins"
    private val store = PluginStateStore(fs, root)
    private val installer = PluginInstaller(fs, root, store)

    // ── 安装 ──

    @Test
    fun `根目录清单的包可以直接安装`() = runBlocking {
        val result = installer.installFromZip(
            zip(
                "plugin.json" to manifestJson("com.demo.hello"),
                "install.sh" to "#!/bin/sh\necho hi\n"
            )
        )

        assertTrue("安装应成功: $result", result is PluginInstallResult.Success)
        assertEquals("com.demo.hello", (result as PluginInstallResult.Success).manifest.id)
        assertEquals("#!/bin/sh\necho hi\n", fs.readFile("$root/com.demo.hello/install.sh"))
        assertNotNull("安装后要记下版本", store.stateOf("com.demo.hello")?.version)
        assertFalse("安装不等于启用", store.stateOf("com.demo.hello")!!.enabled)
    }

    @Test
    fun `单顶层目录的包也能安装且不重复拼目录名`() = runBlocking {
        val result = installer.installFromZip(
            zip(
                "com.demo.hello/plugin.json" to manifestJson("com.demo.hello"),
                "com.demo.hello/files/a.txt" to "a"
            )
        )

        assertTrue(result is PluginInstallResult.Success)
        assertTrue(fs.isFile("$root/com.demo.hello/files/a.txt"))
        assertFalse(
            "包内的顶层目录名不该被当成插件目录的一部分",
            fs.exists("$root/com.demo.hello/com.demo.hello")
        )
    }

    @Test
    fun `缺少清单直接拒绝`() = runBlocking {
        val result = installer.installFromZip(zip("readme.txt" to "hi"))

        assertEquals(
            PluginInstallError.MANIFEST_MISSING,
            (result as PluginInstallResult.Failed).error
        )
        assertFalse(fs.exists("$root/com.demo.hello"))
    }

    @Test
    fun `清单不合法直接拒绝`() = runBlocking {
        // apiVersion 超范围 + 未知权限：解析器会给出一串错误
        val bad = """{"id":"com.demo.hello","name":"X","version":"1.0.0","apiVersion":99}"""
        val result = installer.installFromZip(zip("plugin.json" to bad))

        assertEquals(
            PluginInstallError.MANIFEST_INVALID,
            (result as PluginInstallResult.Failed).error
        )
        assertFalse(fs.exists("$root/com.demo.hello"))
    }

    @Test
    fun `路径穿越的包整包拒绝`() = runBlocking {
        val result = installer.installFromZip(
            zip(
                "plugin.json" to manifestJson("com.demo.hello"),
                "../evil.sh" to "rm -rf /"
            )
        )

        assertEquals(
            PluginInstallError.BAD_PACKAGE,
            (result as PluginInstallResult.Failed).error
        )
        assertFalse("不得写到插件根之外", fs.exists("~/.aicode/evil.sh"))
        assertFalse(fs.exists("$root/com.demo.hello"))
    }

    @Test
    fun `同名插件已安装时不覆盖`() = runBlocking {
        fs.mkdirs("$root/com.demo.hello")
        fs.writeFile("$root/com.demo.hello/keep.txt", "user content")

        val result = installer.installFromZip(
            zip("plugin.json" to manifestJson("com.demo.hello"))
        )

        assertEquals(
            PluginInstallError.ALREADY_INSTALLED,
            (result as PluginInstallResult.Failed).error
        )
        assertEquals(
            "已有插件目录必须原样保留",
            "user content",
            fs.readFile("$root/com.demo.hello/keep.txt")
        )
    }

    @Test
    fun `不是 zip 的字节返回坏包`() = runBlocking {
        val result = installer.installFromZip("not a zip".toByteArray())

        assertEquals(
            PluginInstallError.BAD_PACKAGE,
            (result as PluginInstallResult.Failed).error
        )
    }

    // ── 卸载 ──

    @Test
    fun `卸载会摘能力、删目录并清状态`() = runBlocking {
        installer.installFromZip(zip("plugin.json" to manifestJson("com.demo.hello")))
        var detached: String? = null

        installer.uninstall("com.demo.hello") { id -> detached = id }

        assertEquals("com.demo.hello", detached)
        assertFalse(fs.exists("$root/com.demo.hello"))
        assertNull("状态要一并清掉，否则重装会带着旧开关", store.stateOf("com.demo.hello"))
    }

    // ── 辅助 ──

    private fun manifestJson(id: String): String =
        """{"id":"$id","name":"Demo","version":"1.0.0","apiVersion":1}"""

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/** 内存文件系统：安装器只依赖 [FileAccessProvider]，不需要真实磁盘。 */
private class InstallerMemoryFileAccess : FileAccessProvider {

    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()

    private fun norm(path: String) = path.trimEnd('/')

    override fun readFile(path: String): String =
        files[norm(path)]?.toString(Charsets.UTF_8) ?: throw FileNotFoundException(path)

    override fun readLines(path: String): Sequence<String> = readFile(path).lineSequence()

    override fun writeFile(
        path: String,
        content: String,
        overwrite: Boolean,
        encoding: Charset
    ) = writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun exists(path: String): Boolean = norm(path) in files || norm(path) in dirs

    override fun isDirectory(path: String): Boolean = norm(path) in dirs

    override fun isFile(path: String): Boolean = norm(path) in files

    override fun fileSize(path: String): Long = files[norm(path)]?.size?.toLong() ?: 0

    override fun lastModified(path: String): Long = 0

    override fun fingerprint(path: String): String? = null

    override fun permissions(path: String): String = "rwx"

    override fun listFiles(path: String): List<FileEntry> {
        val base = norm(path)
        return (files.keys + dirs)
            .filter { it.startsWith("$base/") && !it.removePrefix("$base/").contains('/') }
            .map { FileEntry(it.substringAfterLast('/'), it in dirs, 0, 0) }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        files.keys.filter { it.startsWith("${norm(path)}/") }.map { it.removePrefix("${norm(path)}/") }

    override fun readBytes(path: String): ByteArray =
        files[norm(path)] ?: throw FileNotFoundException(path)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val key = norm(path)
        if (!overwrite && key in files) throw FileAlreadyExistsException(path)
        files[key] = bytes
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val bytes = input.readBytes()
        writeBytes(path, bytes, overwrite)
        return bytes.size.toLong()
    }

    override fun copyToLocal(path: String): File = throw UnsupportedOperationException()

    override fun delete(path: String) {
        files.remove(norm(path))
        dirs.remove(norm(path))
    }

    override fun deleteRecursively(path: String) {
        val base = norm(path)
        files.keys.filter { it == base || it.startsWith("$base/") }.forEach { files.remove(it) }
        dirs.filter { it == base || it.startsWith("$base/") }.forEach { dirs.remove(it) }
    }

    override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()

    override fun copy(path: String, newPath: String, overwrite: Boolean) =
        throw UnsupportedOperationException()

    override fun move(path: String, newPath: String, overwrite: Boolean) =
        throw UnsupportedOperationException()

    override fun mkdirs(path: String) {
        var current = norm(path)
        while (current.isNotEmpty()) {
            dirs += current
            current = current.substringBeforeLast('/', "")
        }
    }

    override fun parentPath(path: String): String? {
        val parent = norm(path).substringBeforeLast('/', "")
        return parent.ifEmpty { null }
    }

    override fun toDisplayPath(path: String): String = path
}
