package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors

internal enum class AgentActionTone {
    Neutral,
    /** 高强调主操作（M3：一屏只应有一个），容器用 primary。 */
    Primary,
    Success,
    Danger
}

@Composable
internal fun AgentActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: AgentActionTone = AgentActionTone.Neutral
) {
    // M3 按钮层级靠容器色阶表达，不再描边：Primary=高强调，Neutral/Success/Danger=tonal
    val shape = RoundedCornerShape(Radius.sm)
    val success = MaterialTheme.semanticColors.success
    val (container, content) = when (tone) {
        AgentActionTone.Primary ->
            if (enabled) {
                MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        AgentActionTone.Success ->
            if (enabled) {
                success to MaterialTheme.semanticColors.onSuccess
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        AgentActionTone.Danger ->
            if (enabled) {
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        AgentActionTone.Neutral ->
            if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
    }

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
