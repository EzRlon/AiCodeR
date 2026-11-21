package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.agent.domain.engine.CliModelRef
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import com.aicode.core.ui.settings.ModelSearchField
import com.aicode.core.ui.settings.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

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
    /** 当前会话引擎；null = 本体内置。非空时按钮与内置引擎一致显示模型品牌图标。 */
    engine: String? = null,
    /** CLI 引擎会话的模型名（图标与模型页勾选都用它，而非默认 provider 模型）。 */
    cliModel: String? = null,
    /** CLI 引擎会话选定的 App 供应商（供应商模型用）；null = 引擎自带模型。 */
    cliProviderId: String? = null,
    onSelectCliModel: (providerId: String?, model: String) -> Unit = { _, _ -> },
    /** 会话引擎切换回调；null = 隐藏引擎切换入口。 */
    onSetEngine: ((String?) -> Boolean)? = null,
    /** 思考强度：从控制行收纳至此（控制行为发送按钮居中腾位）。 */
    reasoningEffort: ReasoningEffort? = null,
    onReasoningEffortChange: ((ReasoningEffort) -> Unit)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    if (engine != null) {
        // CLI 引擎会话：与内置引擎一致显示模型品牌图标（胶囊文字过宽会挤压输入栏其它功能）
        val fallbackModel = when (engine) {
            AgentEngines.CODEX -> "gpt"
            AgentEngines.OPENCODE -> "default"
            AgentEngines.DEEPSEEK -> "deepseek"
            else -> "claude"
        }
        val iconModel = cliModel?.takeIf { it.isNotBlank() && it != "default" } ?: fallbackModel
        IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
            ModelLogoIcon(modelName = iconModel, size = 20.dp)
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
            currentCliProviderId = cliProviderId,
            // CLI 引擎会话：模型页勾选对齐会话上的 CLI 模型名（qa P1-4）
            currentModel = if (engine != null) cliModel.orEmpty() else provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelectModel = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onSelectCliModel = { pId, model ->
                onSelectCliModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            },
            onSetEngine = onSetEngine,
            reasoningEffort = reasoningEffort,
            onReasoningEffortChange = onReasoningEffortChange
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

internal fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.NONE -> com.aicode.R.string.chat_reasoning_effort_none
    ReasoningEffort.MINIMAL -> com.aicode.R.string.chat_reasoning_effort_minimal
    ReasoningEffort.LOW -> com.aicode.R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> com.aicode.R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> com.aicode.R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> com.aicode.R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> com.aicode.R.string.chat_reasoning_effort_max
}

