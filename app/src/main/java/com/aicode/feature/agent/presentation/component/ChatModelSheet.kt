package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import com.aicode.feature.settings.presentation.component.ModelSelectionContent
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import kotlinx.coroutines.launch

/**
 * 思考强度入口的灯泡图标：参照 RikkaHub「调整模型思考深度」面板的灯泡意象（验收反馈 F1，
 * 原与自动模式的 FeatherIcons.Zap 重复）。取 lucide lightbulb 同款路径，与 Feather 图标
 * 同源的 24dp/2dp stroke 线性风格（Feather 集无灯泡，故自绘）。
 */
private val LightbulbIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Lightbulb",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 14f)
            curveToRelative(0.2f, -1f, 0.7f, -1.7f, 1.5f, -2.5f)
            curveToRelative(1f, -0.9f, 1.5f, -2.2f, 1.5f, -3.5f)
            arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6f, 8f)
            curveToRelative(0f, 1f, 0.2f, 2.2f, 1.5f, 3.5f)
            curveToRelative(0.7f, 0.7f, 1.3f, 1.5f, 1.5f, 2.5f)
            moveTo(9f, 18f)
            lineTo(15f, 18f)
            moveTo(10f, 21f)
            lineTo(14f, 21f)
        }
    }.build()
}

/**
 * 输入区下行的模型切换图标按钮
 */
