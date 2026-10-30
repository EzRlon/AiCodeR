package com.aicode.feature.settings.presentation.component

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.RefreshCw
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
 * 「模型顾问团」工作台：环境与 MCP 真实连接状态 + 一键安装/接入 + 六模型登录引导。
 * - 环境：容器内 `provision.sh --install multillm`（完成标记 `~/.aicode/multi-llm-venv/.installed`，权威判据）；
 * - MCP：写入全局配置的 multi-llm 预设并立即重连；状态行展示真实连接态；
 * - 登录：首次使用需在 VNC 悬浮窗的浏览器里登录各模型网站（profile 持久保存）。
 */
@HiltViewModel
class MultiLlmWorkbenchViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val commandEngine: CommandEngine,
    private val mcpManager: McpManager,
    private val mcpConfigRepository: McpConfigRepository,
    private val modeHolder: ExecutionModeHolder
) : ViewModel() {

    sealed class EnvState {
        data object Checking : EnvState()
        data object Installed : EnvState()
        data object NotInstalled : EnvState()
        data object NotProvisioned : EnvState()
        data object Unsupported : EnvState()
    }

    private val _env: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Checking)
    val env: StateFlow<EnvState> = _env.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _mcpBusy = MutableStateFlow(false)
    val mcpBusy: StateFlow<Boolean> = _mcpBusy.asStateFlow()

    private val _components = MutableStateFlow<String?>(null)
    val components: StateFlow<String?> = _components.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val mcpConfigured: StateFlow<Boolean> = mcpConfigRepository.globalServersFlow
        .map { servers -> servers.any { it.name == MULTI_LLM_SERVER_NAME } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val mcpStatus: StateFlow<McpServerStatus?> = mcpManager.statuses
        .map { list -> list.firstOrNull { it.name == MULTI_LLM_SERVER_NAME } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mcpToolNames: StateFlow<List<String>> = mcpManager.statuses
        .map { list -> list.firstOrNull { it.name == MULTI_LLM_SERVER_NAME }?.toolCount ?: 0 }
        .map { count -> if (count > 0) mcpManager.getServerTools(MULTI_LLM_SERVER_NAME).map { it.name }.sorted() else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _env.value = checkEnv()
            if (_env.value == EnvState.Installed) {
                loadComponents()
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 环境判定：以 multi-llm-venv 安装标记为最终权威（与脚本写入一致）。 */
    private suspend fun checkEnv(): EnvState {
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) return EnvState.Unsupported
        if (!commandEngine.isContainerInstalled()) return EnvState.NotProvisioned
        val r = commandEngine.runCommandSyncIfReady(
            "test -f ~/.aicode/multi-llm-venv/.installed && echo MULTI_LLM_READY"
        ) ?: return EnvState.NotInstalled
        return if (r.exitCode == 0 && r.output.contains("MULTI_LLM_READY")) {
            EnvState.Installed
        } else {
            EnvState.NotInstalled
        }
    }

    /** 实测环境组件版本（python / playwright），Installed 态展示对照。 */
    private suspend fun loadComponents() {
        val r = commandEngine.runCommandSyncIfReady(
            "python3 --version 2>&1 | sed 's/^/python /'; " +
                "test -x /usr/bin/chromium && echo 'chromium /usr/bin/chromium'; " +
                "test -d ~/.cache/ms-playwright && echo 'playwright-chromium ~/.cache/ms-playwright'"
        ) ?: run {
            _components.value = null
            return
        }
        _components.value = r.output.trim().takeIf { it.isNotBlank() }
    }

    fun installEnvironment() {
        if (_installing.value) return
        viewModelScope.launch {
            _installing.value = true
            _message.value = null
            val runningElsewhere = runCatching {
                commandEngine.runCommandSyncIfReady(
                    "pgrep -f '[p]rovision.sh --install multillm' >/dev/null 2>&1 && echo RUNNING"
                )?.output?.contains("RUNNING") == true
            }.getOrDefault(false)
            if (runningElsewhere) {
                _installing.value = false
                _message.value = context.getString(R.string.rea_install_running_elsewhere)
                return@launch
            }
            val ok = runCatching {
                commandEngine.runCommandSyncWithExit(
                    command = "sh ~/.aicode/provision.sh --install multillm",
                    timeoutMs = 900_000L
                ).exitCode == 0
            }.getOrDefault(false)
            _env.value = checkEnv()
            _message.value = context.getString(if (ok) R.string.multillm_install_done else R.string.multillm_install_failed)
            _installing.value = false
            if (ok) loadComponents()
        }
    }

    fun setupMcp() {
        if (_mcpBusy.value) return
        viewModelScope.launch {
            _mcpBusy.value = true
            val ok = runCatching {
                val project = mcpConfigRepository.getProjectServers()
                if (project.any { it.name == MULTI_LLM_SERVER_NAME }) {
                    mcpConfigRepository.setProjectServers(project.filterNot { it.name == MULTI_LLM_SERVER_NAME })
                }
                val servers = mcpConfigRepository.getGlobalServers().toMutableList()
                servers.removeIf { it.name == MULTI_LLM_SERVER_NAME }
                servers.add(MULTI_LLM_PRESET)
                mcpConfigRepository.setGlobalServers(servers)
                mcpManager.reloadServer(MULTI_LLM_SERVER_NAME)
            }.isSuccess
            _message.value = context.getString(if (ok) R.string.multillm_mcp_added else R.string.multillm_mcp_add_failed)
            _mcpBusy.value = false
        }
    }

    fun reconnectMcp() {
        if (_mcpBusy.value) return
        viewModelScope.launch {
            _mcpBusy.value = true
            runCatching { mcpManager.reloadServer(MULTI_LLM_SERVER_NAME) }
            _mcpBusy.value = false
        }
    }

    private companion object {
        const val MULTI_LLM_SERVER_NAME = "multi-llm"

        /** 与 SettingsViewModel.MULTI_LLM_PRESET 同构（private companion 不可跨文件引用，此处保持一份拷贝）。 */
        val MULTI_LLM_PRESET = McpServerConfig(
            name = MULTI_LLM_SERVER_NAME,
            command = "python3",
            args = listOf("/root/.aicode/multi-llm/server.py"),
            env = mapOf("PYTHONPATH" to "/root/.aicode/multi-llm-venv/site"),
            enabled = true
        )
    }
}

/** 模型顾问团工作台页面（侧栏「模型顾问团」入口）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiLlmWorkbenchScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenVnc: () -> Unit = {},
    viewModel: MultiLlmWorkbenchViewModel = hiltViewModel()
) {
    val env by viewModel.env.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()
    val mcpBusy by viewModel.mcpBusy.collectAsStateWithLifecycle()
    val mcpConfigured by viewModel.mcpConfigured.collectAsStateWithLifecycle()
    val mcpStatus by viewModel.mcpStatus.collectAsStateWithLifecycle()
    val mcpToolNames by viewModel.mcpToolNames.collectAsStateWithLifecycle()
    val components by viewModel.components.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toolsExpanded by remember { mutableStateOf(false) }

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
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.multillm_workbench_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.rea_rescan),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
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

            // 常驻提示横幅：登录态说明，任何状态下都可见
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.multillm_login_banner),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }

            SectionHeader(stringResource(R.string.rea_section_prepare))

            // ── 状态卡：环境 + MCP 真实连接态（配置存在 ≠ 连接可用）──
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
                        mcpStateText(mcpConfigured, mcpStatus)
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
                        text = stringResource(R.string.multillm_installing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                env == MultiLlmWorkbenchViewModel.EnvState.NotProvisioned -> Button(
                    onClick = onOpenTerminal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rea_open_terminal_button))
                }

                env == MultiLlmWorkbenchViewModel.EnvState.NotInstalled -> Button(
                    onClick = { viewModel.installEnvironment() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.multillm_install_button))
                }

                env == MultiLlmWorkbenchViewModel.EnvState.Installed -> {
                    if (!components.isNullOrBlank()) {
                        Text(
                            text = components!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!mcpConfigured) {
                        Button(
                            onClick = { viewModel.setupMcp() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.multillm_setup_mcp_button))
                        }
                    } else {
                        val st = mcpStatus
                        when {
                            st != null && st.state == McpServerStatus.State.FAILED -> {
                                Text(
                                    text = stringResource(R.string.rea_mcp_failed_detail, st.error ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                OutlinedButton(
                                    onClick = { viewModel.reconnectMcp() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.rea_mcp_reconnect))
                                }
                            }
                            else -> Text(
                                text = stringResource(R.string.multillm_ready_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                else -> Unit // Checking / Unsupported：状态卡中已给出说明
            }

            // ── 登录引导区：VNC 登录六模型网站（连接成功后才有意义）──
            if (env == MultiLlmWorkbenchViewModel.EnvState.Installed && mcpConfigured) {
                SectionHeader(stringResource(R.string.multillm_section_login))
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text(
                            text = stringResource(R.string.multillm_login_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = onOpenVnc,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.multillm_open_vnc_button))
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.multillm_login_models),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 引导区：使用说明（折叠沉底）──
            SectionHeader(stringResource(R.string.rea_section_guide))

            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text(
                        text = stringResource(R.string.multillm_usage_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.multillm_usage_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 工具清单：连接成功后展示实际可用的顾问团工具（折叠沉底）
            if (mcpToolNames.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toolsExpanded = !toolsExpanded }
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.rea_tools_title, mcpToolNames.size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(
                                    if (toolsExpanded) R.string.common_collapse_action else R.string.common_expand
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (toolsExpanded) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = mcpToolNames.joinToString("\n") { "· $it" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** 分区标题（准备 / 登录 / 引导）。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.xs)
    )
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = Spacing.md)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** MCP 状态文案：区分「已配置未连接/连接中/已连接/失败(原因)/被禁用/需授权」。 */
@Composable
private fun mcpStateText(configured: Boolean, status: McpServerStatus?): String = when {
    !configured -> stringResource(R.string.rea_status_not_connected)
    status == null -> stringResource(R.string.rea_mcp_state_configured)
    status.state == McpServerStatus.State.CONNECTED ->
        stringResource(R.string.rea_mcp_state_connected, status.toolCount)
    status.state == McpServerStatus.State.CONNECTING -> stringResource(R.string.rea_mcp_state_connecting)
    status.state == McpServerStatus.State.FAILED -> stringResource(R.string.rea_mcp_state_failed)
    status.state == McpServerStatus.State.NEEDS_AUTH -> stringResource(R.string.rea_mcp_state_needs_auth)
    else -> stringResource(R.string.rea_mcp_state_disabled)
}

@Composable
private fun envStatusText(env: MultiLlmWorkbenchViewModel.EnvState): String = when (env) {
    MultiLlmWorkbenchViewModel.EnvState.Checking -> stringResource(R.string.rea_status_checking)
    MultiLlmWorkbenchViewModel.EnvState.Installed -> stringResource(R.string.rea_status_installed)
    MultiLlmWorkbenchViewModel.EnvState.NotInstalled -> stringResource(R.string.rea_status_not_installed)
    MultiLlmWorkbenchViewModel.EnvState.NotProvisioned -> stringResource(R.string.rea_status_not_provisioned)
    MultiLlmWorkbenchViewModel.EnvState.Unsupported -> stringResource(R.string.rea_status_unsupported)
}
