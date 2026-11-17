package com.aicode.feature.settings.presentation.component

import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.CollapsibleGroupHeader
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsDivider
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.util.ApkInstaller
import com.aicode.core.util.ExternalFileActions
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.StorageUsagePalette
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.domain.model.CleanupKind
import com.aicode.feature.settings.domain.model.CleanupRecommendation
import com.aicode.feature.settings.domain.model.StorageCategory
import com.aicode.feature.settings.domain.model.StorageDetail
import com.aicode.feature.settings.domain.model.StorageDetailKey
import com.aicode.feature.settings.domain.model.StorageEntry
import com.aicode.feature.settings.domain.model.StorageSafety
import com.aicode.feature.agent.presentation.component.openLocalFile
import com.aicode.feature.settings.domain.model.formatStorageSize
import com.aicode.feature.settings.presentation.StorageUiState
import com.aicode.feature.settings.presentation.StorageViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import java.io.File

/** 分类色点缩进宽度：色点 10dp + 与标题的间距，明细行据此与标题左对齐。 */
private val DetailIndent = 10.dp + Spacing.md
private val SelectionWidth = 48.dp

/** 连接 [StorageViewModel] 与 [StorageSection]：state 收集下沉到这里，统计逐项到达时只重组本页。 */
@Composable
internal fun StorageSectionHost(
    viewModel: StorageViewModel,
    onRevealInTree: ((String) -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageSection(
        state = state,
        onToggleExpand = viewModel::toggleExpanded,
        onToggleCleanup = viewModel::toggleCleanup,
        onToggleArtifact = viewModel::toggleArtifact,
        onToggleSession = viewModel::toggleSession,
        onCleanSelected = viewModel::cleanSelected,
        onDeleteArtifact = viewModel::deleteArtifact,
        onToggleRecommendExpanded = viewModel::toggleRecommendExpanded,
        onScanRecommendations = viewModel::scanRecommendations,
        onToggleRecommendation = viewModel::toggleRecommendation,
        onCleanRecommendations = viewModel::cleanRecommendations,
        onToggleRuntimeHealth = viewModel::toggleRuntimeHealthExpanded,
        onRevealInTree = onRevealInTree
    )
}

/**
 * 存储空间页：总览、分类明细与选中项清理。
 *
 * 统计逐项到达（大目录慢），未算出的分类先占位显示「—」，不阻塞已算出的部分。
 */
@Composable
internal fun StorageSection(
    state: StorageUiState,
    onToggleExpand: (StorageCategory) -> Unit,
    onToggleCleanup: (CleanupKind) -> Unit,
    onToggleArtifact: (String) -> Unit,
    onToggleSession: (String) -> Unit,
    onCleanSelected: () -> Unit,
    onDeleteArtifact: (String) -> Unit,
    onToggleRecommendExpanded: () -> Unit,
    onScanRecommendations: () -> Unit,
    onToggleRecommendation: (String) -> Unit,
    onCleanRecommendations: () -> Unit,
    onToggleRuntimeHealth: () -> Unit,
    onRevealInTree: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var pendingCleanup by remember { mutableStateOf(false) }
    var pendingRecommendClean by remember { mutableStateOf(false) }
    var actionDetail by remember { mutableStateOf<StorageDetail?>(null) }
    var deleteTarget by remember { mutableStateOf<StorageDetail?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Spacer(Modifier.height(Spacing.sm))
            OverviewCard(state)

            RecommendedCleanupCard(
                state = state,
                onToggleExpanded = onToggleRecommendExpanded,
                onScan = onScanRecommendations,
                onToggleItem = onToggleRecommendation,
                onClean = { pendingRecommendClean = true }
            )

            SettingsGroupHeader(text = stringResource(R.string.storage_section_breakdown))
            SettingsGroup {
                StorageCategory.entries.forEachIndexed { index, category ->
                    if (index > 0) SettingsDivider()
                    val entry = state.entries[category]
                    val details = entry?.details.orEmpty().filter {
                        category != StorageCategory.AiConfig ||
                            it.key == StorageDetailKey.TOOL_OUTPUT || it.key == StorageDetailKey.VISION_SESSIONS ||
                            it.key == StorageDetailKey.GENERATED_IMAGES
                    }
                    val cleanupKind = when (category) {
                        StorageCategory.Logs -> CleanupKind.Logs
                        StorageCategory.Caches -> CleanupKind.Caches
                        StorageCategory.Checkpoints -> CleanupKind.Checkpoints
                        StorageCategory.ContainerImages -> CleanupKind.ContainerImages
                        // 容器数据行勾选即执行「容器内构建缓存」清理（脚本只清白名单缓存目录）
                        StorageCategory.Container -> CleanupKind.ContainerCaches
                        else -> null
                    }
                    CategoryRow(
                        category = category,
                        entry = entry,
                        expanded = category in state.expanded,
                        onToggle = { onToggleExpand(category) },
                        cleanupKind = cleanupKind,
                        expandable = details.isNotEmpty(),
                        selected = cleanupKind in state.selectedCleanup,
                        cleaning = state.cleaning,
                        onSelect = { cleanupKind?.let(onToggleCleanup) }
                    )
                    if (category in state.expanded) {
                        details.forEach { detail ->
                            val detailCleanup = when (detail.key) {
                                StorageDetailKey.TOOL_OUTPUT -> CleanupKind.ToolOutput
                                StorageDetailKey.VISION_SESSIONS -> CleanupKind.VisionSessions
                                StorageDetailKey.GENERATED_IMAGES -> CleanupKind.GeneratedImages
                                else -> null
                            }
                            val sessionKey = detail.key?.takeIf { it.startsWith(StorageDetailKey.CHAT_SESSION_PREFIX) }
                            DetailRow(
                                detail = detail,
                                cleanupKind = detailCleanup,
                                artifact = detail.path != null,
                                sessionKey = sessionKey,
                                selected = (detailCleanup != null && detailCleanup in state.selectedCleanup) ||
                                    (detail.path != null && detail.path in state.selectedArtifacts) ||
                                    (sessionKey != null && sessionKey in state.selectedSessions),
                                cleaning = state.cleaning,
                                onSelect = {
                                    if (detailCleanup != null) onToggleCleanup(detailCleanup)
                                    else sessionKey?.let(onToggleSession) ?: detail.path?.let(onToggleArtifact)
                                },
                                onClick = detail.path?.let { { actionDetail = detail } }
                            )
                        }
                    }
                }
            }
            FootNote(stringResource(R.string.storage_breakdown_note))

            state.freedBytes?.let { freed ->
                FootNote(stringResource(R.string.storage_freed, formatStorageSize(freed)))
            }

            RuntimeHealthSection(
                state = state,
                onToggle = onToggleRuntimeHealth
            )
        }

        Surface(
            color = MaterialTheme.semanticColors.pageBackground,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Button(
                onClick = { pendingCleanup = true },
                enabled = state.selectedBytes > 0L && !state.cleaning,
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                shape = RoundedCornerShape(Radius.lg)
            ) {
                if (state.cleaning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.storage_clean_selected, formatStorageSize(state.selectedBytes)))
                }
            }
        }
    }

    if (pendingCleanup) {
        val kinds = CleanupKind.entries.filter {
            it in state.selectedCleanup && (state.cleanableSizes[it] ?: 0L) > 0L
        }
        AlertDialog(
            onDismissRequest = { pendingCleanup = false },
            title = { Text(stringResource(R.string.storage_clean_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Text(stringResource(
                        R.string.storage_clean_confirm_selected,
                        formatStorageSize(state.selectedBytes)
                    ))
                    kinds.forEach { kind ->
                        Column {
                            Text(stringResource(kind.labelRes), fontWeight = FontWeight.Medium)
                            Text(stringResource(kind.descRes), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.selectedArtifacts.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.storage_clean_confirm_artifacts,
                                state.selectedArtifacts.size,
                                formatStorageSize(state.artifactSelectedBytes)
                            ),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (state.selectedSessions.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.storage_clean_confirm_sessions,
                                state.selectedSessions.size,
                                formatStorageSize(state.sessionSelectedBytes)
                            ),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(stringResource(R.string.storage_clean_scope), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCleanSelected()
                    pendingCleanup = false
                }) { Text(stringResource(R.string.storage_clean)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleanup = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (pendingRecommendClean) {
        val selected = state.recommendations.filter { it.id in state.selectedRecommendations }
        AlertDialog(
            onDismissRequest = { pendingRecommendClean = false },
            title = { Text(stringResource(R.string.storage_rec_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Text(stringResource(
                        R.string.storage_clean_confirm_selected,
                        formatStorageSize(state.recommendationSelectedBytes)
                    ))
                    selected.forEach { item ->
                        Column {
                            Text(item.label, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(item.reason.labelRes) + " · " + formatStorageSize(item.bytes),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(stringResource(R.string.storage_rec_confirm_scope), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRecommendClean = false
                    onCleanRecommendations()
                }) { Text(stringResource(R.string.storage_rec_clean)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecommendClean = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    actionDetail?.let { detail ->
        val path = detail.path
        AlertDialog(
            onDismissRequest = { actionDetail = null },
            title = { Text(detail.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    detail.note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semanticColors.subtleText)
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    if (path != null) {
                        if (!detail.isDirectory) {
                            ActionRow(text = stringResource(R.string.storage_artifact_open)) {
                                actionDetail = null
                                openArtifact(context, detail, scope)
                            }
                        }
                        ActionRow(text = stringResource(R.string.storage_artifact_copy_path)) {
                            actionDetail = null
                            val copiedMsg = context.getString(R.string.storage_artifact_path_copied)
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("path", path)))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        onRevealInTree?.let { reveal ->
                            ActionRow(text = stringResource(R.string.storage_artifact_reveal)) {
                                actionDetail = null
                                reveal(path)
                            }
                        }
                        if (ExternalFileActions.isMtInstalled(context)) {
                            ActionRow(text = stringResource(R.string.file_action_mt)) {
                                actionDetail = null
                                scope.launch {
                                    val file = File(File(context.filesDir, "projects"), path)
                                    if (!file.exists()) {
                                        Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    val shareable = withContext(Dispatchers.IO) { ExternalFileActions.prepareShareable(context, file) }
                                    if (shareable == null || !ExternalFileActions.openWithMt(context, shareable)) {
                                        Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        ActionRow(text = stringResource(R.string.file_action_save)) {
                            actionDetail = null
                            scope.launch {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    Toast.makeText(context, context.getString(R.string.file_action_need_storage), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val file = File(File(context.filesDir, "projects"), path)
                                if (!file.exists()) {
                                    Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val saved = withContext(Dispatchers.IO) { ExternalFileActions.exportToShared(context, file, detail.label) }
                                if (saved != null) {
                                    Toast.makeText(context, context.getString(R.string.file_action_saved, saved), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.file_action_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        ActionRow(text = stringResource(R.string.storage_artifact_delete), danger = true) {
                            deleteTarget = detail
                            actionDetail = null
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionDetail = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    deleteTarget?.let { detail ->
        val path = detail.path
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.storage_artifact_delete_confirm_title)) },
            text = {
                Text(stringResource(
                    R.string.storage_artifact_delete_confirm_message,
                    detail.label,
                    formatStorageSize(detail.bytes)
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    path?.let(onDeleteArtifact)
                }) { Text(stringResource(R.string.storage_artifact_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * 推荐清理卡：头部「预计可释放 X（高置信 Y）」＋扫描按钮；展开后逐条勾选，
 * 推荐级默认勾、需确认级默认不勾；一键清除走逐条预览确认弹窗。
 */
@Composable
private fun RecommendedCleanupCard(
    state: StorageUiState,
    onToggleExpanded: () -> Unit,
    onScan: () -> Unit,
    onToggleItem: (String) -> Unit,
    onClean: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.storage_rec_title),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onScan, enabled = !state.recommendScanning && !state.cleaning) {
                    Text(stringResource(R.string.storage_rec_scan))
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (state.recommendations.isNotEmpty()) it.clickable(onClick = onToggleExpanded) else it },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    when {
                        state.recommendScanning -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = stringResource(R.string.storage_rec_scanning),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        state.recommendations.isEmpty() -> Text(
                            text = stringResource(
                                if (state.recommendScannedAt == null) R.string.storage_rec_not_scanned
                                else R.string.storage_rec_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        else -> {
                            Text(
                                text = stringResource(
                                    R.string.storage_rec_header,
                                    formatStorageSize(state.recommendations.sumOf { it.bytes }),
                                    formatStorageSize(state.recommendedBytes)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (state.recommendFromCache && state.recommendScannedAt != null) {
                                Text(
                                    text = stringResource(
                                        R.string.storage_rec_last_scan,
                                        formatScanTime(state.recommendScannedAt)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.semanticColors.subtleText
                                )
                            }
                        }
                    }
                }
                if (state.recommendations.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.xs))
                    ExpandableChevronIcon(
                        expanded = state.recommendExpanded,
                        style = ChevronRotationStyle.RIGHT_DOWN,
                        size = 18.dp,
                        tint = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
            if (state.recommendExpanded && state.recommendations.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                state.recommendations.forEach { item ->
                    RecommendationRow(
                        item = item,
                        selected = item.id in state.selectedRecommendations,
                        enabled = !state.cleaning && !state.recommendScanning,
                        onToggle = { onToggleItem(item.id) }
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Button(
                    onClick = onClean,
                    enabled = state.recommendationSelectedBytes > 0L && !state.cleaning && !state.recommendScanning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.lg)
                ) {
                    Text(stringResource(
                        R.string.storage_rec_clean_selected,
                        formatStorageSize(state.recommendationSelectedBytes)
                    ))
                }
            }
        }
    }
}

@Composable
private fun RecommendationRow(
    item: CleanupRecommendation,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(item.reason.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = formatStorageSize(item.bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText
        )
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

/** 运行健康（只读）：常驻服务 / 周期任务 / 活跃扫描的状态清单，异常项标红；展开时按需加载一次。 */
@Composable
private fun RuntimeHealthSection(
    state: StorageUiState,
    onToggle: () -> Unit
) {
    CollapsibleGroupHeader(
        text = stringResource(R.string.storage_health_title),
        expanded = state.runtimeHealthExpanded,
        onToggle = onToggle
    )
    if (!state.runtimeHealthExpanded) return
    SettingsGroup {
        if (state.runtimeHealthLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.storage_health_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        } else {
            state.runtimeHealth.forEachIndexed { index, item ->
                if (index > 0) SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.abnormal) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 缓存结论的展示时间。 */
private fun formatScanTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

@Composable
private fun OverviewCard(state: StorageUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.storage_total_title),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = MaterialTheme.semanticColors.subtleText
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatStorageSize(state.totalBytes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.scanning) {
                    Spacer(Modifier.width(Spacing.sm))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.storage_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            StackedBar(state.entries)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.storage_device_free,
                    formatStorageSize(state.deviceSpace.availableBytes),
                    formatStorageSize(state.deviceSpace.totalBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
    }
}

/** 各分类占比条：整条宽度代表 App 总占用，按分类颜色分段。 */
@Composable
private fun StackedBar(entries: Map<StorageCategory, StorageEntry>) {
    val total = entries.values.sumOf { it.bytes }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.semanticColors.capsuleSurface)
    ) {
        if (total <= 0L) return@Box
        Row(modifier = Modifier.fillMaxSize()) {
            StorageCategory.entries.forEach { category ->
                val bytes = entries[category]?.bytes ?: 0L
                if (bytes > 0L) {
                    Box(
                        modifier = Modifier
                            .weight(bytes.toFloat() / total)
                            .fillMaxHeight()
                            .background(category.color())
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: StorageCategory,
    entry: StorageEntry?,
    expanded: Boolean,
    onToggle: () -> Unit,
    cleanupKind: CleanupKind?,
    expandable: Boolean,
    selected: Boolean,
    cleaning: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (expandable) it.clickable(onClick = onToggle) else it }
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(category.color())
        )
        Spacer(Modifier.width(Spacing.md))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(category.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (expandable) {
                Spacer(Modifier.width(Spacing.xs))
                ExpandableChevronIcon(
                    expanded = expanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    size = 18.dp,
                    tint = MaterialTheme.semanticColors.subtleText
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = entry?.let { formatStorageSize(it.bytes) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
        if (cleanupKind != null) {
            Checkbox(
                checked = selected && (entry?.bytes ?: 0L) > 0L,
                onCheckedChange = { onSelect() },
                enabled = (entry?.bytes ?: 0L) > 0L && !cleaning
            )
        } else {
            Spacer(Modifier.width(SelectionWidth))
        }
    }
}

@Composable
private fun DetailRow(
    detail: StorageDetail,
    cleanupKind: CleanupKind?,
    artifact: Boolean,
    sessionKey: String?,
    selected: Boolean,
    cleaning: Boolean,
    onSelect: () -> Unit,
    onClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(
                start = Spacing.lg + DetailIndent + if (detail.indent) Spacing.lg else 0.dp,
                end = Spacing.lg,
                top = Spacing.xs,
                bottom = Spacing.xs
            )
    ) {
        Row(
            modifier = Modifier.heightIn(min = SelectionWidth),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = cleanupKind?.let { stringResource(it.labelRes) } ?: detail.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = if (cleanupKind == null) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = formatStorageSize(detail.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
            if (cleanupKind != null || artifact || sessionKey != null) {
                Checkbox(
                    checked = selected && detail.bytes > 0L,
                    onCheckedChange = { onSelect() },
                    enabled = detail.bytes > 0L && !cleaning
                )
            } else {
                Spacer(Modifier.width(SelectionWidth))
            }
        }
        val note = detail.note
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        detail.safety?.let { safety ->
            Text(
                text = stringResource(safety.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = when (safety) {
                    StorageSafety.SAFE -> MaterialTheme.semanticColors.success
                    StorageSafety.STALE -> MaterialTheme.semanticColors.warning
                    StorageSafety.KEEP_HISTORY -> MaterialTheme.colorScheme.onSurfaceVariant
                    StorageSafety.PROTECTED -> MaterialTheme.semanticColors.subtleText
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 分组下方的灰色小字说明。 */
@Composable
private fun FootNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.semanticColors.subtleText,
        modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
    )
}

private fun StorageCategory.color(): Color = when (this) {
    StorageCategory.Chat -> StorageUsagePalette.Chat
    StorageCategory.Container -> StorageUsagePalette.Container
    StorageCategory.ContainerImages -> StorageUsagePalette.ContainerImages
    StorageCategory.Workspaces -> StorageUsagePalette.Workspaces
    StorageCategory.AiConfig -> StorageUsagePalette.AiConfig
    StorageCategory.Checkpoints -> StorageUsagePalette.Checkpoints
    StorageCategory.Logs -> StorageUsagePalette.Logs
    StorageCategory.Caches -> StorageUsagePalette.Caches
    StorageCategory.OtherData -> StorageUsagePalette.OtherData
    StorageCategory.Apk -> StorageUsagePalette.Apk
}

/** 产物操作：APK 走安装引导，其他文件按分享/打开处理。物理路径直取（不经容器路径映射），任意项目可用。 */
private fun openArtifact(
    context: Context,
    detail: StorageDetail,
    scope: CoroutineScope
) {
    val path = detail.path ?: return
    val file = File(File(context.filesDir, "projects"), path)
    if (!file.isFile) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    val mime = if (detail.label.endsWith(".apk", ignoreCase = true)) {
        ApkInstaller.APK_MIME_TYPE
    } else {
        "application/octet-stream"
    }
    scope.launch { openLocalFile(context, file, detail.label, mime) }
}

/** 操作菜单里的一行动作按钮（左对齐、紧凑）。 */
@Composable
private fun ActionRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
    }
}