@Composable
internal fun ModelIconButton(
    provider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null,
    /** 当前会话引擎；null = 本体内置。非空时按钮显示「CC·/Codex·模型」胶囊。 */
    engine: String? = null,
    /** CLI 引擎会话的模型名（胶囊与模型页勾选都用它，而非默认 provider 模型）。 */
    cliModel: String? = null,
    /** 返回是否实际切换成功（运行中被拒返回 false，弹窗不滑页）。 */
    onSelectEngine: (String?) -> Boolean = { true },
    checkClaudeCodeAvailable: suspend () -> Boolean = { false },
    checkClaudeCodeAuth: suspend () -> String? = { null },
    onInstallClaudeCode: () -> Unit = {},
    checkCodexAvailable: suspend () -> Boolean = { false },
    checkCodexAuth: suspend () -> String? = { null },
    onInstallCodex: () -> Unit = {},
    checkOpenCodeAvailable: suspend () -> Boolean = { false },
    checkOpenCodeAuth: suspend () -> String? = { null },
    onInstallOpenCode: () -> Unit = {},
    onSelectCliModel: (String) -> Unit = {},
    onOpenAgentCli: () -> Unit = {},
    /** Codex 沙箱是否完全放开（引擎卡开关）。 */
    codexSandboxFullAccess: Boolean = false,
    onCodexSandboxFullAccessChange: (Boolean) -> Unit = {},
    /** OpenCode 是否自动批准权限请求（引擎卡开关）。 */
    openCodeAutoApprove: Boolean = true,
    onOpenCodeAutoApproveChange: (Boolean) -> Unit = {}
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    if (engine != null) {
        // CLI 引擎会话：显示「CC·/Codex·/OpenCode·模型」文本胶囊（拍板定稿的底栏/输入栏文案形态）
        val capsulePrefix = when (engine) {
            AgentEngines.CODEX -> "Codex·"
            AgentEngines.OPENCODE -> "OpenCode·"
            else -> "CC·"
        }
        val fallbackModel = when (engine) {
            AgentEngines.CODEX, AgentEngines.OPENCODE -> "default"
            else -> "claude"
        }
        val capsuleModel = cliModel?.takeIf { it.isNotBlank() && it != "default" } ?: fallbackModel
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            onClick = { showSheet = true },
            modifier = modifier
        ) {
            Text(
                text = capsulePrefix + capsuleModel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    } else {
        IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
            ModelLogoIcon(modelName = provider?.effectiveModel.orEmpty(), size = 20.dp)
        }
    }

    if (effectiveShowSheet) {
        ModelSheet(
            engine = engine,
            providers = providers,
            currentProviderId = provider?.id ?: "",
            // CLI 引擎会话：模型页勾选/胶囊都对齐会话上的 CLI 模型名（qa P1-4）
            currentModel = if (engine != null) cliModel.orEmpty() else provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            checkClaudeCodeAvailable = checkClaudeCodeAvailable,
            checkClaudeCodeAuth = checkClaudeCodeAuth,
            checkCodexAvailable = checkCodexAvailable,
            checkCodexAuth = checkCodexAuth,
            checkOpenCodeAvailable = checkOpenCodeAvailable,
            checkOpenCodeAuth = checkOpenCodeAuth,
            onSelectEngine = onSelectEngine,
            onInstallClaudeCode = onInstallClaudeCode,
            onInstallCodex = onInstallCodex,
            onInstallOpenCode = onInstallOpenCode,
            codexSandboxFullAccess = codexSandboxFullAccess,
            onCodexSandboxFullAccessChange = onCodexSandboxFullAccessChange,
            openCodeAutoApprove = openCodeAutoApprove,
            onOpenCodeAutoApproveChange = onOpenCodeAutoApproveChange,
            onOpenAgentCli = {
                onOpenAgentCli()
                showSheet = false
            },
            onSelectModel = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onSelectCliModel = { model ->
                onSelectCliModel(model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            }
        )
    }
}

/**
 * 思考强度选择器：独立图标按钮，点击弹出底部档位选择。
 * [availableEfforts] 由调用方按当前模型元数据生成，元数据未命中时给出全部档位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortSelector(
    effort: ReasoningEffort,
    availableEfforts: List<ReasoningEffort>,
    onChange: (ReasoningEffort) -> Unit,
    enabled: Boolean
) {
    var showSheet by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showSheet = true },
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                LightbulbIcon,
                contentDescription = stringResource(effort.labelRes()),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                Text(
                    text = stringResource(com.aicode.R.string.chat_reasoning_effort),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
                availableEfforts.forEach { e ->
                    val selected = e == effort
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable {
                                showSheet = false
                                onChange(e)
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            LightbulbIcon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = stringResource(e.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Icon(
                                FeatherIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.NONE -> com.aicode.R.string.chat_reasoning_effort_none
    ReasoningEffort.MINIMAL -> com.aicode.R.string.chat_reasoning_effort_minimal
    ReasoningEffort.LOW -> com.aicode.R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> com.aicode.R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> com.aicode.R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> com.aicode.R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> com.aicode.R.string.chat_reasoning_effort_max
}

/**
 * 引擎/模型选择弹窗（用户拍板交互定稿）：顶部「引擎/模型」两段式分段按钮 + HorizontalPager 左右滑双通道
 * （滑是快捷方式，分段按钮是显式入口）；选中 CLI 引擎后自动滑到「模型」页选模型。
 * 悬浮球与主界面共用同一弹窗（经 ModelIconButton）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    engine: String?,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    checkClaudeCodeAvailable: suspend () -> Boolean,
    checkClaudeCodeAuth: suspend () -> String? = { null },
    checkCodexAvailable: suspend () -> Boolean = { false },
    checkCodexAuth: suspend () -> String? = { null },
    checkOpenCodeAvailable: suspend () -> Boolean = { false },
    checkOpenCodeAuth: suspend () -> String? = { null },
    /** 返回是否实际切换成功（运行中被拒返回 false，弹窗不滑页）。 */
    onSelectEngine: (String?) -> Boolean,
    onInstallClaudeCode: () -> Unit,
    onInstallCodex: () -> Unit = {},
    onInstallOpenCode: () -> Unit = {},
    onOpenAgentCli: () -> Unit = {},
    onSelectModel: (String, String) -> Unit,
    onSelectCliModel: (String) -> Unit,
    codexSandboxFullAccess: Boolean = false,
    onCodexSandboxFullAccessChange: (Boolean) -> Unit = {},
    openCodeAutoApprove: Boolean = true,
    onOpenCodeAutoApproveChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = if (engine != null) 1 else 0) { 2 }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SegmentedTabs(
                selected = pagerState.currentPage,
                // 反馈 10：默认打开模型选择列表，「引擎」放第二页
                labels = listOf(stringResource(R.string.common_model), stringResource(R.string.engine_tab_label)),
                onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } }
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                when (page) {
                    0 -> {
                        if (engine != null) {
                            // CLI 会话：currentModel 已由调用方对齐为会话上的 CLI 模型名
                            CliModelPage(engine = engine, currentModel = currentModel, onSelect = onSelectCliModel)
                        } else {
                            ModelSelectionContent(
                                title = stringResource(R.string.common_model),
                                noModelsText = stringResource(R.string.chat_no_models_hint),
                                providers = providers,
                                currentProviderId = currentProviderId,
                                currentModel = currentModel,
                                modelMetadata = modelMetadata,
                                onSelect = onSelectModel,
                                onClear = null
                            )
                        }
                    }
                    else -> EnginePage(
                        engine = engine,
                        checkClaudeCodeAvailable = checkClaudeCodeAvailable,
                        checkClaudeCodeAuth = checkClaudeCodeAuth,
                        checkCodexAvailable = checkCodexAvailable,
                        checkCodexAuth = checkCodexAuth,
                        checkOpenCodeAvailable = checkOpenCodeAvailable,
                        checkOpenCodeAuth = checkOpenCodeAuth,
                        onSelectEngine = { e ->
                            val ok = onSelectEngine(e)
                            if (ok && e != null) scope.launch { pagerState.animateScrollToPage(0) }
                            ok
                        },
                        onInstallClaudeCode = onInstallClaudeCode,
                        onInstallCodex = onInstallCodex,
                        onInstallOpenCode = onInstallOpenCode,
                        codexSandboxFullAccess = codexSandboxFullAccess,
                        onCodexSandboxFullAccessChange = onCodexSandboxFullAccessChange,
                        openCodeAutoApprove = openCodeAutoApprove,
                        onOpenCodeAutoApproveChange = onOpenCodeAutoApproveChange,
                        onOpenAgentCli = onOpenAgentCli
                    )
                }
            }
        }
    }
}

