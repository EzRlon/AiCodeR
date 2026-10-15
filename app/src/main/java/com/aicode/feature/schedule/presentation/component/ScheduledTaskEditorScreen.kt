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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.aicode.core.theme.Radius
import com.aicode.core.ui.AppTextField
import com.aicode.feature.schedule.domain.ScheduleMode
import com.aicode.feature.schedule.domain.ScheduledTaskType
import com.aicode.feature.schedule.domain.TaskSchedule
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 定时任务新建/编辑页：自带顶栏（返回 + 右上角保存），与 [PromptEditorScreen] 一致，
 * 不能嵌进设置页的 Scaffold。
 *
 * 计划支持仅一次（MM-dd HH:mm）/每天固定时刻/固定间隔；AI 型任务的内容只读
 * （在会话入口创建），此处只编名称与排期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskEditorScreen(
    isNew: Boolean,
    initialName: String,
    initialCommand: String,
    initialSchedule: TaskSchedule,
    initialType: ScheduledTaskType = ScheduledTaskType.COMMAND,
    /** AI 型任务的只读内容（prompt 或计划正文）；命令型忽略。 */
    initialAiContent: String? = null,
    onSave: (name: String, command: String, schedule: TaskSchedule) -> Unit,
    onNavigateBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var command by rememberSaveable { mutableStateOf(initialCommand) }
    var mode by rememberSaveable {
        // 待排期任务进入编辑页：默认预选「每天」，保存时即落定
        mutableStateOf(if (initialSchedule.mode == ScheduleMode.UNSET) ScheduleMode.DAILY else initialSchedule.mode)
    }
    var timeText by rememberSaveable {
        mutableStateOf("%02d:%02d".format(initialSchedule.dailyHour, initialSchedule.dailyMinute))
    }
    var intervalText by rememberSaveable { mutableStateOf(initialSchedule.intervalMinutes.toString()) }
    var startAtText by rememberSaveable {
        mutableStateOf(
            initialSchedule.startAt?.let {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
            }.orEmpty()
        )
    }

    val parsedTime = parseTime(timeText)
    val interval = intervalText.toIntOrNull()
    val parsedStartAt = if (mode == ScheduleMode.ONCE) parseStartAt(startAtText) else null
    val canSave = name.isNotBlank() && when (mode) {
        ScheduleMode.ONCE -> parsedStartAt != null && parsedStartAt > System.currentTimeMillis()
        ScheduleMode.DAILY -> parsedTime != null
        ScheduleMode.INTERVAL -> interval != null && interval >= TaskSchedule.MIN_INTERVAL_MINUTES
        ScheduleMode.UNSET -> false
    } && (initialType != ScheduledTaskType.COMMAND || command.isNotBlank())

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
                                ScheduleMode.ONCE -> TaskSchedule(
                                    mode = ScheduleMode.ONCE,
                                    startAt = parsedStartAt ?: 0L
                                )
                                ScheduleMode.DAILY -> TaskSchedule(
                                    mode = ScheduleMode.DAILY,
                                    dailyHour = parsedTime?.first ?: 0,
                                    dailyMinute = parsedTime?.second ?: 0
                                )
                                ScheduleMode.INTERVAL -> TaskSchedule(
                                    mode = ScheduleMode.INTERVAL,
                                    intervalMinutes = interval ?: TaskSchedule.MIN_INTERVAL_MINUTES
                                )
                                ScheduleMode.UNSET -> initialSchedule
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
                    if (initialType == ScheduledTaskType.COMMAND) {
                        AppTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = stringResource(R.string.schedule_field_command),
                            placeholder = stringResource(R.string.schedule_field_command_placeholder),
                            singleLine = false,
                            minLines = 3
                        )
                    } else {
                        // AI 型任务：内容在会话入口创建，此处只读展示（改内容请新建任务）
                        Text(
                            text = stringResource(R.string.schedule_ai_content_readonly),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(Radius.sm),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Text(
                                text = initialAiContent.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 6,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.sm)
                            )
                        }
                    }
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
                            selected = mode == ScheduleMode.ONCE,
                            onClick = { mode = ScheduleMode.ONCE },
                            label = { Text(stringResource(R.string.schedule_mode_once)) }
                        )
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
                        ScheduleMode.ONCE -> AppTextField(
                            value = startAtText,
                            onValueChange = { startAtText = it.take(11) },
                            label = stringResource(R.string.schedule_field_start_at),
                            placeholder = stringResource(R.string.schedule_field_start_at_placeholder),
                            singleLine = true,
                            isError = !(parsedStartAt != null && parsedStartAt > System.currentTimeMillis())
                        )
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
                        ScheduleMode.UNSET -> Unit
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

/** 解析 "MM-dd HH:mm" 为 epoch millis（当年）；格式或日期不合法返回 null。 */
private fun parseStartAt(text: String): Long? {
    val parts = text.trim().split(Regex("[-:\\s]+")).filter { it.isNotEmpty() }
    if (parts.size != 4) return null
    val month = parts[0].toIntOrNull() ?: return null
    val day = parts[1].toIntOrNull() ?: return null
    val hour = parts[2].toIntOrNull() ?: return null
    val minute = parts[3].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
    val cal = Calendar.getInstance().apply { isLenient = false }
    return runCatching {
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }.getOrNull()
}
