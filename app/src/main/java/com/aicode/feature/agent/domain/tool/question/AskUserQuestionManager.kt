package com.aicode.feature.agent.domain.tool.question

import com.aicode.feature.agent.domain.permission.WatchModeSettingsRepository
import com.aicode.feature.schedule.domain.ScheduledAiRunState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理 ask_user_question 工具的 park-and-resume 流程。
 *
 * 镜像 [com.aicode.feature.agent.domain.tool.ToolPermissionManager] 的设计：
 * 工具侧调用 [awaitAnswer] 挂起；UI 侧观察 [pendingQuestion] 渲染面板；
 * 用户选择后调用 [resolve] 唤醒工具侧。
 *
 * 同一时刻只允许一个待决问题（mutex 串行化），与权限弹窗互不干扰。
 */
@Singleton
class AskUserQuestionManager @Inject constructor(
    private val watchModeSettings: WatchModeSettingsRepository,
    private val scheduledAiRunState: ScheduledAiRunState
) {

    private val requestMutex = Mutex()

    private val _pendingQuestion = MutableStateFlow<PendingUserQuestion?>(null)

    /** UI 观察此 StateFlow 来决定是否显示问题面板。 */
    val pendingQuestion: StateFlow<PendingUserQuestion?> = _pendingQuestion.asStateFlow()

    private var currentId: String? = null
    private var currentDecision: CompletableDeferred<UserQuestionAnswer>? = null

    /**
     * 工具侧调用：发起一个问题请求，挂起等待用户回答。
     * 同一时刻只允许一个请求（由 [requestMutex] 保证串行）。
     */
    suspend fun awaitAnswer(question: PendingUserQuestion): UserQuestionAnswer = requestMutex.withLock {
        if (watchModeSettings.isArmed() || scheduledAiRunState.hasActive()) {
            // 守夜或定时 AI 任务运行中：用户不在场，不挂起——自动答复，请 AI 自主决策或跳过。
            return@withLock UserQuestionAnswer(
                answers = question.questions.map { q ->
                    SingleAnswer(
                        question = q.question,
                        selected = emptyList(),
                        customText = "（守夜模式：用户不在场，请基于现有信息自主做出合理决策，或跳过此问题继续推进）"
                    )
                }
            )
        }
        val decision = CompletableDeferred<UserQuestionAnswer>()
        currentId = question.id
        currentDecision = decision
        _pendingQuestion.value = question

        try {
            decision.await()
        } finally {
            if (currentId == question.id) {
                currentId = null
                currentDecision = null
                _pendingQuestion.value = null
            }
        }
    }

    /**
     * UI 侧调用：用户完成选择后，将答案回传，唤醒挂起的 [awaitAnswer]。
     */
    @Synchronized
    fun resolve(id: String, answer: UserQuestionAnswer) {
        if (currentId != id) return
        _pendingQuestion.value = null
        currentDecision?.complete(answer)
    }
}
