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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
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
    /** 当前会话引擎；null = 本体内置。非空时按钮显示「CC·模型」胶囊。 */
    engine: String? = null,
    /** CLI 引擎会话的模型名（胶囊与模型页勾选都用它，而非默认 provider 模型）。 */
    cliModel: String? = null,
    onSelectEngine: (String?) -> Unit = {},
    checkClaudeCodeAvailable: suspend () -> Boolean = { false },
    checkClaudeCodeAuth: suspend () -> String? = { null },
    onInstallClaudeCode: () -> Unit = {},
    onSelectCliModel: (String) -> Unit = {},
    onOpenAgentCli: () -> Unit = {}
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    if (engine == AgentEngines.CLAUDE_CODE) {
        // CLI 引擎会话：显示「CC·模型」文本胶囊（拍板定稿的底栏/输入栏文案形态）
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            onClick = { showSheet = true },
            modifier = modifier
        ) {
            Text(
                text = "CC·" + (cliModel?.takeIf { it.isNotBlank() } ?: "claude"),
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
            currentModel = if (engine == AgentEngines.CLAUDE_CODE) cliModel.orEmpty() else provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            checkClaudeCodeAvailable = checkClaudeCodeAvailable,
            checkClaudeCodeAuth = checkClaudeCodeAuth,
            onSelectEngine = { e ->
                // 选引擎不关弹窗：切换后自动滑到模型页继续选（拍板交互；qa P1-5）
                onSelectEngine(e)
            },
            onInstallClaudeCode = onInstallClaudeCode,
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
    onSelectEngine: (String?) -> Unit,
    onInstallClaudeCode: () -> Unit,
    onOpenAgentCli: () -> Unit = {},
    onSelectModel: (String, String) -> Unit,
    onSelectCliModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = if (engine == AgentEngines.CLAUDE_CODE) 1 else 0) { 2 }

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
                        if (engine == AgentEngines.CLAUDE_CODE) {
                            // CLI 会话：currentModel 已由调用方对齐为会话上的 CLI 模型名
                            CliModelPage(currentModel = currentModel, onSelect = onSelectCliModel)
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
                        onSelectEngine = { e ->
                            onSelectEngine(e)
                            if (e == AgentEngines.CLAUDE_CODE) scope.launch { pagerState.animateScrollToPage(0) }
                        },
                        onInstallClaudeCode = onInstallClaudeCode,
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
    onSelectEngine: (String?) -> Unit,
    onInstallClaudeCode: () -> Unit,
    onOpenAgentCli: () -> Unit = {}
) {
    var claudeAvailable by remember { mutableStateOf<Boolean?>(null) }
    var claudeAuthHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        claudeAvailable = checkClaudeCodeAvailable()
        // 已安装才查登录态：未装时探测无意义
        if (claudeAvailable == true) claudeAuthHint = checkClaudeCodeAuth()
    }

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
        EngineCard(
            title = "Claude Code",
            subtitle = when {
                claudeAvailable == false -> stringResource(R.string.engine_claude_missing)
                claudeAvailable == true && claudeAuthHint != null -> stringResource(R.string.engine_claude_not_authenticated)
                claudeAvailable == true -> stringResource(R.string.engine_claude_ready)
                else -> stringResource(R.string.engine_detecting)
            },
            selected = engine == AgentEngines.CLAUDE_CODE,
            onClick = { if (claudeAvailable != false && claudeAuthHint == null) onSelectEngine(AgentEngines.CLAUDE_CODE) },
            trailingAction = when {
                claudeAvailable == false -> {
                    { TextButton(onClick = onInstallClaudeCode) { Text(stringResource(R.string.engine_install)) } }
                }
                // 已装未登录：选中无意义（发送会被预检拦下），引导先去终端登录
                claudeAvailable == true && claudeAuthHint != null -> {
                    { TextButton(onClick = onOpenAgentCli) { Text(stringResource(R.string.engine_go_login)) } }
                }
                claudeAvailable == true -> {
                    { TextButton(onClick = onOpenAgentCli) { Text(stringResource(R.string.engine_open_manager)) } }
                }
                else -> null
            }
        )
        Text(
            text = stringResource(R.string.engine_limits_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null
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
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
    }
}

/** CLI 引擎的模型页：一行一个 CLI 侧模型名；历史由桥接自动续接（本体 sessionId 对齐 CLI session）。 */
@Composable
private fun CliModelPage(currentModel: String, onSelect: (String) -> Unit) {
    val models = listOf("sonnet", "opus", "haiku")
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