/** 思考强度选择区（模型弹窗内直列选项，不再嵌套弹层）。 */
@Composable
private fun ReasoningEffortSection(
    effort: ReasoningEffort,
    availableEfforts: List<ReasoningEffort>,
    onChange: (ReasoningEffort) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.chat_reasoning_effort),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            availableEfforts.forEach { candidate ->
                val selected = candidate == effort
                Text(
                    text = stringResource(candidate.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            RoundedCornerShape(50)
                        )
                        .clickable { onChange(candidate) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 模型选择弹窗（单页）：内置会话列 App 模型，CLI 引擎会话列该引擎自有模型。
 * 引擎的切换/安装/登录统一在 设置 → 模型与引擎 管理，弹窗不再承载引擎页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(
    engine: String?,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentCliProviderId: String? = null,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    onSelectCliModel: (providerId: String?, model: String) -> Unit,
    onDismiss: () -> Unit,
    onSetEngine: ((String?) -> Boolean)? = null,
    reasoningEffort: ReasoningEffort? = null,
    onReasoningEffortChange: ((ReasoningEffort) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            // 引擎切换：会话维度，下一回合生效（回合进行中拒绝并提示）
            if (onSetEngine != null) {
                EngineSwitcherRow(currentEngine = engine, onSetEngine = onSetEngine)
            }
            // 思考强度：从控制行收纳至此（内置引擎会话）
            if (engine == null && reasoningEffort != null && onReasoningEffortChange != null) {
                val activeProvider = providers.firstOrNull { it.id == currentProviderId }
                val availableEfforts = activeProvider?.let { p ->
                    modelMetadata[modelMetadataKey(p.id, p.effectiveModel)]?.reasoningEffortOptions
                        ?.let { ReasoningEffort.fromValues(it) }
                }.orEmpty().ifEmpty { ReasoningEffort.entries }
                ReasoningEffortSection(
                    effort = reasoningEffort,
                    availableEfforts = availableEfforts,
                    onChange = onReasoningEffortChange
                )
            }
            if (engine != null) {
                // CLI 会话：模型页 = 引擎自有模型 + App 供应商模型（同类型供应商）
                CliModelPage(
                    engine = engine,
                    currentModel = currentModel,
                    currentProviderId = currentCliProviderId,
                    providers = providers,
                    onSelect = onSelectCliModel
                )
            } else {
                // 内置引擎与三引擎统一为同一套呈现：无边框搜索框 + 可折叠供应商分组 + 扁平行
                // （2026-11-16 用户反馈：此前内置页是另一套卡片样式）。
                BuiltinModelPage(
                    providers = providers,
                    currentProviderId = currentProviderId,
                    currentModel = currentModel,
                    onSelect = onSelectModel
                )
            }
        }
    }
}

/**
 * 内置引擎的模型页：与 [CliModelPage] 完全同构——搜索框 + 可折叠供应商分组 + 扁平行 + 分隔线。
 * 不再使用设置页那套「大标题 + 卡片」，避免同一按钮弹出两种视觉。
 */
@Composable
private fun BuiltinModelPage(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    onSelect: (providerId: String, model: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    val active = providers.filter { it.isEnabled && it.models.isNotEmpty() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp, max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        ModelSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.provider_filter_models_hint)
        )
        active.forEach { provider ->
            val models = provider.models
                .filter { searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true) }
            if (models.isEmpty()) return@forEach
            val expanded = searchQuery.isNotBlank() || provider.id !in collapsedGroups
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        collapsedGroups = if (provider.id in collapsedGroups) {
                            collapsedGroups - provider.id
                        } else {
                            collapsedGroups + provider.id
                        }
                    }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${provider.name} (${models.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                models.forEachIndexed { index, model ->
                    if (index > 0) {
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = Spacing.lg)
                        )
                    }
                    CliModelRow(
                        display = model,
                        selected = provider.id == currentProviderId && model == currentModel,
                        onClick = { onSelect(provider.id, model) }
                    )
                }
            }
        }
        if (active.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_no_models_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        }
    }
}

