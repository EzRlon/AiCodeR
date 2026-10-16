package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
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
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import com.aicode.feature.settings.presentation.component.ModelSelectionSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

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
    onSheetDismiss: (() -> Unit)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        ModelLogoIcon(modelName = provider?.effectiveModel.orEmpty(), size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSheet(
            providers = providers,
            currentProviderId = provider?.id ?: "",
            currentModel = provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
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
 * 模型选择弹窗：复用设置页默认模型的 [ModelSelectionSheet]（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 */
@Composable
internal fun ModelSheet(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    ModelSelectionSheet(
        title = stringResource(R.string.common_model),
        noModelsText = stringResource(R.string.chat_no_models_hint),
        providers = providers,
        currentProviderId = currentProviderId,
        currentModel = currentModel,
        modelMetadata = modelMetadata,
        onSelect = onSelect,
        onClear = null,
        onDismiss = onDismiss
    )
}
