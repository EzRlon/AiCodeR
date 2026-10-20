package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.domain.model.CleanupKind
import com.aicode.feature.settings.domain.model.DeviceSpace
import com.aicode.feature.settings.domain.model.StorageCategory
import com.aicode.feature.settings.domain.model.StorageDetailKey
import com.aicode.feature.settings.domain.model.StorageEntry
import com.aicode.feature.settings.domain.service.StorageUsageScanner
import com.aicode.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val freedBytes: Long? = null
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
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val scanner: StorageUsageScanner,
    private val sessionUseCase: SessionUseCase,
    private val checkpointManager: CheckpointManager,
    private val agentDatabase: AgentDatabase
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    /** 单独持有扫描协程：清理完成后触发的重扫不能把清理自身也取消掉。 */
    private var scanJob: Job? = null

    init {
        refresh()
    }

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
        // VACUUM 是整库重建，大库秒级：必须在 IO 线程执行，否则阻塞主线程掉帧/ANR
        runCatching {
            withContext(Dispatchers.IO) {
                val db = agentDatabase.openHelper.writableDatabase
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                db.execSQL("VACUUM")
            }
        }.onFailure { FileLogger.w(TAG, "聊天数据库收缩失败", it) }
        return freed
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
}
