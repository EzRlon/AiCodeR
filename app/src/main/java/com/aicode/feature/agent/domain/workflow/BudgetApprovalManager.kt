package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.permission.WatchModeSettingsRepository
import com.aicode.feature.schedule.domain.ScheduledAiRunState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理「单次任务 token 预算超限」的 park-and-resume 流程。
 *
 * 镜像 [com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager] 的设计：
 * workflow 侧累计本轮用量、触达预算时调用 [awaitApproval] 挂起；UI 侧观察 [pendingApproval]
 * 渲染预算面板；用户选择后调用 [resolve] 唤醒 workflow。
 */
@Singleton
class BudgetApprovalManager @Inject constructor(
    private val watchModeSettings: WatchModeSettingsRepository,
    private val scheduledAiRunState: ScheduledAiRunState
) {

    private val requestMutex = Mutex()

    private val _pendingApproval = MutableStateFlow<BudgetApprovalRequest?>(null)

    /** UI 观察此 StateFlow 来决定是否显示预算面板。 */
    val pendingApproval: StateFlow<BudgetApprovalRequest?> = _pendingApproval.asStateFlow()

    private var currentId: String? = null
    private var currentDecision: CompletableDeferred<BudgetApprovalChoice>? = null

    /**
     * workflow 侧调用：单次任务的累计用量已触达预算，挂起等待用户决定是否继续。
     * 同一时刻只允许一个请求（由 [requestMutex] 保证串行）。
     */
    suspend fun awaitApproval(usedTokens: Int, budgetTokens: Int): BudgetApprovalChoice =
        if (watchModeSettings.isArmed() || scheduledAiRunState.hasActive()) {
        // 守夜或定时 AI 任务运行中：预算耗尽不暂停——按停止处理（工作流的预算停止路径收尾并汇报），不无限等人。
        BudgetApprovalChoice.STOP
    } else requestMutex.withLock {
        val request = BudgetApprovalRequest(usedTokens = usedTokens, budgetTokens = budgetTokens)
        val decision = CompletableDeferred<BudgetApprovalChoice>()
        currentId = request.id
        currentDecision = decision
        _pendingApproval.value = request

        try {
            decision.await()
        } finally {
            if (currentId == request.id) {
                currentId = null
                currentDecision = null
                _pendingApproval.value = null
            }
        }
    }

    /** UI 侧调用：用户完成选择后，将答案回传，唤醒挂起的 [awaitApproval]。 */
    @Synchronized
    fun resolve(id: String, choice: BudgetApprovalChoice) {
        if (currentId != id) return
        _pendingApproval.value = null
        currentDecision?.complete(choice)
    }
}

/** 待用户决策的预算请求。[usedTokens] 为本轮任务的累计用量，[budgetTokens] 为配置的预算值。 */
data class BudgetApprovalRequest(
    val usedTokens: Int,
    val budgetTokens: Int,
    val id: String = UUID.randomUUID().toString()
)

enum class BudgetApprovalChoice {
    /** 继续执行：本轮解除预算限制，不再询问。 */
    CONTINUE,

    /** 停止任务：本轮就此结束。 */
    STOP
}
