package com.aicode.feature.settings.presentation.component

import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsRow
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.settings.presentation.MemoryError
import com.aicode.feature.settings.presentation.MemoryUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText

/**
 * 记忆管理页：说明 + 全局/项目两组记忆列表。
 *
 * 记忆名（对应文件名）即身份，行内展示名称与描述摘要；点击进入编辑，左滑删除。
 * 项目级需要已选工作区，未选择时给出提示。
 */
@Composable
internal fun MemorySection(
    state: MemoryUiState,
    error: MemoryError?,
    onErrorConsumed: () -> Unit,
    onOpenMemory: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(error) {
        if (error != null) {
            val messageRes = when (error) {
                MemoryError.SAVE_FAILED -> R.string.memory_save_failed
                MemoryError.DELETE_FAILED -> R.string.memory_delete_failed
            }
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            onErrorConsumed()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroup(modifier = Modifier.padding(top = Spacing.sm)) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.memory_page_hint_title),
                subtitle = stringResource(R.string.memory_page_hint)
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.memory_group_global))
        MemoryList(
            memories = state.global,
            emptyText = stringResource(R.string.memory_empty_global),
            onOpenMemory = onOpenMemory,
            onDelete = onDelete
        )

        SettingsGroupHeader(text = stringResource(R.string.memory_group_project))
        if (!state.hasWorkspace) {
            MemoryEmptyHint(stringResource(R.string.memory_no_workspace))
        } else {
            MemoryList(
                memories = state.project,
                emptyText = stringResource(R.string.memory_empty_project),
                onOpenMemory = onOpenMemory,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun MemoryList(
    memories: List<Memory>,
    emptyText: String,
    onOpenMemory: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    if (memories.isEmpty()) {
        MemoryEmptyHint(emptyText)
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            memories.forEach { memory ->
                key(memory.name) {
                    MemoryCard(
                        memory = memory,
                        onClick = { onOpenMemory(memory) },
                        onDelete = { onDelete(memory) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 4.dp)
    )
}

/** 单条记忆卡片：图标 + 名称 + 描述摘要 + 箭头；左滑删除。 */
@Composable
private fun MemoryCard(
    memory: Memory,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.md, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (memory.description.isNotBlank()) {
                    Text(
                        text = memory.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        SwipeToDeleteRow(onDelete = onDelete, onClick = onClick, deleteEnabled = true) { row() }
    }
}
