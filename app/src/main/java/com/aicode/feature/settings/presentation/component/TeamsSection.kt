package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.presentation.TeamUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2

/**
 * 「专家团」设置区：列出全部专家团定义（内置释放到 `~/.aicode/teams` 的与项目级 `.aicode/teams` 的），
 * 每项可单独启用/禁用与删除。新增/编辑直接写 `teams/<name>.md`：frontmatter 含
 * name/description/members，正文为团长提示词。
 */
@Composable
internal fun TeamsSection(
    teams: List<TeamUiEntry>,
    onToggle: (TeamUiEntry, Boolean) -> Unit,
    onDelete: (TeamUiEntry) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<TeamUiEntry?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_teams))
        if (teams.isEmpty()) {
            SettingsGroup {
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.settings_teams_empty),
                    subtitle = stringResource(R.string.settings_teams_empty_desc)
                )
            }
        } else {
            SettingsGroup {
                teams.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    val membersText = stringResource(R.string.team_members_count, entry.memberCount)
                    SettingsRow(
                        icon = null,
                        title = buildString {
                            append(entry.name)
                            if (entry.nameZh.isNotBlank()) append("（${entry.nameZh}）")
                            append(" · ")
                            append(membersText)
                        },
                        subtitle = buildString {
                            val description = entry.descriptionZh.ifBlank { entry.description }
                            if (description.isNotBlank()) append(description)
                            if (entry.filePath != null) {
                                if (isNotEmpty()) append("\n")
                                append(entry.filePath)
                            }
                        },
                        showChevron = false,
                        trailing = {
                            TeamActions(
                                enabled = !entry.disabled,
                                onToggle = { onToggle(entry, it) },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_teams_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs)
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_team_delete_title)) },
            text = { Text(stringResource(R.string.settings_team_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 行尾操作：启用开关 + 删除。 */
@Composable
private fun RowScope.TeamActions(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    AppSwitch(
        checked = enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.align(Alignment.CenterVertically)
    )
    IconButton(
        onClick = onDelete,
        modifier = Modifier
            .size(32.dp)
            .align(Alignment.CenterVertically)
    ) {
        Icon(
            FeatherIcons.Trash2,
            contentDescription = stringResource(R.string.common_delete),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
    }
}
