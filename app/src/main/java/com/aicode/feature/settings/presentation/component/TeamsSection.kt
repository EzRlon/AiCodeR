package com.aicode.feature.settings.presentation.component

import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsRow
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.util.containsCjk
import com.aicode.feature.agent.domain.team.TeamDefinitionScope
import com.aicode.feature.settings.presentation.TeamUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText

/**
 * 「专家团」设置区：列出全部专家团定义（内置释放到 `~/.aicode/teams` 的与项目级 `.aicode/teams` 的），
 * 每项可单独启用/禁用与删除。新增/编辑直接写 `teams/<name>.md`：frontmatter 含
 * name/description/members，正文为团长提示词。
 *
 * 排版：卡片式条目——标题行（名称 + 中文名）置顶，操作（开关/删除）与标题行对齐，
 * 描述长文 3 行折叠，文件路径弱化沉底。
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
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md)) {
                teams.forEach { entry ->
                    SwipeToDeleteRow(
                        onDelete = { pendingDelete = entry }
                    ) {
                        TeamCard(
                            entry = entry,
                            onToggle = { onToggle(entry, it) }
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_teams_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.md, start = Spacing.xs)
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

/** 单个专家团卡片（删除交互由外层 SwipeToDeleteRow 提供：左滑露出删除按钮）。 */
@Composable
private fun TeamCard(
    entry: TeamUiEntry,
    onToggle: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = entry.displayName?.takeIf { it.containsCjk() }
        ?: entry.nameZh.takeIf { it.containsCjk() }
        ?: entry.name
    val description = when {
        entry.description.containsCjk() -> entry.description
        entry.descriptionZh.containsCjk() -> entry.descriptionZh
        else -> stringResource(R.string.team_description_unavailable)
    }
    val membersText = stringResource(R.string.team_members_count, entry.memberCount)

    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .animateContentSize()
        ) {
            // 标题行：名称/中文名 + 成员徽章，右侧操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.disabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        // 中文展示名与英文标识不同时，把标识作为次级信息保留，便于对账文件
                        if (displayName != entry.name) {
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                AppSwitch(checked = !entry.disabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(Spacing.sm))

            // 元信息行：成员数徽章 + 来源作用域
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = membersText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(Radius.pill)
                        )
                        .padding(horizontal = Spacing.sm, vertical = 2.dp)
                )
                Text(
                    text = when (entry.scope) {
                        TeamDefinitionScope.GLOBAL -> stringResource(R.string.team_scope_global)
                        TeamDefinitionScope.PROJECT -> stringResource(R.string.team_scope_project)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(Radius.pill)
                        )
                        .padding(horizontal = Spacing.sm, vertical = 2.dp)
                )
            }

            // 描述：长文 3 行折叠，点击展开/收起
            if (description.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
                if (!expanded && description.length > 60) {
                    Text(
                        text = stringResource(R.string.common_expand),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { expanded = true }
                    )
                }
            }

            // 文件路径：弱化沉底（mono 小字 + 图标）
            if (entry.filePath != null) {
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.FileText,
                        contentDescription = null,
                        tint = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = entry.filePath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.semanticColors.subtleText,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                }
            }
        }
    }
}
