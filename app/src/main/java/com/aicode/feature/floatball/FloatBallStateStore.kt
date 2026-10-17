package com.aicode.feature.floatball

import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.presentation.AgentUIState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮球共享状态：单例仓库，AIAgentViewModel 写入任务状态与关键信息，悬浮窗 UI 读取。
 * 同进程内存态，服务与界面共享同一份。
 */
@Singleton
class FloatBallStateStore @Inject constructor() {

    data class State(
        /** 有会话正在执行（含排队中的等待）。 */
        val busy: Boolean = false,
        /** 最近一轮失败。 */
        val failed: Boolean = false,
        /** 当前活跃任务数。 */
        val runningCount: Int = 0,
        /** 正在执行的工具名（原文；UI 侧映射为「正在读取文件」等文案）。 */
        val activeToolName: String? = null,
        /** 正在生成的回复尾部预览（≤160 字符；仅进行中）。 */
        val replyPreview: String? = null,
        /** 待办：总数 / 已完成数 / 进行中的标题。 */
        val todoTotal: Int = 0,
        val todoDone: Int = 0,
        val todoActiveTitle: String? = null,
        /** 当前会话排队中的请求数。 */
        val queuedCount: Int = 0,
        /** 待用户处理的阻塞：permission（工具授权）/ question（提问）/ plan（计划审批）。 */
        val pendingKind: String? = null,
        /** 阻塞的展示标题（工具名 / 问题摘要）。 */
        val pendingTitle: String? = null,
        /** 是否有可停止的运行中任务（决定「停止」按钮显隐）。 */
        val canStop: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 根据全部会话的 agent 状态刷新（busy/failed 三态推导）。 */
    fun syncAgentStates(states: Map<String, AgentUIState>) {
        val busy = states.values.any { it is AgentUIState.Streaming || it is AgentUIState.Loading }
        val failed = states.values.any { it is AgentUIState.Error }
        _state.update {
            it.copy(busy = busy, failed = failed, runningCount = states.count { (_, s) -> s is AgentUIState.Streaming })
        }
    }

    /**
     * 刷新关键信息（当前会话视角）：正在做的事、回复预览、待办进度、排队与待审批。
     * 由 AIAgentViewModel 的合并流驱动，任一来源变化即整包更新（轻量 data class，无性能顾虑）。
     */
    fun syncDetails(
        activeToolName: String?,
        replyPreview: String?,
        todos: List<TodoItem>,
        queuedCount: Int,
        pendingKind: String?,
        pendingTitle: String?,
        canStop: Boolean
    ) {
        _state.update {
            it.copy(
                activeToolName = activeToolName,
                replyPreview = replyPreview,
                todoTotal = todos.size,
                todoDone = todos.count { t -> t.status == com.aicode.feature.agent.domain.model.TodoStatus.COMPLETED },
                todoActiveTitle = todos.firstOrNull { t -> t.status == com.aicode.feature.agent.domain.model.TodoStatus.IN_PROGRESS }?.subject,
                queuedCount = queuedCount,
                pendingKind = pendingKind,
                pendingTitle = pendingTitle,
                canStop = canStop
            )
        }
    }

    /** 清空（服务停止时重置展示态）。 */
    fun reset() {
        _state.value = State()
    }
}