@Composable
private fun EnginePage(
    engine: String?,
    checkClaudeCodeAvailable: suspend () -> Boolean,
    checkClaudeCodeAuth: suspend () -> String?,
    checkCodexAvailable: suspend () -> Boolean,
    checkCodexAuth: suspend () -> String?,
    checkOpenCodeAvailable: suspend () -> Boolean,
    checkOpenCodeAuth: suspend () -> String?,
    onSelectEngine: (String?) -> Unit,
    onInstallClaudeCode: () -> Unit,
    onInstallCodex: () -> Unit = {},
    onInstallOpenCode: () -> Unit = {},
    codexSandboxFullAccess: Boolean = false,
    onCodexSandboxFullAccessChange: (Boolean) -> Unit = {},
    openCodeAutoApprove: Boolean = true,
    onOpenCodeAutoApproveChange: (Boolean) -> Unit = {},
    onOpenAgentCli: () -> Unit = {}
) {
    // 三张 CLI 引擎卡同构（探测/认证态/安装/去登录），用描述符循环避免三段复制
    var states by remember { mutableStateOf<Map<String, Pair<Boolean?, String?>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        // 三引擎并行探测：串行会让最后一张卡的「检测中」时长达三倍（发版 qa P2-4）
        suspend fun probe(id: String, available: suspend () -> Boolean, auth: suspend () -> String?) {
            // 已安装才查登录态：未装时探测无意义
            val a = available()
            states = states + (id to (a to (if (a) auth() else null)))
        }
        launch { probe(AgentEngines.CLAUDE_CODE, checkClaudeCodeAvailable, checkClaudeCodeAuth) }
        launch { probe(AgentEngines.CODEX, checkCodexAvailable, checkCodexAuth) }
        launch { probe(AgentEngines.OPENCODE, checkOpenCodeAvailable, checkOpenCodeAuth) }
    }
    data class CliEngineDescriptor(
        val id: String,
        val title: String,
        val notAuthenticatedRes: Int,
        val hintRes: Int?
    )
    val descriptors = listOf(
        CliEngineDescriptor(AgentEngines.CLAUDE_CODE, "Claude Code", R.string.engine_claude_not_authenticated, null),
        CliEngineDescriptor(AgentEngines.CODEX, "Codex", R.string.engine_codex_not_authenticated, R.string.engine_codex_hint),
        CliEngineDescriptor(AgentEngines.OPENCODE, "OpenCode", R.string.engine_opencode_not_authenticated, R.string.engine_opencode_hint)
    )

    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        EngineCard(
            title = stringResource(R.string.engine_builtin),
            subtitle = stringResource(R.string.engine_builtin_sub),
            selected = engine == null,
            onClick = { onSelectEngine(null) }
        )
        descriptors.forEach { d ->
            val (available, authHint) = states[d.id] ?: (null to null)
            val onInstall = when (d.id) {
                AgentEngines.CLAUDE_CODE -> onInstallClaudeCode
                AgentEngines.CODEX -> onInstallCodex
                else -> onInstallOpenCode
            }
            EngineCard(
                title = d.title,
                subtitle = when {
                    available == false -> stringResource(R.string.engine_claude_missing)
                    available == true && authHint != null -> stringResource(d.notAuthenticatedRes)
                    available == true -> stringResource(R.string.engine_claude_ready)
                    else -> stringResource(R.string.engine_detecting)
                },
                selected = engine == d.id,
                // 已装未登录：选中无意义（发送会被预检拦下），引导先去终端登录
                onClick = { if (available != false && authHint == null) onSelectEngine(d.id) },
                trailingAction = when {
                    available == false -> {
                        { TextButton(onClick = onInstall) { Text(stringResource(R.string.engine_install)) } }
                    }
                    available == true && authHint != null -> {
                        { TextButton(onClick = onOpenAgentCli) { Text(stringResource(R.string.engine_go_login)) } }
                    }
                    available == true -> {
                        { TextButton(onClick = onOpenAgentCli) { Text(stringResource(R.string.engine_open_manager)) } }
                    }
                    else -> null
                },
                // 引擎行为选项：Codex 沙箱档位 / OpenCode 自动批准（全局生效，下一次发送起作用）
                optionRow = when (d.id) {
                    AgentEngines.CODEX -> {
                        {
                            EngineOptionToggle(
                                title = stringResource(R.string.engine_codex_sandbox_toggle),
                                checked = codexSandboxFullAccess,
                                onCheckedChange = onCodexSandboxFullAccessChange
                            )
                        }
                    }
                    AgentEngines.OPENCODE -> {
                        {
                            EngineOptionToggle(
                                title = stringResource(R.string.engine_opencode_auto_toggle),
                                checked = openCodeAutoApprove,
                                onCheckedChange = onOpenCodeAutoApproveChange
                            )
                        }
                    }
                    else -> null
                }
            )
        }
        Text(
            text = stringResource(R.string.engine_limits_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
        descriptors.firstOrNull { it.id == engine }?.hintRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
    /** 卡内附加的引擎行为选项行（如沙箱/自动批准开关）；null 不显示。 */
    optionRow: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    Icon(
                        FeatherIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                trailingAction?.invoke()
            }
            optionRow?.invoke()
        }
    }
}

