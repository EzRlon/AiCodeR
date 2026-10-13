package com.aicode.feature.workspace.presentation.remote

import androidx.lifecycle.ViewModel
import com.aicode.feature.workspace.domain.remote.http.RemoteHttpServerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 远程管理页 ViewModel：仅暴露单例 HTTP 服务管理器（页面状态全在 Manager 的 StateFlow）。 */
@HiltViewModel
class RemoteManagerViewModel @Inject constructor(
    val httpServerManager: RemoteHttpServerManager
) : ViewModel()
