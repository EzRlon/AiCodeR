package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.agent.presentation.AgentCliViewModel
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.data.local.ModelSheetCollapseStore
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Image
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Type
import compose.icons.feathericons.User
import compose.icons.feathericons.Users

/**
 * 默认模型二级页：集中管理应用中的默认/特定用途模型设置（如识图模型、压缩模型）。
 */
@Composable
internal fun DefaultModelsSection(
    providers: List<AIProviderConfig>,
    defaultEngine: String = "",
    onSelectDefaultEngine: (String) -> Unit = {},
    /** 切换当前会话引擎（"" = 切回内置）；会话运行中被拒时仅默认生效。 */
    onSwitchEngine: (String?) -> Unit = {},
    /** 去/回终端页（登录与安装进度都在终端标签里）。 */
    onOpenTerminal: () -> Unit = {},
    visionProviderId: String,
    visionModel: String,
    compactionProviderId: String,
    compactionModel: String,
    titleProviderId: String,
    titleModel: String,
    verificationProviderId: String,
    verificationModel: String,
    imageGenProviderId: String,
    imageGenModel: String,
    mainProviderId: String,
    mainModel: String,
    subAgentModels: Map<String, Pair<String, String>>,
    subAgents: List<Pair<String, String>>,
    modelMetadata: Map<String, ModelMetadata>,
    onLoadMetadata: () -> Unit,
    onSelectMainModel: (providerId: String, model: String) -> Unit,
    onClearMainModel: () -> Unit,
    onSelectSubAgentModel: (agentName: String, providerId: String, model: String) -> Unit,
    onClearSubAgentModel: (agentName: String) -> Unit,
    onSelectVisionModel: (providerId: String, model: String) -> Unit,
    onClearVisionModel: () -> Unit,
    onSelectCompactionModel: (providerId: String, model: String) -> Unit,
    onClearCompactionModel: () -> Unit,
    onSelectTitleModel: (providerId: String, model: String) -> Unit,
    onClearTitleModel: () -> Unit,
    onSelectVerificationModel: (providerId: String, model: String) -> Unit,
    onClearVerificationModel: () -> Unit,
    onSelectImageGenModel: (providerId: String, model: String) -> Unit,
    onClearImageGenModel: () -> Unit
) {
    var showVisionSheet by remember { mutableStateOf(false) }
    var showCompactionSheet by remember { mutableStateOf(false) }
    var showTitleSheet by remember { mutableStateOf(false) }
    var showVerificationSheet by remember { mutableStateOf(false) }
    var showImageGenSheet by remember { mutableStateOf(false) }
    var showMainSheet by remember { mutableStateOf(false) }
    // 子代理模型选择：null 不显示；空串 = 默认通用子代理，否则为 agent 名。
    var subAgentSheetTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { onLoadMetadata() }

    val visionValue = if (visionProviderId.isBlank() || visionModel.isBlank()) {
        stringResource(R.string.settings_vision_follow_chat)
    } else {
        visionModel
    }

    val compactionValue = if (compactionProviderId.isBlank() || compactionModel.isBlank()) {
        stringResource(R.string.settings_compaction_follow_chat)
    } else {
        compactionModel
    }

    val titleValue = if (titleProviderId.isBlank() || titleModel.isBlank()) {
        stringResource(R.string.settings_title_follow_chat)
    } else {
        titleModel
    }

    val verificationValue = if (verificationProviderId.isBlank() || verificationModel.isBlank()) {
        stringResource(R.string.settings_verification_unconfigured)
    } else {
        verificationModel
    }

    val imageGenValue = if (imageGenProviderId.isBlank() || imageGenModel.isBlank()) {
        stringResource(R.string.settings_image_gen_unconfigured)
    } else {
        imageGenModel
    }

    val mainValue = if (mainProviderId.isBlank() || mainModel.isBlank()) {
        stringResource(R.string.settings_main_agent_follow_chat)
    } else {
        mainModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 引擎：点卡片即选中（设为新会话默认引擎并切换当前会话）；安装/登录用 CLI 智能体老接口
        SettingsGroupHeader(text = stringResource(R.string.settings_engine_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(R.string.engine_builtin),
                subtitle = stringResource(R.string.engine_builtin_sub),
                onClick = {
                    onSelectDefaultEngine("")
                    onSwitchEngine(null)
                },
                trailing = {
                    if (defaultEngine.isBlank()) {
                        Icon(FeatherIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
            CliEngineRows(
                defaultEngine = defaultEngine,
                onSelect = { id ->
                    onSelectDefaultEngine(id)
                    onSwitchEngine(id)
                },
                onOpenTerminal = onOpenTerminal
            )
        }
        SettingsGroupHeader(text = stringResource(R.string.settings_agent_models_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.User,
                title = stringResource(R.string.settings_main_agent_model),
                subtitle = mainValue,
                onClick = { showMainSheet = true }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(R.string.settings_subagent_default_model),
                subtitle = subAgentModels[""]?.second
                    ?: stringResource(R.string.settings_subagent_follow_main),
                onClick = { subAgentSheetTarget = "" }
            )
            subAgents.forEach { (name, _) ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Cpu,
                    title = name,
                    subtitle = subAgentModels[name]?.second
                        ?: stringResource(R.string.settings_subagent_follow_main),
                    onClick = { subAgentSheetTarget = name }
                )
            }
        }
        SettingsGroupHeader(text = stringResource(R.string.settings_models_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_vision_model),
                onClick = { showVisionSheet = true },
                trailing = {
                    Text(
                        text = visionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Minimize2,
                title = stringResource(R.string.settings_compaction_model),
                onClick = { showCompactionSheet = true },
                trailing = {
                    Text(
                        text = compactionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Type,
                title = stringResource(R.string.settings_title_model),
                onClick = { showTitleSheet = true },
                trailing = {
                    Text(
                        text = titleValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Check,
                title = stringResource(R.string.settings_verification_model),
                onClick = { showVerificationSheet = true },
                trailing = {
                    Text(
                        text = verificationValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Camera,
                title = stringResource(R.string.settings_image_gen_model),
                onClick = { showImageGenSheet = true },
                trailing = {
                    Text(
                        text = imageGenValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
        }
    }

    if (showMainSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_main_agent_model),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = mainProviderId,
            currentModel = mainModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectMainModel(pid, model)
                showMainSheet = false
            },
            onClear = {
                onClearMainModel()
                showMainSheet = false
            },
            onDismiss = { showMainSheet = false }
        )
    }

    subAgentSheetTarget?.let { target ->
        val current = subAgentModels[target]
        ModelSelectionSheet(
            title = if (target.isEmpty()) stringResource(R.string.settings_subagent_default_model) else target,
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = current?.first.orEmpty(),
            currentModel = current?.second.orEmpty(),
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectSubAgentModel(target, pid, model)
                subAgentSheetTarget = null
            },
            onClear = {
                onClearSubAgentModel(target)
                subAgentSheetTarget = null
            },
            onDismiss = { subAgentSheetTarget = null }
        )
    }

    if (showVisionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_vision_model),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = visionProviderId,
            currentModel = visionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVisionModel(pid, model)
                showVisionSheet = false
            },
            onClear = {
                onClearVisionModel()
                showVisionSheet = false
            },
            onDismiss = { showVisionSheet = false }
        )
    }

    if (showCompactionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_compaction_model),
            noModelsText = stringResource(R.string.compaction_no_models),
            providers = providers,
            currentProviderId = compactionProviderId,
            currentModel = compactionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectCompactionModel(pid, model)
                showCompactionSheet = false
            },
            onClear = {
                onClearCompactionModel()
                showCompactionSheet = false
            },
            onDismiss = { showCompactionSheet = false }
        )
    }

    if (showTitleSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_title_model),
            noModelsText = stringResource(R.string.title_no_models),
            providers = providers,
            currentProviderId = titleProviderId,
            currentModel = titleModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectTitleModel(pid, model)
                showTitleSheet = false
            },
            onClear = {
                onClearTitleModel()
                showTitleSheet = false
            },
            onDismiss = { showTitleSheet = false }
        )
    }

    if (showVerificationSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_verification_model),
            noModelsText = stringResource(R.string.verification_no_models),
            providers = providers,
            currentProviderId = verificationProviderId,
            currentModel = verificationModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVerificationModel(pid, model)
                showVerificationSheet = false
            },
            onClear = {
                onClearVerificationModel()
                showVerificationSheet = false
            },
            onDismiss = { showVerificationSheet = false }
        )
    }

    if (showImageGenSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_image_gen_model),
            noModelsText = stringResource(R.string.image_gen_no_models),
            providers = providers,
            currentProviderId = imageGenProviderId,
            currentModel = imageGenModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectImageGenModel(pid, model)
                showImageGenSheet = false
            },
            onClear = {
                onClearImageGenModel()
                showImageGenSheet = false
            },
            onDismiss = { showImageGenSheet = false }
        )
    }
}

