package com.aicode.feature.schedule.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 正在运行的定时 AI 任务登记：[ScheduledAiTaskWorker] 执行期间持有一条
 * [ActiveScheduledAiRun]，供权限侧做「无人值守语义」判定（见 unattended-security-plan §L2）：
 *
 * - 权限引擎把「定时任务运行中」与守夜开关同等对待（超档挂起后的兜底超时、计划审查自动通过等）；
 * - 预授权类别内的操作由引擎直接放行 + 记录，超出类别走通知授权通道。
 *
 * 单任务串行假设：同一时刻至多登记一条（并发触发时后来者跳过，见 Worker 防重入）。
 */
@Singleton
class ScheduledAiRunState @Inject constructor() {

    /** 一次定时 AI 任务的运行上下文。 */
    data class ActiveScheduledAiRun(
        val taskId: String,
        val taskName: String,
        val sessionId: String,
        /** 预授权类别（引擎按工具能力映射后判定覆盖）。 */
        val authorizedCategories: Set<ScheduledAuthCategory>,
        val startedAt: Long
    )

    private val _active = MutableStateFlow<ActiveScheduledAiRun?>(null)
    val active: StateFlow<ActiveScheduledAiRun?> = _active.asStateFlow()

    /** 当前是否有定时 AI 任务运行中（权限热路径用，非阻塞读取）。 */
    fun hasActive(): Boolean = _active.value != null

    /** 当前运行上下文（无则 null）。 */
    fun current(): ActiveScheduledAiRun? = _active.value

    fun begin(run: ActiveScheduledAiRun) {
        _active.value = run
    }

    /** 结束登记；仅当登记的仍是 [taskId] 对应运行时才清除（防误清新任务）。 */
    fun end(taskId: String) {
        if (_active.value?.taskId == taskId) _active.value = null
    }
}