/** CLI 引擎的模型页：引擎自有模型 + App 同类型供应商的模型（均带品牌图标，格式与内置模型列表一致）；历史由桥接自动续接。 */
@Composable
private fun CliModelPage(
    engine: String,
    currentModel: String,
    currentProviderId: String?,
    providers: List<AIProviderConfig>,
    onSelect: (providerId: String?, model: String) -> Unit
) {
    val builtinModels = when (engine) {
        AgentEngines.CODEX ->
            // default = 不透传 -m，跟随 CLI 侧配置的默认模型（Codex 模型名变动频繁，不硬编码更多）
            listOf("default", "gpt-5.3-codex", "gpt-5.1-codex-max", "gpt-5-codex-mini")
        AgentEngines.OPENCODE ->
            // default 同上；其余为 models.dev 常用 provider/model 形态
            listOf("default", "openai/gpt-5.3-codex", "anthropic/claude-sonnet-4-5")
        AgentEngines.DEEPSEEK ->
            // default = 不改写 agent-default-model，跟随 dsh 内置默认路由；
            // 其余经 --patch overlay 配置到 @deepseek-ai/dsh-agent-default-model
            listOf("default", "deepseek-chat", "deepseek-reasoner")
        else -> listOf("sonnet", "opus", "haiku")
    }
    // 供应商类型口径与桥的透传一致：Claude=Anthropic 型直透 + OpenAI 型全量（host 命中厂商端点表走直连，
    // 未命中走本地 OpenAI→Anthropic 转换网关）；Codex=OpenAI 型，OpenCode=两者；
    // useFullUrl 在 Anthropic/OpenCode 直透路径无法安全还原 base 排除，转换器路径可原样转发故放行
    val matchingProviders = when (engine) {
        AgentEngines.CLAUDE_CODE -> providers.filter {
            it.type == ProviderType.ANTHROPIC && !it.useFullUrl || it.type == ProviderType.OPENAI
        }
        AgentEngines.CODEX -> providers.filter { it.type == ProviderType.OPENAI && !it.useFullUrl }
        // DeepSeek 引擎与 OpenCode 同口径：DeepSeek 型直透内置 provider；OpenAI 兼容端点与
        // Anthropic 兼容端点经 --patch 自定义路由（apiKeyEnv 环境变量）透传，模型目录同源
        AgentEngines.DEEPSEEK -> providers.filter {
            (it.type == ProviderType.OPENAI || it.type == ProviderType.ANTHROPIC || it.type == ProviderType.DEEPSEEK) && !it.useFullUrl
        }
        else -> providers.filter { (it.type == ProviderType.OPENAI || it.type == ProviderType.ANTHROPIC) && !it.useFullUrl }
    }
    // 折叠的供应商组（引擎自带组不参与折叠，组数少且最常用）；弹窗生命周期内记忆
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 与内置模型页同口径：有最大高度且可滚动，供应商模型多时底部条目可达
            .heightIn(min = 360.dp, max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 与内置模型页保持同一观感：不额外放说明行，直接「搜索 + 分组列表」（2026-11-16 反馈 8）。
        // 与内置模型页同一观感：无边框胶囊搜索框（2026-11-16 用户反馈 6）
        var searchQuery by remember { mutableStateOf("") }
        ModelSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.provider_filter_models_hint)
        )
        // 分组可折叠（记忆化避免弹窗内任何状态变化都重建整表，optimizer P2）：
        // label=供应商名（含「引擎自带模型」组），models=该组可选模型；搜索时强制展开命中组
        val builtinLabel = stringResource(R.string.cli_model_builtin_group)
        val groups = remember(engine, providers, builtinLabel, searchQuery) {
            data class Group(val label: String, val providerId: String?, val models: List<Pair<String, String>>)
            buildList {
                val builtinHits = builtinModels.filter { searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true) }
                if (builtinHits.isNotEmpty()) {
                    add(Group(builtinLabel, null, builtinHits.map { it to it }))
                }
                matchingProviders.forEach { provider ->
                    val models = (provider.models + listOf(provider.selectedModel, provider.defaultModel))
                        .filter { it.isNotBlank() }
                        .distinct()
                        .filter { searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true) }
                    if (models.isEmpty()) return@forEach
                    // CC 引擎下 OpenAI 型全部走自动适配（端点表直连或本地转换网关），标注区分
                    val adapted = engine == AgentEngines.CLAUDE_CODE && provider.type == ProviderType.OPENAI
                    val label = if (adapted) provider.name + "（自动适配）" else provider.name
                    add(Group(label, provider.id, models.map { model ->
                        val ref = when (engine) {
                            AgentEngines.OPENCODE -> CliModelRef.opencodeModelRef(provider.name, provider.id, provider.type, provider.baseUrl, model)
                            AgentEngines.DEEPSEEK -> CliModelRef.deepseekModelRef(provider.name, provider.id, provider.type, provider.baseUrl, model)
                            else -> model
                        }
                        model to ref
                    }))
                }
            }
        }
        groups.forEach { group ->
            // 分组头：点击折叠/展开（与内置页 CollapsibleGroupHeader 同交互，简化为行内实现）
            val collapsed = group.providerId != null && group.providerId in collapsedGroups
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val id = group.providerId ?: return@clickable
                        collapsedGroups = if (id in collapsedGroups) collapsedGroups - id else collapsedGroups + id
                    }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.label + " (${group.models.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (group.providerId != null) {
                    Icon(
                        imageVector = if (collapsed) FeatherIcons.ChevronDown else FeatherIcons.ChevronUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (!collapsed) {
                group.models.forEachIndexed { index, (model, ref) ->
                    if (index > 0) {
                        // 与内置模型页一致：行间分隔线，同组靠线聚拢、不同组靠标题分层。
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = Spacing.lg)
                        )
                    }
                    CliModelRow(
                        display = model,
                        selected = currentProviderId == group.providerId && currentModel == ref,
                        onClick = { onSelect(group.providerId, ref) }
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_no_models_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        }
    }
}

@Composable
private fun CliModelRow(
    display: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = display, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
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
