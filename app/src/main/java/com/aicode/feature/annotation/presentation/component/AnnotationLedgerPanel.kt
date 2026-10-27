package com.aicode.feature.annotation.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.annotation.presentation.AnnotationViewModel
import com.aicode.feature.fixledger.domain.model.FixLedgerItem
import com.aicode.feature.fixledger.presentation.FixLedgerViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Send
import compose.icons.feathericons.Tool
import compose.icons.feathericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一画布的账本图层（批 2）：标注账本 + 修复账本合流展示，右侧滑出。
 *
 * - 标注条目：点行 = 载入画布重绘补画（保存走 updateMarks）；▾ 菜单 = 状态流转 /
 *   转入修复账本 / 删除；左滑删除；
 * - 修复账本条目：▾ 菜单 = 状态流转 / 删除；左滑删除；头部「+」手动新建；
 * - 头部「汇总发给 AI」：未关闭条目一次性注入聊天输入框（fixledger 回写提示随行）。
 */
@Composable
internal fun AnnotationLedgerPanel(
    annotationViewModel: AnnotationViewModel,
    fixLedgerViewModel: FixLedgerViewModel,
    onOpenAnnotation: (AnnotationItem) -> Unit,
    onSummaryReady: (String, List<AnnotationItem>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val annotationItems by annotationViewModel.items.collectAsStateWithLifecycle()
    val fixItems by fixLedgerViewModel.items.collectAsStateWithLifecycle()
    val activeAnnotationItems = remember(annotationItems) {
        annotationItems.filter { it.status != AnnotationStatus.CLOSED }
    }
    var showAddFixDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(topStart = Radius.mdLarge, bottomStart = Radius.mdLarge),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxHeight()
            .width(340.dp)
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.annotation_canvas_ledger_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showAddFixDialog = true }) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.fix_ledger_add),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(
                    onClick = {
                        if (activeAnnotationItems.isNotEmpty()) {
                            onSummaryReady(
                                annotationViewModel.buildSummaryText(activeAnnotationItems),
                                activeAnnotationItems
                            )
                        }
                    },
                    enabled = activeAnnotationItems.isNotEmpty()
                ) {
                    Icon(FeatherIcons.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.annotation_summary_send))
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                item(key = "header-annotations") {
                    PanelSectionHeader(stringResource(R.string.annotation_canvas_section_annotations))
                }
                if (annotationItems.isEmpty()) {
                    item(key = "annotations-empty") {
                        PanelEmptyText(stringResource(R.string.annotation_ledger_empty))
                    }
                }
                items(annotationItems, key = { "a-${it.id}" }) { item ->
                    val convertFallbackTitle = item.note.ifBlank { stringResource(item.type.labelRes()) }
                    SwipeToDeleteRow(
                        onDelete = { annotationViewModel.delete(item) },
                        // onClick 交给滑动行统一仲裁：滑开状态下点行先收回，不误触发载入
                        onClick = { onOpenAnnotation(item) }
                    ) {
                        AnnotationLedgerRow(
                            item = item,
                            onStatusChange = { status -> annotationViewModel.updateStatus(item.id, status) },
                            onConvertToFixLedger = {
                                fixLedgerViewModel.addFromAnnotation(
                                    item,
                                    convertFallbackTitle
                                ) { added ->
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(if (added) R.string.fix_ledger_added else R.string.fix_ledger_already_exists),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onDelete = { annotationViewModel.delete(item) }
                        )
                    }
                }
                item(key = "header-fix") {
                    PanelSectionHeader(stringResource(R.string.fix_ledger_title))
                }
                if (fixItems.isEmpty()) {
                    item(key = "fix-empty") {
                        PanelEmptyText(stringResource(R.string.fix_ledger_empty))
                    }
                }
                items(fixItems, key = { "f-${it.id}" }) { item ->
                    SwipeToDeleteRow(onDelete = { fixLedgerViewModel.delete(item.id) }) {
                        FixLedgerLedgerRow(
                            item = item,
                            onStatusChange = { status -> fixLedgerViewModel.updateStatus(item.id, status) },
                            onDelete = { fixLedgerViewModel.delete(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddFixDialog) {
        AddFixLedgerDialog(
            onConfirm = { title, note ->
                fixLedgerViewModel.add(title, note)
                showAddFixDialog = false
            },
            onDismiss = { showAddFixDialog = false }
        )
    }
}

@Composable
private fun PanelSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = 2.dp)
    )
}

@Composable
private fun PanelEmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.sm)
    )
}

