package com.aicode.feature.settings.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SubprogramCrashStoreTest {

    /** 每个测试实例配独立临时目录（子程序崩溃账本按 filesDir/subprogram-crashes.json 落库），互不串扰。 */
    private fun newContext(): Context {
        val dir = Files.createTempDirectory("subcrash-test").toFile()
        return mockk { every { filesDir } returns dir }
    }

    /** 单次记录：count=1 且类型/消息原样落库。 */
    @Test
    fun record_firstCrash_countsOne() {
        val ctx = newContext()

        SubprogramCrashStore.record(ctx, "com.demo.app", "java.lang.NullPointerException", "boom at line 1", atMs = 1000L)

        val stats = SubprogramCrashStore.load(ctx)
        assertEquals(1, stats.size)
        val s = stats.getValue("com.demo.app")
        assertEquals(1, s.count)
        assertEquals(1000L, s.lastAtMs)
        assertEquals("java.lang.NullPointerException", s.lastType)
        assertEquals("boom at line 1", s.lastMessage)
    }

    /** 同包名多次崩溃：次数累计、最近时间/详情被新记录覆盖。 */
    @Test
    fun record_repeated_accumulatesCountAndOverwritesLast() {
        val ctx = newContext()

        SubprogramCrashStore.record(ctx, "com.demo.app", "java.lang.IllegalStateException", "first", atMs = 1000L)
        SubprogramCrashStore.record(ctx, "com.demo.app", "java.lang.IllegalStateException", "second", atMs = 2000L)
        SubprogramCrashStore.record(ctx, "com.demo.app", "java.lang.IllegalStateException", "third", atMs = 3000L)

        val s = SubprogramCrashStore.load(ctx).getValue("com.demo.app")
        assertEquals(3, s.count)
        assertEquals(3000L, s.lastAtMs)
        assertEquals("third", s.lastMessage)
    }

    /** 不同包名分别聚合，互不串扰。 */
    @Test
    fun record_multiplePackages_separateStats() {
        val ctx = newContext()

        SubprogramCrashStore.record(ctx, "com.a", "T", "ma", atMs = 1L)
        SubprogramCrashStore.record(ctx, "com.b", "T", "mb", atMs = 2L)
        SubprogramCrashStore.record(ctx, "com.a", "T", "ma2", atMs = 3L)

        val stats = SubprogramCrashStore.load(ctx)
        assertEquals(2, stats.size)
        assertEquals(2, stats.getValue("com.a").count)
        assertEquals(1, stats.getValue("com.b").count)
    }

    /** 超长消息截断到 500 字符，防止单条崩溃报告撑爆账本文件。 */
    @Test
    fun record_hugeMessage_truncated() {
        val ctx = newContext()

        SubprogramCrashStore.record(ctx, "com.a", "T", "x".repeat(10_000))

        assertTrue(SubprogramCrashStore.load(ctx).getValue("com.a").lastMessage.length <= 500)
    }

    /** 空白包名忽略——防御畸形/伪造广播不落账。 */
    @Test
    fun record_blankPackage_ignored() {
        val ctx = newContext()

        SubprogramCrashStore.record(ctx, "", "T", "m")
        SubprogramCrashStore.record(ctx, "   ", "T", "m")

        assertTrue(SubprogramCrashStore.load(ctx).isEmpty())
    }

    /** 持久化往返：记录后用新 Context（同目录）读回一致——进程重启不丢崩溃账。 */
    @Test
    fun persistence_roundTripAcrossInstances() {
        val dir = Files.createTempDirectory("subcrash-rt").toFile()
        val ctxA = mockk<Context> { every { filesDir } returns dir }
        val ctxB = mockk<Context> { every { filesDir } returns dir }

        SubprogramCrashStore.record(ctxA, "com.demo.app", "java.lang.OutOfMemoryError", "oom", atMs = 42L)

        val loaded = SubprogramCrashStore.load(ctxB).getValue("com.demo.app")
        assertEquals(1, loaded.count)
        assertEquals(42L, loaded.lastAtMs)
        assertEquals("java.lang.OutOfMemoryError", loaded.lastType)
    }

    /** 账本文件损坏（非 JSON）时重建为空账而非抛错——崩溃账本坏了不能反噬诊断页。 */
    @Test
    fun load_corruptedFile_rebuildsEmpty() {
        val dir = Files.createTempDirectory("subcrash-bad").toFile()
        val ctx = mockk<Context> { every { filesDir } returns dir }
        java.io.File(dir, "subprogram-crashes.json").writeText("不是JSON{{{")

        assertTrue(SubprogramCrashStore.load(ctx).isEmpty())
        // 损坏后仍可正常记录
        SubprogramCrashStore.record(ctx, "com.a", "T", "m")
        assertEquals(1, SubprogramCrashStore.load(ctx).getValue("com.a").count)
    }
}
