package com.aicode.feature.agent.domain.engine

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException

/**
 * `send_file` 入参校验的行为边界。
 *
 * 核心约束是**原子语义**（与内置 SendFileTool 一致）：任一文件无效则整体失败，
 * 不产出任何附件。破坏它的后果是双重的——用户看到半份交付，模型却收到「成功」。
 */
class CliDeliveryPayloadTest {

    private val fs = FakeFileAccess()

    private fun input(vararg paths: String) = buildJsonObject {
        put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
    }

    // ── 入参提取 ──────────────────────────────────────────────────

    @Test
    fun `提取路径数组`() {
        val paths = CliDeliveryPayload.extractPaths(input("/a.apk", "/b.apk"))

        assertEquals(listOf("/a.apk", "/b.apk"), paths)
    }

    @Test
    fun `缺 paths 字段时返回空`() {
        assertTrue(CliDeliveryPayload.extractPaths(buildJsonObject {}).isEmpty())
    }

    @Test
    fun `非数组的 paths 返回空`() {
        val bad = buildJsonObject { put("paths", JsonPrimitive("单个字符串")) }

        assertTrue(CliDeliveryPayload.extractPaths(bad).isEmpty())
    }

    @Test
    fun `数组里的非字符串项被忽略`() {
        val mixed = JsonObject(
            mapOf(
                "paths" to JsonArray(
                    listOf(JsonPrimitive("/ok.apk"), JsonPrimitive(123), JsonPrimitive(true))
                )
            )
        )

        assertEquals(listOf("/ok.apk"), CliDeliveryPayload.extractPaths(mixed))
    }

    // ── 原子语义 ──────────────────────────────────────────────────

    @Test
    fun `单个文件正常产出附件`() {
        fs.addFile("/root/workspace/app.apk", 2048)

        val payload = CliDeliveryPayload.build(listOf("/root/workspace/app.apk"), fs)

        assertTrue(payload.ok)
        assertEquals(1, payload.attachments.size)
        assertEquals("app.apk", payload.attachments.first().fileName)
        assertEquals("/root/workspace/app.apk", payload.attachments.first().containerPath)
        assertEquals(2048L, payload.attachments.first().sizeBytes)
    }

    @Test
    fun `多个文件全部有效时全部产出`() {
        fs.addFile("/w/a.apk", 1)
        fs.addFile("/w/b.txt", 2)

        val payload = CliDeliveryPayload.build(listOf("/w/a.apk", "/w/b.txt"), fs)

        assertTrue(payload.ok)
        assertEquals(listOf("a.apk", "b.txt"), payload.attachments.map { it.fileName })
    }

    @Test
    fun `任一文件不存在则整体失败，不产出任何附件`() {
        fs.addFile("/w/a.apk", 1)

        val payload = CliDeliveryPayload.build(listOf("/w/a.apk", "/w/missing.apk"), fs)

        assertFalse("有一个无效就必须整体失败", payload.ok)
        assertTrue("不能产出半份交付", payload.attachments.isEmpty())
        assertTrue(payload.errors.any { it.contains("missing.apk") })
    }

    @Test
    fun `目录不算可交付文件`() {
        fs.addDir("/w/somedir")

        val payload = CliDeliveryPayload.build(listOf("/w/somedir"), fs)

        assertFalse(payload.ok)
        assertTrue(payload.attachments.isEmpty())
    }

    @Test
    fun `空列表整体失败`() {
        val payload = CliDeliveryPayload.build(emptyList(), fs)

        assertFalse(payload.ok)
        assertTrue(payload.errors.any { it.contains("paths") })
    }

    @Test
    fun `只有空白路径时整体失败`() {
        val payload = CliDeliveryPayload.build(listOf("  ", ""), fs)

        assertFalse(payload.ok)
    }

    @Test
    fun `路径首尾空白被裁掉`() {
        fs.addFile("/w/a.apk", 1)

        val payload = CliDeliveryPayload.build(listOf("  /w/a.apk  "), fs)

        assertTrue(payload.ok)
        assertEquals("/w/a.apk", payload.attachments.first().containerPath)
    }

    // ── MIME 与图片判定 ──────────────────────────────────────────

    @Test
    fun `按扩展名推断 MIME`() {
        fs.addFile("/w/pic.png", 10)

        val payload = CliDeliveryPayload.build(listOf("/w/pic.png"), fs)

        assertEquals("image/png", payload.attachments.first().mimeType)
        assertTrue("图片应标记 isImage", payload.attachments.first().isImage)
    }

    @Test
    fun `apk 的 MIME 不是图片`() {
        fs.addFile("/w/app.apk", 10)

        val payload = CliDeliveryPayload.build(listOf("/w/app.apk"), fs)

        assertFalse(payload.attachments.first().isImage)
    }

    @Test
    fun `无法推断扩展名时用默认 MIME`() {
        fs.addFile("/w/未知文件", 10)

        val payload = CliDeliveryPayload.build(listOf("/w/未知文件"), fs)

        assertEquals(CliDeliveryPayload.DEFAULT_MIME, payload.attachments.first().mimeType)
    }
}

/** 内存文件系统：只实现本测试用到的方法。 */
private class FakeFileAccess : FileAccessProvider {

    private val files = mutableMapOf<String, Long>()
    private val dirs = mutableSetOf<String>()

    fun addFile(path: String, size: Long) {
        files[path] = size
    }

    fun addDir(path: String) {
        dirs += path
    }

    override fun exists(path: String) = path in files || path in dirs

    override fun isDirectory(path: String) = path in dirs

    override fun isFile(path: String) = path in files

    override fun fileSize(path: String) = files[path] ?: 0L

    override fun copyToLocal(path: String): File =
        File("/tmp/${path.substringAfterLast('/')}")

    override fun readFile(path: String): String = throw FileNotFoundException(path)

    override fun readLines(path: String): Sequence<String> = emptySequence()

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) = Unit

    override fun lastModified(path: String) = 0L

    override fun fingerprint(path: String): String? = null

    override fun permissions(path: String) = "rwx"

    override fun listFiles(path: String): List<FileEntry> = emptyList()

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = emptyList()

    override fun readBytes(path: String): ByteArray = throw FileNotFoundException(path)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) = Unit

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = 0

    override fun delete(path: String) = Unit

    override fun deleteRecursively(path: String) = Unit

    override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()

    override fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()

    override fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()

    override fun mkdirs(path: String) = Unit

    override fun parentPath(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }

    override fun toDisplayPath(path: String) = path
}
