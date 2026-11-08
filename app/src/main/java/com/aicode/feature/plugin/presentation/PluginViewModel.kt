package com.aicode.feature.plugin.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.plugin.domain.DefaultPluginHostConfig
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginProblem
import com.aicode.feature.plugin.domain.PluginRegistry
import com.aicode.feature.plugin.domain.PluginResourceReleaser
import com.aicode.feature.plugin.domain.ReleaseConflict
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class PluginUiState(
    val plugins: List<InstalledPlugin> = emptyList(),
    /** 扫描失败但目录存在的插件：用户得知道哪个目录为什么没生效。 */
    val problems: List<PluginProblem> = emptyList(),
    val loading: Boolean = true,
    /** 正在启用/禁用的插件 id，用于禁用该行开关。 */
    val busyId: String? = null,
    /** 一次性提示（启用被拒、部分内容没装成），显示后清空。 */
    @param:StringRes val messageRes: Int? = null,
    val messageArgs: List<String> = emptyList(),
    /** 最近一次装配被跳过的项，详情页展开给用户看。 */
    val conflicts: List<ReleaseConflict> = emptyList()
)

/**
 * 原生插件的列表、启用/禁用与能力装配。
 *
 * 插件根目录固定为 `~/.aicode/plugins`（App 私有 `filesDir/aicode/plugins`），
 * 与技能 / 子代理等既有目录同级——用户手工把插件目录拷进去就能被扫到，不需要额外的「安装」步骤。
 *
 * 启用不是只改个布尔：必须先把能力装进宿主既有目录（见 [PluginResourceReleaser]），
 * 禁用则先把装进去的东西摘干净。任何一步失败都保持「未启用」，不留半装状态。
 */
@HiltViewModel
class PluginViewModel @Inject constructor(
    containerInstaller: ContainerInstaller,
    fileAccess: FileAccessProvider,
    mcpConfig: McpConfigRepository,
    commandEngine: CommandEngine
) : ViewModel() {

    private val pluginsRoot = File(containerInstaller.aicodeDir, DIR_NAME)
    private val stateStore = PluginStateStore(pluginsRoot)
    private val registry = PluginRegistry(pluginsRoot, stateStore)
    private val releaser = PluginResourceReleaser(
        fileAccess = fileAccess,
        hostConfig = DefaultPluginHostConfig(mcpConfig, fileAccess),
        commandEngine = commandEngine,
        stateStore = stateStore
    )

    private val _state = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { registry.scan() }
            _state.update {
                it.copy(plugins = result.plugins, problems = result.problems, loading = false)
            }
        }
    }

    /**
     * 启用 = 装配 + 置位；禁用 = 摘除 + 复位。
     *
     * 装配有跳过项不算失败（插件仍可用，只是少装了那几项），但要把跳过数报给用户——
     * 静默少装比报错更难排查。装配抛异常则整体算失败，不置位。
     */
    fun setEnabled(id: String, enabled: Boolean) {
        if (_state.value.busyId != null) return
        val plugin = _state.value.plugins.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(busyId = id) }

        viewModelScope.launch {
            val failure = runCatching {
                if (enabled) {
                    val outcome = releaser.attach(plugin)
                    if (!registry.setEnabled(id, true)) {
                        // 免责校验没过（理论上前面已拦），把刚装的摘掉，别留半装状态
                        releaser.detach(id)
                        return@runCatching R.string.plugin_enable_blocked_no_consent
                    }
                    if (outcome.conflicts.isEmpty()) {
                        null
                    } else {
                        _state.update { it.copy(conflicts = outcome.conflicts) }
                        R.string.plugin_enabled_with_skips
                    }
                } else {
                    releaser.detach(id)
                    registry.setEnabled(id, false)
                    null
                }
            }.getOrElse { e ->
                FileLogger.e(TAG, "插件状态切换失败: $id", e)
                R.string.plugin_toggle_failed
            }

            _state.update { s ->
                s.copy(
                    busyId = null,
                    messageRes = failure,
                    messageArgs = if (failure == R.string.plugin_enabled_with_skips) {
                        listOf(s.conflicts.size.toString())
                    } else {
                        emptyList()
                    }
                )
            }
            refresh()
        }
    }

    /** 记录用户接受免责，顺带启用——用户点「同意」的语义就是「同意并使用」。 */
    fun acceptConsent(id: String, version: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                registry.recordConsent(id, version, System.currentTimeMillis())
            }
            setEnabled(id, true)
        }
    }

    fun consumeMessage() = _state.update { it.copy(messageRes = null, messageArgs = emptyList()) }

    fun consumeConflicts() = _state.update { it.copy(conflicts = emptyList()) }

    /** 插件目录的绝对路径，详情页「查看插件目录」用。 */
    fun pluginDir(id: String): File = registry.pluginDir(id)

    private companion object {
        const val TAG = "PluginViewModel"
        const val DIR_NAME = "plugins"
    }
}
