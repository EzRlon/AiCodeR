package com.aicode.feature.annotation.presentation.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationMarksCodec
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.annotation.presentation.AnnotationViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import androidx.compose.ui.text.rememberTextMeasurer
import compose.icons.feathericons.Send
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截图标注账本：列表（类型色点 + 说明 + 状态 + 时间）+ 详情预览（原图重绘标注 + 状态流转）
 * + 顶部「汇总发给 AI」（文本与图片附件一起注入聊天输入框）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnnotationLedgerScreen(
    onBack: () -> Unit,
    onSummaryReady: (text: String, items: List<AnnotationItem>) -> Unit,
    viewModel: AnnotationViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var detailItem by remember { mutableStateOf<AnnotationItem?>(null) }
    val activeItems = remember(items) { items.filter { it.status != AnnotationStatus.CLOSED } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.annotation_ledger_title)) },
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
                    TextButton(
                        onClick = { onSummaryReady(viewModel.buildSummaryText(activeItems), activeItems) },
                        enabled = activeItems.isNotEmpty()
                    ) {
                        Icon(FeatherIcons.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.annotation_summary_send))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.annotation_ledger_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(items, key = { it.id }) { item ->
                        SwipeToDeleteRow(onDelete = { viewModel.delete(item) }) {
                            AnnotationRow(item = item, onClick = { detailItem = item })
                        }
                    }
                }
            }
        }
    }

    detailItem?.let { item ->
        AnnotationDetailDialog(
            item = item,
            viewModel = viewModel,
            onStatusChange = { status ->
                viewModel.updateStatus(item.id, status)
                detailItem = null
            },
            onDelete = {
                viewModel.delete(item)
                detailItem = null
            },
            onDismiss = { detailItem = null }
        )
    }
}

@Composable
private fun AnnotationRow(item: AnnotationItem, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        AnnotationStatus.REVIEW -> R.string.annotation_status_review to com.aicode.core.theme.Brand.Orange
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

/** 详情：原图 + 重绘标注 + 说明 + 状态流转（长按/点击状态菜单）。 */
@Composable
private fun AnnotationDetailDialog(
    item: AnnotationItem,
    viewModel: AnnotationViewModel,
    onStatusChange: (AnnotationStatus) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val marks = remember(item.id) { AnnotationMarksCodec.decode(item.marksJson) }
    val bitmapState by produceState<android.graphics.Bitmap?>(initialValue = null, item.id) {
        val bytes = viewModel.readImageBytes(item)
        value = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    var statusMenuOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(item.type.labelRes()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formatTime(item.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        Surface(
                            shape = RoundedCornerShape(Radius.pill),
                            color = annotationColor(item.type).copy(alpha = 0.14f),
                            onClick = { statusMenuOpen = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(item.status)
                                Spacer(Modifier.width(4.dp))
                                Text("▾", color = annotationColor(item.type))
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

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = Spacing.md)
                ) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val areaWidthPx = with(density) { maxWidth.toPx() }
                    val areaHeightPx = with(density) { maxHeight.toPx() }
                    val bitmap = bitmapState
                    if (bitmap == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.annotation_loading),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val fit = remember(bitmap, areaWidthPx, areaHeightPx) {
                            computeFitRect(areaWidthPx, areaHeightPx, bitmap.width.toFloat(), bitmap.height.toFloat())
                        }
                        Image(
                            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                        val textMeasurer = rememberTextMeasurer()
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            marks.forEach { drawMark(it, fit, fade = false, textMeasurer = textMeasurer) }
                        }
                    }
                }

                if (item.note.isNotBlank()) {
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    )
                }

                TextButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.annotation_delete),
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