/**
 * 模型选择弹窗：风格与拉取模型弹窗保持一致（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 识图模型、压缩模型与主页聊天模型共用此组件，仅文案不同；右上角「重置」清除专用模型配置（回退跟随聊天模型），主页场景传 null 不显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(
    title: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = settingsPageBackground()
    ) {
        ModelSelectionContent(
            title = title,
            noModelsText = noModelsText,
            providers = providers,
            currentProviderId = currentProviderId,
            currentModel = currentModel,
            modelMetadata = modelMetadata,
            onSelect = onSelect,
            onClear = onClear
        )
    }
}

/**
 * 模型选择弹窗的内容体（不含底部弹窗外壳）：引擎/模型两段式弹窗的「模型」页也复用这份。
 * 搜索/折叠状态在本组合内持有。
 */
@Composable
internal fun ModelSelectionContent(
    title: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?
) {
    val context = LocalContext.current
    val collapseStore = remember { ModelSheetCollapseStore(context.applicationContext) }
    var searchQuery by remember { mutableStateOf("") }
    var collapsedProviderIds by remember {
        mutableStateOf(
            if (collapseStore.hasSaved()) {
                collapseStore.collapsedProviderIds()
            } else {
                // 首次使用：只展开当前选中的供应商，其余收起——避免模型多时一打开就是长列表要逐条翻。
                // 用户手动调整任一组后即按其习惯记忆（hasSaved 后不再套用本默认）。
                providers.filter { it.isEnabled && it.models.isNotEmpty() && it.id != currentProviderId }
                    .map { it.id }
                    .toSet()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            onClear?.let { onClear ->
                Text(
                    text = stringResource(R.string.common_reset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onClear() }
                )
            }
        }

        ModelSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.provider_filter_models_hint)
        )

        val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
        if (activeProviders.isEmpty()) {
            SettingsGroup {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = noModelsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.lg)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                activeProviders.forEach { provider ->
                    val filteredModels = provider.models.filter {
                        searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
                    }
                    if (filteredModels.isNotEmpty()) {
                        // 搜索时强制展开，否则用折叠状态：用户搜到名字却看不到结果会很困惑。
                        val expanded = searchQuery.isNotBlank() || provider.id !in collapsedProviderIds
                        item(key = "header_${provider.id}") {
                            CollapsibleGroupHeader(
                                text = "${provider.name} (${filteredModels.size})",
                                expanded = expanded,
                                onToggle = {
                                    val updated = if (provider.id in collapsedProviderIds) {
                                        collapsedProviderIds - provider.id
                                    } else {
                                        collapsedProviderIds + provider.id
                                    }
                                    collapsedProviderIds = updated
                                    collapseStore.save(updated)
                                }
                            )
                        }
                        if (expanded) {
                            item(key = "card_${provider.id}") {
                                SettingsGroup {
                                    filteredModels.forEachIndexed { index, model ->
                                        if (index > 0) {
                                            SettingsDivider()
                                        }
                                        ModelSelectionRow(
                                            model = model,
                                            selected = provider.id == currentProviderId && model == currentModel,
                                            metadata = modelMetadata[modelMetadataKey(provider.id, model)],
                                            onClick = { onSelect(provider.id, model) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata)
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (metadata != null) {
            if (metadata.supportsVision) {
                ModelTag(text = "Image", isHighlight = true)
            }
            if (metadata.supportsTools) {
                ModelTag(text = "Tools")
            }
            val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = formatTokenLimit(input), icon = FeatherIcons.ArrowUp)
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = formatTokenLimit(output), icon = FeatherIcons.ArrowDown)
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor
            )
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")

/**
 * 三个 CLI 引擎卡（Claude Code / Codex / OpenCode）：状态 + 安装/重装/登录动作，
 * 复用老「AI 智能体」接口（后台终端标签跑 npm、装完自动复检；启动拉起 CLI 交互会话完成登录）。
 */
@Composable
private fun CliEngineRows(
    defaultEngine: String,
    onSelect: (String) -> Unit,
    onOpenTerminal: () -> Unit
) {
    val viewModel: AgentCliViewModel = hiltViewModel()
    val states by viewModel.states.collectAsStateWithLifecycle()
    val codexSandboxFullAccess by viewModel.codexSandboxFullAccess.collectAsStateWithLifecycle()
    val openCodeAutoApprove by viewModel.openCodeAutoApprove.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    error?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, context.getString(R.string.agent_cli_action_failed, message), Toast.LENGTH_SHORT).show()
            viewModel.consumeError()
        }
    }

    states.forEach { state ->
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Terminal,
            title = state.agent.displayName,
            subtitle = when {
                state.installing -> stringResource(R.string.agent_cli_installing)
                state.checking -> stringResource(R.string.agent_cli_checking)
                state.version == null -> stringResource(R.string.agent_cli_not_installed)
                state.authHint != null ->
                    stringResource(R.string.agent_cli_installed, state.version) + " · " +
                        stringResource(R.string.engine_not_logged_in)
                else -> stringResource(R.string.agent_cli_installed, state.version)
            },
            onClick = {
                // 未安装禁选（对齐旧引擎页门禁）：选中后发送才报错的死路体验不如就地安装
                if (state.version != null) onSelect(engineIdOf(state.agent.id))
            },
            trailing = {
                if (defaultEngine == engineIdOf(state.agent.id)) {
                    Icon(FeatherIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                when {
                    state.installing || state.checking ->
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    state.version == null ->
                        TextButton(onClick = { viewModel.install(state.agent) }) {
                            Text(stringResource(R.string.agent_cli_install))
                        }
                    else -> {
                        TextButton(onClick = { viewModel.install(state.agent) }) {
                            Text(stringResource(R.string.agent_cli_reinstall))
                        }
                        TextButton(onClick = { viewModel.launch(state.agent) { onOpenTerminal() } }) {
                            Text(
                                stringResource(
                                    if (state.authHint != null) R.string.engine_go_login else R.string.agent_cli_launch
                                )
                            )
                        }
                    }
                }
            }
        )
        // 引擎行为选项：Codex 沙箱档位 / OpenCode 自动批准（全局生效，下一次发送起作用）
        when (state.agent.id) {
            "codex" -> {
                SettingsDivider()
                EngineOptionToggleRow(
                    title = stringResource(R.string.engine_codex_sandbox_toggle),
                    checked = codexSandboxFullAccess,
                    onCheckedChange = viewModel::setCodexSandboxFullAccess
                )
            }
            "opencode" -> {
                SettingsDivider()
                EngineOptionToggleRow(
                    title = stringResource(R.string.engine_opencode_auto_toggle),
                    checked = openCodeAutoApprove,
                    onCheckedChange = viewModel::setOpenCodeAutoApprove
                )
            }
        }
    }
}

/** CliAgentCatalog 的 agent id → 会话引擎标识。 */
private fun engineIdOf(agentId: String): String = when (agentId) {
    "claude" -> AgentEngines.CLAUDE_CODE
    "codex" -> AgentEngines.CODEX
    else -> AgentEngines.OPENCODE
}

/** 引擎行为选项开关行：标题（弱化色）+ 右侧开关；整行可点切换。 */
@Composable
private fun EngineOptionToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(
        title = title,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            AppSwitch(checked = checked, onCheckedChange = null)
        }
    )
}
