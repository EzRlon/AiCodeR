package com.aicode.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.agent.domain.script.ScriptInfo
import com.aicode.feature.agent.domain.script.ScriptRepository
import com.aicode.feature.agent.presentation.component.ToolSection
import com.aicode.feature.settings.presentation.ScriptsError
import com.aicode.feature.settings.presentation.ScriptsUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Play
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 脚本管理页：说明 + 脚本站清单。
 *
 * 每行显示名称、解释器、描述（正文首条注释）与上次运行；点击进文件编辑器，行内播放键运行
 * （输出内联在卡片下方，长输出由 [ToolSection] 折叠加展开），左滑删除。
 * 当前执行环境不支持脚本时（远程 SSH），整页只给一句说明。
 */
@Composable
internal fun ScriptsSection(
    state: ScriptsUiState,
    error: ScriptsError?,
    onErrorConsumed: () -> Unit,
    onOpenScript: (ScriptInfo) -> Unit,
    onRun: (ScriptInfo) -> Unit,
    onDelete: (ScriptInfo) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(error) {
        if (error != null) {
            val messageRes = when (error) {
                ScriptsError.CREATE_FAILED -> R.string.scripts_create_failed
                ScriptsError.DELETE_FAILED -> R.string.scripts_delete_failed
                ScriptsError.RUN_FAILED -> R.string.scripts_run_failed
            }
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            onErrorConsumed()
        }
    }

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
                title = stringResource(R.string.scripts_hint_title),
                subtitle = state.unavailableReason ?: stringResource(R.string.scripts_hint)
            )
        }

        when {
            state.unavailableReason != null -> Unit
            // 首次加载还没回来：不渲染空态，否则会闪一下「还没有脚本」
            state.loading -> Unit
            state.scripts.isEmpty() -> ScriptsEmptyHint(stringResource(R.string.scripts_empty))
            else -> {
                SettingsGroupHeader(text = stringResource(R.string.scripts_group))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.scripts.forEach { script ->
                        key(script.name) {
                            val run = state.runs[script.name]
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ScriptCard(
                                    script = script,
                                    running = run?.running == true,
                                    onClick = { onOpenScript(script) },
                                    onRun = { onRun(script) },
                                    onDelete = { onDelete(script) }
                                )
                                val output = run?.output.orEmpty()
                                if (output.isNotEmpty()) {
                                    ToolSection(
                                        label = run?.exitCode?.let {
                                            stringResource(R.string.scripts_run_output_with_exit, it)
                                        } ?: stringResource(R.string.scripts_run_output),
                                        content = output
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptsEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 4.dp)
    )
}

/** 单个脚本卡片：图标 + 名称 + 描述 + 解释器与上次运行；行内播放键运行，左滑删除。 */
@Composable
private fun ScriptCard(
    script: ScriptInfo,
    running: Boolean,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.sm, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                script.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = scriptRunSummary(script),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            IconButton(onClick = onRun, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = FeatherIcons.Play,
                        contentDescription = stringResource(R.string.scripts_run),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.semanticColors.cardSurface) {
        SwipeToDeleteRow(onDelete = onDelete, onClick = onClick, deleteEnabled = !running) { row() }
    }
}

/** 解释器与上次运行的一行摘要。 */
@Composable
private fun scriptRunSummary(script: ScriptInfo): String {
    val lastRun = script.lastRunAt
    return when {
        lastRun == null -> "${script.interpreter} · ${stringResource(R.string.scripts_never_run)}"
        script.lastExitCode != null -> stringResource(
            R.string.scripts_last_run_with_exit,
            script.interpreter,
            formatScriptTime(lastRun),
            script.lastExitCode
        )
        else -> "${script.interpreter} · " + stringResource(R.string.scripts_last_run, formatScriptTime(lastRun))
    }
}

/** 新建脚本弹窗：只收名称——后缀决定语言，正文随后在文件编辑器里写。 */
@Composable
internal fun ScriptNewDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val invalid = trimmed.isNotEmpty() && !ScriptRepository.isValidName(trimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_new_title)) },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.scripts_new_name_label),
                    isError = invalid,
                    colors = dialogTextFieldColors()
                )
                Text(
                    text = if (invalid) {
                        stringResource(R.string.scripts_new_invalid_name)
                    } else {
                        stringResource(R.string.scripts_new_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !invalid,
                onClick = { onConfirm(trimmed) }
            ) { Text(stringResource(R.string.scripts_new_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

private val ScriptTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatScriptTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(ScriptTimeFormat)
