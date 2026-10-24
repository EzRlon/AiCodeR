package com.aicode.feature.fixledger.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.fixledger.domain.model.FixLedgerItem
import com.aicode.feature.fixledger.presentation.FixLedgerViewModel
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Tool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 修复账本：「用户反馈 → 修复 → 复验」的可追溯列表，状态机与标注账本同构。
 * 条目来源两个：本页手动新建、标注中心详情页「转入修复账本」（回填来源标注防重复）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixLedgerScreen(
    onBack: () -> Unit,
    viewModel: FixLedgerViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<FixLedgerItem?>(null) }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fix_ledger_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            FeatherIcons.Plus,
                            contentDescription = stringResource(R.string.fix_ledger_add),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground()
                )
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    FeatherIcons.Tool,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.fix_ledger_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(items, key = { it.id }) { item ->
                    SwipeToDeleteRow(onDelete = { viewModel.delete(item.id) }) {
                        FixLedgerRow(item = item, onClick = { detailItem = item })
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFixLedgerDialog(
            onConfirm = { title, note ->
                viewModel.add(title, note)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    detailItem?.let { item ->
        FixLedgerDetailDialog(
            item = item,
            onStatusChange = { status ->
                viewModel.updateStatus(item.id, status)
                detailItem = null
            },
            onDelete = {
                viewModel.delete(item.id)
                detailItem = null
            },
            onDismiss = { detailItem = null }
        )
    }
}

@Composable
private fun FixLedgerRow(item: FixLedgerItem, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    text = formatTime(item.updatedAt) + if (item.note.isBlank()) {
                        ""
                    } else {
                        " · " + item.note
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            StatusPill(item.status)
        }
    }
}

@Composable
private fun StatusPill(status: AnnotationStatus) {
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
private fun AddFixLedgerDialog(
    onConfirm: (title: String, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fix_ledger_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.fix_ledger_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.fix_ledger_note_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, note) },
                enabled = title.isNotBlank()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/** 详情：摘要 + 备注 + 状态流转 + 删除。 */
@Composable
private fun FixLedgerDetailDialog(
    item: FixLedgerItem,
    onStatusChange: (AnnotationStatus) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var statusMenuOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        Surface(
                            shape = RoundedCornerShape(Radius.pill),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            onClick = { statusMenuOpen = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(item.status)
                                Spacer(Modifier.width(4.dp))
                                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }) {
                            AnnotationStatus.entries.forEach { status ->
                                DropdownMenuItem(
                                    text = { Text(statusLabel(status)) },
                                    onClick = {
                                        statusMenuOpen = false
                                        onStatusChange(status)
                                    }
                                )
                            }
                        }
                    }
                }
                if (item.note.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = formatTime(item.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = stringResource(R.string.fix_ledger_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: AnnotationStatus): String = stringResource(
    when (status) {
        AnnotationStatus.PENDING -> R.string.annotation_status_pending
        AnnotationStatus.SENT -> R.string.annotation_status_sent
        AnnotationStatus.FIXING -> R.string.annotation_status_fixing
        AnnotationStatus.REVIEW -> R.string.annotation_status_review
        AnnotationStatus.CLOSED -> R.string.annotation_status_closed
    }
)

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
