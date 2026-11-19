package com.aicode.feature.agent.domain.tool.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 构建产物自动交付（内置机制，2026-11-19 用户要求）。
 *
 * 此前「编译好把 APK 发到聊天区」依赖 AI 会话里模型去调 `send_file`：
 * 会话一断、或模型忘了调，包就躺在容器里用户拿不到。这里改成 App 本身的机制：
 * [ExecuteCommandTool] 每次命令成功结束后扫一遍输出里的构建产物路径
 * （APK / AAB），存在且不是刚交付过的，就直接登记为附件挂到这条工具消息上
 * （与 send_file 同一条聊天渲染链路），并写入交付物页。
 *
 * 判定故意保守：只认输出里明确出现、且文件真实存在的 `.apk` / `.aab`，
 * 避免把日志里的普通词误报成交付。
 */
@Singleton
class BuildArtifactDelivery @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    private companion object {
        const val TAG = "BuildArtifactDelivery"

        /** 关注的构建产物扩展名。 */
        val ARTIFACT_EXTENSIONS = listOf(".apk", ".aab")

        /** 同一路径的交付去重窗口：Gradle 一次构建可能多次打印同一路径，短时间不重复发。 */
        const val DEDUP_WINDOW_MS = 10 * 60_000L
        const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024
        val PENDING_FILE = WorkspacePathMapper.CONTAINER_ROOT + "/.aicode/pending-delivery.txt"
    }

    /** path → 上次交付时间；进程内去重。 */
    private val recent = ConcurrentHashMap<String, Long>()

    /**
     * 从命令输出里提取构建产物路径并构造附件。找不到或已交付过（去重窗口内）返回空。
     * 路径必须是输出里以 `/` 开头或 `~/` 开头的完整路径——相对路径无法可靠定位文件。
     */
    fun extract(output: String): List<AgentAttachment> {
        if (ARTIFACT_EXTENSIONS.none { output.contains(it) }) return emptyList()
        val now = System.currentTimeMillis()
        val paths = Regex("""(?:~/|/)[\w./+-]*\.(?:apk|aab)""")
            .findAll(output)
            .map { it.value }
            .distinct()
            .toList()
        if (paths.isEmpty()) return emptyList()
        val attachments = mutableListOf<AgentAttachment>()
        for (rawPath in paths) {
            val path = rawPath
            val recentAt = recent[path]
            if (recentAt != null && now - recentAt < DEDUP_WINDOW_MS) continue
            runCatching {
                if (!fileAccess.exists(path) || fileAccess.isDirectory(path)) return@runCatching
                val size = fileAccess.fileSize(path)
                if (size > MAX_ARTIFACT_BYTES) {
                    FileLogger.w(TAG, "构建产物过大(${size / 1024 / 1024}MB)，未自动交付: $path")
                    return@runCatching
                }
                val local = fileAccess.copyToLocal(path)
                val name = path.substringAfterLast('/')
                val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                recent[path] = now
                attachments += AgentAttachment(
                    fileName = name,
                    containerPath = path,
                    localPath = local.absolutePath,
                    mimeType = mime,
                    sizeBytes = size,
                    isImage = false
                )
                FileLogger.i(TAG, "命令产出构建产物，自动交付: $name (${path})")
            }.onFailure { e ->
                FileLogger.w(TAG, "构建产物读取失败 $path: ${e.message}")
            }
        }
        if (attachments.isNotEmpty()) recordPending(attachments)
        return attachments
    }

    private fun recordPending(attachments: List<AgentAttachment>) {
        PendingDeliveryStore.append(fileAccess, attachments.map { it.containerPath })
    }

    /** 提示后缀：拼在工具结果末尾，让模型与用户都知道包已自动送达。 */
    fun deliveryNotice(attachments: List<AgentAttachment>): String? =
        if (attachments.isEmpty()) null else
            "\n\n[构建产物已自动发送到聊天区] " + attachments.joinToString("、") { it.fileName }
}

/**
 * 补投递标记的单一读写入口：工具端 append，App 冷启动补投递成功后只移除已送达路径。
 * 两侧在同一进程内共用一把锁，避免「读旧文件—合并—写回」与整文件删除互相覆盖。
 */
object PendingDeliveryStore {
    private const val TAG = "PendingDeliveryStore"
    private val lock = Any()
    val pendingFile = WorkspacePathMapper.CONTAINER_ROOT + "/.aicode/pending-delivery.txt"

    fun append(fileAccess: FileAccessProvider, paths: Collection<String>) {
        if (paths.isEmpty()) return
        synchronized(lock) {
            runCatching {
                fileAccess.mkdirs(pendingFile.substringBeforeLast('/'))
                val existing = runCatching { fileAccess.readFile(pendingFile) }.getOrNull().orEmpty()
                val merged = (existing.lines().map { it.trim() } + paths)
                    .filter { it.isNotEmpty() }
                    .distinct()
                fileAccess.writeFile(pendingFile, merged.joinToString("\n"))
            }.onFailure { FileLogger.w(TAG, "写补投递标记失败: ${it.message}") }
        }
    }

    fun remove(fileAccess: FileAccessProvider, deliveredPaths: Collection<String>) {
        if (deliveredPaths.isEmpty()) return
        synchronized(lock) {
            runCatching {
                val existing = runCatching { fileAccess.readFile(pendingFile) }.getOrNull().orEmpty()
                val remaining = existing.lines().map { it.trim() }
                    .filter { it.isNotEmpty() && it !in deliveredPaths }
                    .distinct()
                if (remaining.isEmpty()) fileAccess.delete(pendingFile)
                else fileAccess.writeFile(pendingFile, remaining.joinToString("\n"))
            }.onFailure { FileLogger.w(TAG, "更新补投递标记失败: ${it.message}") }
        }
    }
}
