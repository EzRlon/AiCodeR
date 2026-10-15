package com.aicode.feature.schedule.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.presentation.component.AgentActionButton
import com.aicode.feature.agent.presentation.component.AgentActionTone
import com.aicode.feature.schedule.domain.ScheduledAuthCategory
import com.aicode.feature.schedule.domain.ScheduleMode
import com.aicode.feature.schedule.domain.TaskSchedule
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clock

/** 创建/排期面板的快捷时间档位（完整排期在设置页「定时任务」中配置）。 */
enum class QuickSchedule(val labelRes: Int) {
    PENDING(R.string.schedule_quick_pending),
    DAILY_9(R.string.schedule_quick_daily9),
    HOURLY(R.string.schedule_quick_hourly),
    EVERY_6H(R.string.schedule_quick_every6h);

    /** 转换为触发计划；[PENDING]（待排期）返回 null，由调用方落为 UNSET 模式。 */
    fun toTaskSchedule(): TaskSchedule? = when (this) {
        PENDING -> null
        DAILY_9 -> TaskSchedule(mode = ScheduleMode.DAILY, dailyHour = 9, dailyMinute = 0)
        HOURLY -> TaskSchedule(mode = ScheduleMode.INTERVAL, intervalMinutes = 60)
        EVERY_6H -> TaskSchedule(mode = ScheduleMode.INTERVAL, intervalMinutes = 360)
    }
}

