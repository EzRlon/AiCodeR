package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.permission.PermissionAuditRecorder
import com.aicode.feature.agent.domain.permission.PermissionAuditSource
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.WatchModeSettingsRepository
import com.aicode.feature.schedule.domain.ScheduledAiRunState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ToolPermissionManager @Inject constructor(
    private val auditRecorder: PermissionAuditRecorder,
    private val watchModeSettings: WatchModeSettingsRepository,
    private val scheduledAiRunState: ScheduledAiRunState
) {
    private companion object {
        const val TAG = "ToolPermissionManager"

        /** 守夜弹窗兜底超时（默认 10 分钟）：超时自动拒绝让会话继续，不无限等待。 */
        const val WATCH_APPROVAL_TIMEOUT_MS = 10 * 60 * 1000L
    }

    /** 守夜超时时长；仅测试覆写为短值。 */
    internal var watchApprovalTimeoutMs: Long = WATCH_APPROVAL_TIMEOUT_MS

    /** 守夜超时事件（供通知中心订阅发通知）。 */
    private val _watchTimeoutEvents = MutableSharedFlow<PendingToolPermission>(extraBufferCapacity = 8)
    val watchTimeoutEvents: SharedFlow<PendingToolPermission> = _watchTimeoutEvents.asSharedFlow()
    /**
     * 所有待决授权请求，按插入顺序保存（key = 请求 id）。多会话并行时每个会话可各自挂起一个请求，
     * 互不阻塞——这样侧边栏才能同时点亮所有等待授权的会话。同一会话内的权限请求由 workflow 串行处理，
     * 故一个会话在此表中至多出现一次。
     */
    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** 弹窗展示用：当前应展示的单个请求（取最早进入的），解决后自动切到下一个。 */
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    /** 侧边栏用：当前正在等待授权的会话 id 集合。 */
    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    /**
     * 挂起等待用户在弹窗中的选择（拒绝/本次/始终）。不同会话可并行挂起，互不阻塞。
     * 守夜模式下带 10 分钟兜底超时：超时自动拒绝（记录 + 事件流通知），不无限等待。
     */
    suspend fun awaitApproval(request: PendingToolPermission): ToolApprovalOutcome {
        val decision = CompletableDeferred<PermissionChoice>()
        synchronized(lock) {
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            // 守夜或定时 AI 任务运行中：带兜底超时，不无限等待（无人值守语义，见 L2.5）。
            if (watchModeSettings.isArmed() || scheduledAiRunState.hasActive()) {
                val choice = withTimeoutOrNull(watchApprovalTimeoutMs) { decision.await() }
                if (choice == null) {
                    // 竞态防护：超时瞬间用户可能刚好点了按钮（decision 已完成）——采纳用户选择，避免弃选与审计失真。
                    if (decision.isCompleted) return ToolApprovalOutcome.Decided(decision.getCompleted())
                    FileLogger.w(TAG, "守夜超时，自动拒绝: ${request.toolName} id=${request.id}")
                    auditRecorder.recordWatchTimeout(request)
                    _watchTimeoutEvents.tryEmit(request)
                    return ToolApprovalOutcome.WatchTimedOut
                }
                return ToolApprovalOutcome.Decided(choice)
            }
            return ToolApprovalOutcome.Decided(decision.await())
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                publish()
            }
        }
    }

    /**
     * UI 回传用户选择，唤醒对应 id 的挂起请求。返回是否命中（未命中说明请求已被解决或进程重建）。
     * [source] 用于授权记录区分弹窗/通知栏来源，不影响裁决。
     */
    fun resolve(id: String, choice: PermissionChoice, source: PermissionAuditSource = PermissionAuditSource.POPUP): Boolean {
        val entry = synchronized(lock) { pending[id] } ?: return false
        entry.decision.complete(choice)
        auditRecorder.recordUserDecision(entry.request, choice, source)
        return true
    }

    /**
     * 审批通知上的按钮用：在无歧义（至多一个待决请求）时按选择解决最早的请求。
     * 调用方须保证当前只有一个待决请求，否则会作用到最早进入的那个。
     * 默认记通知来源（与前台弹窗区分，审计可追溯）。
     */
    fun resolveEarliest(choice: PermissionChoice, source: PermissionAuditSource = PermissionAuditSource.NOTIFICATION): Boolean {
        val id = synchronized(lock) { pending.keys.firstOrNull() } ?: return false
        return resolve(id, choice, source)
    }

    /** 取某会话当前的待决请求（stopAgent 停止单个会话时用，避免误取其它会话的弹窗）。 */
    fun pendingForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) { pending.values.firstOrNull { it.request.sessionId == sessionId }?.request }

    /** 在持锁区内调用：把内部表投影到两个对外 StateFlow。 */
    private fun publish() {
        _pendingRequest.value = pending.values.firstOrNull()?.request
        _awaitingSessionIds.value = pending.values.mapNotNull { it.request.sessionId.ifBlank { null } }.toSet()
    }

    private class Entry(
        val request: PendingToolPermission,
        val decision: CompletableDeferred<PermissionChoice>
    )
}

/** 工具授权挂起的结果。 */
sealed interface ToolApprovalOutcome {
    /** 用户（或通知栏）做出了选择。 */
    data class Decided(val choice: PermissionChoice) : ToolApprovalOutcome

    /** 守夜兜底：弹窗超时无人应答，自动拒绝（引导语由工作流给模型）。 */
    data object WatchTimedOut : ToolApprovalOutcome
}
