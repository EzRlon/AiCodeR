package com.aicode.feature.settings.domain.service

import android.content.Context
import com.aicode.R
import com.aicode.core.util.AILogger
import com.aicode.core.util.FileLogger
import com.aicode.core.util.SafeFileDelete
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.file.VisionSessionStore
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.domain.model.CleanupKind
import com.aicode.feature.settings.domain.model.DeviceSpace
import com.aicode.feature.settings.domain.model.StorageCategory
import com.aicode.feature.settings.domain.model.StorageDetail
import com.aicode.feature.settings.domain.model.StorageDetailKey
import com.aicode.feature.settings.domain.model.StorageEntry
import com.aicode.feature.settings.domain.model.formatStorageSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统计 App 各部分磁盘占用，并清理可安全重建的临时数据。
 *
 * 分类之间互斥（见 [StorageCategory]），加总即总占用。**外部目录工作区不计入**：它物理上在设备共享
 * 存储里，可能是用户挑的任意大目录，扫进来既不属于 App 占用也会拖垮统计。
 */
@Singleton
class StorageUsageScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val toolOutputStore: ToolOutputStore,
    private val visionSessionStore: VisionSessionStore,
    private val agentMessageDao: AgentMessageDao,
    private val checkpointManager: CheckpointManager,
    private val commandEngine: CommandEngine
) {

    /**
     * 逐分类发射统计结果：数据库、缓存这类秒出的先算，rootfs 与工作区最后，界面可以边算边填。
     *
     * 收集方取消（如离开页面）后，正在进行的目录遍历会在下一个文件处中止，不会白跑完整棵树。
     */
    fun scan(): Flow<StorageEntry> = flow {
        val job = currentCoroutineContext()[Job]
        val cancelled: () -> Boolean = { job?.isActive == false }

        emit(chatEntry())
        emit(cacheEntry(cancelled))
        emit(logsEntry())
        emit(apkEntry())
        emit(checkpointsEntry(cancelled))
        emit(imagesEntry())
        emit(otherDataEntry(cancelled))
        emit(aiConfigEntry(cancelled))
        emit(workspacesEntry(cancelled))
        emit(containerEntry(cancelled))
    }.flowOn(Dispatchers.IO)

    /** App 私有目录所在存储卷的容量。 */
    fun deviceSpace(): DeviceSpace = runCatching {
        DeviceSpace(
            totalBytes = context.filesDir.totalSpace,
            availableBytes = context.filesDir.usableSpace
        )
    }.getOrDefault(DeviceSpace(0, 0))

    /** 执行一项清理，返回释放的字节数。 */
    suspend fun clean(kind: CleanupKind): Long = withContext(Dispatchers.IO) {
        when (kind) {
            CleanupKind.Caches -> clearDirContents(context.cacheDir) + clearDirContents(context.codeCacheDir)
            CleanupKind.Logs -> FileLogger.clearLogs() + AILogger.clearLogs()
            CleanupKind.ToolOutput -> clearDirContents(toolOutputStore.outputDir)
            CleanupKind.VisionSessions -> clearDirContents(visionSessionStore.sessionDir)
            // 先量后删：清空检查点（含数据库记录与快照文件），回滚历史不可恢复
            CleanupKind.Checkpoints -> {
                val size = dirSize(File(context.filesDir, CHECKPOINTS_DIR))
                checkpointManager.clearAllCheckpoints()
                size
            }
            CleanupKind.ContainerImages -> clearDirContents(File(context.filesDir, IMAGES_DIR))
            CleanupKind.GeneratedImages -> clearDirContents(File(containerInstaller.aicodeDir, GENERATED_IMAGES_DIR))
            // 容器内构建缓存（Gradle/npm/Maven/通用 cache）：删除后下次构建自动重新下载；
            // du 先统计再删，容器未就绪时静默返回 0
            CleanupKind.ContainerCaches -> cleanContainerCaches()
        }
    }

    /**
     * 清理容器内构建缓存。目标均为可重建缓存（不碰源码/容器系统目录/工作区）；
     * 单命令先 du -sk 汇总后 rm，输出首行即释放的 KB 数。
     */
    private suspend fun cleanContainerCaches(): Long {
        val result = commandEngine.runCommandSyncIfReady(
            command = CONTAINER_CACHE_SCRIPT,
            timeoutMs = 600_000L
        ) ?: return 0L
        return (result.output.lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L) * 1024L
    }

    /**
     * 删除工作区产物（`<项目名>/<相对路径>`，来自 [scan] 的明细）。仅限内置工作区：
     * 映射到 `filesDir/projects` 下并做 canonical 前缀与项目目录校验；
     * 递归删除走 [SafeFileDelete]（不跟随符号链接，防止越出目标子树）。返回释放的字节数。
     */
    suspend fun cleanArtifacts(paths: List<String>): Long = withContext(Dispatchers.IO) {
        val projectsRoot = File(context.filesDir, PROJECTS_DIR).canonicalFile
        paths.sumOf { rel ->
            val target = physicalOf(rel, projectsRoot) ?: return@sumOf 0L
            val size = dirSize(target)
            if (SafeFileDelete.deleteRecursively(target)) size else 0L
        }
    }

    /** `<项目名>/<相对路径>` → `projects/<该项目>/<相对路径>` 物理路径；非法或越界返回 null。 */
    private fun physicalOf(rel: String, projectsRoot: File): File? {
        val clean = rel.trim('/')
        if (clean.isEmpty() || ".." in clean) return null
        val firstSegment = clean.substringBefore('/')
        if (firstSegment.isEmpty() || firstSegment.startsWith(".")) return null
        if (!File(projectsRoot, firstSegment).isDirectory) return null
        val canon = runCatching { File(projectsRoot, clean).canonicalFile }.getOrNull() ?: return null
        return canon.takeIf { it.path.startsWith(projectsRoot.path + File.separator) }
    }

    // ── 各分类 ──────────────────────────────────────────────────────────────

    /** 聊天记录：整个 databases 目录（本项目只有一个 Room 库，WAL 的 -wal/-shm 也在其中）。 */
    private suspend fun chatEntry(): StorageEntry {
        val dbDir = context.getDatabasePath(AgentDatabase.DATABASE_NAME).parentFile
        val bytes = dbDir?.let { dirSize(it) } ?: 0L
        val details = runCatching { agentMessageDao.sessionStorageUsage(TOP_SESSIONS) }
            .getOrDefault(emptyList())
            .filter { it.bytes > 0 }
            .map { usage ->
                StorageDetail(
                    label = usage.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.storage_untitled_session),
                    bytes = usage.bytes,
                    note = context.getString(R.string.storage_chat_messages, usage.messageCount),
                    key = StorageDetailKey.CHAT_SESSION_PREFIX + usage.sessionId
                )
            }
        return StorageEntry(StorageCategory.Chat, bytes, details)
    }

    /**
     * 容器：`filesDir/rootfs`（内置）与 `filesDir/rootfs_<profileId>`（自定义本地镜像）。
     *
     * 直接按目录扫而不是按 profile 列表扫，profile 删了但目录还在的残留也能露出来；profile 列表只用来取显示名。
     * 远程 SSH 的 profile 没有本地 rootfs，天然不会出现在这里。
     */
    private suspend fun containerEntry(cancelled: () -> Boolean): StorageEntry {
        val nameById = runCatching { containerSettingsRepository.customProfilesFlow.first() }
            .getOrDefault(emptyList())
            .associate { it.id to it.name }
        val dirs = context.filesDir.listFiles { f: File ->
            f.isDirectory && (f.name == ROOTFS_DIR || f.name.startsWith("${ROOTFS_DIR}_")) && f.name != IMAGES_DIR
        }?.sortedBy { it.name } ?: emptyList()

        val details = mutableListOf<StorageDetail>()
        var total = 0L
        var largest: Pair<File, Long>? = null
        dirs.forEach { dir ->
            val id = if (dir.name == ROOTFS_DIR) ContainerProfile.BUILTIN_ID else dir.name.removePrefix("${ROOTFS_DIR}_")
            val size = dirSize(dir, cancelled)
            total += size
            val label = nameById[id]
                ?: if (id == ContainerProfile.BUILTIN_ID) ContainerProfile.BUILTIN_ALPINE.name else dir.name
            details += StorageDetail(label = label, bytes = size, note = dir.name)
            if (size > (largest?.second ?: 0L)) largest = dir to size
        }

        // 占用最大的那个容器再下钻一层：容器里吃掉几个 G 的通常是 /usr 与 /root 下的构建缓存，
        // 只看总量看不出来。
        largest?.let { (dir, _) ->
            dir.listFiles { f: File -> f.isDirectory }
                ?.map { it.name to dirSize(it, cancelled) }
                ?.filter { it.second > 0 }
                ?.sortedByDescending { it.second }
                ?.take(TOP_SUBDIRS)
                ?.forEach { (name, size) ->
                    details += StorageDetail(label = "/$name", bytes = size, indent = true)
                }
        }
        // 容器内构建缓存大小探测（只读 du，路径与清理脚本一致）：给「容器数据」行提供
        // ContainerCaches 的可清理大小；扫描链路本身在 IO 协程，容器命令不占主线程
        runCatching {
            val kb = commandEngine.runCommandSyncIfReady(
                command = CONTAINER_CACHE_DU,
                timeoutMs = 60_000L
            )?.output?.lineSequence()?.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            if (kb > 0) {
                details += StorageDetail(
                    label = context.getString(R.string.storage_clean_container_caches),
                    bytes = kb * 1024L,
                    key = StorageDetailKey.CONTAINER_CACHES,
                    indent = true
                )
            }
        }
        return StorageEntry(StorageCategory.Container, total, details)
    }

    /** 容器镜像包：已下载或导入的 rootfs 压缩包，逐个文件列出。 */
    private fun imagesEntry(): StorageEntry {
        val dir = File(context.filesDir, IMAGES_DIR)
        val files = dir.listFiles { f: File -> f.isFile }?.sortedByDescending { it.length() } ?: emptyList()
        return StorageEntry(
            category = StorageCategory.ContainerImages,
            bytes = files.sumOf { it.length() },
            details = files.map { StorageDetail(label = it.name, bytes = it.length()) }
        )
    }

    /** 工作区：App 内置的 `filesDir/projects`，按项目列出，并标出其中的聊天附件与开发产物明细。 */
    private fun workspacesEntry(cancelled: () -> Boolean): StorageEntry {
        val root = File(context.filesDir, PROJECTS_DIR)
        val projects = root.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val projectRows = projects.map { project ->
            val attachments = dirSize(File(project, ATTACHMENTS_RELATIVE), cancelled)
            StorageDetail(
                label = project.name,
                bytes = dirSize(project, cancelled),
                note = attachments.takeIf { it > 0 }?.let {
                    context.getString(R.string.storage_workspace_attachments, formatStorageSize(it))
                }
            )
        }
        // 产物明细只作可操作项展示，不重复计入项目总量；排列上紧随各自项目行。
        val rows = buildList {
            projects.forEachIndexed { index, project ->
                add(projectRows[index])
                addAll(workspaceArtifacts(project, cancelled))
            }
        }
        val looseFiles = root.listFiles { f: File -> f.isFile }?.sumOf { it.length() } ?: 0L
        return StorageEntry(
            StorageCategory.Workspaces,
            projectRows.sumOf { it.bytes } + looseFiles,
            rows
        )
    }

    /**
     * 单个工作区的开发产物明细：安装包（按构建时间倒序，最多 [MAX_ARTIFACT_FILES] 条）、
     * 构建目录、依赖目录（node_modules / venv）与下载的大文件（压缩包、安装包、库文件等）。
     * 全部为可重建的派生物，逐条可选中清理；一次目录遍历收集全部目标，跳过 .git / node_modules 内部。
     */
    private fun workspaceArtifacts(project: File, cancelled: () -> Boolean): List<StorageDetail> {
        val apks = mutableListOf<Pair<File, Long>>()
        val downloads = mutableListOf<Pair<File, Long>>()
        val buildDirs = mutableListOf<File>()
        val projectPath = project.toPath()
        runCatching {
            Files.walkFileTree(projectPath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) return FileVisitResult.TERMINATE
                    val name = dir.fileName?.toString().orEmpty()
                    if (dir != projectPath && name in SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                    // build 目录记录后继续深入：编译完成的安装包就在 build/outputs 下（下载物另行排除 build）。
                    if (dir != projectPath && name == "build") {
                        if (buildDirs.size < MAX_BUILD_DIRS) buildDirs += dir.toFile()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) return FileVisitResult.TERMINATE
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val lower = file.fileName?.toString().orEmpty().lowercase()
                    val mtime = attrs.lastModifiedTime().toMillis()
                    when {
                        lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") ->
                            if (apks.size < MAX_ARTIFACT_FILES * 3) apks += file.toFile() to mtime
                        DOWNLOAD_EXTS.any { lower.endsWith(it) } && attrs.size() >= MIN_DOWNLOAD_BYTES &&
                            !file.toString().contains("/build/") ->
                            if (downloads.size < MAX_ARTIFACT_FILES * 3) downloads += file.toFile() to mtime
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }.onFailure { FileLogger.w(TAG, "扫描工作区产物失败: ${project.name}", it) }

        val result = mutableListOf<StorageDetail>()
        apks.sortedByDescending { it.second }.take(MAX_ARTIFACT_FILES).forEach { (file, mtime) ->
            result += artifactDetail(project, file, mtime, label = file.name)
        }
        buildDirs.forEach { dir ->
            result += artifactDetail(
                project, dir, dir.lastModified(),
                label = context.getString(R.string.storage_artifacts_build_dir, dir.relativeTo(project).path),
                isDirectory = true
            )
        }
        File(project, "node_modules").takeIf { it.isDirectory }?.let { dir ->
            result += artifactDetail(project, dir, dir.lastModified(),
                label = context.getString(R.string.storage_artifacts_node_modules), isDirectory = true)
        }
        listOf("venv", ".venv").map { File(project, it) }.firstOrNull { it.isDirectory }?.let { dir ->
            result += artifactDetail(project, dir, dir.lastModified(),
                label = context.getString(R.string.storage_artifacts_venv), isDirectory = true)
        }
        // 聊天附件（随会话上传/生成的图片等，存于项目 .aicode/attachments）单独成行可勾选清理
        File(project, ATTACHMENTS_RELATIVE).takeIf { it.isDirectory }?.let { dir ->
            if (dirSize(dir, cancelled) > 0) {
                result += artifactDetail(project, dir, dir.lastModified(),
                    label = context.getString(R.string.storage_artifacts_attachments), isDirectory = true)
            }
        }
        downloads.sortedByDescending { it.second }.take(MAX_ARTIFACT_FILES).forEach { (file, mtime) ->
            result += artifactDetail(project, file, mtime, label = file.name)
        }
        return result
    }

    /**
     * 构造一条可操作产物明细。[path] 为「`<项目名>/<相对路径>`」（存储页与删除链路共用的定位标识）；
     * note = 「项目 · 时间 · 相对路径」。
     */
    private fun artifactDetail(
        project: File,
        target: File,
        modifiedAt: Long,
        label: String,
        isDirectory: Boolean = false
    ): StorageDetail {
        val rel = target.relativeTo(project).path
        return StorageDetail(
            label = label,
            bytes = dirSize(target),
            note = "${project.name} · ${formatTime(modifiedAt)} · $rel",
            indent = true,
            path = "${project.name}/$rel",
            modifiedAt = modifiedAt,
            isDirectory = isDirectory
        )
    }

    /** AI 配置与产物：`filesDir/aicode`，按子目录拆开（工具输出与识图会话可清理）。 */
    private fun aiConfigEntry(cancelled: () -> Boolean): StorageEntry {
        val root = containerInstaller.aicodeDir
        val cleanableKeys = mapOf(
            toolOutputStore.outputDir.name to StorageDetailKey.TOOL_OUTPUT,
            visionSessionStore.sessionDir.name to StorageDetailKey.VISION_SESSIONS,
            GENERATED_IMAGES_DIR to StorageDetailKey.GENERATED_IMAGES
        )
        val details = root.listFiles { f: File -> f.isDirectory }
            ?.map {
                StorageDetail(
                    label = it.name,
                    bytes = dirSize(it, cancelled),
                    key = cleanableKeys[it.name]
                )
            }
            ?.filter { it.bytes > 0 }
            ?.sortedByDescending { it.bytes }
            ?: emptyList()
        return StorageEntry(StorageCategory.AiConfig, dirSize(root, cancelled), details)
    }

    private fun checkpointsEntry(cancelled: () -> Boolean): StorageEntry =
        StorageEntry(StorageCategory.Checkpoints, dirSize(File(context.filesDir, CHECKPOINTS_DIR), cancelled))

    /**
     * 日志：直接问两个 logger 要文件清单，而不是猜目录——它们优先写外部私有目录，
     * 外部不可用时回退到内部，路径由 logger 自己决定。
     */
    private fun logsEntry(): StorageEntry {
        val appLogs = runCatching { FileLogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        val aiLogs = runCatching { AILogger.listLogFiles().sumOf { it.length() } }.getOrDefault(0L)
        val details = buildList {
            if (appLogs > 0) add(StorageDetail(context.getString(R.string.storage_logs_app), appLogs))
            if (aiLogs > 0) add(StorageDetail(context.getString(R.string.storage_logs_ai), aiLogs))
        }
        return StorageEntry(StorageCategory.Logs, appLogs + aiLogs, details)
    }

    private fun cacheEntry(cancelled: () -> Boolean): StorageEntry =
        StorageEntry(
            category = StorageCategory.Caches,
            bytes = dirSize(context.cacheDir, cancelled) + dirSize(context.codeCacheDir, cancelled)
        )

    /** 其他数据：filesDir 里未被上述分类覆盖的部分（DataStore、背景图、字体、SSH 私钥等）加 shared_prefs。 */
    private fun otherDataEntry(cancelled: () -> Boolean): StorageEntry {
        val rest = context.filesDir.listFiles()?.filterNot { file ->
            file.name in CLASSIFIED_DIRS || file.name == ROOTFS_DIR || file.name.startsWith("${ROOTFS_DIR}_")
        }?.sumOf { dirSize(it, cancelled) } ?: 0L
        val prefs = context.filesDir.parentFile?.let { dirSize(File(it, PREFS_DIR), cancelled) } ?: 0L
        return StorageEntry(StorageCategory.OtherData, rest + prefs)
    }

    /** 应用安装包本体（base APK 与各 split）。 */
    private fun apkEntry(): StorageEntry {
        val info = context.applicationInfo
        val paths = (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()))
            .filterNotNull()
            .distinct()
        return StorageEntry(StorageCategory.Apk, paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) })
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 删除目录下所有内容但保留目录本身，返回释放字节数。递归删除走 [SafeFileDelete]（不跟随符号链接）。 */
    private fun clearDirContents(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            val size = dirSize(child)
            if (SafeFileDelete.deleteRecursively(child)) freed += size
        }
        return freed
    }

    companion object {
        private const val TAG = "StorageUsageScanner"

        private const val ROOTFS_DIR = "rootfs"
        private const val IMAGES_DIR = "rootfs_images"
        private const val PROJECTS_DIR = "projects"
        private const val CHECKPOINTS_DIR = "checkpoints"
        private const val AICODE_DIR = "aicode"
        private const val PREFS_DIR = "shared_prefs"
        private const val ATTACHMENTS_RELATIVE = ".aicode/attachments"
        private const val GENERATED_IMAGES_DIR = "generated-images"

        /** 容器内可重建缓存目录白名单（探测与清理严格同源；避开 /root/.cache 下的手工资产）。 */
        private val CONTAINER_CACHE_DIRS = listOf(
            "/root/.gradle/caches", "/root/.npm/_cacache", "/root/.m2/repository", "/root/.cache/pip", "/root/.cache/uv"
        )

        /** 只 du 不删（容器缓存大小探测用），路径与 [CONTAINER_CACHE_SCRIPT] 严格一致。 */
        private val CONTAINER_CACHE_DU =
            "du -sk ${CONTAINER_CACHE_DIRS.joinToString(" ")} 2>/dev/null | " +
                "awk '{s+=\$1} END {print s+0}'"

        /**
         * 容器内构建缓存清理脚本：du -sk 汇总后 rm（均为可重建缓存；不碰源码/工作区/容器
         * 系统目录；不用 /root/.cache 整目录——实测其下有手工装的 chromium 等不可重建资产）。
         * 输出首行 = 释放的 KB 数。
         */
        private val CONTAINER_CACHE_SCRIPT =
            "du -sk ${CONTAINER_CACHE_DIRS.joinToString(" ")} 2>/dev/null | " +
                "awk '{s+=\$1} END {print s+0}'; " +
                "rm -rf ${CONTAINER_CACHE_DIRS.joinToString(" ")} 2>/dev/null; true"

        /** 已被其它分类覆盖的 filesDir 子项，不再计入「其他数据」。 */
        private val CLASSIFIED_DIRS = setOf(
            PROJECTS_DIR, AICODE_DIR, IMAGES_DIR, CHECKPOINTS_DIR, "logs", "ai-logs"
        )

        private const val TOP_SESSIONS = 5
        private const val TOP_SUBDIRS = 5

        /** 产物扫描上限与阈值：安装包/下载文件每类最多展示条数、构建目录每项目上限、下载物最小计入大小。 */
        private const val MAX_ARTIFACT_FILES = 8
        private const val MAX_BUILD_DIRS = 4
        private const val MIN_DOWNLOAD_BYTES = 1L * 1024 * 1024

        /** 视为「下载物」的扩展名（压缩包 / 安装包）；jar/aar 不列入：多为随源码检入的依赖。 */
        private val DOWNLOAD_EXTS = listOf(
            ".zip", ".tar.gz", ".tgz", ".tar.xz", ".xz", ".tar",
            ".deb", ".rpm", ".whl", ".7z", ".rar"
        )

        /** 遍历时整体跳过内部的大目录（大小按整目录另行统计；.aicode 含附件与配置，不视为内存产物）。 */
        private val SKIP_DIRS = setOf(".git", ".aicode", "node_modules", "venv", ".venv")

        /** 产物明细里展示的最后修改时间。 */
        private fun formatTime(ts: Long): String =
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

        /**
         * 目录占用求和。
         *
         * 走 [Files.walkFileTree]（默认不跟随符号链接）：rootfs 与 node_modules 里到处是 symlink，
         * 跟随会重复计数甚至陷入环。单个条目读失败一律跳过，不让整次统计失败。
         */
        internal fun dirSize(target: File, cancelled: () -> Boolean = { false }): Long {
            if (!target.exists()) return 0L
            val path = target.toPath()
            if (runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)) return 0L
            if (target.isFile) return target.length()
            var total = 0L
            runCatching {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return if (cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                        if (cancelled()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                })
            }.onFailure { FileLogger.w(TAG, "统计目录占用失败: ${target.absolutePath}", it) }
            return total
        }
    }
}
