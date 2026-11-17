package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.plugin.domain.PluginRegistry
import com.aicode.feature.plugin.domain.PluginSlot
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工具入口冲突裁决。
 *
 * 背景（2026-11-16 用户反馈）：顾问团已解耦为原生插件，VNC 远程桌面仍是内置能力，
 * 两者都可能占用聊天工具栏。若不裁决，就会出现「同一个按钮入口被两方争用」。
 *
 * 规则（宿主说了算，插件只能声明）：
 * - 插件清单 `slots` 里声明 `slot = "chat.toolbar"` 且 `anchor = "vnc"`，即表示**接管**
 *   远程桌面入口（可自带更合适的实现）；
 * - 任一已启用插件这样声明时，内置 VNC 按钮自动隐藏，避免重复入口；
 * - 插件被禁用/卸载后自动恢复内置入口。
 *
 * 只读取已启用插件的清单，不执行插件代码；扫描在 IO 线程，失败按「未接管」处理。
 */
@HiltViewModel
class ToolEntryViewModel @Inject constructor(
    fileAccess: FileAccessProvider
) : ViewModel() {

    // PluginRegistry 没有 Hilt 绑定（插件页在 VM 内自建），这里按同一约定构造。
    private val pluginsRoot = "~/.aicode/plugins"
    private val pluginRegistry = PluginRegistry(
        fileAccess = fileAccess,
        pluginsRoot = pluginsRoot,
        stateStore = PluginStateStore(fileAccess, pluginsRoot)
    )

    private val _desktopEntryClaimed = MutableStateFlow(false)
    val desktopEntryClaimed: StateFlow<Boolean> = _desktopEntryClaimed.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val claimed = withContext(Dispatchers.IO) {
                runCatching {
                    pluginRegistry.enabledPlugins().any { plugin ->
                        plugin.manifest.slots.any { spec ->
                            spec.slot == PluginSlot.CHAT_TOOLBAR.id && spec.anchor == ANCHOR_VNC
                        }
                    }
                }.getOrDefault(false)
            }
            _desktopEntryClaimed.value = claimed
        }
    }

    companion object {
        /** 内置远程桌面入口的锚点名；插件以它声明接管。 */
        const val ANCHOR_VNC = "vnc"
    }
}
