package com.aicode.feature.agent.presentation.overlay

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级浮层开关（目前只有「随记」）。
 *
 * 为什么放在 agent 而不是 core：顶栏按钮在 `feature/agent/presentation`，浮窗宿主在
 * `MainActivity`；按 feature 边界基线，同一 feature 内引用不算跨 feature 依赖，
 * 而 Activity 引用任意 feature 都合法。若放进 core 会引入 Hilt VM，与 core 现有分层不一致。
 *
 * 顶栏按钮与浮窗渲染必须共用**Activity 级**实例（与 VNC 浮窗同一纪律），
 * 否则按钮只改页面级 VM，浮窗永远不出现。
 */
@HiltViewModel
class AppOverlayViewModel @Inject constructor() : ViewModel() {

    private val _notebookOpen = MutableStateFlow(false)
    val notebookOpen: StateFlow<Boolean> = _notebookOpen.asStateFlow()

    fun toggleNotebook() {
        _notebookOpen.value = !_notebookOpen.value
    }

    fun closeNotebook() {
        _notebookOpen.value = false
    }
}
