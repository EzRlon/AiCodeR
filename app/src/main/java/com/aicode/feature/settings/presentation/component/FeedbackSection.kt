package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.settings.SettingsDivider
import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsRow
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.feedback.FeedbackDraft
import com.aicode.feature.settings.domain.feedback.FeedbackKind
import com.aicode.feature.settings.domain.feedback.FeedbackReportBuilder
import com.aicode.feature.settings.domain.feedback.LogExcerptSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 反馈与建议页：填写内容 → 生成 GitHub Issue 预填链接 → 用系统浏览器打开。
 *
 * 不经过 App 内 API：不存令牌、不用后端；完整正文同时复制到剪贴板，
 * 用于链接长度裁剪或 Issue 页未自动填充时兜底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedbackSection() {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(FeedbackKind.SUGGESTION) }
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var reproduction by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var includeLogs by remember { mutableStateOf(false) }
    var logExcerpt by remember { mutableStateOf("") }
    var loadingLogs by remember { mutableStateOf(false) }

    // 日志只在用户打开开关时读一次；文件可能较大，读取与脱敏都放到 IO 线程
    LaunchedEffect(includeLogs) {
        if (!includeLogs) {
            logExcerpt = ""
            return@LaunchedEffect
        }
        loadingLogs = true
        logExcerpt = withContext(Dispatchers.IO) {
            val newest = FileLogger.listLogFiles().maxByOrNull { it.lastModified() }
                ?: return@withContext ""
            val raw = runCatching { newest.readText() }.getOrDefault("")
            LogExcerptSanitizer.sanitize(raw)
        }
        loadingLogs = false
    }

    val diagnostics = remember { buildFeedbackDiagnostics(context) }
    val canSubmit = subject.isNotBlank() && description.isNotBlank()
    val openedHint = stringResource(R.string.settings_feedback_opened)
    val noBrowserHint = stringResource(R.string.settings_feedback_no_browser)
    val copiedHint = stringResource(R.string.settings_feedback_copied)

    fun currentDraft() = FeedbackDraft(
        kind = kind,
        title = subject,
        description = description,
        reproduction = reproduction,
        diagnostics = if (includeDiagnostics) diagnostics else "",
        logExcerpt = if (includeLogs) logExcerpt else ""
    )

    fun copyToClipboard(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("AiCodeR feedback", text))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.settings_feedback_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(R.string.settings_feedback_kind),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FeedbackKind.entries.forEach { item ->
                FilterChip(
                    selected = kind == item,
                    onClick = { kind = item },
                    label = { Text(stringResource(item.titleRes())) }
                )
            }
        }

        AppTextField(
            value = subject,
            onValueChange = { subject = it.take(80) },
            label = stringResource(R.string.settings_feedback_subject),
            placeholder = stringResource(R.string.settings_feedback_subject_hint),
            singleLine = true
        )
        AppTextField(
            value = description,
            onValueChange = { description = it },
            label = stringResource(R.string.settings_feedback_detail),
            placeholder = stringResource(R.string.settings_feedback_detail_hint),
            singleLine = false,
            minLines = 4,
            maxLines = 10
        )
        if (kind == FeedbackKind.BUG) {
            AppTextField(
                value = reproduction,
                onValueChange = { reproduction = it },
                label = stringResource(R.string.settings_feedback_repro),
                placeholder = stringResource(R.string.settings_feedback_repro_hint),
                singleLine = false,
                minLines = 3,
                maxLines = 8
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_feedback_attach))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_feedback_diagnostics),
                subtitle = stringResource(R.string.settings_feedback_diagnostics_hint),
                showChevron = false,
                trailing = {
                    AppSwitch(checked = includeDiagnostics, onCheckedChange = { includeDiagnostics = it })
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_feedback_logs),
                subtitle = if (loadingLogs) {
                    stringResource(R.string.settings_feedback_logs_loading)
                } else {
                    stringResource(R.string.settings_feedback_logs_hint)
                },
                showChevron = false,
                trailing = {
                    AppSwitch(checked = includeLogs, onCheckedChange = { includeLogs = it })
                }
            )
        }

        Text(
            text = stringResource(R.string.settings_feedback_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                val report = FeedbackReportBuilder.build(currentDraft())
                // 先复制完整正文：即使浏览器打开失败或 Issue 页未自动填充，用户也不会丢内容
                copyToClipboard(report.fullBody)
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(report.issueUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }.getOrDefault(false)
                Toast.makeText(context, if (opened) openedHint else noBrowserHint, Toast.LENGTH_LONG).show()
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_feedback_submit))
        }
        OutlinedButton(
            onClick = {
                copyToClipboard(FeedbackReportBuilder.build(currentDraft()).fullBody)
                Toast.makeText(context, copiedHint, Toast.LENGTH_SHORT).show()
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_feedback_copy))
        }
    }
}

private fun FeedbackKind.titleRes(): Int = when (this) {
    FeedbackKind.SUGGESTION -> R.string.settings_feedback_kind_suggestion
    FeedbackKind.BUG -> R.string.settings_feedback_kind_bug
    FeedbackKind.OTHER -> R.string.settings_feedback_kind_other
}

/** 环境信息由页面采集：只放定位问题必需的字段，不采集设备标识。 */
private fun buildFeedbackDiagnostics(context: Context): String {
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }
    return buildString {
        appendLine("- App 版本: $versionName")
        appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("- 机型: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("- 系统语言: ${Locale.getDefault()}")
    }.trim()
}