/**
 * 「定时任务」创建面板（长按模式胶囊 / 顶栏长按选择层进入）：
 * 内容（预填输入草稿）、快捷时间、运行模式、预授权范围、自动档风险确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledAiTaskSheet(
    initialContent: String,
    initialMode: AgentMode,
    onDismiss: () -> Unit,
    onCreate: (content: String, mode: AgentMode, categories: Set<ScheduledAuthCategory>, quick: QuickSchedule) -> Unit
) {
    var content by rememberSaveable { mutableStateOf(initialContent) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var quick by rememberSaveable { mutableStateOf(QuickSchedule.PENDING) }
    var authRead by rememberSaveable { mutableStateOf(true) }
    var authNetwork by rememberSaveable { mutableStateOf(true) }
    var authWrite by rememberSaveable { mutableStateOf(false) }
    var authExecute by rememberSaveable { mutableStateOf(false) }
    var authDelete by rememberSaveable { mutableStateOf(false) }
    var autoRiskAccepted by rememberSaveable { mutableStateOf(false) }

    val categories = buildSet {
        if (authRead) add(ScheduledAuthCategory.READ)
        if (authNetwork) add(ScheduledAuthCategory.NETWORK)
        if (authWrite) add(ScheduledAuthCategory.WRITE)
        if (authExecute) add(ScheduledAuthCategory.EXECUTE)
        if (authDelete) add(ScheduledAuthCategory.DELETE)
    }
    val canCreate = content.isNotBlank() && (mode != AgentMode.AUTO || autoRiskAccepted)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SheetTitle(stringResource(R.string.schedule_ai_sheet_title))

            AppTextField(
                value = content,
                onValueChange = { content = it },
                label = stringResource(R.string.schedule_ai_content_label),
                placeholder = stringResource(R.string.schedule_ai_content_placeholder),
                singleLine = false,
                minLines = 3
            )

            SectionLabel(stringResource(R.string.schedule_ai_time_label))
            QuickScheduleChips(selected = quick, onSelect = { quick = it })

            SectionLabel(stringResource(R.string.schedule_ai_mode_label))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AgentMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(stringResource(modeLabelResForSheet(m))) }
                    )
                }
            }

            if (mode == AgentMode.AUTO) {
                // 自动档：执行时全放行，必须显式确认风险（决策 1）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.schedule_ai_auto_warn),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    AppSwitch(checked = autoRiskAccepted, onCheckedChange = { autoRiskAccepted = it })
                }
            } else {
                SectionLabel(stringResource(R.string.schedule_ai_auth_label))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AuthChip(stringResource(R.string.schedule_ai_auth_read), authRead) { authRead = it }
                    AuthChip(stringResource(R.string.schedule_ai_auth_network), authNetwork) { authNetwork = it }
                    AuthChip(stringResource(R.string.schedule_ai_auth_write), authWrite) { authWrite = it }
                    AuthChip(stringResource(R.string.schedule_ai_auth_execute), authExecute) { authExecute = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AuthChip(stringResource(R.string.schedule_ai_auth_delete), authDelete) { authDelete = it }
                    Text(
                        text = stringResource(R.string.schedule_ai_auth_delete_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AgentActionButton(
                text = stringResource(R.string.schedule_ai_create),
                onClick = {
                    onCreate(content.trim(), mode, categories, quick)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canCreate,
                tone = AgentActionTone.Success
            )
            Text(
                text = stringResource(R.string.schedule_ai_manage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 「定时执行计划」排期弹层（计划审批面板的「定时执行…」）：
 * 计划正文已由调用方存档；这里只选快捷时间与预授权范围。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleExecutionSheet(
    onDismiss: () -> Unit,
    onConfirm: (categories: Set<ScheduledAuthCategory>, quick: QuickSchedule) -> Unit
) {
    var quick by rememberSaveable { mutableStateOf(QuickSchedule.PENDING) }
    var authRead by rememberSaveable { mutableStateOf(true) }
    var authNetwork by rememberSaveable { mutableStateOf(true) }
    var authWrite by rememberSaveable { mutableStateOf(false) }
    var authExecute by rememberSaveable { mutableStateOf(false) }
    var authDelete by rememberSaveable { mutableStateOf(false) }

    val categories = buildSet {
        if (authRead) add(ScheduledAuthCategory.READ)
        if (authNetwork) add(ScheduledAuthCategory.NETWORK)
        if (authWrite) add(ScheduledAuthCategory.WRITE)
        if (authExecute) add(ScheduledAuthCategory.EXECUTE)
        if (authDelete) add(ScheduledAuthCategory.DELETE)
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SheetTitle(stringResource(R.string.schedule_exec_sheet_title))
            Text(
                text = stringResource(R.string.schedule_exec_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionLabel(stringResource(R.string.schedule_ai_time_label))
            QuickScheduleChips(selected = quick, onSelect = { quick = it })

            SectionLabel(stringResource(R.string.schedule_ai_auth_label))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AuthChip(stringResource(R.string.schedule_ai_auth_read), authRead) { authRead = it }
                AuthChip(stringResource(R.string.schedule_ai_auth_network), authNetwork) { authNetwork = it }
                AuthChip(stringResource(R.string.schedule_ai_auth_write), authWrite) { authWrite = it }
                AuthChip(stringResource(R.string.schedule_ai_auth_execute), authExecute) { authExecute = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AuthChip(stringResource(R.string.schedule_ai_auth_delete), authDelete) { authDelete = it }
                Text(
                    text = stringResource(R.string.schedule_ai_auth_delete_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            AgentActionButton(
                text = stringResource(R.string.schedule_exec_confirm),
                onClick = {
                    onConfirm(categories, quick)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                tone = AgentActionTone.Success
            )
        }
    }
}

/**
 * 「定时 Plan · 长任务」入口面板（长按 Plan 胶囊 / 顶栏长按选择层进入）：
 * 输入目标后进入「计划先行」流程——新开 PLAN 会话聊完整，再由审批面板排期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledPlanSheet(
    initialGoal: String,
    onDismiss: () -> Unit,
    onPrepare: (goal: String) -> Unit
) {
    var goal by rememberSaveable { mutableStateOf(initialGoal) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SheetTitle(stringResource(R.string.schedule_plan_sheet_title))
            Text(
                text = stringResource(R.string.schedule_plan_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppTextField(
                value = goal,
                onValueChange = { goal = it },
                label = stringResource(R.string.schedule_plan_goal_label),
                placeholder = stringResource(R.string.schedule_ai_content_placeholder),
                singleLine = false,
                minLines = 3
            )
            AgentActionButton(
                text = stringResource(R.string.schedule_plan_prepare),
                onClick = {
                    onPrepare(goal.trim())
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = goal.isNotBlank(),
                tone = AgentActionTone.Success
            )
            Text(
                text = stringResource(R.string.schedule_plan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = FeatherIcons.Clock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(Spacing.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 快捷时间选项：自动换行排列——四项在窄屏排不下时换到下一行，避免最后一个被挤出面板。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickScheduleChips(selected: QuickSchedule, onSelect: (QuickSchedule) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        QuickSchedule.entries.forEach { q ->
            FilterChip(
                selected = selected == q,
                onClick = { onSelect(q) },
                label = { Text(stringResource(q.labelRes)) }
            )
        }
    }
}

@Composable
private fun AuthChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label) }
    )
}

/** 三个面板共用的模式名映射（与底部胶囊文案一致）。 */
internal fun modeLabelResForSheet(mode: AgentMode): Int = when (mode) {
    AgentMode.BUILD -> R.string.agent_mode_build
    AgentMode.PLAN -> R.string.agent_mode_plan
    AgentMode.AUTO -> R.string.agent_mode_auto
}
