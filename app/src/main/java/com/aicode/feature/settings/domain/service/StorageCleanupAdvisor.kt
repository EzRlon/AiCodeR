package com.aicode.feature.settings.domain.service

import android.content.Context
import com.aicode.core.util.AILogger
import com.aicode.core.util.FileLogger
import com.aicode.core.util.SafeDelete
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.domain.model.CleanupKind
import com.aicode.feature.settings.domain.model.CleanupRecommendation
import com.aicode.feature.settings.domain.model.CleanupRecommendationKind
import com.aicode.feature.settings.domain.model.CleanupRecommendationLevel
import com.aicode.feature.settings.domain.model.CleanupRecommendationReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储清理推荐引擎：三级判定（推荐级 / 需确认级 / 禁区）。
 *
 * 只判定与统计，**从不自行删除**；执行入口 [clean] 仅作用于调用方显式传入的勾选集。
 * 禁区以 [PROTECTED_DIR_NAMES] 白名单显式写出，每条推荐落池前都会过 [isProtected] 校验，
 * 白名单内路径永不进推荐池（代码可审计）。
 */
@Singleton
class StorageCleanupAdvisor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val checkpointDao: CheckpointDao,
    private val chatSessionDao: ChatSessionDao,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val toolOutputStore: ToolOutputStore,
    private val commandEngine: CommandEngine,
    private val scanner: StorageUsageScanner
) {

    /** 扫描推荐池：孤儿检测 + 超期产物统计，全部在 IO 线程；容器缓存探测容器未就绪时静默为 0。 */
    suspend fun scan(): List<CleanupRecommendation> = withContext(Dispatchers.IO) {
        val sessionIds = runCatching { chatSessionDao.getAllOnce().map { it.id }.toSet() }
            .getOrDefault(emptySet())
        buildList {
            addAll(categoryRecommendations())
            addAll(staleProductRecommendations(toolOutputStore.outputDir, CleanupRecommendationKind.STALE_TOOL_OUTPUT))
            addAll(staleProductRecommendations(generatedImagesDir(), CleanupRecommendationKind.STALE_GENERATED_IMAGES))
            addAll(orphanCheckpointRecommendations(sessionIds))
            addAll(orphanAiLogRecommendations(sessionIds))
            addAll(orphanImageRecommendations())
        }.filter { it.kind !in PATH_GUARD_KINDS || !isProtected(targetOf(it)) }
    }

    /**
     * 执行勾选的推荐项清理，返回释放字节数。逐条 runCatching，单条失败不中断其余项。
     * 每条执行前重过禁区校验与存在性检查，防止缓存数据过期后误删。
     */
    suspend fun clean(items: List<CleanupRecommendation>): Long = withContext(Dispatchers.IO) {
        items.sumOf { item ->
            if (item.kind in PATH_GUARD_KINDS && isProtected(targetOf(item))) {
                FileLogger.w(TAG, "推荐项命中禁区白名单，跳过: ${item.id}")
                0L
            } else {
                runCatching { cleanItem(item) }
                    .onFailure { FileLogger.w(TAG, "清理推荐项失败: ${item.id}", it) }
                    .getOrDefault(0L)
            }
        }
    }

    // ── 各类推荐 ────────────────────────────────────────────────────────────

    /** 全分类聚合项：应用缓存 / 运行日志 / 容器构建缓存（白名单五目录）。均为可重建数据。 */
    private suspend fun categoryRecommendations(): List<CleanupRecommendation> = buildList {
        val cacheBytes = StorageUsageScanner.dirSize(context.cacheDir) +
            StorageUsageScanner.dirSize(context.codeCacheDir)
        if (cacheBytes > 0) {
            add(recommendation(CleanupRecommendationKind.APP_CACHE, context.getString(com.aicode.R.string.storage_clean_caches), cacheBytes))
        }
        val logBytes = runCatching { FileLogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L) +
            runCatching { AILogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        if (logBytes > 0) {
            add(recommendation(CleanupRecommendationKind.APP_LOGS, context.getString(com.aicode.R.string.storage_clean_logs), logBytes))
        }
        val kb = runCatching {
            commandEngine.runCommandSyncIfReady(command = CONTAINER_CACHE_DU, timeoutMs = 60_000L)
                ?.output?.lineSequence()?.firstOrNull()?.trim()?.toLongOrNull()
        }.getOrNull() ?: 0L
        if (kb > 0) {
            add(recommendation(CleanupRecommendationKind.CONTAINER_CACHES, context.getString(com.aicode.R.string.storage_clean_container_caches), kb * 1024L))
        }
    }

    /** 超期产物（tool-output / generated-images）：目录里 mtime 超过 [STALE_DAYS] 的子项聚合为一条推荐。 */
    private fun staleProductRecommendations(dir: File, kind: CleanupRecommendationKind): List<CleanupRecommendation> {
        val cutoff = System.currentTimeMillis() - STALE_DAYS * DAY_MS
        val stale = dir.listFiles { f: File -> f.lastModified() < cutoff } ?: return emptyList()
        val bytes = stale.sumOf { StorageUsageScanner.dirSize(it) }
        if (bytes <= 0) return emptyList()
        val label = context.getString(
            if (kind == CleanupRecommendationKind.STALE_TOOL_OUTPUT) {
                com.aicode.R.string.storage_rec_stale_tool_output
            } else {
                com.aicode.R.string.storage_rec_stale_generated_images
            }
        )
        return listOf(recommendation(kind, label, bytes))
    }

    /** 孤儿检查点：`checkpoints/<sessionId>/` 目录在会话表里无对应记录。逐条一条推荐。 */
    private suspend fun orphanCheckpointRecommendations(sessionIds: Set<String>): List<CleanupRecommendation> {
        val root = File(context.filesDir, CHECKPOINTS_DIR)
        val orphans = root.listFiles { f: File -> f.isDirectory && f.name !in sessionIds } ?: return emptyList()
        return orphans.mapNotNull { dir ->
            val bytes = StorageUsageScanner.dirSize(dir)
            if (bytes <= 0) return@mapNotNull null
            recommendation(
                CleanupRecommendationKind.ORPHAN_CHECKPOINT,
                context.getString(com.aicode.R.string.storage_rec_orphan_checkpoint, dir.name.take(8)),
                bytes,
                level = CleanupRecommendationLevel.NEEDS_CONFIRM,
                reason = CleanupRecommendationReason.NO_SESSION,
                detail = dir.name
            )
        }
    }

    /** 孤儿 AI 会话日志：`session-<id>.log`（含滚动归档 .1~.3）在会话表里无对应记录，按会话聚合一条。 */
    private suspend fun orphanAiLogRecommendations(sessionIds: Set<String>): List<CleanupRecommendation> {
        val files = runCatching { AILogger.listLogFiles() }.getOrDefault(emptyList())
        return files
            .groupBy { it.name.removePrefix("session-").substringBefore(".log") }
            .filterKeys { it != "unknown" && it !in sessionIds }
            .mapNotNull { (id, logs) ->
                val bytes = logs.sumOf { it.length() }
                if (bytes <= 0) return@mapNotNull null
                recommendation(
                    CleanupRecommendationKind.ORPHAN_AI_LOG,
                    context.getString(com.aicode.R.string.storage_rec_orphan_ai_log, id.take(8)),
                    bytes,
                    level = CleanupRecommendationLevel.NEEDS_CONFIRM,
                    reason = CleanupRecommendationReason.NO_SESSION,
                    detail = id
                )
            }
    }

    /** 孤儿镜像包：`rootfs_images/` 下的文件既无下载记录也无任何自定义 profile（LocalFile uri）引用。 */
    private suspend fun orphanImageRecommendations(): List<CleanupRecommendation> {
        val dir = File(context.filesDir, IMAGES_DIR)
        val files = dir.listFiles { f: File -> f.isFile } ?: return emptyList()
        if (files.isEmpty()) return emptyList()
        val referenced = runCatching {
            containerSettingsRepositoryUris() + downloadedImageUris()
        }.getOrDefault(emptySet())
        return files.filter { it.toURI().toString() !in referenced && it.path !in referenced }.mapNotNull { file ->
            if (file.length() <= 0) return@mapNotNull null
            recommendation(
                CleanupRecommendationKind.ORPHAN_IMAGE,
                file.name,
                file.length(),
                level = CleanupRecommendationLevel.NEEDS_CONFIRM,
                reason = CleanupRecommendationReason.NO_CONTAINER,
                detail = file.name
            )
        }
    }

    /** 自定义 profile 的 LocalFile uri 引用（指向 rootfs_images 下的导入副本）。 */
    private suspend fun containerSettingsRepositoryUris(): Set<String> {
        val profiles = runCatching { containerSettingsRepository.customProfilesFlow.first() }
            .getOrDefault(emptyList())
        return profiles.mapNotNull { (it.rootfsSource as? RootfsSource.LocalFile)?.uri }.toSet()
    }

    /** 下载镜像记录的 fileUri 引用。 */
    private suspend fun downloadedImageUris(): Set<String> =
        runCatching { containerSettingsRepository.downloadedImagesFlow.first() }
            .getOrDefault(emptyList())
            .map { it.fileUri }
            .toSet()

    // ── 执行 ────────────────────────────────────────────────────────────────

    private suspend fun cleanItem(item: CleanupRecommendation): Long = when (item.kind) {
        CleanupRecommendationKind.APP_CACHE -> scanner.clean(CleanupKind.Caches)
        CleanupRecommendationKind.APP_LOGS -> scanner.clean(CleanupKind.Logs)
        CleanupRecommendationKind.CONTAINER_CACHES -> scanner.clean(CleanupKind.ContainerCaches)
        CleanupRecommendationKind.STALE_TOOL_OUTPUT ->
            deleteStaleChildren(toolOutputStore.outputDir)
        CleanupRecommendationKind.STALE_GENERATED_IMAGES ->
            deleteStaleChildren(generatedImagesDir())
        CleanupRecommendationKind.ORPHAN_CHECKPOINT -> {
            val dir = File(File(context.filesDir, CHECKPOINTS_DIR), item.detail)
            if (!dir.isDirectory) 0L else {
                checkpointDao.deleteFileSnapshotsForSession(item.detail)
                checkpointDao.deleteCheckpointsForSession(item.detail)
                if (SafeDelete.deleteTreeOrFalse(dir)) item.bytes else 0L
            }
        }
        CleanupRecommendationKind.ORPHAN_AI_LOG -> {
            val logs = runCatching { AILogger.listLogFiles() }.getOrDefault(emptyList())
                .filter { it.name.startsWith("session-${item.detail}.log") }
            var freed = 0L
            logs.forEach { if (it.delete()) freed += it.length() }
            freed
        }
        CleanupRecommendationKind.ORPHAN_IMAGE -> {
            val file = File(File(context.filesDir, IMAGES_DIR), item.detail)
            if (file.parentFile?.name != IMAGES_DIR || !file.isFile) 0L
            else if (SafeDelete.deleteTreeOrFalse(file)) item.bytes else 0L
        }
    }

    /** 删除目录内 mtime 已超期的子项；执行时重查 mtime，只删扫描时已超期、且至今仍未被使用的数据。 */
    private fun deleteStaleChildren(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val cutoff = System.currentTimeMillis() - STALE_DAYS * DAY_MS
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            if (child.lastModified() < cutoff) {
                val size = StorageUsageScanner.dirSize(child)
                if (SafeDelete.deleteTreeOrFalse(child)) freed += size
            }
        }
        return freed
    }

    // ── 禁区白名单 ──────────────────────────────────────────────────────────

    /**
     * 禁区判定：工作区本体、密钥、数据库、DataStore、容器 rootfs 本体、APK 本体等
     * 用户数据与系统命脉，永不进推荐池。相对名集中成常量便于审计。
     */
    /**
     * 禁区判定只适用于「目标路径来自盘上枚举」的项（孤儿/产物）：路径落入禁区即拦截。
     * 聚合型推荐（缓存/日志/容器五缓存）的执行动作是按 kind 路由的固定审计函数，
     * 不走路径校验——容器缓存刻意位于 rootfs 白名单内，路径校验会与设计自相矛盾。
     */
    private val PATH_GUARD_KINDS = setOf(
        CleanupRecommendationKind.ORPHAN_CHECKPOINT,
        CleanupRecommendationKind.ORPHAN_IMAGE,
        CleanupRecommendationKind.STALE_TOOL_OUTPUT,
        CleanupRecommendationKind.STALE_GENERATED_IMAGES,
        CleanupRecommendationKind.APP_CACHE
    )

    private fun isProtected(target: File?): Boolean {
        if (target == null) return true
        val path = runCatching { target.canonicalPath }.getOrNull() ?: return true
        val protectedRoots = buildList {
            PROTECTED_DIR_NAMES.forEach { add(File(context.filesDir, it)) }
            add(containerInstaller.rootfsDir)
            context.filesDir.listFiles()?.forEach { dir ->
                // rootfs_images 是镜像包存放区（孤儿检测的对象），不属解压 rootfs
                if (dir.isDirectory && dir.name.startsWith("rootfs_") && dir.name != IMAGES_DIR) add(dir)
            }
            add(File(context.filesDir.parentFile, "shared_prefs"))
        }
        return protectedRoots.any { root ->
            val canon = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            path == canon || path.startsWith(canon + File.separator)
        } || context.applicationInfo.sourceDir == path
    }

    /** 推荐项对应的磁盘对象（禁区校验与失败排查用；聚合型返回其统计根目录）。 */
    private fun targetOf(item: CleanupRecommendation): File? = when (item.kind) {
        CleanupRecommendationKind.APP_CACHE -> context.cacheDir
        CleanupRecommendationKind.APP_LOGS -> null
        CleanupRecommendationKind.CONTAINER_CACHES -> null
        CleanupRecommendationKind.STALE_TOOL_OUTPUT -> toolOutputStore.outputDir
        CleanupRecommendationKind.STALE_GENERATED_IMAGES -> generatedImagesDir()
        CleanupRecommendationKind.ORPHAN_CHECKPOINT -> File(File(context.filesDir, CHECKPOINTS_DIR), item.detail)
        CleanupRecommendationKind.ORPHAN_AI_LOG -> null
        CleanupRecommendationKind.ORPHAN_IMAGE -> File(File(context.filesDir, IMAGES_DIR), item.detail)
    }

    private fun generatedImagesDir(): File = File(containerInstaller.aicodeDir, GENERATED_IMAGES_DIR)

    private fun recommendation(
        kind: CleanupRecommendationKind,
        label: String,
        bytes: Long,
        level: CleanupRecommendationLevel = CleanupRecommendationLevel.RECOMMENDED,
        reason: CleanupRecommendationReason = CleanupRecommendationReason.REBUILDABLE,
        detail: String = ""
    ): CleanupRecommendation {
        val id = if (detail.isEmpty()) kind.name else "${kind.name}:$detail"
        return CleanupRecommendation(id, kind, level, label, bytes, reason)
    }

    private companion object {
        private const val TAG = "StorageCleanupAdvisor"
        private const val CHECKPOINTS_DIR = "checkpoints"
        private const val IMAGES_DIR = "rootfs_images"
        private const val GENERATED_IMAGES_DIR = "generated-images"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 产物超期阈值（mtime）：与设计稿一致，30 天未使用即入推荐池。 */
        private const val STALE_DAYS = 30L

        /** 容器内可重建缓存白名单五目录，与 StorageUsageScanner 的清理脚本严格同源。 */
        private val CONTAINER_CACHE_DIRS = listOf(
            "/root/.gradle/caches", "/root/.npm/_cacache", "/root/.m2/repository", "/root/.cache/pip", "/root/.cache/uv"
        )
        private val CONTAINER_CACHE_DU =
            "du -sk ${CONTAINER_CACHE_DIRS.joinToString(" ")} 2>/dev/null | " +
                "awk '{s+=\$1} END {print s+0}'"

        /** 禁区相对 filesDir 的目录名：用户数据与命脉，推荐池 0 命中（rootfs 与 APK 另行动态拼入）。 */
        private val PROTECTED_DIR_NAMES = listOf(
            "projects", "aicode/keys", "aicode/skills", "aicode/prompts", "aicode/mcp",
            "databases", "datastore"
        )
    }
}