@Composable
private fun AnnotationLedgerRow(
    item: AnnotationItem,
    onStatusChange: (AnnotationStatus) -> Unit,
    onConvertToFixLedger: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(annotationColor(item.type))
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.note.ifBlank { stringResource(R.string.annotation_no_note) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(item.type.labelRes()) + " · " + formatTime(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Box {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = { menuOpen = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LedgerStatusPill(item.status)
                        Spacer(Modifier.width(4.dp))
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LedgerStatusMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onStatusChange = {
                        menuOpen = false
                        onStatusChange(it)
                    },
                    extraItems = listOf(
                        stringResource(R.string.fix_ledger_convert) to onConvertToFixLedger,
                        stringResource(R.string.annotation_delete) to onDelete
                    )
                )
            }
        }
    }
}

@Composable
private fun FixLedgerLedgerRow(
    item: FixLedgerItem,
    onStatusChange: (AnnotationStatus) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.Tool,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTime(item.updatedAt) + if (item.note.isBlank()) "" else " · " + item.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Box {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = { menuOpen = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LedgerStatusPill(item.status)
                        Spacer(Modifier.width(4.dp))
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LedgerStatusMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onStatusChange = {
                        menuOpen = false
                        onStatusChange(it)
                    },
                    extraItems = listOf(stringResource(R.string.fix_ledger_delete) to onDelete)
                )
            }
        }
    }
}

@Composable
internal fun LedgerStatusPill(status: AnnotationStatus) {
    val (textRes, color) = when (status) {
        AnnotationStatus.PENDING -> R.string.annotation_status_pending to MaterialTheme.colorScheme.onSurfaceVariant
        AnnotationStatus.SENT -> R.string.annotation_status_sent to MaterialTheme.colorScheme.primary
        AnnotationStatus.FIXING -> R.string.annotation_status_fixing to MaterialTheme.colorScheme.tertiary
        AnnotationStatus.REVIEW -> R.string.annotation_status_review to Brand.Orange
        AnnotationStatus.CLOSED -> R.string.annotation_status_closed to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LedgerStatusMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onStatusChange: (AnnotationStatus) -> Unit,
    extraItems: List<Pair<String, () -> Unit>>
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        AnnotationStatus.entries.forEach { status ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            when (status) {
                                AnnotationStatus.PENDING -> R.string.annotation_status_pending
                                AnnotationStatus.SENT -> R.string.annotation_status_sent
                                AnnotationStatus.FIXING -> R.string.annotation_status_fixing
                                AnnotationStatus.REVIEW -> R.string.annotation_status_review
                                AnnotationStatus.CLOSED -> R.string.annotation_status_closed
                            }
                        )
                    )
                },
                onClick = { onStatusChange(status) }
            )
        }
        extraItems.forEach { (label, action) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); action() })
        }
    }
}

@Composable
private fun AddFixLedgerDialog(
    onConfirm: (title: String, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fix_ledger_add)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.fix_ledger_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                androidx.compose.material3.OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.fix_ledger_note_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(title, note) },
                enabled = title.isNotBlank()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

private fun formatTime(ts: Long): String =
    // 列表项里逐条调用，每次都 new 一个 SimpleDateFormat 等于每行一次对象分配与格式化器初始化。
    // 格式化器非线程安全，但这里只在组合/主线程调用，用 ThreadLocal 兼顾复用与安全。
    TIME_FORMATTER.get()!!.format(Date(ts))

private val TIME_FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
}
