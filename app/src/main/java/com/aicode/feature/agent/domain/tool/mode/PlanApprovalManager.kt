package com.aicode.feature.agent.domain.tool.mode

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.permission.PermissionAuditRecorder
import com.aicode.feature.agent.domain.permission.WatchModeSettingsRepository
import com.aicode.feature.schedule.domain.ScheduledAiRunState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 管理计划审查流程的挂起/恢复：
 * 当 AI 从 PLAN 模式切回 BUILD 模式时，workflow 调用 [awaitApproval] 挂起，
 * 等 UI 上的计划审查面板得到用户决策后恢复。
 *
 * 多会话并行时每个会话各自挂起一个请求，互不阻塞：内部按请求分槽保存，
 * UI 展示最早进入的那个，解决后自动切到下一个。若只保留单槽，后到的会话会覆盖
 * 前者的 decision，导致前者永久挂起。
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val watchModeSettings: WatchModeSettingsRepository,
    private val auditRecorder: PermissionAuditRecorder,
    private val scheduledAiRunState: ScheduledAiRunState
) {
    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    // 回滚协程所在 Scope 必须带 SupervisorJob：否则某个回滚失败会让整个 Scope 永久取消，
    // 之后所有「拒绝计划后回滚到 PLAN」静默失效。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 挂起等待用户在计划审查面板中的决策（守夜模式下自动通过 + 记录，不挂起）。 */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        // 守夜或定时 AI 任务运行中：无人值守不卡在审查面板——自动通过并记录（可回看）。
        if (watchModeSettings.isArmed() || scheduledAiRunState.hasActive()) {
            // 守夜：无人值守不卡在审查面板——自动通过并记录（可回看）。
            auditRecorder.recordWatchAllow(sessionId, "planMode", "计划审查", "守夜：计划审查自动通过")
            return PlanApprovalChoice.APPROVE
        }
        val decision = CompletableDeferred<PlanApprovalChoice>()
        val key = UUID.randomUUID().toString()
        synchronized(lock) {
            pending[key] = Entry(reason, sessionId, decision)
            publish()
        }

        try {
            return decision.await()
        } catch (e: CancellationException) {
            // 用户未批准切换（点「停止」等取消路径）：SwitchModeTool 已把目标模式写入 DB，
            // 取消意味着未获批准，需回滚，否则模式会未经批准直接切过去。
            rollbackModeToPlan(sessionId)
            throw e
        } finally {
            synchronized(lock) {
                pending.remove(key)
                publish()
            }
        }
    }

    /** UI 回传用户选择，唤醒最早挂起的 [awaitApproval]。 */
    fun resolve(choice: PlanApprovalChoice) {
        // 取最早条目与移除必须在同一持锁区内完成：先读 id 再进锁的话，两次之间该条目
        // 可能已被取消并换成新请求，remove(staleId) 返回 null，用户这次点击被静默丢弃。
        val entry = synchronized(lock) {
            val earliest = pending.entries.firstOrNull() ?: return
            pending.remove(earliest.key)
            publish()
            earliest.value
        }
        completeResolved(entry, choice)
    }

    /** 按请求 id 回传选择（通知按钮用，避免新通知复用旧 PendingIntent 误批后来的请求）。 */
    fun resolve(id: String, choice: PlanApprovalChoice) {
        val entry = synchronized(lock) {
            val target = pending.remove(id) ?: return
            publish()
            target
        }
        completeResolved(entry, choice)
    }

    /** 完成一个已被移出内部表的请求：拒绝时回滚模式，然后唤醒挂起方。 */
    private fun completeResolved(entry: Entry, choice: PlanApprovalChoice) {
        // 用户拒绝时，回滚数据库中的模式到 PLAN（SwitchModeTool 已写入 BUILD）
        if (choice == PlanApprovalChoice.REFINE) {
            rollbackModeToPlan(entry.sessionId)
        }

        entry.decision.complete(choice)
    }

    /** 在持锁区内调用：把内部表投影到对外展示的单个请求（取最早进入的）。 */
    private fun publish() {
        _pendingApproval.value = pending.entries.firstOrNull()?.let { (id, entry) ->
            PlanApprovalRequest(reason = entry.reason, sessionId = entry.sessionId, id = id)
        }
    }

    /** 回滚会话模式到 PLAN（SwitchModeTool 已提前写入目标模式，未获批准时还原）。 */
    private fun rollbackModeToPlan(sessionId: String?) {
        val sid = sessionId ?: return
        scope.launch {
            runCatching {
                val entity = chatSessionDao.getById(sid)
                if (entity != null && entity.mode != "PLAN") {
                    chatSessionDao.upsert(entity.copy(mode = "PLAN"))
                }
            }.onFailure { FileLogger.w(TAG, "计划审批回滚到 PLAN 失败: $sid", it) }
        }
    }

    private class Entry(
        val reason: String,
        val sessionId: String?,
        val decision: CompletableDeferred<PlanApprovalChoice>
    )

    private companion object {
        const val TAG = "PlanApprovalManager"
    }
}

data class PlanApprovalRequest(
    val reason: String,
    val sessionId: String? = null,
    val id: String = UUID.randomUUID().toString()
)

enum class PlanApprovalChoice {
    /** 批准计划，切换到 BUILD 模式执行 */
    APPROVE,
    /** 拒绝，留在 PLAN 模式继续反馈 */
    REFINE
}
