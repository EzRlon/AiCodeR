package com.aicode.feature.schedule.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.schedule.domain.ScheduleMode
import com.aicode.feature.schedule.domain.ScheduledTask
import com.aicode.feature.schedule.presentation.ScheduledTasksUiState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务页：说明 + 任务卡片列表。
 *
 * 每张卡片显示名称、触发计划与上次执行结果；开关即时启停，点击进入编辑，左滑删除。
 */
@Composable
internal fun ScheduledTasksSection(
    state: ScheduledTasksUiState,
    onOpenTask: (ScheduledTask) -> Unit,
    onToggle: (ScheduledTask, Boolean) -> Unit,
    onDelete: (ScheduledTask) -> Unit
) {
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
                title = stringResource(R.string.schedule_page_hint_title),
                subtitle = stringResource(R.string.schedule_page_hint)
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.schedule_group_tasks))
        when {
            // 首帧：DataStore 尚未读出，不显示空态，避免闪一下「还没有任务」
            state.loading -> Unit
            state.tasks.isEmpty() -> Text(
                text = stringResource(R.string.schedule_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 4.dp)
            )
            else -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.tasks.forEach { task ->
                    key(task.id) {
                        TaskCard(
                            task = task,
                            onClick = { onOpenTask(task) },
                            onToggle = { enabled -> onToggle(task, enabled) },
                            onDelete = { onDelete(task) }
                        )
                    }
                }
            }
        }
    }
}

/** 单条任务卡片：名称 + 计划/上次结果 + 启停开关；点击编辑，左滑删除。 */
@Composable
private fun TaskCard(
    task: ScheduledTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val subtitle = when (task.schedule.mode) {
        ScheduleMode.DAILY -> stringResource(
            R.string.schedule_daily_at,
            "%02d:%02d".format(task.schedule.dailyHour, task.schedule.dailyMinute)
        )
        ScheduleMode.INTERVAL ->
            stringResource(R.string.schedule_every_minutes, task.schedule.intervalMinutes)
    }

    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.md, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LastRunText(task)
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            AppSwitch(checked = task.enabled, onCheckedChange = onToggle)
        }
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.semanticColors.cardSurface) {
        SwipeToDeleteRow(onDelete = onDelete, onClick = onClick, deleteEnabled = true) { row() }
    }
}

/** 上次执行结果摘要；从未执行时不显示。 */
@Composable
private fun LastRunText(task: ScheduledTask) {
    val runAt = task.lastRunAt ?: return
    val time = remember(runAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(runAt))
    }
    val status = when (task.lastExitCode) {
        null -> stringResource(R.string.schedule_last_unknown)
        0 -> stringResource(R.string.schedule_last_success)
        else -> stringResource(R.string.schedule_last_failed, task.lastExitCode)
    }
    Text(
        text = stringResource(R.string.schedule_last_run, time, status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
