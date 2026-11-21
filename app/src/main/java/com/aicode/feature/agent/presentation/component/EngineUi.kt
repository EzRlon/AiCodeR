package com.aicode.feature.agent.presentation.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Terminal

/** 会话引擎的显示名（engine 值 → UI 名称；null=内置引擎）。 */
@Composable
internal fun engineDisplayName(engine: String?): String = when (engine) {
    null -> stringResource(R.string.engine_builtin)
    AgentEngines.CLAUDE_CODE -> "Claude Code"
    AgentEngines.CODEX -> "Codex"
    AgentEngines.OPENCODE -> "OpenCode"
    AgentEngines.DEEPSEEK -> "DeepSeek"
    else -> engine
}

/** 会话列表徽章文案（内置引擎无徽章）。 */
internal fun engineBadgeLabel(engine: String?): String? = when (engine) {
    AgentEngines.CLAUDE_CODE -> "CC"
    AgentEngines.CODEX -> "CX"
    AgentEngines.OPENCODE -> "OC"
    AgentEngines.DEEPSEEK -> "DS"
    else -> null
}

/** 商店/选择器等窄空间的引擎短名（内置引擎有名字，外部引擎用双字徽章）。 */
internal fun engineShortLabel(engine: String?): String = engineBadgeLabel(engine) ?: "内置"

/**
 * 引擎品牌色（商店选择器、会话徽章等处的引擎识别色）。
 * 不随主题变：暗色下直接铺底 + 白字，亮色下降饱和处理由调用方控制透明度。
 */
internal fun engineBrandColor(engine: String?): Color = when (engine) {
    AgentEngines.CLAUDE_CODE -> Color(0xFFD97757)
    AgentEngines.CODEX -> Color(0xFF10A37F)
    AgentEngines.OPENCODE -> Color(0xFF8B7CF6)
    AgentEngines.DEEPSEEK -> Color(0xFF4D6BFE)
    else -> Color(0xFF5A7BD8)
}

/** 商店安装目标 → 会话引擎 id 的映射（品牌色/短名复用同源）。 */
internal fun EngineTarget.toEngineId(): String = when (this) {
    EngineTarget.CLAUDE_CODE -> AgentEngines.CLAUDE_CODE
    EngineTarget.CODEX -> AgentEngines.CODEX
    EngineTarget.OPENCODE -> AgentEngines.OPENCODE
    EngineTarget.DEEPSEEK -> AgentEngines.DEEPSEEK
}

internal fun engineShortLabel(target: EngineTarget): String = engineShortLabel(target.toEngineId())

internal fun engineBrandColor(target: EngineTarget): Color = engineBrandColor(target.toEngineId())

/** 引擎标识图标：内置=星形，CLI=品牌 logo / 终端图标。[tint] 为 null 时按次级文字色着色。 */
@Composable
internal fun EngineMarkIcon(
    engine: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    val iconTint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    when (engine) {
        AgentEngines.CLAUDE_CODE -> ModelLogoIcon(modelName = "claude", size = size, modifier = modifier)
        AgentEngines.CODEX -> ModelLogoIcon(modelName = "gpt", size = size, modifier = modifier)
        AgentEngines.OPENCODE -> Icon(
            FeatherIcons.Terminal,
            contentDescription = null,
            tint = iconTint,
            modifier = modifier.size(size)
        )
        AgentEngines.DEEPSEEK -> ModelLogoIcon(modelName = "deepseek", size = size, modifier = modifier)
        null -> Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = iconTint,
            modifier = modifier.size(size)
        )
        else -> Icon(
            FeatherIcons.Terminal,
            contentDescription = null,
            tint = iconTint,
            modifier = modifier.size(size)
        )
    }
}

/** 引擎切换选项（会话维度；null=内置引擎）。 */
internal val selectableEngines: List<String?> =
    listOf(null, AgentEngines.CLAUDE_CODE, AgentEngines.CODEX, AgentEngines.OPENCODE, AgentEngines.DEEPSEEK)

/**
 * 模型弹窗顶部的引擎切换行：四个胶囊一排，当前高亮。
 * 引擎入口收编在这里而非输入区控制行——控制行按钮已满一排，再进一个会换行成两排（用户反馈）。
 * 切换失败（回合进行中）弹 Toast。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EngineSwitcherRow(currentEngine: String?, onSetEngine: (String?) -> Boolean) {
    val context = LocalContext.current
    Column {
        Text(
            text = stringResource(R.string.engine_pick_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.size(6.dp))
        // FlowRow：四个胶囊一行放不下时整体换行，而不是把最后一个胶囊挤成竖排单字（用户反馈）。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            selectableEngines.forEach { engine ->
                val current = currentEngine == engine
                // 选中态用实心 primary 填充 + 对勾，与未选中的描边胶囊拉开明显层级：
                // 之前选中只是浅色底，在四个胶囊里几乎看不出当前是哪个（用户反馈）。
                val background = if (current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
                val labelColor = if (current) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(background)
                        .then(
                            if (current) Modifier
                            else Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(50)
                            )
                        )
                        .clickable(enabled = !current) {
                            if (onSetEngine(engine)) return@clickable
                            Toast.makeText(
                                context,
                                R.string.engine_switch_running,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(start = 10.dp, end = if (current) 8.dp else 10.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    EngineMarkIcon(engine = engine, size = 14.dp, tint = labelColor)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = engineDisplayName(engine),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        color = labelColor
                    )
                    if (current) {
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = labelColor,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