/** 引擎卡内的行为选项开关行：标题（弱化色）+ 右侧开关。 */
@Composable
private fun EngineOptionToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            // 整行可切：点标题文字也能切换，开关本体退为纯视觉（避免双层 toggleable 双触发）
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

/** CLI 引擎的模型页：一行一个 CLI 侧模型名；历史由桥接自动续接（App 会话与 CLI 侧会话自动对齐）。 */
@Composable
private fun CliModelPage(engine: String, currentModel: String, onSelect: (String) -> Unit) {
    val models = when (engine) {
        AgentEngines.CODEX ->
            // default = 不透传 -m，跟随 CLI 侧配置的默认模型（Codex 模型名变动频繁，不硬编码更多）
            listOf("default", "gpt-5.3-codex", "gpt-5.1-codex-max", "gpt-5-codex-mini")
        AgentEngines.OPENCODE ->
            // default 同上；其余为 models.dev 常用 provider/model 形态
            listOf("default", "openai/gpt-5.3-codex", "anthropic/claude-sonnet-4-5")
        else -> listOf("sonnet", "opus", "haiku")
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.engine_resume_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm)
        )
        Surface(
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                models.forEachIndexed { index, model ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )
                    }
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(model) }
                        .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentModel == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (currentModel == model) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    }
                }
            }
        }
    }
}
