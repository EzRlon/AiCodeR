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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.aicode.feature.settings.domain.model.ReleaseReadiness
import com.aicode.feature.settings.presentation.DevKeysUiState
import com.aicode.feature.settings.presentation.KeysViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Download
import compose.icons.feathericons.Key
import compose.icons.feathericons.Package
import compose.icons.feathericons.Tool
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
    currentProject: com.aicode.feature.workspace.domain.model.Workspace?,
    onRecheck: () -> Unit,
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
            text = stringResource(R.string.settings_dev_keys_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.md)
        )
        Text(
            text = stringResource(R.string.dev_keys_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md)
        )

        // 当前工作区就是当前项目：只读展示，切换工作区后由 KeysViewModel 自动重扫。
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Folder,
                title = stringResource(R.string.dev_keys_project),
                subtitle = currentProject?.name ?: "—",
                showChevron = false
            )
        }

        // ── 项目 → 发布材料 → 构建版本 → 打包（2026-11-18 用户反馈：层级仍不清）──
        // 发布材料只有一套、三个正式版本共用，因此先呈现材料再列版本；
        // 旧实现把同一套四项材料在每个版本下各列一遍，且「发布就绪」悬在版本之外，
        // 让人误以为每个版本要求不同、就绪状态与版本无隶属关系。
        val readiness = state.readiness
            ?.takeIf { it.projectName == null || it.projectName == currentProject?.name }
        if (readiness != null) {
            ReleaseReadinessCard(readiness = readiness, onRecheck = onRecheck)
            Spacer(Modifier.height(Spacing.md))
        }

        BuildVariantsSection(readiness = readiness)
        Spacer(Modifier.height(Spacing.md))

        // ── 打包发布资产：属于「正式版」，紧跟版本矩阵 ──
        if (readiness != null) {
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = stringResource(R.string.dev_keys_export_bundle),
                    subtitle = if (readiness.ready) {
                        stringResource(R.string.dev_keys_export_bundle_desc)
                    } else {
                        stringResource(R.string.dev_keys_export_bundle_hint)
                    },
                    enabled = !state.exporting && readiness.ready,
                    onClick = onExportBundle
                )
                if (readiness.ready) {
                    // 包内含 keystore.properties 明文口令：明确提示存放边界（反馈 2）。
                    Text(
                        text = stringResource(R.string.dev_keys_export_plaintext_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    )
                }
            }
        }

        // 以项目为单位导入/导出：导出的 zip 可带到另一台设备恢复（跨设备）。
        SettingsGroupHeader(text = stringResource(R.string.dev_keys_actions_prepare))
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
        }
        if (state.entries.isNotEmpty()) {
            SettingsGroupHeader(text = stringResource(R.string.dev_keys_actions_backup))
            SettingsGroup {
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
                        text = stringResource(R.string.dev_keys_empty_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.dev_keys_empty_next),
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
/** 一个构建版本：debug 包不需要发布材料；正式包需要证书 + 口令配置 + 四个字段。 */
private data class BuildVariant(val titleRes: Int, val isDebug: Boolean)

private val BUILD_VARIANTS = listOf(
    BuildVariant(R.string.dev_keys_variant_release_armsolo, false),
    BuildVariant(R.string.dev_keys_variant_release_universal, false),
    BuildVariant(R.string.dev_keys_variant_release_x86, false),
    BuildVariant(R.string.dev_keys_variant_debug, true)
)

/**
 * 构建版本矩阵（2026-11-18 层级重做）：
 * 项目 → 发布材料 → 构建版本，材料是项目级、三个正式版本共用一套，只在上面列一次。
 * 每个版本行只回答一件事：现在能不能打包。旧实现把同一套四项材料在每个版本下各列一遍，
 * 让人误以为每个版本要求不同。
 */
@Composable
private fun BuildVariantsSection(readiness: ReleaseReadiness?) {
    val missing = readiness?.items?.count { !it.passed && it.blocking }

    SettingsGroupHeader(text = stringResource(R.string.dev_keys_variants_title))
    SettingsGroup {
        BUILD_VARIANTS.forEachIndexed { index, variant ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                icon = if (variant.isDebug) FeatherIcons.Tool else FeatherIcons.Package,
                title = stringResource(variant.titleRes),
                subtitle = if (variant.isDebug) {
                    stringResource(R.string.dev_keys_variant_debug_desc)
                } else when {
                    missing == null -> stringResource(R.string.dev_keys_variant_needs_check)
                    missing == 0 -> stringResource(R.string.dev_keys_variant_ready)
                    else -> stringResource(R.string.dev_keys_variant_missing, missing)
                },
                showChevron = false
            )
        }
    }
}

/**
 * 发布就绪卡：逐项列出「打正式包需要什么、现在缺什么」，全绿才亮「打包发布资产」。
 * 这是对「备份只带了证书、发布当天才发现缺口令配置」那次踩坑的结构性回应。
 */
@Composable
private fun ReleaseReadinessCard(
    readiness: com.aicode.feature.settings.domain.model.ReleaseReadiness,
    onRecheck: () -> Unit
) {
    val blockingFailures = readiness.items.count { !it.passed && it.blocking }
    val ready = readiness.ready
    val accent = if (ready) MaterialTheme.semanticColors.success else MaterialTheme.semanticColors.warning
    SettingsGroup {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) FeatherIcons.CheckCircle else FeatherIcons.Key,
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
                        if (ready) R.string.dev_keys_readiness_ready
                        else R.string.dev_keys_readiness_not_ready
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            // 先用一句话说清「现在能不能打」，细节再逐项列，普通用户不用读术语。
            Text(
                text = if (ready) {
                    stringResource(R.string.dev_keys_ready_summary)
                } else {
                    stringResource(R.string.dev_keys_not_ready_summary, blockingFailures)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            readiness.items.forEach { item ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = when {
                            item.passed -> "✓"
                            !item.blocking -> "⚠"
                            else -> "✗"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            item.passed -> MaterialTheme.semanticColors.success
                            !item.blocking -> MaterialTheme.semanticColors.warning
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Column {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.passed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        val next = item.nextStep
                        if (!item.passed && next != null) {
                            Text(
                                text = stringResource(R.string.dev_keys_next_step, next),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.semanticColors.subtleText
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = onRecheck,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.dev_keys_recheck), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun DevKeysSectionHost(viewModel: KeysViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentProject by viewModel.currentProject.collectAsStateWithLifecycle()
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
        currentProject = currentProject,
        onRecheck = viewModel::refresh,
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
