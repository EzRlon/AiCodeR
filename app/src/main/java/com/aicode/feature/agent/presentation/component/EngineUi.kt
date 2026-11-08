package com.aicode.feature.agent.presentation.component

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal

/** 会话引擎的显示名（engine 值 → UI 名称；null=内置引擎）。 */
@Composable
internal fun engineDisplayName(engine: String?): String = when (engine) {
    null -> stringResource(R.string.engine_builtin)
    AgentEngines.CLAUDE_CODE -> "Claude Code"
    AgentEngines.CODEX -> "Codex"
    AgentEngines.OPENCODE -> "OpenCode"
    else -> engine
}

/** 会话列表徽章文案（内置引擎无徽章）。 */
internal fun engineBadgeLabel(engine: String?): String? = when (engine) {
    AgentEngines.CLAUDE_CODE -> "CC"
    AgentEngines.CODEX -> "CX"
    AgentEngines.OPENCODE -> "OC"
    else -> null
}

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

/** 引擎切换四选项（会话维度；null=内置引擎）。 */
internal val selectableEngines: List<String?> =
    listOf(null, AgentEngines.CLAUDE_CODE, AgentEngines.CODEX, AgentEngines.OPENCODE)

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
                val background = if (current) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
                val labelColor = if (current) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(background, RoundedCornerShape(50))
                        .clickable {
                            if (current || onSetEngine(engine)) return@clickable
                            Toast.makeText(
                                context,
                                R.string.engine_switch_running,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    EngineMarkIcon(engine = engine, size = 14.dp, tint = labelColor)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = engineDisplayName(engine),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        color = labelColor
                    )
                }
            }
        }
    }
}
