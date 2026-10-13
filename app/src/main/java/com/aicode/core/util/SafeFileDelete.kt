package com.aicode.core.util

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 符号链接安全的递归删除。
 *
 * [java.io.File.deleteRecursively] 会**跟随目录符号链接**并把链接目标一并删除——
 * 于 node_modules（file: 依赖 / workspace 链接 / pnpm 链接）与 venv（`lib64 -> lib`）
 * 等场景，可能越出目标子树、误删真实源码甚至其它目录。
 *
 * 本实现基于 [Files.walkFileTree]（默认不跟随符号链接）：符号链接只删除链接本身，
 * 目录自底向上删除。返回是否全部删除成功（部分失败为 false）。
 */
object SafeFileDelete {
    fun deleteRecursively(target: java.io.File): Boolean {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            // 普通文件或符号链接：只删条目本身。
            return runCatching { Files.deleteIfExists(path) }.getOrDefault(false)
        }
        var ok = true
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) ok = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (!runCatching { Files.deleteIfExists(dir) }.getOrDefault(false)) ok = false
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    ok = false
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { ok = false }
        return ok
    }
}
