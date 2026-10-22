package com.aicode.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.aicode.R
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import com.aicode.feature.settings.data.repository.StorageCleanupRepository
import com.aicode.feature.settings.domain.model.CleanupKind
import com.aicode.feature.settings.domain.model.CleanupRecommendation
import com.aicode.feature.settings.domain.model.CleanupRecommendationCache
import com.aicode.feature.settings.domain.model.CleanupRecommendationLevel
import com.aicode.feature.settings.domain.model.DeviceSpace
import com.aicode.feature.settings.domain.model.StorageCategory
import com.aicode.feature.settings.domain.model.StorageDetailKey
import com.aicode.feature.settings.domain.model.StorageEntry
import com.aicode.feature.settings.domain.service.StorageCleanupAdvisor
import com.aicode.feature.settings.domain.service.StorageUsageScanner
import com.aicode.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class StorageUiState(
    /** 已算出的分类；扫描中时逐项填充，缺项界面显示「统计中」。 */
    val entries: Map<StorageCategory, StorageEntry> = emptyMap(),
    val scanning: Boolean = false,
    val deviceSpace: DeviceSpace = DeviceSpace(0, 0),
    val expanded: Set<StorageCategory> = emptySet(),
    val cleaning: Boolean = false,
    val selectedCleanup: Set<CleanupKind> = setOf(CleanupKind.Caches, CleanupKind.Logs),
    /** 勾选的产物明细（定位标识 `<项目名>/<相对路径>`）。 */
    val selectedArtifacts: Set<String> = emptySet(),
    /** 勾选的聊天会话（明细 key `chat-session:<id>`，删除含子代理会话与检查点）。 */
    val selectedSessions: Set<String> = emptySet(),
    /** 上一次清理释放的字节数，非空时界面给出提示。 */
    val freedBytes: Long? = null,
    /** 推荐清理池：打开页面先展示上次每周扫描的缓存结论，手动/自动重扫后更新。 */
    val recommendations: List<CleanupRecommendation> = emptyList(),
    val recommendScanning: Boolean = false,
    val recommendScannedAt: Long? = null,
    /** 当前展示的是缓存结论（尚未在本次会话里重扫过），界面给「上次扫描」提示。 */
    val recommendFromCache: Boolean = false,
    val selectedRecommendations: Set<String> = emptySet(),
    val recommendExpanded: Boolean = false,
    /** 运行健康只读清单：展开折叠组时按需加载一次，不做轮询。 */
    val runtimeHealth: List<RuntimeHealthItem> = emptyList(),
    val runtimeHealthLoading: Boolean = false,
    val runtimeHealthExpanded: Boolean = false
) {
    val totalBytes: Long get() = entries.values.sumOf { it.bytes }

    /** 各可清理项当前大小；未统计完的项按 0 计，界面据此禁用按钮。 */
    val cleanableSizes: Map<CleanupKind, Long>
        get() = mapOf(
            CleanupKind.Caches to (entries[StorageCategory.Caches]?.bytes ?: 0L),
            CleanupKind.Logs to (entries[StorageCategory.Logs]?.bytes ?: 0L),
            CleanupKind.ToolOutput to aiConfigDetail(StorageDetailKey.TOOL_OUTPUT),
            CleanupKind.VisionSessions to aiConfigDetail(StorageDetailKey.VISION_SESSIONS),
            CleanupKind.Checkpoints to (entries[StorageCategory.Checkpoints]?.bytes ?: 0L),
            CleanupKind.ContainerImages to (entries[StorageCategory.ContainerImages]?.bytes ?: 0L),
            CleanupKind.GeneratedImages to aiConfigDetail(StorageDetailKey.GENERATED_IMAGES),
            CleanupKind.ContainerCaches to (entries[StorageCategory.Container]?.details
                ?.firstOrNull { it.key == StorageDetailKey.CONTAINER_CACHES }?.bytes ?: 0L)
        )

    /** 产物明细（带路径的明细行）的定位标识 → 大小。 */
    val artifactSizes: Map<String, Long>
        get() = entries.values
            .flatMap { it.details }
            .mapNotNull { detail -> detail.path?.let { it to detail.bytes } }
            .toMap()

    /** 聊天会话明细 key（`chat-session:<id>`）→ 估算字节。 */
    val sessionSizes: Map<String, Long>
        get() = entries[StorageCategory.Chat]?.details
            ?.mapNotNull { d ->
                d.key?.takeIf { it.startsWith(StorageDetailKey.CHAT_SESSION_PREFIX) }?.let { it to d.bytes }
            }
            ?.toMap()
            ?: emptyMap()

    /** 勾选产物的合计大小（去除「已选中目录」与其后代项的重复计）。 */
    val artifactSelectedBytes: Long
        get() {
            val kept = mutableListOf<String>()
            var sum = 0L
            selectedArtifacts.sorted().forEach { path ->
                if (kept.none { path == it || path.startsWith("$it/") }) {
                    kept += path
                    sum += artifactSizes[path] ?: 0L
                }
            }
            return sum
        }

    /** 勾选会话的估算合计（子代理会话若也勾选，其估算可能与父会话重叠，此处不去重、仅供预估）。 */
    val sessionSelectedBytes: Long
        get() = selectedSessions.sumOf { sessionSizes[it] ?: 0L }

    val selectedBytes: Long
        get() = cleanableSizes.filterKeys { it in selectedCleanup }.values.sum() +
            artifactSelectedBytes + sessionSelectedBytes

    private fun aiConfigDetail(key: String): Long =
        entries[StorageCategory.AiConfig]?.details?.firstOrNull { it.key == key }?.bytes ?: 0L

    /** 推荐池里推荐级（高置信）的合计大小，头部展示「高置信 Y」。 */
    val recommendedBytes: Long
        get() = recommendations.filter { it.level == CleanupRecommendationLevel.RECOMMENDED }.sumOf { it.bytes }

    /** 勾选推荐项的合计大小。 */
    val recommendationSelectedBytes: Long
        get() = recommendations.filter { it.id in selectedRecommendations }.sumOf { it.bytes }
}

