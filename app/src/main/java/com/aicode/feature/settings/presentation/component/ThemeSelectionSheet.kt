package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.AppThemePreset
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.data.repository.AppThemeMode
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * 外观主题面板：明暗模式、配色方案与莫奈取色。
 *
 * 选择后不关闭弹窗，便于连续对比几套配色。
 *
 * @param selectedPresetId 当前配色方案 id，null 表示未选过（回退默认）。
 * @param dynamicColorEnabled 莫奈取色开关状态；开启时配色方案不可选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSelectionSheet(
    selected: AppThemeMode,
    selectedPresetId: String?,
    dynamicColorEnabled: Boolean,
    scheduleStart: String,
    scheduleEnd: String,
    onSelected: (AppThemeMode) -> Unit,
    onPresetSelected: (String) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onScheduleChanged: (start: String, end: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activePreset = AppThemePreset.findById(selectedPresetId)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            SectionLabel(stringResource(R.string.theme_mode_section))

            // 明暗模式一排胶囊按钮（与会话模型按钮同风格；打钩列表在新增定时/极黑后过长）
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    Surface(
                        onClick = { onSelected(mode) },
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = stringResource(mode.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 定时模式的深色时段编辑（跨午夜支持：start > end 视为跨夜区间）
            if (selected == AppThemeMode.SCHEDULED) {
                var picking: String? by remember { mutableStateOf(null) }
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 「跨午夜支持」从「跨」处换行成两排短字，与右侧时间按钮的高度匹配更协调
                    Text(
                        text = stringResource(R.string.theme_schedule_desc).replace("跨", "\n跨"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    // 紧凑化：与正文高度一致的小号时间按钮（过大显突兀，用户反馈）
                    OutlinedButton(
                        onClick = { picking = "start" },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(scheduleStart, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Text(
                        text = " → ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { picking = "end" },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(scheduleEnd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                picking?.let { which ->
                    val initial = runCatching {
                        java.time.LocalTime.parse(if (which == "start") scheduleStart else scheduleEnd)
                    }.getOrDefault(java.time.LocalTime.of(19, 0))
                    val pickerState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
                    AlertDialog(
                        onDismissRequest = { picking = null },
                        confirmButton = {
                            TextButton(onClick = {
                                val value = String.format("%02d:%02d", pickerState.hour, pickerState.minute)
                                if (which == "start") onScheduleChanged(value, scheduleEnd)
                                else onScheduleChanged(scheduleStart, value)
                                picking = null
                            }) { Text(stringResource(android.R.string.ok)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { picking = null }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                        text = { TimePicker(state = pickerState) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(Spacing.md))

            SectionLabel(stringResource(R.string.theme_preset_section))

            // 每排固定 4 个：8 套配色整齐排成两排（用户反馈：组成两排）
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .alpha(if (dynamicColorEnabled || selected == AppThemeMode.BLACK) 0.4f else 1f),
                maxItemsInEachRow = 4,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppThemePreset.ALL_PRESETS.forEach { preset ->
                    PresetChip(
                        preset = preset,
                        darkTheme = isDarkTheme,
                        isSelected = preset.id == activePreset.id,
                        enabled = !dynamicColorEnabled && selected != AppThemeMode.BLACK,
                        onSelect = { onPresetSelected(preset.id) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (selected == AppThemeMode.BLACK) 0.4f else 1f)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.theme_dynamic_color),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.theme_dynamic_color_desc),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                AppSwitch(
                    checked = dynamicColorEnabled && selected != AppThemeMode.BLACK,
                    onCheckedChange = { if (selected != AppThemeMode.BLACK) onDynamicColorChanged(it) },
                    enabled = selected != AppThemeMode.BLACK
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.sm)
    )
}

/** 配色方案色块：外圈是该主题的页面底色，内圈是主色，让浅色/深色主题一眼可辨。 */
@Composable
private fun PresetChip(
    preset: AppThemePreset,
    darkTheme: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    val (accentColor, canvasColor) = preset.previewColors(darkTheme)
    val shape = RoundedCornerShape(Radius.pill)

    Surface(
        modifier = Modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onSelect)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = shape
            ),
        shape = shape,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.sm, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(canvasColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(preset.nameRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
