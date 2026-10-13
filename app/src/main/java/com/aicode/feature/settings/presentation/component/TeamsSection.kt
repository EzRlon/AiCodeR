package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.team.TeamDefinitionEntry

/**
 * 「编队」设置区：只读展示全部编队定义（内置释放到 `~/.aicode/teams` 的与项目级 `.aicode/teams` 的）。
 * 新增/编辑直接写 `teams/<name>.md`：frontmatter 含 name/description/members，正文为团长提示词。
 */
@Composable
internal fun TeamsSection(teams: List<TeamDefinitionEntry>) {
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
                    val team = entry.definition
                    SettingsRow(
                        icon = null,
                        title = team.name + " · " + stringResource(R.string.team_members_count, team.members.size),
                        subtitle = buildString {
                            if (team.description.isNotBlank()) append(team.description)
                            if (team.filePath != null) {
                                if (isNotEmpty()) append("\n")
                                append(team.filePath)
                            }
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
}
