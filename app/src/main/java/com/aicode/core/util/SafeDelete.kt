package com.aicode.core.util

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * 目录树安全删除（NOFOLLOW）：Kotlin 的 File.deleteRecursively 会跟随目录符号链接下钻，
 * 链接指向目录外（如 venv、系统目录）时会误删目标数据（项目已验证坑，详见
 * ContainerInstaller 的 KDoc）。这里用 NOFOLLOW_LINKS 判定真目录，符号链接一律当普通条目 unlink。
 * 用户内容、技能、缓存等任何可能被外部投放符号链接的目录删除都应走这里。
 */
object SafeDelete {

    /**
     * 删除 [root] 整棵树（不跟随符号链接）。
     * @param startCount 起始计数（用于外部进度累计）
     * @param onProgress 每删除 [PROGRESS_STEP] 个条目回调一次累计数
     * @return 实际删除的条目数（文件+目录）
     */
    fun deleteTree(root: File, startCount: Int = 0, onProgress: (Int) -> Unit = {}): Int {
        var count = startCount
        val pending = ArrayDeque<File>()
        val dirs = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (isRealDirectory(entry)) {
                dirs.addLast(entry)
                entry.listFiles()?.forEach { pending.addLast(it) }
            } else if (entry.delete()) {
                count++
                if (count % PROGRESS_STEP == 0) onProgress(count)
            }
        }
        // 目录得等自己空了才删得掉：按自浅入深的发现顺序入队，从队尾往回删即先深后浅。
        while (dirs.isNotEmpty()) {
            if (dirs.removeLast().delete()) count++
        }
        onProgress(count)
        return count
    }

    /**
     * 同 [deleteTree]，但返回「是否全部删干净」，供需要按字节记账的调用方（存储清理）使用。
     * 目标不存在视为成功。
     */
    fun deleteTreeOrFalse(root: File): Boolean {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        var ok = true
        val pending = ArrayDeque<File>()
        val dirs = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (isRealDirectory(entry)) {
                dirs.addLast(entry)
                entry.listFiles()?.forEach { pending.addLast(it) }
            } else if (!entry.delete()) {
                ok = false
            }
        }
        while (dirs.isNotEmpty()) {
            if (!dirs.removeLast().delete()) ok = false
        }
        return ok
    }

    private const val PROGRESS_STEP = 2000

    /** 是否真目录：符号链接即便指向目录也返回 false，防止删除时跟着链接下钻。 */
    private fun isRealDirectory(file: File): Boolean = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
    }.getOrDefault(false)
}
