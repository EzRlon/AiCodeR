package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [SafeDelete] 的 NOFOLLOW 删除语义。
 *
 * 回归点：工作区被 bind mount 进容器，目录内容由 AI 全权写入且支持符号链接（npm/pnpm/git 都会建）。
 * 用 File.deleteRecursively 删工作区会跟着链接下钻，删掉链接指向的 ~/.aicode 等外部数据。
 */
class SafeDeleteTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "safedelete-test-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun deleteTree_删除整棵目录树() {
        val target = File(root, "workspace")
        File(target, "src/main").mkdirs()
        File(target, "src/main/Main.kt").writeText("fun main() {}")
        File(target, "README.md").writeText("hi")

        SafeDelete.deleteTree(target)

        assertFalse("目标目录必须被删干净", target.exists())
    }

    @Test
    fun deleteTree_不跟随目录符号链接_链接目标保留() {
        val outside = File(root, "outside").apply { mkdirs() }
        val important = File(outside, "important.txt").apply { writeText("keep me") }
        val target = File(root, "workspace").apply { mkdirs() }
        File(target, "local.txt").writeText("gone")
        Files.createSymbolicLink(File(target, "escape").toPath(), outside.toPath())

        SafeDelete.deleteTree(target)

        assertFalse("工作区本身要删掉", target.exists())
        assertTrue("符号链接指向的目录不能被跟着删掉", outside.exists())
        assertEquals("keep me", important.readText())
    }

    @Test
    fun deleteTree_符号链接指向文件时只删链接() {
        val outside = File(root, "outside.txt").apply { writeText("keep me") }
        val target = File(root, "workspace").apply { mkdirs() }
        Files.createSymbolicLink(File(target, "escape.txt").toPath(), outside.toPath())

        SafeDelete.deleteTree(target)

        assertFalse(target.exists())
        assertEquals("keep me", outside.readText())
    }

    @Test
    fun deleteTree_目标不存在时不抛异常() {
        SafeDelete.deleteTree(File(root, "nope"))
    }

    @Test
    fun deleteTree_返回删除条目数并从起始计数累加() {
        val target = File(root, "workspace")
        File(target, "a/b").mkdirs()
        File(target, "a/b/c.txt").writeText("x")

        // 文件 c.txt + 目录 b、a、workspace 共 4 个条目
        assertEquals(4, SafeDelete.deleteTree(target))
        // 起始计数原样带入：目标不存在时只回调一次 onProgress，累计值等于 startCount
        var reported = -1
        SafeDelete.deleteTree(File(root, "nope"), startCount = 10) { reported = it }
        assertEquals(10, reported)
    }
}
