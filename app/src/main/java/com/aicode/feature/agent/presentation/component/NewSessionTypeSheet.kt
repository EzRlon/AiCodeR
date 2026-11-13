package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.settings.SettingsDivider
import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Calendar
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Plus

/**
 * 顶栏「新建会话」长按的选择层：新建会话（=单击）/ 定时任务 / 定时 Plan·长任务。
 * 单击仍走原有「立即新建」，长按弹本层兜住误触（见 scheduled-ai-task-plan §2.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewSessionTypeSheet(
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onScheduledTask: () -> Unit,
    onScheduledPlan: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.chat_new_session_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.chat_new_session),
                    subtitle = stringResource(R.string.chat_new_session_sheet_now),
                    onClick = {
                        onDismiss()
                        onNewSession()
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Clock,
                    title = stringResource(R.string.schedule_ai_sheet_title),
                    subtitle = stringResource(R.string.chat_new_session_sheet_scheduled),
                    onClick = {
                        onDismiss()
                        onScheduledTask()
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Calendar,
                    title = stringResource(R.string.schedule_plan_sheet_title),
                    subtitle = stringResource(R.string.chat_new_session_sheet_plan),
                    onClick = {
                        onDismiss()
                        onScheduledPlan()
                    }
                )
            }
        }
    }
}