/** 运行健康清单里的一行：常驻服务 / 周期任务 / 活跃扫描的状态快照。 */
data class RuntimeHealthItem(
    val title: String,
    val detail: String,
    val abnormal: Boolean = false
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val scanner: StorageUsageScanner,
    private val sessionUseCase: SessionUseCase,
    private val checkpointManager: CheckpointManager,
    private val agentDatabase: AgentDatabase,
    private val advisor: StorageCleanupAdvisor,
    private val cleanupRepository: StorageCleanupRepository,
    private val scheduledTaskRepository: ScheduledTaskRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    /** 单独持有扫描协程：清理完成后触发的重扫不能把清理自身也取消掉。 */
    private var scanJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            // 存储页首次打开：确保每周推荐扫描已注册，并读入上次扫描的缓存结论
            cleanupRepository.ensureWeeklyScanScheduled()
            val cache = runCatching { cleanupRepository.cacheFlow.first() }.getOrNull() ?: return@launch
            if (cache.scannedAt > 0) {
                _state.update {
                    it.copy(
                        recommendations = cache.items,
                        recommendScannedAt = cache.scannedAt,
                        recommendFromCache = true,
                        selectedRecommendations = defaultSelectedRecommendations(cache.items)
                    )
                }
            }
        }
    }

    private fun defaultSelectedRecommendations(items: List<CleanupRecommendation>): Set<String> =
        items.filter { it.level == CleanupRecommendationLevel.RECOMMENDED }.map { it.id }.toSet()

    fun refresh(clearMessage: Boolean = true) {
        scanJob?.cancel()
        _state.update {
            it.copy(
                entries = emptyMap(),
                scanning = true,
                deviceSpace = scanner.deviceSpace(),
                freedBytes = if (clearMessage) null else it.freedBytes
            )
        }
        scanJob = viewModelScope.launch {
            scanner.scan().collect { entry ->
                _state.update { s -> s.copy(entries = s.entries + (entry.category to entry)) }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun toggleExpanded(category: StorageCategory) {
        _state.update {
            val expanded = if (category in it.expanded) it.expanded - category else it.expanded + category
            it.copy(expanded = expanded)
        }
    }

    fun toggleCleanup(kind: CleanupKind) {
        _state.update {
            if (it.cleaning) return@update it
            val selected = if (kind in it.selectedCleanup) it.selectedCleanup - kind else it.selectedCleanup + kind
            it.copy(selectedCleanup = selected)
        }
    }

    /** 勾选/取消勾选一条产物明细（定位标识）。 */
    fun toggleArtifact(path: String) {
        _state.update {
            if (it.cleaning) return@update it
            val selected = if (path in it.selectedArtifacts) it.selectedArtifacts - path else it.selectedArtifacts + path
            it.copy(selectedArtifacts = selected)
        }
    }

    /** 勾选/取消勾选一个聊天会话（明细 key `chat-session:<id>`）。 */
    fun toggleSession(sessionKey: String) {
        _state.update {
            if (it.cleaning) return@update it
            val selected = if (sessionKey in it.selectedSessions) it.selectedSessions - sessionKey else it.selectedSessions + sessionKey
            it.copy(selectedSessions = selected)
        }
    }

    /** 清理当前选中的可重建数据、产物文件与聊天会话。 */
    fun cleanSelected() {
        val snapshot = _state.value
        if (snapshot.cleaning) return
        val kinds = CleanupKind.entries.filter {
            it in snapshot.selectedCleanup && (snapshot.cleanableSizes[it] ?: 0L) > 0L
        }
        val artifacts = snapshot.selectedArtifacts.toList()
        val sessions = snapshot.selectedSessions.toList()
        if (kinds.isEmpty() && artifacts.isEmpty() && sessions.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(cleaning = true) }
            var freed = 0L
            // 扫描/删除过程含 DB 写与文件 IO，磁盘满等异常不能让协程崩掉（viewModelScope 无全局 handler）
            // 也不得把 cleaning 卡在 true：逐段 runCatching，失败段计 0 继续后续段
            kinds.forEach { kind ->
                freed += runCatching { scanner.clean(kind) }
                    .onFailure { FileLogger.w(TAG, "清理 ${kind.name} 失败", it) }
                    .getOrDefault(0L)
            }
            if (artifacts.isNotEmpty()) {
                freed += runCatching { scanner.cleanArtifacts(artifacts) }
                    .onFailure { FileLogger.w(TAG, "清理产物失败", it) }
                    .getOrDefault(0L)
            }
            if (sessions.isNotEmpty()) freed += deleteChatSessions(sessions)
            _state.update {
                it.copy(
                    cleaning = false,
                    freedBytes = freed,
                    selectedArtifacts = emptySet(),
                    selectedSessions = emptySet()
                )
            }
            vacuumChatDatabase()
            refresh(clearMessage = false)
        }
    }

    /**
     * 删除勾选的聊天会话（含子代理会话、消息与其检查点），并收缩数据库文件。
     * 删除行时 Room 只标记空闲页，文件不缩小；VACUUM 后大小才真正回落。
     */
    private suspend fun deleteChatSessions(sessionKeys: List<String>): Long {
        var freed = 0L
        sessionKeys.forEach { key ->
            val sessionId = key.removePrefix(StorageDetailKey.CHAT_SESSION_PREFIX)
            runCatching {
                sessionUseCase.deleteSession(sessionId).forEach { child ->
                    checkpointManager.clearSessionCheckpoints(child)
                }
                freed += _state.value.sessionSizes[key] ?: 0L
            }.onFailure { FileLogger.w(TAG, "删除会话失败: $sessionId", it) }
        }
        return freed
    }

    /**
     * 收缩聊天数据库：删除行时 Room 只标记空闲页，文件不缩小；VACUUM 后大小才真正回落。
     * VACUUM 是整库重建，大库秒级：必须在 IO 线程执行，否则阻塞主线程掉帧/ANR。
     */
    private suspend fun vacuumChatDatabase() {
        runCatching {
            withContext(Dispatchers.IO) {
                val db = agentDatabase.openHelper.writableDatabase
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                db.execSQL("VACUUM")
            }
        }.onFailure { FileLogger.w(TAG, "聊天数据库收缩失败", it) }
    }

    private companion object {
        private const val TAG = "StorageViewModel"
    }

    /** 删除单个产物文件/目录（操作菜单里的快捷删除）。 */
    fun deleteArtifact(path: String) {
        if (_state.value.cleaning) return
        viewModelScope.launch {
            _state.update { it.copy(cleaning = true) }
            val freed = runCatching { scanner.cleanArtifacts(listOf(path)) }
                .onFailure { FileLogger.w(TAG, "删除产物失败: $path", it) }
                .getOrDefault(0L)
            _state.update {
                it.copy(cleaning = false, freedBytes = freed, selectedArtifacts = it.selectedArtifacts - path)
            }
            refresh(clearMessage = false)
        }
    }

    // ── 推荐清理 ──────────────────────────────────────────────────────────────

    fun toggleRecommendExpanded() {
        _state.update { it.copy(recommendExpanded = !it.recommendExpanded) }
    }

    /** 手动重扫推荐池：结果落盘缓存，推荐级默认勾选。 */
    fun scanRecommendations() {
        if (_state.value.recommendScanning || _state.value.cleaning) return
        viewModelScope.launch {
            _state.update { it.copy(recommendScanning = true) }
            val items = runCatching { advisor.scan() }
                .onFailure { FileLogger.w(TAG, "扫描推荐失败", it) }
                .getOrDefault(emptyList())
            val scannedAt = System.currentTimeMillis()
            runCatching { cleanupRepository.save(CleanupRecommendationCache(items = items, scannedAt = scannedAt)) }
                .onFailure { FileLogger.w(TAG, "推荐池缓存写入失败", it) }
            _state.update {
                it.copy(
                    recommendations = items,
                    recommendScanning = false,
                    recommendScannedAt = scannedAt,
                    recommendFromCache = false,
                    selectedRecommendations = defaultSelectedRecommendations(items)
                )
            }
        }
    }

    fun toggleRecommendation(id: String) {
        _state.update {
            if (it.cleaning || it.recommendScanning) return@update it
            val selected =
                if (id in it.selectedRecommendations) it.selectedRecommendations - id
                else it.selectedRecommendations + id
            it.copy(selectedRecommendations = selected)
        }
    }

    /** 清除勾选的推荐项：逐条执行 → VACUUM → 重扫存储分类与推荐池。 */
    fun cleanRecommendations() {
        val snapshot = _state.value
        if (snapshot.cleaning || snapshot.recommendScanning) return
        val items = snapshot.recommendations.filter { it.id in snapshot.selectedRecommendations }
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(cleaning = true) }
            val freed = runCatching { advisor.clean(items) }
                .onFailure { FileLogger.w(TAG, "清除推荐项失败", it) }
                .getOrDefault(0L)
            _state.update {
                it.copy(
                    cleaning = false,
                    freedBytes = freed,
                    selectedRecommendations = emptySet(),
                    recommendations = emptyList(),
                    recommendScannedAt = null
                )
            }
            vacuumChatDatabase()
            refresh(clearMessage = false)
            scanRecommendations()
        }
    }

    // ── 运行健康（只读） ────────────────────────────────────────────────────

    fun toggleRuntimeHealthExpanded() {
        val willExpand = !_state.value.runtimeHealthExpanded
        _state.update { it.copy(runtimeHealthExpanded = willExpand) }
        if (willExpand && _state.value.runtimeHealth.isEmpty()) loadRuntimeHealth()
    }

    /** 按需加载一次运行健康快照（WorkManager 状态 + 常驻服务 + 定时任务），不做轮询。 */
    private fun loadRuntimeHealth() {
        if (_state.value.runtimeHealthLoading) return
        viewModelScope.launch {
            _state.update { it.copy(runtimeHealthLoading = true) }
            val items = buildList {
                val keepalive = runCatching { TerminalKeepaliveService.isRunning(appContext) }.getOrDefault(false)
                add(
                    RuntimeHealthItem(
                        title = appContext.getString(R.string.storage_health_keepalive),
                        detail = appContext.getString(
                            if (keepalive) R.string.storage_health_running else R.string.storage_health_stopped
                        )
                    )
                )
                val weeklyState = runCatching {
                    withContext(Dispatchers.IO) {
                        WorkManager.getInstance(appContext)
                            .getWorkInfosForUniqueWork(StorageCleanupRepository.UNIQUE_WORK_NAME)
                            .get(5, TimeUnit.SECONDS)
                    }
                }.getOrNull()?.firstOrNull()?.state
                val (weeklyLabel, weeklyAbnormal) = when (weeklyState) {
                    WorkInfo.State.RUNNING -> R.string.storage_health_running to false
                    WorkInfo.State.FAILED -> R.string.storage_health_failed to true
                    WorkInfo.State.SUCCEEDED -> R.string.storage_health_work_succeeded to false
                    WorkInfo.State.CANCELLED -> R.string.storage_health_work_cancelled to true
                    WorkInfo.State.ENQUEUED -> R.string.storage_health_work_enqueued to false
                    null -> R.string.storage_health_work_enqueued to false
                    else -> R.string.storage_health_work_enqueued to false
                }
                add(
                    RuntimeHealthItem(
                        title = appContext.getString(R.string.storage_health_weekly_scan),
                        detail = appContext.getString(weeklyLabel),
                        abnormal = weeklyAbnormal
                    )
                )
                runCatching { scheduledTaskRepository.tasksFlow.first() }.getOrDefault(emptyList())
                    .forEach { task ->
                        val time = task.lastRunAt?.let {
                            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                        }
                        val (label, abnormal) = when {
                            task.lastRunAt == null -> R.string.storage_health_never_ran to false
                            task.lastExitCode == null -> R.string.storage_health_last_run_interrupted to true
                            task.lastExitCode == 0 -> R.string.storage_health_last_run_ok to false
                            else -> R.string.storage_health_last_run_failed to true
                        }
                        add(
                            RuntimeHealthItem(
                                title = task.name.ifBlank { appContext.getString(R.string.storage_health_task) },
                                detail = appContext.getString(label, time ?: ""),
                                abnormal = abnormal
                            )
                        )
                    }
                val busy = _state.value.scanning || _state.value.recommendScanning
                add(
                    RuntimeHealthItem(
                        title = appContext.getString(R.string.storage_health_active_scan),
                        detail = appContext.getString(
                            if (busy) R.string.storage_health_scanning_now else R.string.storage_health_idle
                        )
                    )
                )
            }
            _state.update { it.copy(runtimeHealth = items, runtimeHealthLoading = false) }
        }
    }
}
