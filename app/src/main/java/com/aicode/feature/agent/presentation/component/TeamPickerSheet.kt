package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.domain.team.TeamDefinitionEntry
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.StoreTranslationStore
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * 专家团选择面板：列出全部专家团（内置释放的 + 全局 + 项目级），选中后本会话注入团长指令；
 * 「不使用专家团」清除。选择即刻生效于后续回合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamPickerSheet(
    teams: List<TeamDefinitionEntry>,
    translations: Map<String, StoreTranslation>,
    currentTeam: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(null) }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.team_picker_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (currentTeam == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (currentTeam == null) {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(teams, key = { it.definition.name }) { entry ->
                        val team = entry.definition
                        val translation = StoreTranslationStore.find(
                            translations,
                            "EXPERT:${team.filePath?.substringAfterLast('/').orEmpty()}",
                            team.name,
                            team.description
                        )
                        val displayName = translation?.nameZh?.takeIf { it.isNotBlank() }.orEmpty()
                        val displayDescription =
                            translation?.descriptionZh?.takeIf { it.isNotBlank() } ?: team.description
                        val selected = currentTeam != null && team.name.equals(currentTeam, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(team.name) }
                                .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = team.name +
                                        (if (displayName.isNotEmpty()) "（$displayName）" else "") +
                                        " · " + stringResource(R.string.team_members_count, team.members.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (displayDescription.isNotBlank()) {
                                    Text(
                                        text = displayDescription,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.semanticColors.subtleText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    imageVector = FeatherIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.semanticColors.subtleBorder,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = Spacing.xs)
                        )
                    }
                }
            }
        }
    }
}
