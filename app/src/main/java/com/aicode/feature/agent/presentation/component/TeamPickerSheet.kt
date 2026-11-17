package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.aicode.core.util.containsCjk
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.domain.team.TeamDefinitionEntry
import com.aicode.feature.agent.domain.team.TeamDefinitionScope
import com.aicode.feature.agent.domain.team.TeamMemberNaming
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.StoreTranslationStore
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Users

/**
 * 专家团选择面板：列出全部专家团（内置释放的 + 全局 + 项目级），选中后本会话注入团长指令；
 * 「不使用专家团」清除。选择即刻生效于后续回合。
 *
 * 排版要点（2026-11-15 反馈 ①）：
 * - 按来源分组（内置 / 全局 / 项目），团多起来后不再混排；
 * - 卡片化 + 选中主色边框，选中态一眼可辨（不再只靠文字变色）；
 * - 卡片内直接铺成员胶囊，序号即建议派发顺序（对应 TeamDefinition.lifecycle 的次序）。
 *
 * @param memberNameOf 成员标识 → 显示名（由宿主注入 TeamMemberNaming，保证与横幅/消息流同源）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamPickerSheet(
    teams: List<TeamDefinitionEntry>,
    translations: Map<String, StoreTranslation>,
    currentTeam: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    memberNameOf: (String) -> String = { it }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.semanticColors.pageBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.team_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
            NoTeamRow(selected = currentTeam == null, onClick = { onSelect(null) })
            HorizontalDivider(
                color = MaterialTheme.semanticColors.subtleBorder,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = Spacing.xs)
            )
            if (teams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.team_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 内置（无 filePath）= 随包释放的团，恒在最前；其后按作用域分全局/项目。
                val builtin = teams.filter { it.definition.filePath == null }
                val global = teams.filter { it.definition.filePath != null && it.scope == TeamDefinitionScope.GLOBAL }
                val project = teams.filter { it.definition.filePath != null && it.scope == TeamDefinitionScope.PROJECT }
                val groups = listOf(
                    stringResource(R.string.team_picker_group_builtin) to builtin,
                    stringResource(R.string.team_picker_group_global) to global,
                    stringResource(R.string.team_picker_group_project) to project
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    groups.forEach { (title, group) ->
                        if (group.isEmpty()) return@forEach
                        item(key = "header-$title") { GroupHeader(title) }
                        items(group, key = { it.definition.name }) { entry ->
                            TeamCard(
                                entry = entry,
                                translations = translations,
                                selected = currentTeam != null &&
                                    entry.definition.name.equals(currentTeam, ignoreCase = true),
                                memberNameOf = memberNameOf,
                                onClick = { onSelect(entry.definition.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「不使用专家团」行：清除绑定。 */
@Composable
private fun NoTeamRow(selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.team_picker_none),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 分组标题（内置 / 全局 / 项目）。 */
@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.semanticColors.subtleText,
        modifier = Modifier.padding(start = Spacing.sm, top = Spacing.sm, bottom = Spacing.xs)
    )
}

/** 单个专家团卡片：图标 + 团名 + 描述 + 成员胶囊，选中时主色边框并显示勾。 */
@Composable
private fun TeamCard(
    entry: TeamDefinitionEntry,
    translations: Map<String, StoreTranslation>,
    selected: Boolean,
    memberNameOf: (String) -> String,
    onClick: () -> Unit
) {
    val team = entry.definition
    val translation = StoreTranslationStore.find(
        translations,
        "EXPERT:${team.filePath?.substringAfterLast('/').orEmpty()}",
        team.name,
        team.description
    )
    // 本地化纪律：展示名优先用定义里的中文名，其次只接受中文译文；描述同理。
    // 中文界面下绝不把英文论文摘要之类的未本地化文本直接铺给用户。
    val displayName = team.displayName?.takeIf { it.containsCjk() }
        ?: translation?.nameZh?.takeIf { it.isNotBlank() && it.containsCjk() }
        ?: team.name
    val displayDescription = when {
        team.description.containsCjk() -> team.description
        translation?.descriptionZh?.containsCjk() == true -> translation.descriptionZh
        else -> stringResource(R.string.team_description_unavailable)
    }
    val accent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .then(
                if (selected) Modifier.border(1.5.dp, accent, RoundedCornerShape(Radius.mdLarge))
                else Modifier.border(1.dp, MaterialTheme.semanticColors.subtleBorder, RoundedCornerShape(Radius.mdLarge))
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = if (selected) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Users,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.team_members_count, team.members.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1
                )
                if (selected) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (displayDescription.isNotBlank()) {
                Text(
                    text = displayDescription,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (team.members.isNotEmpty()) {
                // 成员胶囊：序号即建议派发顺序，让用户在选中前就知道这支队伍由谁组成。
                Text(
                    text = team.members.mapIndexed { index, id ->
                        "${index + 1}${memberNameOf(id)}"
                    }.joinToString("  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
