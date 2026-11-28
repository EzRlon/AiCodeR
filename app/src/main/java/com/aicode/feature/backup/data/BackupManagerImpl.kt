package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionTier
import com.aicode.feature.agent.domain.permission.ToolPermissionTierRepository
import com.aicode.feature.agent.domain.permission.WatchModeSettingsRepository
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.BackupCrypto
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.backup.domain.ChatSessionDto
import com.aicode.feature.backup.domain.ImportPreview
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.RemoteMountDto
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.TodoItemDto
import com.aicode.feature.backup.domain.GraphNodeDto
import com.aicode.feature.backup.domain.GraphEdgeDto
import com.aicode.feature.backup.domain.PartialRestoreException
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.toMetadata
import com.aicode.feature.credentials.data.repository.FileCredentialRepository
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import com.aicode.feature.schedule.domain.ScheduledTask
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsSnapshot
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.local.entity.RemoteMountEntity
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val liveNotificationSettingsRepository: LiveNotificationSettingsRepository,
    private val approvalNotificationSettingsRepository: ApprovalNotificationSettingsRepository,
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val logSettingsRepository: LogSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerInstaller: com.aicode.feature.agent.domain.container.ContainerInstaller,
    private val watchModeSettingsRepository: WatchModeSettingsRepository,
    private val toolPermissionTierRepository: ToolPermissionTierRepository,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val fileCredentialRepository: FileCredentialRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val graphDao: com.aicode.feature.memorygraph.data.local.dao.GraphDao,
    private val agentDatabase: AgentDatabase
) : BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun currentSchemaVersion(): Int = AgentDatabase.SCHEMA_VERSION

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    override suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val pw = password?.takeIf { it.isNotEmpty() }
            if (pw == null) {
                writeTarGz(output, options)
                return@withContext
            }
            val temp = createTempFile()
            try {
                FileOutputStream(temp).buffered().use { writeTarGz(it, options) }
                FileInputStream(temp).buffered().use { input ->
                    BackupCrypto.encryptStream(input, output, pw)
                }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun prepareImport(input: InputStream, password: CharArray?): File = withContext(Dispatchers.IO) {
        val temp = createTempFile()
        try {
            FileOutputStream(temp).buffered().use { output ->
                val source = input.buffered()
                val pw = password?.takeIf { it.isNotEmpty() }
                if (pw == null) source.copyTo(output) else BackupCrypto.decryptStream(source, output, pw)
            }
            temp
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    override suspend fun exportSession(sessionId: String, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val session = chatSessionDao.getById(sessionId) ?: error("Session not found: $sessionId")
            val temp = createTempFile()
            try {
                FileOutputStream(temp).use { fos ->
                    GzipCompressorOutputStream(fos, backupGzipParameters()).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                            writeMetadataEntry(tar, BackupMetadata(
                                schemaVersion = currentSchemaVersion(),
                                appVersion = appVersionName(),
                                createdAt = System.currentTimeMillis(),
                                sessionExport = true
                            ))
                            writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                                writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), session.toDto()))
                            }
                            writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = agentMessageDao.getPageBySessionAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().timestamp
                                    lastId = batch.last().id
                                }
                            }
                            writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = todoItemDao.getBySessionPageAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().createdAt
                                    lastId = batch.last().id
                                }
                            }
                        }
                    }
                }
                FileInputStream(temp).use { it.copyTo(output) }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>?
    ): Result<RestoreStats> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "导入备份开始（${if (pw != null) "加密" else "明文"}${if (selectedWorkspaces != null) "，勾选工作区=${selectedWorkspaces.size}个" else "，全量"}）")
            runCatching {
                openTar(input, pw).use { source ->
                    restoreFromTar(source.tar, selectedWorkspaces)
                }
            }
            .onSuccess { FileLogger.i(TAG, "导入备份完成：$it") }
            .onFailure { FileLogger.e(TAG, "导入备份失败", it) }
            .recoverCatching { e -> mapImportError(e, pw) }
        }
    }

    override suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "导入预览开始（${if (pw != null) "加密" else "明文"}）")
            runCatching {
                openTar(input, pw).use { source ->
                    val tar = source.tar
                    var workspaces: List<WorkspaceBackupMeta> = emptyList()
                    var sessionId: String? = null
                    var sessionTitle: String? = null
                    var isSessionExport = false
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (entry.name == FILE_METADATA) {
                            val plain = tar.readBytes()
                            val metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                            checkVersion(metadata.schemaVersion)
                            workspaces = metadata.workspaces
                            isSessionExport = metadata.sessionExport
                            // 单会话包还要读出包内会话 id/标题供冲突判断，不能在 metadata 处停；
                            // 其余场景 metadata.json 为首个条目，读到即可停，避免遍历大段 jsonl
                            if (!isSessionExport) break
                        } else if (isSessionExport && entry.name == FILE_SESSIONS) {
                            val firstLine = readFirstLine(tar)
                            if (firstLine != null) {
                                val dto = json.decodeFromString(ChatSessionDto.serializer(), firstLine)
                                sessionId = dto.id
                                sessionTitle = dto.title
                            }
                            break
                        }
                        entry = tar.nextEntry
                    }
                    ImportPreview(workspaces, sessionId, sessionTitle)
                }
            }
            .onSuccess { FileLogger.i(TAG, "导入预览完成：${it.workspaces.size} 个工作区") }
            .onFailure { FileLogger.e(TAG, "导入预览失败", it) }
            .recoverCatching { e -> mapImportError(e, pw) }
        }
    }

    /** 把导入异常映射为用户可读的 IllegalArgumentException（解密异常原样抛出）。 */
    private fun mapImportError(e: Throwable, pw: CharArray?): Nothing {
        when (e) {
            is BackupDecryptionException -> throw e
            is IllegalStateException -> throw e
            else -> throw IllegalArgumentException(
                if (pw != null) {
                    "备份文件已损坏，或口令与备份文件不匹配"
                } else {
                    "不是有效的AiCodeR备份文件；如果这是加密备份，请输入导出口令"
                },
                e
            )
        }
    }

    /** 打开 tar 流：先按需解密到临时文件，再解压；调用方负责 [TarSource.close]。 */
    private fun openTar(input: InputStream, pw: CharArray?): TarSource {
        if (pw == null) {
            FileLogger.i(TAG, "明文备份：直接解压 tar.gz")
            val p = BufferedInputStream(input)
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), null)
        }
        FileLogger.i(TAG, "加密备份：先解密到临时文件")
        val temp = createTempFile()
        try {
            BufferedInputStream(input).use { src ->
                FileOutputStream(temp).buffered().use { dst -> BackupCrypto.decryptStream(src, dst, pw) }
            }
            val p = FileInputStream(temp).buffered()
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), temp)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    // ── 导出辅助 ──────────────────────────────────────────────

    /**
     * 备份导出的 gzip 参数：压缩级别 [BACKUP_GZIP_LEVEL]（fast 档）。
     * 实测（本机 aarch64，23MB 典型文本）：级别 6→9 体积几乎无差（12.3%→12.2%）但耗时 +65%；
     * 6→4 耗时约降到 1/3、体积 +~1/4（12.3%→15.5%）。备份是导出-上传低频路径，
     * 手机 CPU 上导出更快更省电优先，体积代价有界。
     */
    private fun backupGzipParameters() = GzipParameters().apply {
        compressionLevel = BACKUP_GZIP_LEVEL
    }

    private suspend fun writeTarGz(output: OutputStream, options: BackupOptions) {
        val stream = object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
            }

            override fun close() {
                flush()
            }
        }
        stream.buffered().use { fos ->
            GzipCompressorOutputStream(fos, backupGzipParameters()).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    writeMetadataEntry(tar, buildMetadata(options))
                    if (options.aiAssets) {
                        writeAicodeEntries(tar, AI_ASSET_DIRS, AI_ASSET_FILES)
                    }
                    if (options.keys) {
                        writeAicodeEntries(tar, listOf(AICODE_KEYS_DIR), emptyList())
                    }
                    if (options.workspaceFiles) {
                        writeWorkspaceEntries(tar)
                    }
                    if (options.containerEnv) {
                        writeContainerEntries(tar)
                    }
                    if (options.chatHistory) {
                        writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = chatSessionDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), it.toDto())) }
                                lastTs = batch.last().updatedAt
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = agentMessageDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                lastTs = batch.last().timestamp
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = todoItemDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                lastTs = batch.last().createdAt
                                lastId = batch.last().id
                            }
                        }
                        // 记忆图谱：AI 的长期沉淀（结论/关系），换机不能丢
                        writeJsonlFileEntry(tar, FILE_GRAPH_NODES) { writer ->
                            graphDao.allNodes().forEach { writer.writeLine(json.encodeToString(GraphNodeDto.serializer(), it.toDto())) }
                        }
                        writeJsonlFileEntry(tar, FILE_GRAPH_EDGES) { writer ->
                            graphDao.allEdges().forEach { writer.writeLine(json.encodeToString(GraphEdgeDto.serializer(), it.toDto())) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun buildMetadata(options: BackupOptions): BackupMetadata = BackupMetadata(
        schemaVersion = currentSchemaVersion(),
        appVersion = appVersionName(),
        createdAt = System.currentTimeMillis(),
        providers = if (options.providers) aiProviderDao.getAllProvidersOnce().map { it.toDto() } else emptyList(),
        remoteConnections = if (options.remoteConnections) remoteConnectionDao.getAllConnectionsOnce().map { it.toDto() } else emptyList(),
        remoteMounts = if (options.remoteConnections) remoteConnectionDao.getAllMountsOnce().map { it.toDto() } else emptyList(),
        mcpServers = if (options.mcpServers) mcpConfigRepository.getGlobalServers() else emptyList(),
        globalPermissionRules = if (options.permissionRules) permissionRulesRepository.getGlobalRulesOnce() else emptyList(),
        themeMode = if (options.appSettings) themeSettingsRepository.snapshot() else null,
        themePresetId = if (options.appSettings) themeSettingsRepository.presetSnapshot() else null,
        dynamicColorEnabled = if (options.appSettings) themeSettingsRepository.dynamicColorSnapshot() else false,
        keepaliveEnabled = if (options.appSettings) keepaliveSettingsRepository.snapshot() else false,
        liveNotificationEnabled = if (options.appSettings) liveNotificationSettingsRepository.snapshot() else false,
        approvalNotificationEnabled = if (options.appSettings) approvalNotificationSettingsRepository.snapshot() else false,
        screenOnEnabled = if (options.appSettings) screenOnSettingsRepository.snapshot() else false,
        agentSoundEnabled = if (options.appSettings) agentSoundSettingsRepository.snapshot() else false,
        autoRemoveStaleModels = if (options.appSettings) generalSettingsRepository.autoRemoveStaleModelsSnapshot() else true,
        startupSessionMode = if (options.appSettings) generalSettingsRepository.startupSessionModeSnapshot() else null,
        firstByteTimeoutSec = if (options.appSettings) generalSettingsRepository.firstByteTimeoutSecSnapshot() else 300,
        streamIdleTimeoutSec = if (options.appSettings) generalSettingsRepository.streamIdleTimeoutSecSnapshot() else 0,
        maxNetworkRetries = if (options.appSettings) generalSettingsRepository.maxNetworkRetriesSnapshot() else 6,
        enterToSend = if (options.appSettings) generalSettingsRepository.enterToSendSnapshot() else false,
        compactionThresholdPercent = if (options.appSettings) generalSettingsRepository.compactionThresholdPercentSnapshot() else 90,
        sendFileMaxSizeMb = if (options.appSettings) generalSettingsRepository.sendFileMaxSizeMbSnapshot() else 100,
        deleteExternalWorkspaceSessions = if (options.appSettings) generalSettingsRepository.deleteExternalWorkspaceSessionsSnapshot() else false,
        handoffPromptEnabled = if (options.appSettings) generalSettingsRepository.handoffPromptEnabledSnapshot() else true,
        handoffPromptPercent = if (options.appSettings) generalSettingsRepository.handoffPromptPercentSnapshot() else 50,
        handoffAutoEnabled = if (options.appSettings) generalSettingsRepository.handoffAutoEnabledSnapshot() else true,
        handoffAutoPercent = if (options.appSettings) generalSettingsRepository.handoffAutoPercentSnapshot() else 75,
        floatBallEnabled = if (options.appSettings) generalSettingsRepository.floatBallEnabledSnapshot() else false,
        logLevel = if (options.appSettings) logSettingsRepository.snapshot() else null,
        visionProviderId = if (options.appSettings) visionModelSettingsRepository.getVisionProviderId() else "",
        visionModel = if (options.appSettings) visionModelSettingsRepository.getVisionModel() else "",
        compactionProviderId = if (options.appSettings) compactionModelSettingsRepository.getCompactionProviderId() else "",
        compactionModel = if (options.appSettings) compactionModelSettingsRepository.getCompactionModel() else "",
        syncSettings = if (options.appSettings) syncSettingsRepository.snapshot() else null,
        scheduledTasks = if (options.appSettings) scheduledTaskRepository.tasksFlow.first() else emptyList(),
        toolPermissionTiers = if (options.permissionRules) {
            toolPermissionTierRepository.tiersFlow.first().mapValues { it.value.key }
        } else {
            emptyMap()
        },
        watchModeArmed = if (options.permissionRules) watchModeSettingsRepository.isArmed() else null,
        containerSettings = if (options.appSettings) containerSettingsRepository.snapshot() else null,
        workspaces = if (options.workspaceFiles) collectWorkspaceMetas() else emptyList(),
        includesAppSettings = options.appSettings
    )

    private fun writeMetadataEntry(tar: TarArchiveOutputStream, metadata: BackupMetadata) {
        val content = json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray(Charsets.UTF_8)
        writeTarEntry(tar, FILE_METADATA, content)
    }

    private fun writeTarEntry(tar: TarArchiveOutputStream, name: String, content: ByteArray) {
        val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
        tar.putArchiveEntry(entry)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    /**
     * 先将 jsonl 写入一个临时文件，获取确切的 [File.length] 设置 TarArchiveEntry.size，
     * 然后流式拷入 TarArchiveOutputStream，避免在 Header 中 size 设为 0 导致写入越界异常。
     */
    private suspend fun writeJsonlFileEntry(
        tar: TarArchiveOutputStream,
        entryName: String,
        block: suspend (JsonlWriter) -> Unit
    ) {
        val tmp = createTempFile()
        try {
            FileOutputStream(tmp).use { fos ->
                val writer = JsonlWriter(fos)
                block(writer)
                writer.flush()
            }
            val entry = TarArchiveEntry(entryName).apply { size = tmp.length() }
            tar.putArchiveEntry(entry)
            FileInputStream(tmp).use { fis -> fis.copyTo(tar) }
            tar.closeArchiveEntry()
        } finally {
            tmp.delete()
        }
    }

    // ── 工作区文件备份 ──────────────────────────────────────────

    /** 仅备份内部本地工作区；外部本地工作区是用户设备上的目录，不属于 App 私有数据，不纳入备份。 */
    private fun backupLocalWorkspaces(): List<Workspace> =
        workspaceRepository.workspaces.value.filter { it.type != WorkspaceType.EXTERNAL_LOCAL }

    /** 第一遍：统计每个本地工作区将备份的文件数（写 metadata 用）。 */
    private fun collectWorkspaceMetas(): List<WorkspaceBackupMeta> =
        backupLocalWorkspaces().mapNotNull { ws ->
            var count = 0
            walkWorkspaceFiles(ws) { _, _ -> count++ }
            if (count > 0) WorkspaceBackupMeta(ws.name, count) else null
        }

    /** 第二遍：把各本地工作区文件写入 tar（`workspaces/<name>/<相对路径>`）。 */
    private fun writeWorkspaceEntries(tar: TarArchiveOutputStream) {
        backupLocalWorkspaces().forEach { ws ->
            walkWorkspaceFiles(ws) { file, parts ->
                writeTarFileEntry(tar, "workspaces/${ws.name}/${parts.joinToString("/")}", file)
            }
        }
    }

    /**
     * 递归遍历本地工作区，按「.gitignore（锚定）+ 同步忽略清单」排除文件；
     * `.git` 目录强制包含（其内部不参与忽略判断）；符号链接跳过（防循环）。
     */
    private fun walkWorkspaceFiles(
        workspace: Workspace,
        onFile: (File, List<String>) -> Unit
    ) {
        val root = File(workspace.path)
        if (!root.isDirectory) return  // 远程工作区或不存在：本地无文件可备份
        val customIgnores = syncSettingsRepository.ignoredPatterns.value
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val gitignorePatterns = parseGitIgnore(File(root, ".gitignore"))

        fun walk(dir: File, relParts: List<String>) {
            dir.listFiles()?.forEach { f ->
                val parts = relParts + f.name
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                if (parts.first() != ".git" &&
                    (customIgnores.any { it in parts } ||
                        GitIgnoreMatcher.isIgnored(gitignorePatterns, parts, anchored = true))
                ) {
                    return@forEach
                }
                if (f.isDirectory) walk(f, parts) else onFile(f, parts)
            }
        }
        walk(root, emptyList())
    }

    /** 解析工作区根 .gitignore：去空行/注释/结尾斜杠。 */
    private fun parseGitIgnore(file: File): List<String> =
        if (!file.isFile) emptyList()
        else file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().removeSuffix("/") }

    // ── AI 资产（~/.aicode 全局目录）备份 ──────────────────────

    /**
     * 把 ~/.aicode 下的白名单资产写入 tar（条目名 `aicode/<相对路径>`）：
     * [dirs] 为目录白名单（递归、跳过符号链接），[files] 为根级文件白名单。不存在的项静默跳过。
     */
    private fun writeAicodeEntries(tar: TarArchiveOutputStream, dirs: List<String>, files: List<String>) {
        val root = File(context.filesDir, AICODE_DIR)
        dirs.forEach { dirName ->
            val dir = File(root, dirName)
            if (dir.isDirectory) {
                walkAicodeFiles(dir, dirName) { file, rel ->
                    writeTarFileEntry(tar, "$AICODE_PREFIX$rel", file)
                }
            }
        }
        files.forEach { fileName ->
            val file = File(root, fileName)
            if (file.isFile) writeTarFileEntry(tar, "$AICODE_PREFIX$fileName", file)
        }
    }

    /** 递归遍历 ~/.aicode 资产目录（跳过符号链接防循环）；[relPrefix] 为相对 aicode 根的前缀。 */
    private fun walkAicodeFiles(dir: File, relPrefix: String, onFile: (File, String) -> Unit) {
        dir.listFiles()?.forEach { f ->
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
            val rel = if (relPrefix.isEmpty()) f.name else "$relPrefix/${f.name}"
            if (f.isDirectory) walkAicodeFiles(f, rel, onFile) else onFile(f, rel)
        }
    }

    /**
     * 把容器内的开发环境写入 tar（条目名 `container/<相对 rootfs 的路径>`）。
     *
     * 只备用户层，不备系统镜像本体：
     * - `/root`（home）：用户配置、脚本、引擎 CLI 的登录态与配置
     * - `/opt`：场景安装的组件（jadx、llama.cpp 等）
     * - `/usr/local`：npm 全局包（claude/codex/opencode）、pip 包、手工装的二进制
     *
     * 不备 `/proc` `/sys` `/dev` `/tmp` `/var/cache` `/var/log` 与 apk 数据库：
     * 前四者是内核/临时挂载，后两者是缓存与包管理器元数据（镜像重建后由场景脚本重新生成）。
     */
    private fun writeContainerEntries(tar: TarArchiveOutputStream) {
        val rootfs = containerInstaller.rootfsDir
        if (!rootfs.isDirectory) return
        CONTAINER_DIRS.forEach { rel ->
            val dir = File(rootfs, rel)
            if (dir.isDirectory) {
                walkContainerFiles(dir, rel) { file, path ->
                    writeTarFileEntry(tar, "$CONTAINER_PREFIX$path", file)
                }
            }
        }
    }

    /** 递归遍历容器用户层（跳过符号链接防循环）。 */
    private fun walkContainerFiles(dir: File, relPrefix: String, onFile: (File, String) -> Unit) {
        dir.listFiles()?.forEach { f ->
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
            val rel = "$relPrefix/${f.name}"
            if (f.isDirectory) walkContainerFiles(f, rel, onFile) else onFile(f, rel)
        }
    }

    private fun writeTarFileEntry(tar: TarArchiveOutputStream, name: String, file: File) {
        val entry = TarArchiveEntry(name).apply { size = file.length() }
        tar.putArchiveEntry(entry)
        FileInputStream(file).use { it.copyTo(tar) }
        tar.closeArchiveEntry()
    }

    // ── 导入辅助 ──────────────────────────────────────────────

    private suspend fun restoreFromTar(tar: TarArchiveInputStream, selectedWorkspaces: Set<String>?): RestoreStats {
        val restoreMapping = mutableMapOf<String, Workspace>()
        var metadata: BackupMetadata? = null
        var stats = RestoreStats()
        try {
            var entry = tar.nextEntry
            // 第一阶段：metadata 与文件类条目（AI 资产 / 工作区文件）流式落地。
            // 文件先写、DB 后写：DB 段可以整体包进一个事务，而文件段无法回滚（见下方的部分写入提示）。
            while (entry != null && !isDatabaseEntry(entry.name)) {
                if (entry.name == FILE_LEGACY_SNAPSHOT) {
                    val plain = tar.readBytes()
                    val snapshot = json.decodeFromString(BackupSnapshot.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(snapshot.schemaVersion)
                    return restoreLegacy(snapshot)
                }
                if (entry.name == FILE_METADATA) {
                    val plain = tar.readBytes()
                    metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(metadata!!.schemaVersion)
                } else {
                    stats += restoreFileEntry(tar, entry, selectedWorkspaces, restoreMapping)
                }
                entry = tar.nextEntry
            }
            val meta = metadata ?: run {
                FileLogger.e(TAG, "导入失败：tar 中缺少 metadata.json")
                error("不是有效的AiCodeR备份文件：缺少 metadata.json")
            }
            FileLogger.i(TAG, "文件段还原完成，开始还原 DB 段")
            // 第二阶段：DB 段同事务，中途失败整体回滚，不会留下「一半会话已入库」。
            // 元数据段不进事务：它写的是 DataStore/配置文件，Room 事务管不到，包进来只会拉长事务。
            var dbEntry = entry
            val dbStats = agentDatabase.withTransaction {
                var restored = RestoreStats()
                while (dbEntry != null && isDatabaseEntry(dbEntry.name)) {
                    restored += restoreDatabaseEntry(tar, dbEntry.name, restoreMapping)
                    dbEntry = tar.nextEntry
                }
                restored
            }
            // 第三阶段：导出顺序固定为「metadata → 文件 → DB」，正常不会走到这里；
            // 手改/第三方重打包把文件条目放到 DB 段之后时，仍在事务外补完，既不丢文件也不把文件 IO 塞进事务。
            while (dbEntry != null) {
                stats += if (isDatabaseEntry(dbEntry.name)) {
                    // 乱序的 DB 条目单独开事务补做：跳过就等于丢数据。
                    agentDatabase.withTransaction { restoreDatabaseEntry(tar, dbEntry.name, restoreMapping) }
                } else {
                    restoreFileEntry(tar, dbEntry, selectedWorkspaces, restoreMapping)
                }
                dbEntry = tar.nextEntry
            }
            val metaStats = restoreMeta(meta)
            return stats + dbStats + metaStats
        } catch (e: Throwable) {
            // 文件段已落盘而后续失败时，磁盘上是「有新文件、没新会话」的半成品，必须让用户知道。
            val writtenFiles = stats.workspaceFiles + stats.aiAssetFiles + stats.keyFiles
            if (writtenFiles > 0 && e !is PartialRestoreException) {
                throw PartialRestoreException(writtenFiles, e)
            }
            throw e
        }
    }

    /** DB 段条目：全部走同一个事务。 */
    private fun isDatabaseEntry(name: String): Boolean = when (name) {
        FILE_SESSIONS, FILE_MESSAGES, FILE_TODOS, FILE_GRAPH_NODES, FILE_GRAPH_EDGES -> true
        else -> false
    }

    /** 还原单个文件类条目（工作区文件 / AI 资产）；其余条目计数为 0。 */
    private suspend fun restoreFileEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        selectedWorkspaces: Set<String>?,
        restoreMapping: MutableMap<String, Workspace>
    ): RestoreStats = when {
        entry.name.startsWith(WORKSPACE_PREFIX) ->
            restoreWorkspaceEntry(tar, entry.name, selectedWorkspaces, restoreMapping)
        entry.name.startsWith(AICODE_PREFIX) -> restoreAicodeEntry(tar, entry)
        entry.name.startsWith(CONTAINER_PREFIX) -> restoreContainerEntry(tar, entry.name)
        else -> RestoreStats()
    }

    /**
     * 还原单个容器用户层文件：直接写回 rootfs 同名路径。
     *
     * 路径做了越界防护：条目名去掉前缀后不允许含 `..`，否则备份包可写到 rootfs 之外。
     */
    private fun restoreContainerEntry(tar: TarArchiveInputStream, name: String): RestoreStats {
        val rel = name.removePrefix(CONTAINER_PREFIX)
        if (rel.isBlank() || rel.contains("..")) {
            FileLogger.w(TAG, "跳过可疑容器条目：$name")
            return RestoreStats()
        }
        val rootfs = containerInstaller.rootfsDir
        val target = File(rootfs, rel)
        // 只允许落在 CONTAINER_DIRS 之下的路径，防止备份包向 rootfs 其他位置写文件
        val allowed = CONTAINER_DIRS.any { rel == it || rel.startsWith("$it/") }
        if (!allowed) return RestoreStats()

        target.parentFile?.mkdirs()
        val bytes = tar.readBytes()
        target.writeBytes(bytes)
        // tar 不保留权限位时脚本会丢可执行位，这里按扩展名/目录约定补回
        if (target.parentFile?.path?.contains("/bin") == true || target.name.endsWith(".sh")) {
            target.setExecutable(true, false)
        }
        return RestoreStats(containerFiles = 1)
    }

    /** 还原单个 DB 段 jsonl 条目（调用方负责事务）。未知条目记日志后跳过。 */
    private suspend fun restoreDatabaseEntry(
        tar: TarArchiveInputStream,
        name: String,
        restoreMapping: MutableMap<String, Workspace>
    ): RestoreStats = when (name) {
        FILE_SESSIONS -> {
            val count = restoreJsonl(tar, ChatSessionDto.serializer()) { dtos ->
                chatSessionDao.upsertAll(
                    dtos.map { dto ->
                        dto.copy(workspacePath = resolveSessionWorkspace(dto.workspacePath, restoreMapping))
                            .toEntity()
                    }
                )
            }
            FileLogger.i(TAG, "恢复会话 $count 条")
            RestoreStats(chatSessions = count)
        }
        FILE_MESSAGES -> {
            val count = restoreJsonl(tar, AgentMessageDto.serializer()) { dtos ->
                agentMessageDao.insertAll(dtos.map { it.toEntity() })
            }
            FileLogger.i(TAG, "恢复消息 $count 条")
            RestoreStats(agentMessages = count)
        }
        FILE_TODOS -> {
            val count = restoreJsonl(tar, TodoItemDto.serializer()) { dtos ->
                todoItemDao.upsertAll(dtos.map { it.toEntity() })
            }
            FileLogger.i(TAG, "恢复待办 $count 条")
            RestoreStats(todoItems = count)
        }
        FILE_GRAPH_NODES -> {
            val count = restoreJsonl(tar, GraphNodeDto.serializer()) { dtos ->
                dtos.forEach { graphDao.upsertNode(it.toEntity()) }
            }
            FileLogger.i(TAG, "恢复图谱节点 $count 条")
            RestoreStats()
        }
        FILE_GRAPH_EDGES -> {
            val count = restoreJsonl(tar, GraphEdgeDto.serializer()) { dtos ->
                dtos.forEach { graphDao.upsertEdge(it.toEntity()) }
            }
            FileLogger.i(TAG, "恢复图谱关系 $count 条")
            RestoreStats()
        }
        else -> {
            FileLogger.w(TAG, "跳过未知的备份条目：$name")
            RestoreStats()
        }
    }

    /** 从当前 tar 条目读首行（到 \n 或流末尾）；不关闭流，剩余内容由调用方决定是否继续读。 */
    private fun readFirstLine(tar: TarArchiveInputStream): String? {
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream(16 * 1024)
        while (true) {
            val n = tar.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    return String(line.toByteArray(), Charsets.UTF_8)
                }
                line.write(buffer[i].toInt())
            }
        }
        return if (line.size() > 0) String(line.toByteArray(), Charsets.UTF_8) else null
    }

    /** 逐行解析 jsonl 条目，每 [PAGE_SIZE] 条回调一次批量插入；返回该文件的总条数。 */
    private suspend fun <T> restoreJsonl(tar: TarArchiveInputStream, serializer: KSerializer<T>, insert: suspend (List<T>) -> Unit): Int {
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream(16 * 1024)
        val batch = ArrayList<T>(PAGE_SIZE)
        var count = 0
        while (true) {
            val n = tar.read(buffer)
            if (n < 0) break
            var start = 0
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    line.write(buffer, start, i - start)
                    start = i + 1
                    if (line.size() > 0) {
                        // 注意：ByteArrayOutputStream.toString(Charset) 是 API 33 才有的方法，
                        // 在 Android 13 以下会抛 NoSuchMethodError，必须用 String(byte[], Charset) 构造器。
                        batch.add(json.decodeFromString(serializer, String(line.toByteArray(), Charsets.UTF_8)))
                        line.reset()
                        if (batch.size >= PAGE_SIZE) {
                            count += batch.size
                            insert(batch.toList())
                            batch.clear()
                        }
                    } else {
                        line.reset()
                    }
                }
            }
            line.write(buffer, start, n - start)
        }
        if (line.size() > 0) {
            batch.add(json.decodeFromString(serializer, String(line.toByteArray(), Charsets.UTF_8)))
        }
        if (batch.isNotEmpty()) {
            count += batch.size
            insert(batch.toList())
        }
        return count
    }

    private fun checkVersion(schemaVersion: Int) {
        if (schemaVersion > currentSchemaVersion()) {
            error("备份的数据库版本 v$schemaVersion 高于本应用 v${currentSchemaVersion()}，请升级应用")
        }
    }

    /** 旧格式（单文件 snapshot.json 完整快照）还原。 */
    private suspend fun restoreLegacy(snapshot: BackupSnapshot): RestoreStats {
        // 先还原 DB 段（调用方已包事务），再还原元数据段：后者写 DataStore/配置文件，不属于 Room 事务范围。
        val stats = agentDatabase.withTransaction {
            if (snapshot.chatSessions.isNotEmpty()) {
                // 按备份中的工作区路径逐个解析到目标工作区（不存在则自动创建空工作区），不再全部塞进当前工作区。
                val restoreMapping = mutableMapOf<String, Workspace>()
                chatSessionDao.upsertAll(
                    snapshot.chatSessions.map {
                        it.copy(workspacePath = resolveSessionWorkspace(it.workspacePath, restoreMapping)).toEntity()
                    }
                )
            }
            if (snapshot.agentMessages.isNotEmpty()) {
                agentMessageDao.insertAll(snapshot.agentMessages.map { it.toEntity() })
            }
            if (snapshot.todoItems.isNotEmpty()) {
                todoItemDao.upsertAll(snapshot.todoItems.map { it.toEntity() })
            }
            RestoreStats(
                chatSessions = snapshot.chatSessions.size,
                agentMessages = snapshot.agentMessages.size,
                todoItems = snapshot.todoItems.size
            )
        }
        return restoreMeta(snapshot.toMetadata()) + stats
    }

    /** 元数据段还原（小表 + 应用设置），新旧格式共用。 */
    private suspend fun restoreMeta(meta: BackupMetadata): RestoreStats {
        FileLogger.i(
            TAG,
            "还原元数据：providers=${meta.providers.size} remoteConnections=${meta.remoteConnections.size} remoteMounts=${meta.remoteMounts.size} " +
                "mcpServers=${meta.mcpServers.size} permissionRules=${meta.globalPermissionRules.size} syncSettings=${meta.syncSettings != null}"
        )
        if (meta.providers.isNotEmpty() || meta.remoteConnections.isNotEmpty() || meta.remoteMounts.isNotEmpty()) {
            agentDatabase.withTransaction {
                if (meta.providers.isNotEmpty()) {
                    aiProviderDao.insertAllProviders(meta.providers.map { it.toEntity() })
                }
                if (meta.remoteConnections.isNotEmpty()) {
                    remoteConnectionDao.insertAllConnections(meta.remoteConnections.mapNotNull { it.toEntity() })
                }
                if (meta.remoteMounts.isNotEmpty()) {
                    // 指向已移除协议（旧版本的本地通道）的挂载一并跳过，避免外键约束失败。
                    val connectionIds = remoteConnectionDao.getAllConnectionsOnce().map { it.id }.toSet()
                    remoteConnectionDao.insertAllMounts(
                        meta.remoteMounts.map { it.toEntity() }.filter { it.connectionId in connectionIds }
                    )
                }
            }
        }
        if (meta.mcpServers.isNotEmpty()) {
            mcpConfigRepository.setGlobalServers(meta.mcpServers)
            mcpManager.reload()
        }
        if (meta.globalPermissionRules.isNotEmpty()) {
            permissionRulesRepository.setGlobalRules(meta.globalPermissionRules)
        }
        if (meta.toolPermissionTiers.isNotEmpty()) {
            val tiers = meta.toolPermissionTiers.mapNotNull { (tool, key) ->
                ToolPermissionTier.fromKey(key)?.let { tool to it }
            }.toMap()
            if (tiers.isNotEmpty()) toolPermissionTierRepository.restoreTiers(tiers)
        }
        meta.watchModeArmed?.let { watchModeSettingsRepository.setArmed(it) }
        if (meta.includesAppSettings) {
            meta.themeMode?.let { themeSettingsRepository.restore(it) }
            themeSettingsRepository.restoreColors(meta.themePresetId, meta.dynamicColorEnabled)
            keepaliveSettingsRepository.restore(meta.keepaliveEnabled)
            liveNotificationSettingsRepository.restore(meta.liveNotificationEnabled)
            approvalNotificationSettingsRepository.restore(meta.approvalNotificationEnabled)
            screenOnSettingsRepository.restore(meta.screenOnEnabled)
            agentSoundSettingsRepository.restore(meta.agentSoundEnabled)
            generalSettingsRepository.restoreAutoRemoveStaleModels(meta.autoRemoveStaleModels)
            generalSettingsRepository.restoreStartupSessionMode(meta.startupSessionMode)
            generalSettingsRepository.restoreFirstByteTimeoutSec(meta.firstByteTimeoutSec)
            generalSettingsRepository.restoreStreamIdleTimeoutSec(meta.streamIdleTimeoutSec)
            generalSettingsRepository.restoreMaxNetworkRetries(meta.maxNetworkRetries)
            generalSettingsRepository.restoreEnterToSend(meta.enterToSend)
            generalSettingsRepository.restoreCompactionThresholdPercent(meta.compactionThresholdPercent)
            generalSettingsRepository.restoreSendFileMaxSizeMb(meta.sendFileMaxSizeMb)
            generalSettingsRepository.restoreDeleteExternalWorkspaceSessions(meta.deleteExternalWorkspaceSessions)
            generalSettingsRepository.restoreHandoffPromptEnabled(meta.handoffPromptEnabled)
            generalSettingsRepository.restoreHandoffPromptPercent(meta.handoffPromptPercent)
            generalSettingsRepository.restoreHandoffAutoEnabled(meta.handoffAutoEnabled)
            generalSettingsRepository.restoreHandoffAutoPercent(meta.handoffAutoPercent)
            generalSettingsRepository.restoreFloatBallEnabled(meta.floatBallEnabled)
            logSettingsRepository.restore(meta.logLevel)
            if (meta.visionProviderId.isNotBlank() || meta.visionModel.isNotBlank()) {
                visionModelSettingsRepository.setVisionModel(meta.visionProviderId, meta.visionModel)
            }
            if (meta.compactionProviderId.isNotBlank() || meta.compactionModel.isNotBlank()) {
                compactionModelSettingsRepository.setCompactionModel(meta.compactionProviderId, meta.compactionModel)
            }
            meta.syncSettings?.let { syncSettingsRepository.restore(it) }
            meta.containerSettings?.let { containerSettingsRepository.restore(it) }
            if (meta.scheduledTasks.isNotEmpty()) {
                scheduledTaskRepository.upsertAll(meta.scheduledTasks)
            }
        }

        return RestoreStats(
            providers = meta.providers.size,
            remoteConnections = meta.remoteConnections.size,
            remoteMounts = meta.remoteMounts.size,
            mcpServers = meta.mcpServers.size,
            globalPermissionRules = meta.globalPermissionRules.size,
            scheduledTasks = meta.scheduledTasks.size
        )
    }

    /**
     * 还原单个工作区文件条目：`workspaces/<name>/<相对路径>`。
     * 仅处理勾选的工作区（[selectedWorkspaces] 非 null 时），且只允许写入内部工作区；本地无同名工作区时自动创建，
     * 避免新设备/重装后导入的工作区文件因找不到目标而丢失。
     */
    private suspend fun restoreWorkspaceEntry(
        tar: TarArchiveInputStream,
        entryName: String,
        selectedWorkspaces: Set<String>?,
        restoreMapping: MutableMap<String, Workspace>
    ): RestoreStats {
        val segments = entryName.split("/", limit = 3)
        if (segments.size != 3) return RestoreStats()
        val wsName = segments[1]
        if (selectedWorkspaces != null && wsName !in selectedWorkspaces) return RestoreStats()

        val ws = resolveWorkspaceForRestore(wsName, restoreMapping) ?: return RestoreStats()

        val wsDir = File(ws.path)
        if (!isPathInsideWorkspace(wsDir, segments[2])) return RestoreStats()
        val target = File(wsDir, segments[2])
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> tar.copyTo(out) }
        FileLogger.i(TAG, "恢复工作区文件：${ws.name}/${segments[2]}")
        return RestoreStats(workspaceFiles = 1)
    }

    /**
     * 还原单个 ~/.aicode 资产条目：`aicode/<相对路径>`。
     * 防御：白名单（[isAllowedAicodeAsset]）+ ~/.aicode 非符号链接 + 规范化路径仍在根内（防目录穿越）；
     * 目录条目只建目录；单文件超过 [MAX_AICODE_ENTRY_BYTES] 视为异常跳过（防恶意超大条目写满磁盘）。
     * git-credentials 写出后刷新凭据缓存，使导入立即生效；其余资产由各自 watch / 扫描机制处理。
     */
    private fun restoreAicodeEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry): RestoreStats {
        val rel = entry.name.removePrefix(AICODE_PREFIX)
        if (!isAllowedAicodeAsset(rel)) {
            FileLogger.w(TAG, "跳过不在白名单的 AI 资产条目：${entry.name}")
            return RestoreStats()
        }
        val root = File(context.filesDir, AICODE_DIR)
        root.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(root.toPath())) {
            FileLogger.w(TAG, "跳过 AI 资产导入：~/.aicode 是符号链接")
            return RestoreStats()
        }
        val target = File(root, rel)
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            FileLogger.w(TAG, "跳过越界的 AI 资产条目：${entry.name}")
            return RestoreStats()
        }
        if (entry.isDirectory) {
            target.mkdirs()
            return RestoreStats()
        }
        if (entry.size > MAX_AICODE_ENTRY_BYTES) {
            FileLogger.w(TAG, "跳过超大的 AI 资产条目：${entry.name}（${entry.size} 字节）")
            return RestoreStats()
        }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> tar.copyTo(out) }
        if (rel == AICODE_GIT_CREDENTIALS) fileCredentialRepository.reload()
        FileLogger.i(TAG, "恢复 AI 资产：$rel")
        val isKey = rel == AICODE_KEYS_DIR || rel.startsWith("$AICODE_KEYS_DIR/")
        return RestoreStats(aiAssetFiles = if (isKey) 0 else 1, keyFiles = if (isKey) 1 else 0)
    }

    /**
     * 按备份中的工作区名解析到目标工作区：优先复用本轮导入已解析的映射，其次复用本地同名内部工作区，
     * 都不存在则自动创建一个空内部工作区。工作区文件条目与会话共用同一映射，保证两者落到同一个工作区。
     */
    private suspend fun resolveWorkspaceForRestore(
        wsName: String,
        restoreMapping: MutableMap<String, Workspace>
    ): Workspace? {
        restoreMapping[wsName]?.let { return it }
        val reservedNames = restoreMapping.values.map { it.name }.toSet()
        val existing = workspaceRepository.workspaces.value.firstOrNull {
            it.name == wsName && it.type == WorkspaceType.INTERNAL && it.name !in reservedNames
        }
        val resolved = existing ?: run {
            val occupied = workspaceRepository.workspaces.value.map { it.name }.toSet() + reservedNames
            val targetName = WorkspaceRepository.uniqueName(wsName, occupied)
            workspaceRepository.createWorkspace(targetName) ?: return null
        }
        restoreMapping[wsName] = resolved
        FileLogger.i(TAG, "导入会话目标工作区：${resolved.name} -> ${resolved.path}")
        return resolved
    }

    /**
     * 解析某会话在备份中的原始工作区路径到目标设备的工作区：
     * 1. 现有工作区路径与备份一致（同设备重装场景，内部/外部本地都能对上）直接复用；
     * 2. 否则按路径末段当作工作区名，经 [resolveWorkspaceForRestore] 复用同名内部工作区或自动创建空工作区；
     * 3. 路径为空或解析失败（如无法创建）时兜底回当前工作区。
     */
    private suspend fun resolveSessionWorkspace(
        backupWorkspacePath: String,
        restoreMapping: MutableMap<String, Workspace>
    ): String {
        if (backupWorkspacePath.isBlank()) return workspaceRepository.currentPath()
        workspaceRepository.workspaces.value
            .firstOrNull { it.path == backupWorkspacePath && it.name !in restoreMapping.keys }
            ?.let { return it.path }
        val name = backupWorkspacePath.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return workspaceRepository.currentPath()
        return resolveWorkspaceForRestore(name, restoreMapping)?.path ?: workspaceRepository.currentPath()
    }

    /** 防路径穿越：工作区必须是本地目录，且相对路径规范化后仍位于其内。 */
    private fun isPathInsideWorkspace(wsDir: File, relPath: String): Boolean {
        if (!wsDir.isDirectory) return false
        val root = wsDir.canonicalPath
        val target = File(wsDir, relPath).canonicalPath
        return target == root || target.startsWith(root + File.separator)
    }

    private fun createTempFile(): File = File.createTempFile("backup", ".tmp", context.cacheDir)

    // ── Entity ↔ DTO 转换 ──────────────────────────────────────

    private fun AIProviderEntity.toDto() = ProviderDto(
        id = id,
        name = name,
        type = type,
        apiKey = KeystoreCipher.decryptString(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints,
        openaiChatCacheKey = openaiChatCacheKey,
        dashboardScriptPath = dashboardScriptPath,
        dashboardRefreshInterval = dashboardRefreshInterval,
        sortOrder = sortOrder,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = KeystoreCipher.decryptString(apiKeys),
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes,
        scriptParams = KeystoreCipher.decryptString(scriptParams),
        keySwitchStatusCodes = keySwitchStatusCodes
    )

    private fun ProviderDto.toEntity() = AIProviderEntity(
        id = id,
        name = name,
        type = type,
        apiKey = KeystoreCipher.encryptString(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints ?: true,
        openaiChatCacheKey = openaiChatCacheKey ?: false,
        dashboardScriptPath = dashboardScriptPath ?: "",
        dashboardRefreshInterval = dashboardRefreshInterval ?: 5,
        sortOrder = sortOrder ?: 0,
        multiKeyEnabled = multiKeyEnabled ?: false,
        apiKeys = KeystoreCipher.encryptString(apiKeys ?: ""),
        keyRotationStrategy = keyRotationStrategy ?: "SEQUENTIAL",
        keyFailoverThreshold = keyFailoverThreshold ?: 2,
        keyCooldownMinutes = keyCooldownMinutes ?: 5,
        scriptParams = KeystoreCipher.encryptString(scriptParams ?: ""),
        keySwitchStatusCodes = keySwitchStatusCodes ?: ""
    )

    private fun RemoteConnectionEntity.toDto() = RemoteConnectionDto(
        id, name, protocol.name, host, port, username, authType,
        KeystoreCipher.decryptString(authData), passphrase?.let { KeystoreCipher.decryptString(it) },
        sortOrder
    )

    /** 旧备份可能含已移除的协议（本地通道），此类记录跳过不恢复。 */
    private fun RemoteConnectionDto.toEntity(): RemoteConnectionEntity? {
        val parsed = runCatching { RemoteProtocol.valueOf(protocol) }.getOrNull() ?: return null
        return RemoteConnectionEntity(
            id, name, parsed, host, port, username, authType,
            if (authType.equals("PASSWORD", ignoreCase = true)) KeystoreCipher.encryptString(authData) else authData,
            passphrase?.let { KeystoreCipher.encryptString(it) },
            sortOrder = sortOrder ?: 0
        )
    }

    private fun RemoteMountEntity.toDto() = RemoteMountDto(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    private fun RemoteMountDto.toEntity() = RemoteMountEntity(id, connectionId, remotePath, localMountPath, isActive, autoConnect)

    private fun ChatSessionEntity.toDto() = ChatSessionDto(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, modeBeforePlan = modeBeforePlan, reasoningEffort = reasoningEffort, providerId = providerId,
        model = model, isPinned = isPinned, parentId = parentId, subagentType = subagentType, deletedAt = deletedAt, engine = engine
    )

    private fun ChatSessionDto.toEntity() = ChatSessionEntity(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, modeBeforePlan = modeBeforePlan, reasoningEffort = reasoningEffort, providerId = providerId,
        model = model, isPinned = isPinned, parentId = parentId, subagentType = subagentType, deletedAt = deletedAt, engine = engine
    )

    private fun AgentMessageEntity.toDto() = AgentMessageDto(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker,
        thinkingBlocksJson, isContextExcluded, compactedBySummaryId, intentType,
        ttfbMillis, generationWindowMillis
    )

    private fun AgentMessageDto.toEntity(): AgentMessageEntity {
        val summary = isContextSummary || (role == "ASSISTANT" && content.startsWith(
            com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
        ))
        val excluded = isContextExcluded || (
            isCompacted && !summary && !isCompactionMarker && role == "ASSISTANT" &&
                toolCallsJson == null && compactedBySummaryId == null && isLegacyUsageTable(content)
        )
        return AgentMessageEntity(
            id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
            isError, reasoning, signature, attachmentsJson, isCompacted && !excluded, summary, isCompactionMarker,
            thinkingBlocksJson = thinkingBlocksJson,
            isContextExcluded = excluded,
            compactedBySummaryId = if (excluded) null else compactedBySummaryId,
            intentType = intentType,
            ttfbMillis = ttfbMillis,
            generationWindowMillis = generationWindowMillis
        )
    }

    private fun isLegacyUsageTable(content: String): Boolean {
        val lines = content.split('\n')
        if (lines.size != 7 || lines[0] != "| 项目 | 今日 | 累计 |" || lines[1] != "|---|---|---|") {
            return false
        }
        val labels = listOf("调用次数", "输入 tokens", "输出 tokens", "缓存命中 tokens", "预估费用")
        return labels.indices.all { index ->
            val line = lines[index + 2]
            line.startsWith("| ${labels[index]} | ") && line.endsWith(" |") &&
                line.count { it == '|' } == 4 && line.split('|').let { columns ->
                    columns[2].startsWith(" ") && columns[2].endsWith(" ") &&
                        columns[3].startsWith(" ") && columns[3].endsWith(" ")
                }
        }
    }

    private fun TodoItemEntity.toDto() = TodoItemDto(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt)
    private fun TodoItemDto.toEntity() = TodoItemEntity(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt)

    private fun com.aicode.feature.memorygraph.data.local.entity.GraphNodeEntity.toDto() =
        GraphNodeDto(id, workspacePath, name, type, summary, sourceRefId, scope, validFrom, invalidAt, sourceSessionId, createdAt, updatedAt)

    private fun GraphNodeDto.toEntity() = com.aicode.feature.memorygraph.data.local.entity.GraphNodeEntity(
        id, workspacePath, name, type, summary, sourceRefId, scope, validFrom, invalidAt, sourceSessionId, createdAt, updatedAt
    )

    private fun com.aicode.feature.memorygraph.data.local.entity.GraphEdgeEntity.toDto() =
        GraphEdgeDto(id, workspacePath, fromNodeId, toNodeId, relation, validAt, invalidAt, createdAt, updatedAt)

    private fun GraphEdgeDto.toEntity() = com.aicode.feature.memorygraph.data.local.entity.GraphEdgeEntity(
        id, workspacePath, fromNodeId, toNodeId, relation, validAt, invalidAt, createdAt, updatedAt
    )

    internal companion object {
        private const val TAG = "BackupManager"
        const val PAGE_SIZE = 500
        const val FILE_METADATA = "metadata.json"
        const val FILE_SESSIONS = "chatSessions.jsonl"
        const val FILE_MESSAGES = "messages.jsonl"
        const val FILE_TODOS = "todoItems.jsonl"
        const val FILE_GRAPH_NODES = "graphNodes.jsonl"
        const val FILE_GRAPH_EDGES = "graphEdges.jsonl"
        const val FILE_LEGACY_SNAPSHOT = "snapshot.json"
        const val WORKSPACE_PREFIX = "workspaces/"
        const val AICODE_DIR = "aicode"
        const val AICODE_PREFIX = "aicode/"
        /** 容器开发环境（用户层）条目前缀。 */
        const val CONTAINER_PREFIX = "container/"
        /** 容器内需备份的用户层目录（相对 rootfs）。 */
        val CONTAINER_DIRS = listOf("root", "opt", "usr/local")
        const val AICODE_GIT_CREDENTIALS = "git-credentials"
        const val AICODE_KEYS_DIR = "keys"

        /** 备份 gzip 压缩级别（fast 档；取舍依据见 [backupGzipParameters]）。 */
        const val BACKUP_GZIP_LEVEL = 4

        /**
         * AI 资产单文件上限（防恶意超大条目写满磁盘；技能/记忆/密钥等正常文件远小于此值）。
         * 多条目「总量」不设限：与工作区段/消息段同级的既有一致性设计——导入他人备份即视为信任该文件。
         */
        const val MAX_AICODE_ENTRY_BYTES = 64L * 1024 * 1024

        /** AI 资产目录白名单（递归打包）；keys 为独立勾选段，不在此列。 */
        val AI_ASSET_DIRS = listOf("skills", "scripts", "agents", "teams", "prompts.custom", "memory", "notes")

        /** AI 资产根级文件白名单。 */
        val AI_ASSET_FILES = listOf("agents.json", "skills.json", "git-credentials", "hooks.json")

        /**
         * AI 资产条目白名单校验（导入侧防穿越第一道闸）：目录白名单下的任意文件，或根级文件白名单；
         * 拒绝空段与 `.` / `..` 段（第二道闸为 [restoreAicodeEntry] 里的规范化路径前缀校验）。
         */
        internal fun isAllowedAicodeAsset(rel: String): Boolean {
            val segments = rel.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
            if (segments.size == 1) return segments[0] in AI_ASSET_FILES
            return segments[0] in AI_ASSET_DIRS || segments[0] == AICODE_KEYS_DIR
        }
    }
}

/** 带内部缓冲的 jsonl 写入器：行缓冲满 64KB 时刷入 tar 流，避免逐行写系统调用。 */
private class JsonlWriter(private val out: OutputStream) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    fun writeLine(line: String) {
        buffer.write(line.toByteArray(Charsets.UTF_8))
        buffer.write('\n'.code)
        if (buffer.size() >= 64 * 1024) flush()
    }

    fun flush() {
        buffer.writeTo(out)
        buffer.reset()
    }
}

/** 打开的 tar 流封装：加密导入时附带解密用的临时文件，关闭时一并清理。 */
private class TarSource(
    val tar: TarArchiveInputStream,
    private val temp: File?
) : AutoCloseable {
    override fun close() {
        runCatching { tar.close() }
        temp?.delete()
    }
}
