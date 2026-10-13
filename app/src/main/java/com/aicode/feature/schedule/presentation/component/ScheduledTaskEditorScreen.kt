package com.aicode.feature.schedule.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import compose.icons.feathericons.Check
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.schedule.domain.ScheduleMode
import com.aicode.feature.schedule.domain.TaskSchedule
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 定时任务新建/编辑页：自带顶栏（返回 + 右上角保存），与 [PromptEditorScreen] 一致，
 * 不能嵌进设置页的 Scaffold。
 *
 * 计划有两种模式：每天固定时刻，或固定间隔。间隔下限取 WorkManager 允许的最小周期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskEditorScreen(
    isNew: Boolean,
    initialName: String,
    initialCommand: String,
    initialSchedule: TaskSchedule,
    onSave: (name: String, command: String, schedule: TaskSchedule) -> Unit,
    onNavigateBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var command by rememberSaveable { mutableStateOf(initialCommand) }
    var mode by rememberSaveable { mutableStateOf(initialSchedule.mode) }
    var timeText by rememberSaveable {
        mutableStateOf("%02d:%02d".format(initialSchedule.dailyHour, initialSchedule.dailyMinute))
    }
    var intervalText by rememberSaveable { mutableStateOf(initialSchedule.intervalMinutes.toString()) }

    val parsedTime = parseTime(timeText)
    val interval = intervalText.toIntOrNull()
    val canSave = name.isNotBlank() && command.isNotBlank() && when (mode) {
        ScheduleMode.DAILY -> parsedTime != null
        ScheduleMode.INTERVAL -> interval != null && interval >= TaskSchedule.MIN_INTERVAL_MINUTES
    }

    BackHandler { onNavigateBack() }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.schedule_editor_new else R.string.schedule_editor_edit
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = canSave,
                        onClick = {
                            val schedule = when (mode) {
                                ScheduleMode.DAILY -> TaskSchedule(
                                    mode = ScheduleMode.DAILY,
                                    dailyHour = parsedTime?.first ?: 0,
                                    dailyMinute = parsedTime?.second ?: 0
                                )
                                ScheduleMode.INTERVAL -> TaskSchedule(
                                    mode = ScheduleMode.INTERVAL,
                                    intervalMinutes = interval ?: TaskSchedule.MIN_INTERVAL_MINUTES
                                )
                            }
                            onSave(name.trim(), command.trim(), schedule)
                        }
                    ) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = stringResource(R.string.common_save),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.schedule_field_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.schedule_field_name),
                        placeholder = stringResource(R.string.schedule_field_name_placeholder),
                        singleLine = true
                    )
                    AppTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = stringResource(R.string.schedule_field_command),
                        placeholder = stringResource(R.string.schedule_field_command_placeholder),
                        singleLine = false,
                        minLines = 3
                    )
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.schedule_field_plan))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        FilterChip(
                            selected = mode == ScheduleMode.DAILY,
                            onClick = { mode = ScheduleMode.DAILY },
                            label = { Text(stringResource(R.string.schedule_mode_daily)) }
                        )
                        FilterChip(
                            selected = mode == ScheduleMode.INTERVAL,
                            onClick = { mode = ScheduleMode.INTERVAL },
                            label = { Text(stringResource(R.string.schedule_mode_interval)) }
                        )
                    }
                    when (mode) {
                        ScheduleMode.DAILY -> AppTextField(
                            value = timeText,
                            onValueChange = { timeText = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                            label = stringResource(R.string.schedule_field_time),
                            placeholder = stringResource(R.string.schedule_field_time_placeholder),
                            singleLine = true,
                            isError = parsedTime == null
                        )
                        ScheduleMode.INTERVAL -> AppTextField(
                            value = intervalText,
                            onValueChange = { intervalText = it.filter { ch -> ch.isDigit() }.take(4) },
                            label = stringResource(R.string.schedule_field_interval),
                            placeholder = stringResource(R.string.schedule_field_interval_placeholder),
                            singleLine = true,
                            isError = !(interval != null && interval >= TaskSchedule.MIN_INTERVAL_MINUTES)
                        )
                    }
                }
            }
        }
    }
}

/** 解析 "HH:mm"；格式或范围不合法返回 null。 */
private fun parseTime(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}
