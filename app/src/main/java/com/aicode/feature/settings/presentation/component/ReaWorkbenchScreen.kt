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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
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
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Play
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Send
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「逆向分析（REA）」工作台：环境与 MCP 真实连接状态 + 一键安装/接入 +
 * 分析目标（样本）选择 + 一键派发分析任务 + 工具清单，面向逆向学习用户设计。
 * - 环境：容器内 `provision.sh --install rea`（完成标记 `/opt/rea/.installed`，为最终权威判据——
 *   基础容器 provision 菜单与该标记相互独立，不再以 `.provisioned` 短路误判，见 checkEnv）；
 * - MCP：写入全局配置的 REA 预设并立即重连；状态行展示 [McpManager.statuses] 的真实连接态，
 *   失败时给出重连入口（配置存在 ≠ 连接可用）。
 */
@HiltViewModel
class ReaWorkbenchViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val commandEngine: CommandEngine,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val modeHolder: ExecutionModeHolder,
    private val fileAccess: FileAccessProvider,
) : ViewModel() {

    enum class EnvState { Checking, NotProvisioned, NotInstalled, Installed, Unsupported }

    /** 工作区内可作为逆向分析目标的样本文件。 */
    data class ReaSample(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long
    )

    private val _env = MutableStateFlow(EnvState.Checking)
    val env: StateFlow<EnvState> = _env.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _mcpBusy = MutableStateFlow(false)

    /** MCP 配置存在性（写入预设即 true）。 */
    val mcpConfigured: StateFlow<Boolean> = mcpConfigRepository.effectiveEntriesFlow
        .map { entries -> entries.any { it.server.name == REA_SERVER_NAME && it.server.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** MCP 真实连接状态（连接中/已连接/失败/需授权），与配置存在性区分。 */
    val mcpStatus: StateFlow<McpServerStatus?> = mcpManager.statuses
        .map { list -> list.firstOrNull { it.name == REA_SERVER_NAME } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 已连接时可用的 REA 工具名清单（来自 ToolRegistry 实际注册）。 */
    val mcpToolNames: StateFlow<List<String>> = mcpManager.statuses
        .map { list -> list.firstOrNull { it.name == REA_SERVER_NAME }?.toolCount ?: 0 }
        .map { count -> if (count > 0) mcpManager.getServerTools(REA_SERVER_NAME).map { it.name }.sorted() else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _samples = MutableStateFlow<List<ReaSample>>(emptyList())
    val samples: StateFlow<List<ReaSample>> = _samples.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 环境组件摘要（node / jdk / jadx jar 实测版本行）。 */
    private val _components = MutableStateFlow<String?>(null)
    val components: StateFlow<String?> = _components.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _env.value = checkEnv()
            if (_env.value == EnvState.Installed) {
                loadComponents()
                scanSamples()
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 环境判定（修复「装好 REA 仍显示未初始化」的误报）：
     * 旧链先查 `.provisioned` 标记并要求精确版本串——而 REA 安装脚本只写 `/opt/rea/.installed`，
     * 未走过终端初始化菜单/选过手动安装/PROVISION_VERSION 升级三种场景都会被误判为未初始化。
     * 新链以 REA 标记为最终权威：命令能执行（rootfs 就绪）且标记存在即 Installed；
     * rootfs 缺失才引导 NotProvisioned。
     */
    private suspend fun checkEnv(): EnvState {
        // 远程 SSH 模式不支持：REA 场景与 provision.sh 均属本地容器（assets 提取），远程无此链路
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) return EnvState.Unsupported
        // rootfs 都不存在：引导先打开终端完成容器安装
        if (!commandEngine.isContainerInstalled()) return EnvState.NotProvisioned
        val r = commandEngine.runCommandSyncIfReady("test -f /opt/rea/.installed && echo REA_READY")
            ?: return EnvState.NotInstalled
        return if (r.exitCode == 0 && r.output.contains("REA_READY")) EnvState.Installed else EnvState.NotInstalled
    }

    /** 实测环境组件版本（node / jdk / jadx jar），Installed 态展示给学习者对照。 */
    private suspend fun loadComponents() {
        val r = commandEngine.runCommandSyncIfReady(
            "node -v 2>/dev/null | sed 's/^/node /'; " +
                "java -version 2>&1 | head -1 | sed 's/^/java /'; " +
                "ls /opt/rea/*.jar 2>/dev/null | head -1 | xargs -r basename 2>/dev/null | sed 's/^/jadx /'"
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
            if (ok) {
                loadComponents()
                scanSamples()
            }
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

    /** 重连 REA MCP（失败态的修复动作）。 */
    fun reconnectMcp() {
        if (_mcpBusy.value) return
        viewModelScope.launch {
            _mcpBusy.value = true
            runCatching { mcpManager.reloadServer(REA_SERVER_NAME) }
            _mcpBusy.value = false
        }
    }

    /** 扫描工作区内可分析目标：APK/DEX/JAR/SO/JS 等逆向常见格式，排除依赖与版本目录。 */
    fun scanSamples() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            val samples = withContext(Dispatchers.IO) {
                runCatching {
                    // listFilesRecursive 返回相对 root 的路径（不含元数据），元数据需按目录回查 FileEntry
                    val relPaths = fileAccess.listFilesRecursive(WorkspacePathMapper.CONTAINER_ROOT, SCAN_DEPTH)
                        .filter { path ->
                            val n = path.substringAfterLast('/').lowercase()
                            SAMPLE_EXTENSIONS.any { n.endsWith(it) }
                        }
                        .filter { path ->
                            !path.contains("node_modules/") && !path.contains("/.git/")
                        }
                    // 先按父目录回查全部元数据、按修改时间排序、再截断名额：路径序截断会漏掉最新文件
                    relPaths.groupBy { it.substringBeforeLast('/', "") }
                        .flatMap { (parent, paths) ->
                            val entries = fileAccess.listFiles(
                                if (parent.isEmpty()) WorkspacePathMapper.CONTAINER_ROOT
                                else "${WorkspacePathMapper.CONTAINER_ROOT}/$parent"
                            )
                            paths.mapNotNull { rel ->
                                val name = rel.substringAfterLast('/')
                                entries.firstOrNull { it.name == name && !it.isDirectory }?.let { entry ->
                                    ReaSample(
                                        path = "${WorkspacePathMapper.CONTAINER_ROOT}/$rel",
                                        name = name,
                                        sizeBytes = entry.size,
                                        lastModified = entry.lastModified
                                    )
                                }
                            }
                        }
                        .sortedByDescending { it.lastModified }
                        .take(SAMPLE_LIMIT)
                }.getOrDefault(emptyList())
            }
            _samples.value = samples
            _scanning.value = false
        }
    }

    private companion object {
        const val REA_SERVER_NAME = "rea"
        const val SCAN_DEPTH = 4
        const val SAMPLE_LIMIT = 50
        val SAMPLE_EXTENSIONS = listOf(
            ".apk", ".apks", ".xapk", ".aab", ".jar", ".dex", ".so",
            ".bin", ".js", ".zip", ".ipa", ".dll", ".exe"
        )
    }
}

/** 逆向分析工作台页面（侧栏「逆向分析」入口）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaWorkbenchScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onSendToChat: (String) -> Unit = {},
    viewModel: ReaWorkbenchViewModel = hiltViewModel()
) {
    val env by viewModel.env.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()
    val mcpConfigured by viewModel.mcpConfigured.collectAsStateWithLifecycle()
    val mcpStatus by viewModel.mcpStatus.collectAsStateWithLifecycle()
    val mcpToolNames by viewModel.mcpToolNames.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
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
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.rea_rescan),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        text = stringResource(R.string.rea_installing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 每个状态都有明确的下一步动作（修复原 NotProvisioned 无任何控件的死胡同）
                env == ReaWorkbenchViewModel.EnvState.NotProvisioned -> Button(
                    onClick = onOpenTerminal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rea_open_terminal_button))
                }

                env == ReaWorkbenchViewModel.EnvState.NotInstalled -> Button(
                    onClick = { viewModel.installEnvironment() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.rea_install_button))
                }

                env == ReaWorkbenchViewModel.EnvState.Installed -> {
                    // 环境组件实测摘要
                    if (!components.isNullOrBlank()) {
                        Text(
                            text = components!!,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!mcpConfigured) {
                        Button(
                            onClick = { viewModel.setupMcp() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.rea_setup_mcp_button))
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
                                text = stringResource(R.string.rea_ready_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                else -> Unit // Checking / Unsupported：状态卡中已给出说明
            }

            // ── 分析目标（样本）区：选择文件 → 一键派发分析 ──
            if (env == ReaWorkbenchViewModel.EnvState.Installed) {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.rea_samples_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (scanning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = { viewModel.scanSamples() }) {
                                    Text(stringResource(R.string.rea_rescan), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (samples.isEmpty()) {
                            Text(
                                text = stringResource(R.string.rea_samples_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                samples.forEach { sample ->
                                    SampleRow(
                                        sample = sample,
                                        onAnalyze = {
                                            onSendToChat(
                                                context.getString(R.string.rea_analyze_prompt, sample.path)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 工具清单：连接成功后展示实际可用的 REA 工具（学习者知道自己有什么能力）──
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
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ── 学习路径：常见分析任务模板（点击预填进聊天）──
            if (env == ReaWorkbenchViewModel.EnvState.Installed && mcpConfigured) {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text(
                            text = stringResource(R.string.rea_tasks_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        TaskTemplateRow(stringResource(R.string.rea_task_overview)) { onSendToChat(it) }
                        TaskTemplateRow(stringResource(R.string.rea_task_class)) { onSendToChat(it) }
                        TaskTemplateRow(stringResource(R.string.rea_task_string)) { onSendToChat(it) }
                        TaskTemplateRow(stringResource(R.string.rea_task_js)) { onSendToChat(it) }
                    }
                }
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

/** 样本行：文件名/大小/时间 + 「分析」动作（预填派发进聊天）。 */
@Composable
private fun SampleRow(sample: ReaWorkbenchViewModel.ReaSample, onAnalyze: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sample.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSampleMeta(sample),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        TextButton(onClick = onAnalyze) {
            Icon(FeatherIcons.Send, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.rea_analyze_button), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 学习任务模板行：点击把模板指令预填进聊天输入框。 */
@Composable
private fun TaskTemplateRow(template: String, onSend: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSend(template) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FeatherIcons.Play,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = template,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
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
private fun envStatusText(env: ReaWorkbenchViewModel.EnvState): String = when (env) {
    ReaWorkbenchViewModel.EnvState.Checking -> stringResource(R.string.rea_status_checking)
    ReaWorkbenchViewModel.EnvState.Installed -> stringResource(R.string.rea_status_installed)
    ReaWorkbenchViewModel.EnvState.NotInstalled -> stringResource(R.string.rea_status_not_installed)
    ReaWorkbenchViewModel.EnvState.NotProvisioned -> stringResource(R.string.rea_status_not_provisioned)
    ReaWorkbenchViewModel.EnvState.Unsupported -> stringResource(R.string.rea_status_unsupported)
}

private fun formatSampleMeta(sample: ReaWorkbenchViewModel.ReaSample): String {
    val kb = sample.sizeBytes.coerceAtLeast(0) / 1024
    val sizeText = if (kb >= 1024) "%.1f MB".format(Locale.US, kb / 1024f) else "$kb KB"
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(sample.lastModified))
    return "$sizeText · $time"
}
