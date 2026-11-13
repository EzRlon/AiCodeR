package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.model.ChatSession
import compose.icons.FeatherIcons

/**
 * 单条会话行：短按选中，长按弹出功能菜单（置顶/重命名/导出/删除）。供侧边栏历史记录列表复用。
 * 置顶会话显示浅蓝背景（primaryContainer）。
 * [trailing] 用于行尾额外控件（如子代理展开箭头），它自己的点击不应触发整行选中。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSessionRow(
    session: ChatSession,
    selected: Boolean,
    isExecuting: Boolean = false,
    awaitingPermission: Boolean = false,
    pinned: Boolean = false,
    /** 子代理行等更低层级：字号再降一档，与二级会话标题拉开层次。 */
    compact: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (pinned) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = Spacing.lg, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        if (awaitingPermission) {
            // 等待用户授权：橙色常亮圆点，优先级高于执行中的绿色脉冲，提示该会话已挂起待处理。
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.warning)
            )
            Spacer(Modifier.width(Spacing.md))
        } else if (isExecuting) {
            val transition = rememberInfiniteTransition(label = "tool-status-dot")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
                label = "tool-status-dot-alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.success.copy(alpha = alpha))
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    // 层级：分组标题(一级) > 会话标题(二级) > 子代理(三级，compact)
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (compact) 11.sp else 12.sp,
                        lineHeight = if (compact) 14.sp else 16.sp
                    ),
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary
                        compact -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (session.engine != null) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = engineBadgeLabel(session.engine).orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            // 会话 id 小字：与 ai-logs/session-<id>.log 等文件名逐字对应，方便手动到开发目录找内容。
            Text(
                text = session.id,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (compact) 9.sp else 10.sp),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}
