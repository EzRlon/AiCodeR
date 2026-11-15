package com.aicode.feature.settings.presentation.component

import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsRow
import com.aicode.core.ui.settings.SettingsDivider
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.domain.model.KeyCategory
import com.aicode.feature.settings.domain.model.KeyFile
import com.aicode.feature.settings.domain.model.KeySource
import com.aicode.feature.settings.presentation.DevKeysUiState
import com.aicode.feature.settings.presentation.KeysViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Download
import compose.icons.feathericons.Key
import compose.icons.feathericons.Package
import compose.icons.feathericons.Upload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 指纹短码位数（sha256 hex 前 12 位，列表里用于快速识别）。 */
private const val FINGERPRINT_SHORT_LENGTH = 12

/**
 * 密钥管理页：按来源（统一密钥库 / SSH / 工作区）分组列出密钥文件；
 * 页面顶部提供「导入密钥」与「导出全部（zip）」；条目点击弹出操作菜单（导出 / 复制路径 / 详情）。
 */
@Composable
internal fun DevKeysSection(
    state: DevKeysUiState,
    onImportClick: () -> Unit,
    onExportAll: () -> Unit,
    onExportBundle: () -> Unit,
    onExportOne: (KeyFile) -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menuTarget by remember { mutableStateOf<KeyFile?>(null) }
    var detailTarget by remember { mutableStateOf<KeyFile?>(null) }
    var showExportConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onConsumeMessage()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        Text(
            text = stringResource(R.string.dev_keys_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(vertical = Spacing.md)
        )

        // ── 发布就绪卡：回答「现在打正式包，够不够」，而不是只列文件 ──
        // 当年发布踩坑：备份只带走 .jks，同目录 keystore.properties（口令）没跟上，正式包当天签不出。
        state.readiness?.let { readiness ->
            ReleaseReadinessCard(readiness = readiness)
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = stringResource(R.string.dev_keys_export_bundle),
                    subtitle = stringResource(R.string.dev_keys_export_bundle_desc),
                    enabled = !state.exporting && readiness.ready,
                    onClick = onExportBundle
                )
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Upload,
                title = stringResource(R.string.dev_keys_import),
                subtitle = stringResource(
                    if (state.importing) R.string.dev_keys_import_running else R.string.dev_keys_import_desc
                ),
                enabled = !state.importing && !state.exporting,
                onClick = onImportClick
            )
            if (state.entries.isNotEmpty()) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Download,
                    title = stringResource(R.string.dev_keys_export_all),
                    subtitle = stringResource(
                        if (state.exporting) R.string.dev_keys_export_running else R.string.dev_keys_export_all_desc
                    ),
                    enabled = !state.exporting && !state.importing,
                    onClick = { showExportConfirm = true }
                )
            }
        }

        KeySource.entries.forEach { source ->
            val group = state.entries.filter { it.source == source }
            if (group.isEmpty()) return@forEach
            SettingsGroupHeader(text = stringResource(sourceHeaderRes(source)))
            SettingsGroup {
                group.forEachIndexed { index, key ->
                    if (index > 0) SettingsDivider()
                    DevKeyRow(key = key, onOpenMenu = { menuTarget = key })
                }
            }
        }

        when {
            state.scanning && state.entries.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.dev_keys_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
            !state.scanning && state.entries.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = FeatherIcons.Key,
                        contentDescription = null,
                        tint = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.dev_keys_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.dev_keys_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.dev_keys_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(vertical = Spacing.md)
        )
    }

    menuTarget?.let { key ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(key.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ActionRow(text = stringResource(R.string.dev_keys_action_export)) {
                        menuTarget = null
                        onExportOne(key)
                    }
                    ActionRow(text = stringResource(R.string.dev_keys_action_copy_path)) {
                        menuTarget = null
                        val copiedMessage = context.getString(R.string.dev_keys_copied)
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("path", key.containerPath))
                            )
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                    ActionRow(text = stringResource(R.string.dev_keys_action_detail)) {
                        menuTarget = null
                        detailTarget = key
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    detailTarget?.let { key ->
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            title = { Text(key.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    DetailLine(stringResource(R.string.dev_keys_detail_path), key.containerPath)
                    DetailLine(
                        stringResource(R.string.dev_keys_detail_size),
                        if (key.size >= 0) formatBytes(key.size) else "—"
                    )
                    DetailLine(
                        stringResource(R.string.dev_keys_detail_modified),
                        remember(key.lastModified) {
                            if (key.lastModified <= 0) "—"
                            else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(key.lastModified))
                        }
                    )
                    DetailLine(
                        stringResource(R.string.dev_keys_detail_category),
                        stringResource(categoryLabelRes(key.category))
                    )
                    DetailLine(
                        stringResource(R.string.dev_keys_detail_fingerprint),
                        key.fingerprint ?: stringResource(R.string.dev_keys_fingerprint_skipped)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailTarget = null }) { Text(stringResource(R.string.common_got_it)) }
            }
        )
    }

    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text(stringResource(R.string.dev_keys_export_confirm_title)) },
            text = { Text(stringResource(R.string.dev_keys_export_confirm_body, state.entries.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    onExportAll()
                }) { Text(stringResource(R.string.dev_keys_export_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 单个密钥文件行：名称 + 「大小 · 时间 · 指纹短码」副行 + 类别标签。 */
@Composable
private fun DevKeyRow(key: KeyFile, onOpenMenu: () -> Unit) {
    val time = remember(key.lastModified) {
        if (key.lastModified <= 0) "—"
        else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(key.lastModified))
    }
    val sizeText = if (key.size >= 0) formatBytes(key.size) else "—"
    val shortFingerprint = key.fingerprint
        ?.removePrefix("sha256:")
        ?.take(FINGERPRINT_SHORT_LENGTH)
        ?: "—"
    SettingsRow(
        icon = FeatherIcons.Key,
        title = key.name,
        subtitle = "$sizeText · $time · $shortFingerprint",
        onClick = onOpenMenu,
        trailing = {
            val (textColor, backgroundColor) = when (key.category) {
                KeyCategory.RELEASE -> MaterialTheme.colorScheme.primary to
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                KeyCategory.SSH -> MaterialTheme.semanticColors.success to
                    MaterialTheme.semanticColors.success.copy(alpha = 0.12f)
                KeyCategory.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant to
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
            McpPill(
                text = stringResource(categoryLabelRes(key.category)),
                textColor = textColor,
                backgroundColor = backgroundColor
            )
        }
    )
}

/** 详情弹窗里的一行：小字标签 + 值。 */
@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.semanticColors.subtleText
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 操作弹窗里的一行动作按钮（左对齐、紧凑）。 */
@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
    }
}

private fun sourceHeaderRes(source: KeySource): Int = when (source) {
    KeySource.LIBRARY -> R.string.dev_keys_source_library
    KeySource.SSH -> R.string.dev_keys_source_ssh
    KeySource.WORKSPACE -> R.string.dev_keys_source_workspace
}

private fun categoryLabelRes(category: KeyCategory): Int = when (category) {
    KeyCategory.RELEASE -> R.string.dev_keys_category_release
    KeyCategory.SSH -> R.string.dev_keys_category_ssh
    KeyCategory.OTHER -> R.string.dev_keys_category_other
}

/**
 * 密钥管理页宿主：注册 SAF launcher（多选导入 / 导出全部 zip / 单条导出），
 * 与 [KeysViewModel] 同作用域（随分区进入 / 离开注册与注销）。
 */
/**
 * 发布就绪卡：逐项列出「打正式包需要什么、现在缺什么」，全绿才亮「打包发布资产」。
 * 这是对「备份只带了证书、发布当天才发现缺口令配置」那次踩坑的结构性回应。
 */
@Composable
private fun ReleaseReadinessCard(readiness: com.aicode.feature.settings.domain.model.ReleaseReadiness) {
    val accent = if (readiness.ready) MaterialTheme.semanticColors.success else MaterialTheme.semanticColors.warning
    SettingsGroup {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (readiness.ready) FeatherIcons.CheckCircle else FeatherIcons.Key,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.dev_keys_readiness_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(
                        if (readiness.ready) R.string.dev_keys_readiness_ready
                        else R.string.dev_keys_readiness_not_ready
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            readiness.items.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = if (item.passed) "✓" else "✗",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.passed) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
internal fun DevKeysSectionHost(viewModel: KeysViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingExportKey by remember { mutableStateOf<KeyFile?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importKeys(uris)
    }
    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val output = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (output != null) {
                    viewModel.exportAllTo(output)
                } else {
                    Toast.makeText(context, context.getString(R.string.dev_keys_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val exportOneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val key = pendingExportKey
        pendingExportKey = null
        if (uri != null && key != null) {
            scope.launch {
                val output = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (output != null) {
                    viewModel.exportOneTo(key, output)
                } else {
                    Toast.makeText(context, context.getString(R.string.dev_keys_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val exportBundleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val output = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (output != null) {
                    viewModel.exportReleaseBundleTo(output)
                } else {
                    Toast.makeText(context, context.getString(R.string.dev_keys_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    DevKeysSection(
        state = state,
        onImportClick = { importLauncher.launch(arrayOf("*/*")) },
        onExportAll = { exportAllLauncher.launch("aicode-keys-${System.currentTimeMillis()}.zip") },
        onExportBundle = { exportBundleLauncher.launch("aicode-release-bundle-${System.currentTimeMillis()}.zip") },
        onExportOne = { key ->
            pendingExportKey = key
            exportOneLauncher.launch(key.name)
        },
        onConsumeMessage = viewModel::consumeMessage,
    )
}
