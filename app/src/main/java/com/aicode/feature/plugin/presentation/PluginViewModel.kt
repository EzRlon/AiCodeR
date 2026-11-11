package com.aicode.feature.plugin.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.plugin.domain.DefaultPluginHostConfig
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LegacyPluginCleanup
import com.aicode.feature.plugin.domain.PluginInstallError
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginInstaller
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
 * 插件根目录固定为 `~/.aicode/plugins`（本地模式落 App 私有目录，远程 SSH 模式落服务器 home），
 * 与技能 / 子代理等既有目录同级——用户手工把插件目录拷进去就能被扫到，不需要额外的「安装」步骤。
 * 读写一律经 [FileAccessProvider]，跟随当前执行环境。
 *
 * 启用不是只改个布尔：必须先把能力装进宿主既有目录（见 [PluginResourceReleaser]），
 * 禁用则先把装进去的东西摘干净。任何一步失败都保持「未启用」，不留半装状态。
 */
@HiltViewModel
class PluginViewModel @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val mcpConfig: McpConfigRepository,
    commandEngine: CommandEngine
) : ViewModel() {

    private val pluginsRoot = "~/.aicode/" + DIR_NAME
    private val stateStore = PluginStateStore(fileAccess, pluginsRoot)
    private val registry = PluginRegistry(fileAccess, pluginsRoot, stateStore)
    private val releaser = PluginResourceReleaser(
        fileAccess = fileAccess,
        hostConfig = DefaultPluginHostConfig(mcpConfig, fileAccess),
        commandEngine = commandEngine,
        stateStore = stateStore
    )
    private val installer = PluginInstaller(fileAccess, pluginsRoot, stateStore)

    private val _state = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch { scanNow() }
    }

    /** 重新扫描并写回状态。安装/卸载/启停之后都要走一次。 */
    private suspend fun scanNow() {
        // 扫描前先清一次旧版内置插件残留：本地/远程各自跟随当前 FileAccess，
        // 用户在远程模式下打开插件页也能把服务器上的旧目录与 MCP 条目清干净（幂等）。
        withContext(Dispatchers.IO) {
            runCatching { LegacyPluginCleanup.run(fileAccess, mcpConfig) }
                .onFailure { FileLogger.w(TAG, "清理旧内置插件残留失败", it) }
        }
        val result = withContext(Dispatchers.IO) { registry.scan() }
        _state.update {
            it.copy(plugins = result.plugins, problems = result.problems, loading = false)
        }
    }

    /**
     * 从插件包（zip 字节）安装。
     *
     * 安装**不等于启用**：装完默认关闭，用户仍需去详情页读免责说明并打开开关——
     * 安装是文件落盘，授权是能力注入，两件事分开才能让「我不想给它权限」保持可用。
     */
    fun installPackage(bytes: ByteArray, displayName: String? = null) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = INSTALL_BUSY_ID) }
        viewModelScope.launch {
            val result = installer.installFromZip(bytes)
            val message = when (result) {
                is PluginInstallResult.Success -> {
                    scanNow()
                    _state.update {
                        it.copy(
                            busyId = null,
                            messageRes = R.string.plugin_install_success,
                            messageArgs = listOf(
                                displayName?.takeIf { n -> n.isNotBlank() } ?: result.manifest.name
                            )
                        )
                    }
                    return@launch
                }
                is PluginInstallResult.Failed -> when (result.error) {
                    PluginInstallError.BAD_PACKAGE -> R.string.plugin_install_bad_package
                    PluginInstallError.TOO_LARGE -> R.string.plugin_install_too_large
                    PluginInstallError.MANIFEST_MISSING -> R.string.plugin_install_manifest_missing
                    PluginInstallError.MANIFEST_INVALID -> R.string.plugin_install_manifest_invalid
                    PluginInstallError.ALREADY_INSTALLED -> R.string.plugin_install_already_installed
                    PluginInstallError.WRITE_FAILED -> R.string.plugin_install_write_failed
                }
            }
            _state.update {
                it.copy(
                    busyId = null,
                    messageRes = message,
                    messageArgs = listOfNotNull((result as? PluginInstallResult.Failed)?.detail)
                )
            }
        }
    }

    /** 卸载：摘能力 → 删目录 → 清状态。装与卸都不碰用户自己的同名资源（见 PluginResourceReleaser 的归属记账）。 */
    fun uninstall(id: String) {
        if (_state.value.busyId != null) return
        val plugin = _state.value.plugins.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(busyId = id) }
        viewModelScope.launch {
            installer.uninstall(id) { detachId -> releaser.detach(detachId) }
            scanNow()
            _state.update {
                it.copy(
                    busyId = null,
                    messageRes = R.string.plugin_uninstall_done,
                    messageArgs = listOf(plugin.manifest.name)
                )
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
            // 装配会列举并读取插件目录：远程 SSH 模式下每次列举都是同步 SFTP 往返，必须离开主线程。
            val failure = withContext(Dispatchers.IO) {
                runCatching {
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

    companion object {
        private const val TAG = "PluginViewModel"
        private const val DIR_NAME = "plugins"

        /** 「正在安装」占住 busyId 的哨兵值：没有具体插件 id 可标，但要挡住重复点击。UI 用它判断安装行是否在转圈。 */
        const val INSTALL_BUSY_ID = "__install__"
    }
}
