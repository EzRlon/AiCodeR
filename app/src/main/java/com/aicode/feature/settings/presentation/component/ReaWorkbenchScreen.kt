package com.aicode.feature.settings.presentation.component

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「逆向分析（REA）」工作台：环境与 MCP 状态自检 + 一键安装 + 一键接入 + 用法指引。
 * - 环境：调用容器内 `provision.sh --install rea`（非交互场景安装，完成标记 `/opt/rea/.installed`）；
 * - MCP：写入全局配置的 REA 预设（[RecommendedReaConfig]，含工具白名单）并立即重连。
 */
@HiltViewModel
class ReaWorkbenchViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val commandEngine: CommandEngine,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val modeHolder: ExecutionModeHolder,
) : ViewModel() {

    enum class EnvState { Checking, NotProvisioned, NotInstalled, Installed, Unsupported }

    private val _env = MutableStateFlow(EnvState.Checking)
    val env: StateFlow<EnvState> = _env.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _mcpBusy = MutableStateFlow(false)

    val mcpConfigured: StateFlow<Boolean> = mcpConfigRepository.effectiveEntriesFlow
        .map { entries -> entries.any { it.server.name == REA_SERVER_NAME && it.server.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _env.value = checkEnv() }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun checkEnv(): EnvState {
        // 远程 SSH 模式不支持：REA 场景与 provision.sh 均属本地容器（assets 提取），远程无此链路
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) return EnvState.Unsupported
        // 本地容器未就绪（rootfs 未解压 / 未完成初始化）→ 引导先打开终端
        if (!commandEngine.isContainerInstalled() || !commandEngine.isProvisioned()) return EnvState.NotProvisioned
        val r = commandEngine.runCommandSyncIfReady("test -f /opt/rea/.installed && echo REA_READY")
            ?: return EnvState.NotInstalled
        return if (r.exitCode == 0 && r.output.contains("REA_READY")) EnvState.Installed else EnvState.NotInstalled
    }

    fun installEnvironment() {
        if (_installing.value) return
        viewModelScope.launch {
            _installing.value = true
            _message.value = null
            // 防重：上次进入本页发起的安装在页面销毁后仍会在后台跑——先探测是否已有安装进程
            val runningElsewhere = runCatching {
                commandEngine.runCommandSyncIfReady(
                    // [p] 防自匹配：探针命令行自身含该字符串，pgrep -f 会匹配到外层 shell
                    "pgrep -f '[p]rovision.sh --install rea' >/dev/null 2>&1 && echo RUNNING"
                )?.output?.contains("RUNNING") == true
            }.getOrDefault(false)
            if (runningElsewhere) {
                _installing.value = false
                _message.value = context.getString(R.string.rea_install_running_elsewhere)
                return@launch
            }
            val ok = runCatching {
                commandEngine.runCommandSyncWithExit(
                    command = "sh ~/.aicode/provision.sh --install rea",
                    timeoutMs = 900_000L
                ).exitCode == 0
            }.getOrDefault(false)
            _env.value = checkEnv()
            _message.value = context.getString(if (ok) R.string.rea_install_done else R.string.rea_install_failed)
            _installing.value = false
        }
    }

    fun setupMcp() {
        if (_mcpBusy.value) return
        viewModelScope.launch {
            _mcpBusy.value = true
            val ok = runCatching {
                // 项目级同名会覆盖全局预设（导致「已接入」却不生效）——一并清理
                val project = mcpConfigRepository.getProjectServers()
                if (project.any { it.name == REA_SERVER_NAME }) {
                    mcpConfigRepository.setProjectServers(project.filterNot { it.name == REA_SERVER_NAME })
                }
                val servers = mcpConfigRepository.getGlobalServers().toMutableList()
                servers.removeIf { it.name == REA_SERVER_NAME }
                servers.add(RecommendedReaConfig)
                mcpConfigRepository.setGlobalServers(servers)
                mcpManager.reloadServer(REA_SERVER_NAME)
            }.isSuccess
            _message.value = context.getString(if (ok) R.string.rea_mcp_added else R.string.rea_mcp_add_failed)
            _mcpBusy.value = false
        }
    }

    private companion object {
        const val REA_SERVER_NAME = "rea"
    }
}

/** 逆向分析工作台页面（侧栏「逆向分析」入口）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaWorkbenchScreen(
    onBack: () -> Unit,
    viewModel: ReaWorkbenchViewModel = hiltViewModel()
) {
    val env by viewModel.env.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()
    val mcpConfigured by viewModel.mcpConfigured.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 安装中退出页面不中止安装（进程在后台继续）——拦截返回时明确告知
    BackHandler(enabled = installing) {
        Toast.makeText(context, context.getString(R.string.rea_install_bg_hint), Toast.LENGTH_LONG).show()
        onBack()
    }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        Toast.makeText(context, m, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rea_workbench_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground())
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Spacer(Modifier.height(Spacing.xs))

            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
                    StatusRow(stringResource(R.string.rea_env_label), envStatusText(env))
                    SettingsDivider()
                    StatusRow(
                        stringResource(R.string.rea_mcp_label),
                        stringResource(
                            if (mcpConfigured) R.string.rea_status_connected else R.string.rea_status_not_connected
                        )
                    )
                }
            }

            when {
                installing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.rea_installing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                env == ReaWorkbenchViewModel.EnvState.NotInstalled -> Button(
                    onClick = { viewModel.installEnvironment() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rea_install_button))
                }

                env == ReaWorkbenchViewModel.EnvState.Installed -> {
                    if (!mcpConfigured) {
                        Button(
                            onClick = { viewModel.setupMcp() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.rea_setup_mcp_button))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.rea_ready_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                else -> Unit // Checking / NotProvisioned / Unsupported：状态卡中已给出说明
            }

            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text(
                        text = stringResource(R.string.rea_usage_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.rea_usage_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.rea_legal_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun envStatusText(env: ReaWorkbenchViewModel.EnvState): String = when (env) {
    ReaWorkbenchViewModel.EnvState.Checking -> stringResource(R.string.rea_status_checking)
    ReaWorkbenchViewModel.EnvState.Installed -> stringResource(R.string.rea_status_installed)
    ReaWorkbenchViewModel.EnvState.NotInstalled -> stringResource(R.string.rea_status_not_installed)
    ReaWorkbenchViewModel.EnvState.NotProvisioned -> stringResource(R.string.rea_status_not_provisioned)
    ReaWorkbenchViewModel.EnvState.Unsupported -> stringResource(R.string.rea_status_unsupported)
}
