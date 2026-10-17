package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing

/**
 * 交接提示面板：上下文占用达到阈值时出现在输入框上方。
 * - 软阈值：用户选「暂不」或「交接开新会话」；
 * - 硬阈值（paused）：会话已暂停，提供「复制交接内容」与「交接开新会话」，并保留手动继续出口。
 * 样式对齐 [BudgetApprovalPanel]。
 */
@Composable
internal fun HandoffPromptPanel(
    percent: Int,
    generating: Boolean,
    paused: Boolean,
    preparingCopy: Boolean,
    onConfirm: () -> Unit,
    onCopy: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(if (paused) R.string.handoff_paused_title else R.string.handoff_prompt_title, percent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(if (paused) R.string.handoff_paused_detail else R.string.handoff_prompt_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (paused) {
                    AgentActionButton(
                        text = if (preparingCopy) stringResource(R.string.handoff_copying)
                        else stringResource(R.string.handoff_copy_action),
                        onClick = onCopy,
                        modifier = Modifier.weight(1f),
                        enabled = !generating && !preparingCopy,
                        tone = AgentActionTone.Neutral
                    )
                } else {
                    AgentActionButton(
                        text = stringResource(R.string.handoff_prompt_dismiss),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !generating && !preparingCopy,
                        tone = AgentActionTone.Neutral
                    )
                }
                AgentActionButton(
                    text = if (generating) stringResource(R.string.handoff_prompt_generating)
                    else stringResource(R.string.handoff_prompt_action),
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = !generating && !preparingCopy,
                    tone = AgentActionTone.Success
                )
            }
            if (paused) {
                TextButton(
                    onClick = onContinue,
                    enabled = !generating && !preparingCopy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.handoff_continue_anyway),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
