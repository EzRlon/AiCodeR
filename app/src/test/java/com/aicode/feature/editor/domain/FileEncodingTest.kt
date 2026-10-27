package com.aicode.feature.editor.domain

import com.aicode.feature.editor.domain.FileEncoding.Companion.strictEncode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/**
 * 编码检测与严格编码。
 *
 * 回归点：编辑器曾把 GBK 文件按 UTF-8 解码后默认按 UTF-8 写回，一次保存就把原文件编码永久改坏；
 * 反过来，GBK 编码表示不了的字符经 `toByteArray` 会静默变成 '?'，等于丢字。
 */
class FileEncodingTest {

    private val gbk = Charset.forName("GBK")

    @Test
    fun 纯ASCII按UTF8解码() {
        val decoded = FileEncoding.decode("fun main() {}".toByteArray(Charsets.UTF_8))

        assertEquals("fun main() {}", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
        assertNull(decoded.bom)
    }

    @Test
    fun GBK中文解出正确文本而不是乱码() {
        val original = "中文注释"
        val decoded = FileEncoding.decode(original.toByteArray(gbk))

        assertEquals(original, decoded.text)
        assertEquals(FileEncoding.GBK, decoded.encoding)
    }

    @Test
    fun 带BOM的UTF8解码剥掉BOM并记下BOM() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val decoded = FileEncoding.decode(bom + "abc".toByteArray(Charsets.UTF_8))

        assertEquals("abc", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
        assertArrayEquals(bom, decoded.bom)
    }

    @Test
    fun 带BOM的UTF16LE解码() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val decoded = FileEncoding.decode(bom + "中文".toByteArray(Charsets.UTF_16LE))

        assertEquals("中文", decoded.text)
        assertEquals(FileEncoding.UTF_16_LE, decoded.encoding)
        assertArrayEquals(bom, decoded.bom)
    }

    @Test
    fun 空文件按UTF8空串() {
        val decoded = FileEncoding.decode(ByteArray(0))

        assertEquals("", decoded.text)
        assertEquals(FileEncoding.UTF_8, decoded.encoding)
    }

    @Test
    fun 严格编码_可表示时字节与原编码一致() {
        val text = "中文注释"
        assertArrayEquals(text.toByteArray(gbk), gbk.strictEncode(text))
    }

    @Test
    fun 严格编码_GBK表示不了的字符抛异常而不是变成问号() {
        val text = "emoji 😀"
        try {
            gbk.strictEncode(text)
            fail("GBK 无法表示 emoji，必须抛异常而不是静默替换为 ?")
        } catch (e: CharacterCodingException) {
            assertTrue(true)
        }
    }

    @Test
    fun 严格编码_UTF8可表示任意字符() {
        val text = "中文 emoji 😀"
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8.strictEncode(text))
    }
}
