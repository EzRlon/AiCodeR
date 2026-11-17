package com.aicode.feature.changelog.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.TokenStatsPalette
import com.aicode.core.theme.semanticColors
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.changelog.domain.model.ChangeEntry
import com.aicode.feature.changelog.domain.model.ChangeKind
import com.aicode.feature.changelog.presentation.ChangeLedgerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 改动账本页：跨会话回答「哪些改好了、哪些没做」。
 *
 * 一页双视图共用同一份记录（不做两份数据，否则必不同步）：
 * - **用户视图**（默认）：一行大白话摘要 + 状态徽章，扫一眼就知道进度；
 * - **Agent 视图**（卡片展开）：结构化细节（为什么改 / 改前改后）+ 验证证据，供接续工作。
 *
 * 状态机复用 [AnnotationStatus]（与标注、修复账本同构），颜色沿用 [TokenStatsPalette]，
 * 与「会话 / 结论」两页视觉一致。
 */
@Composable
internal fun ChangeLedgerSection(
    viewModel: ChangeLedgerViewModel,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.visibleEntries.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()
    val kindFilter by viewModel.kindFilter.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        SummaryHeader(summary)
        FilterRow(
            statusFilter = statusFilter,
            kindFilter = kindFilter,
            onStatus = viewModel::setStatusFilter,
            onKind = viewModel::setKindFilter
        )
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.change_ledger_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.md, end = Spacing.md, top = Spacing.xs, bottom = 84.dp
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(entries, key = { it.id }) { entry ->
                    ChangeEntryCard(
                        entry = entry,
                        onAdvance = { viewModel.advance(entry.id, it) },
                        onDelete = { viewModel.delete(entry.id) }
                    )
                }
            }
        }
    }
}

/** 概览条：已验/待复验/进行中/待确认 + 未验条数，直接回答「还剩多少没做完」。 */
@Composable
private fun SummaryHeader(summary: ChangeLedgerViewModel.Summary) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.change_ledger_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.change_ledger_summary_total, summary.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
            if (summary.total > 0) {
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { summary.verifiedRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(Radius.pill)),
                    color = TokenStatsPalette.Cache,
                    trackColor = MaterialTheme.semanticColors.mutedSurface
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    CountChip(stringResource(R.string.change_ledger_summary_closed, summary.closed), TokenStatsPalette.Cache)
                    CountChip(stringResource(R.string.change_ledger_summary_review, summary.review), TokenStatsPalette.Output)
                    CountChip(stringResource(R.string.change_ledger_summary_doing, summary.doing), TokenStatsPalette.Input)
                    CountChip(stringResource(R.string.change_ledger_summary_pending, summary.pending), TokenStatsPalette.Cancelled)
                }
                if (summary.withoutEvidence > 0) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.change_ledger_no_evidence, summary.withoutEvidence),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.warning
                    )
                }
            }
        }
    }
}

@Composable
private fun CountChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
    }
}

@Composable
private fun FilterRow(
    statusFilter: AnnotationStatus?,
    kindFilter: ChangeKind?,
    onStatus: (AnnotationStatus?) -> Unit,
    onKind: (ChangeKind?) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FilterPill(stringResource(R.string.change_ledger_filter_all), statusFilter == null) { onStatus(null) }
            listOf(AnnotationStatus.FIXING, AnnotationStatus.REVIEW, AnnotationStatus.CLOSED).forEach { status ->
                FilterPill(
                    // 复用标注账本的状态文案，与「标注 / 修复账本」三方口径一致
                    statusLabelOf(status),
                    statusFilter == status
                ) { onStatus(if (statusFilter == status) null else status) }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FilterPill(stringResource(R.string.change_ledger_filter_all), kindFilter == null) { onKind(null) }
            listOf(ChangeKind.FEAT, ChangeKind.FIX, ChangeKind.REFACTOR, ChangeKind.DOCS).forEach { kind ->
                FilterPill(kind.labelZh, kindFilter == kind) { onKind(if (kindFilter == kind) null else kind) }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.semanticColors.mutedSurface,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp)
        )
    }
}

/** 单条账目卡片：用户视图常显，展开后是 Agent 视图（细节 + 证据）。 */
@Composable
private fun ChangeEntryCard(
    entry: ChangeEntry,
    onAdvance: (AnnotationStatus) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val statusColor = statusColorOf(entry.status)

    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = entry.displaySummary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.xs))
                KindBadge(entry.kind)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusLabelOf(entry.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
                if (entry.hasEvidence) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.change_ledger_verified),
                        style = MaterialTheme.typography.labelSmall,
                        color = TokenStatsPalette.Cache
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatDate(entry.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = Spacing.sm)) {
                    if (entry.agentDetail.isNotBlank()) {
                        DetailBlock(
                            label = stringResource(R.string.change_ledger_detail_label),
                            text = entry.agentDetail
                        )
                    }
                    if (entry.evidence.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.xs))
                        DetailBlock(
                            label = stringResource(R.string.change_ledger_evidence_label),
                            text = entry.evidence,
                            mono = true
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (entry.status.ordinal < AnnotationStatus.REVIEW.ordinal) {
                            TextButton(onClick = { onAdvance(AnnotationStatus.REVIEW) }) {
                                Text(stringResource(R.string.change_ledger_advance_review))
                            }
                        }
                        if (entry.status != AnnotationStatus.CLOSED) {
                            TextButton(onClick = { onAdvance(AnnotationStatus.CLOSED) }) {
                                Text(stringResource(R.string.change_ledger_advance_closed))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) {
                            Text(
                                text = stringResource(R.string.change_ledger_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, text: String, mono: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.semanticColors.subtleText
        )
        Text(
            text = text,
            style = if (mono) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KindBadge(kind: ChangeKind) {
    Surface(
        shape = RoundedCornerShape(Radius.xs),
        color = MaterialTheme.semanticColors.mutedSurface
    ) {
        Text(
            text = kind.labelZh,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun statusLabelOf(status: AnnotationStatus): String = stringResource(
    when (status) {
        AnnotationStatus.PENDING -> R.string.annotation_status_pending
        AnnotationStatus.SENT -> R.string.annotation_status_sent
        AnnotationStatus.FIXING -> R.string.annotation_status_fixing
        AnnotationStatus.REVIEW -> R.string.annotation_status_review
        AnnotationStatus.CLOSED -> R.string.annotation_status_closed
    }
)

private fun statusColorOf(status: AnnotationStatus): androidx.compose.ui.graphics.Color = when (status) {
    AnnotationStatus.PENDING, AnnotationStatus.SENT -> TokenStatsPalette.Cancelled
    AnnotationStatus.FIXING -> TokenStatsPalette.Input
    AnnotationStatus.REVIEW -> TokenStatsPalette.Output
    AnnotationStatus.CLOSED -> TokenStatsPalette.Cache
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(epochMillis))
