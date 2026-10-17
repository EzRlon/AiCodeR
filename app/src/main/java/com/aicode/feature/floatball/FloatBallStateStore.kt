package com.aicode.feature.floatball

import com.aicode.feature.agent.presentation.AgentUIState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮球共享状态：单例仓库，AIAgentViewModel 写入任务状态，悬浮窗 UI 读取。
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
        val runningCount: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 根据全部会话的 agent 状态刷新（busy/failed 三态推导）。 */
    fun syncAgentStates(states: Map<String, AgentUIState>) {
        val busy = states.values.any { it is AgentUIState.Streaming || it is AgentUIState.Loading }
        val failed = states.values.any { it is AgentUIState.Error }
        _state.update { it.copy(busy = busy, failed = failed, runningCount = states.count { (_, s) -> s is AgentUIState.Streaming }) }
    }

    /** 清空（服务停止时重置展示态）。 */
    fun reset() {
        _state.value = State()
    }
}
