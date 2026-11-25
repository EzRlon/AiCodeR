package com.aicode.feature.store.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.aicode.feature.agent.presentation.component.engineBrandColor
import com.aicode.feature.agent.presentation.component.engineShortLabel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import android.widget.Toast
import com.aicode.core.ui.AppSwitch
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.util.containsCjk
import com.aicode.feature.store.data.TeamStoreItem
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import com.aicode.feature.store.data.StoreOrigin
import com.aicode.feature.store.data.StoreSourceFilter
import com.aicode.feature.store.data.PluginStoreItem
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.storeTranslationKey
import com.aicode.feature.store.domain.ScanHit
import com.aicode.feature.store.domain.ScanLevel
import com.aicode.feature.store.domain.ScanResult
import com.aicode.feature.store.presentation.PendingScanGate
import com.aicode.feature.store.presentation.StoreUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Search

/**
 * 在线商店（「设置 → 在线商店」的内容组件，无自带 Scaffold）：
 * 技能 / MCP 双 Tab、搜索（技能）、分页加载、安装（安装 ≠ 启用）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun StoreScreen(
    state: StoreUiState,
    onTab: (StoreKind) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onInstall: (StoreItem, EngineTarget?) -> Unit,
    onUninstallMcp: (StoreItem, EngineTarget) -> Unit = { _, _ -> },
    onInstallTeam: (TeamStoreItem, EngineTarget?) -> Unit,
    onInstallPlugin: (PluginStoreItem, EngineTarget) -> Unit = { _, _ -> },
    onUninstallPlugin: (PluginStoreItem, EngineTarget) -> Unit = { _, _ -> },
    onPluginEngineFilter: (EngineTarget?) -> Unit = {},
    onSourceFilter: (StoreSourceFilter) -> Unit = {},
    onInstallPluginToAll: (PluginStoreItem) -> Unit = {},
    onConsumeMessage: () -> Unit,
    onRetryTranslation: () -> Unit = {},
    onConfirmScan: () -> Unit = {},
    onDismissScan: () -> Unit = {},
    sources: List<com.aicode.feature.store.data.StoreSource> = emptyList(),
    onToggleSource: (String, Boolean) -> Unit = { _, _ -> },
    onRemoveSource: (String) -> Unit = {},
    onAddSource: (com.aicode.feature.store.data.StoreSource) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 条目详情弹层（用户反馈：希望能点开每个技能/MCP/专家团看完整介绍）
    var detailItem by remember { mutableStateOf<StoreItem?>(null) }
    var detailTeam by remember { mutableStateOf<TeamStoreItem?>(null) }
    var showSources by remember { mutableStateOf(false) }

    // 一次性提示（安装结果）→ Toast
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onConsumeMessage()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Tab：技能 / MCP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentedTabs(
                selected = when (state.tab) {
                    StoreKind.SKILL -> 0
                    StoreKind.MCP -> 1
                    StoreKind.EXPERT -> 2
                    StoreKind.PLUGIN -> 3
                },
                labels = listOf(
                    stringResource(R.string.store_tab_skills),
                    stringResource(R.string.store_tab_mcp),
                    stringResource(R.string.store_tab_experts),
                    stringResource(R.string.store_tab_plugins)
                ),
                onSelect = {
                    onTab(
                        when (it) {
                            0 -> StoreKind.SKILL
                            1 -> StoreKind.MCP
                            2 -> StoreKind.EXPERT
                            else -> StoreKind.PLUGIN
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            )
            // 商店源入口：与 tab 同行、同高、同风格（之前是行外单独一个悬浮图标，视觉上脱离按钮组）。
            Spacer(Modifier.width(Spacing.xs))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { showSources = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Layers,
                    contentDescription = stringResource(R.string.store_sources_title),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 搜索框（技能 tab 提供；MCP 不提供搜索）：放大镜收进框内尾部（点击=搜索），
        // 输入框随容器宽度自适应对齐（验收反馈）。
        if (state.tab == StoreKind.SKILL) {
            AppTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = stringResource(R.string.store_search_hint),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onSearch) {
                        Icon(
                            FeatherIcons.Search,
                            contentDescription = stringResource(R.string.store_search_action),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.sm)
            )
        }

        if (state.translating) {
            Text(
                text = stringResource(R.string.store_translating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.xs)
            )
        }

        if (state.translationFailed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.store_translation_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRetryTranslation) {
                    Text(
                        text = stringResource(R.string.store_translation_retry),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.size(Spacing.xs))

        when {
            state.tab == StoreKind.PLUGIN -> PluginList(
                state = state,
                onInstall = onInstallPlugin,
                onUninstall = onUninstallPlugin,
                onEngineFilter = onPluginEngineFilter,
                onSourceFilter = onSourceFilter,
                onInstallToAll = onInstallPluginToAll
            )

            state.tab == StoreKind.EXPERT -> TeamList(
                state = state,
                onInstall = onInstallTeam,
                onOpen = { detailTeam = it }
            )

            state.loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            state.error != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onSearch(); }) {
                    Text(stringResource(R.string.store_retry))
                }
            }

            state.items.isEmpty() -> Text(
                text = stringResource(R.string.store_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 24.dp)
            )

            else -> Column(modifier = Modifier.fillMaxWidth()) {
                SourceFilterRow(
                    selected = state.sourceFilter,
                    onSelect = onSourceFilter
                )
                val filteredItems = remember(state.items, state.sourceFilter) {
                    when (state.sourceFilter) {
                        StoreSourceFilter.ALL -> state.items
                        StoreSourceFilter.OFFICIAL -> state.items.filter { it.origin == StoreOrigin.OFFICIAL }
                        StoreSourceFilter.COMMUNITY -> state.items.filter { it.origin != StoreOrigin.OFFICIAL }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.xl
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                officialFirstGroups(
                    items = filteredItems,
                    isOfficial = { it.origin == StoreOrigin.OFFICIAL },
                    key = { "${it.kind}:${it.id}" }
                ) { item ->
                    StoreItemCard(
                        item = item,
                        translation = state.translations[item.storeTranslationKey()],
                        installed = item.id in state.installedIds,
                        installing = state.installingId == item.id,
                        installBusy = state.installingId != null,
                        installedEngines = state.installedMcpEngines[item.id].orEmpty(),
                        onInstall = { target -> onInstall(item, target) },
                        onUninstall = { target -> onUninstallMcp(item, target) },
                        onOpen = { detailItem = item }
                    )
                }
                if (state.hasMore) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = onLoadMore) {
                                    Text(stringResource(R.string.store_load_more))
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    // ── 详情弹层：技能/MCP ──
    if (showSources) {
        StoreSourcesSheet(
            sources = sources,
            onToggle = onToggleSource,
            onRemove = onRemoveSource,
            onAdd = onAddSource,
            onDismiss = { showSources = false }
        )
    }
    detailItem?.let { item ->
        val translation = state.translations[item.storeTranslationKey()]
        AdaptiveModalBottomSheet(onDismissRequest = { detailItem = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                val nameZh = translation?.nameZh.orEmpty()
                Text(
                    text = nameZh.ifBlank { item.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val meta = listOfNotNull(
                    item.author.takeIf { it.isNotBlank() },
                    item.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    item.category.takeIf { it.isNotBlank() },
                    item.transportType.takeIf { it.isNotBlank() && item.kind == StoreKind.MCP }
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 有译文时只显示译文；没有译文才回退英文原文，避免同一段话中英各一遍。
                val description = translation?.descriptionZh?.takeIf { it.isNotBlank() } ?: item.description
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.detailUrl.isNotBlank()) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.detailUrl)))
                        }
                    }) {
                        Icon(FeatherIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.store_open_source), style = MaterialTheme.typography.labelMedium)
                    }
                }
                // 最近一次安装扫描的结论（PASS 也展示，让用户知道扫描存在）
                state.lastScans[item.id]?.let { scan -> ScanStatusBlock(scan) }
                val installed = item.id in state.installedIds
                val installing = state.installingId == item.id
                Button2(
                    text = when {
                        installed -> stringResource(R.string.store_installed)
                        installing -> stringResource(R.string.store_installing)
                        else -> stringResource(R.string.store_install)
                    },
                    enabled = !installed && !installing && state.installingId == null,
                    loading = installing
                ) {
                    onInstall(item, null)
                }
            }
        }
    }

    // ── 详情弹层：专家团 ──
    detailTeam?.let { team ->
        val teamTranslation = state.translations[team.storeTranslationKey()]
        val nameZh = teamTranslation?.nameZh.orEmpty()
        val descZh = teamTranslation?.descriptionZh.orEmpty()
        val displayName = team.displayName?.takeIf { it.containsCjk() }
            ?: nameZh.takeIf { it.containsCjk() }
            ?: team.name
        val displayDescription = when {
            team.description.containsCjk() -> team.description
            descZh.containsCjk() -> descZh
            else -> stringResource(R.string.team_description_unavailable)
        }
        AdaptiveModalBottomSheet(onDismissRequest = { detailTeam = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (displayDescription.isNotBlank()) {
                    Text(
                        text = displayDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (team.members.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.store_team_members, team.members.joinToString("、")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (team.online) {
                    Text(
                        text = stringResource(R.string.store_team_online_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.lastScans[team.fileName]?.let { scan -> ScanStatusBlock(scan) }
                Button2(
                    text = when {
                        team.installed -> stringResource(R.string.store_expert_installed_tag)
                        state.installingId == team.fileName -> stringResource(R.string.store_installing)
                        else -> stringResource(R.string.store_expert_install)
                    },
                    enabled = !team.installed && state.installingId == null,
                    loading = state.installingId == team.fileName
                ) {
                    onInstallTeam(team, null)
                }
            }
        }
    }

    // ── 安装安全扫描：WARN 确认 / BLOCK 红色警示（最终决定权在用户） ──
    state.pendingScan?.let { gate ->
        ScanAlertDialog(gate = gate, onConfirm = onConfirmScan, onDismiss = onDismissScan)
    }
}

/** 安装安全扫描弹窗：WARN 黄色确认；BLOCK 红色警示 + 显式二次放行。 */
@Composable
private fun ScanAlertDialog(gate: PendingScanGate, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val block = gate.scan is ScanResult.Block
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (block) R.string.store_scan_block_title else R.string.store_scan_warn_title
                ),
                color = if (block) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(
                        if (block) R.string.store_scan_block_body else R.string.store_scan_warn_body
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                gate.scan.hits.forEach { hit -> ScanHitRow(hit) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        if (block) R.string.store_scan_confirm_block else R.string.store_scan_confirm_warn
                    ),
                    color = if (block) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.store_scan_cancel))
            }
        }
    )
}

/** 详情弹层的「安全扫描」区块：展示该条目最近一次安装扫描的结论（命中时列出前几条）。 */
@Composable
private fun ScanStatusBlock(scan: ScanResult) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.store_scan_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        when (scan) {
            is ScanResult.Pass -> Text(
                text = stringResource(R.string.store_scan_passed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is ScanResult.Warn -> {
                Text(
                    text = stringResource(R.string.store_scan_warn_found, scan.hits.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.warning
                )
                scan.hits.take(3).forEach { hit -> ScanHitRow(hit) }
            }
            is ScanResult.Block -> {
                Text(
                    text = stringResource(R.string.store_scan_block_found, scan.hits.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                scan.hits.take(3).forEach { hit -> ScanHitRow(hit) }
            }
        }
    }
}

/** 单条命中：类别 · 规则（· 来源 · 行号）+ 原文片段。 */
@Composable
private fun ScanHitRow(hit: ScanHit) {
    val riskColor = if (hit.severity == ScanLevel.BLOCK) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.semanticColors.warning
    }
    val lineText = if (hit.line > 0) stringResource(R.string.store_scan_hit_line, hit.line) else null
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = buildString {
                append(hit.category)
                append(" · ")
                append(hit.rule)
                if (hit.source.isNotBlank()) {
                    append("（")
                    append(hit.source)
                    append("）")
                }
                if (lineText != null) {
                    append(" · ")
                    append(lineText)
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = riskColor
        )
        Text(
            text = "「${hit.snippet}」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 详情弹层的安装/状态主按钮（圆角填充，统一商店弹层视觉）。 */
@Composable
private fun Button2(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.pill),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text)
    }
}

/**
 * 商店列表统一分组渲染：官方精选在前、社区资源在后，各带分组头——
 * 让「来自哪、可不可信」的层级在滚动一眼可辨（官方渠道用品牌色组头）。
 */
private fun <T> LazyListScope.officialFirstGroups(
    items: List<T>,
    isOfficial: (T) -> Boolean,
    key: (T) -> String,
    card: @Composable (T) -> Unit
) {
    val official = items.filter(isOfficial)
    val community = items.filterNot(isOfficial)
    if (official.isNotEmpty()) {
        item(key = "hdr-official") {
            StoreGroupHeader(
                title = "官方精选",
                subtitle = "来自引擎官方渠道，可信度最高",
                accent = MaterialTheme.colorScheme.primary
            )
        }
        official.forEach { item(key = key(it), contentType = "store-card") { card(it) } }
    }
    if (community.isNotEmpty()) {
        item(key = "hdr-community") {
            StoreGroupHeader(
                title = "社区资源",
                subtitle = "来自第三方市场与社区贡献，安装前请留意来源",
                accent = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        community.forEach { item(key = key(it), contentType = "store-card") { card(it) } }
    }
}

/** 商店分组头：小型色条 + 标题 + 说明。 */
@Composable
private fun StoreGroupHeader(title: String, subtitle: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .background(accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 来源筛选行：全部 / 官方 / 社区（引擎官方渠道优先展示）。 */
@Composable
private fun SourceFilterRow(
    selected: StoreSourceFilter,
    onSelect: (StoreSourceFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StoreSourceFilter.entries.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = {
                    Text(
                        when (f) {
                            StoreSourceFilter.ALL -> "全部"
                            StoreSourceFilter.OFFICIAL -> "官方"
                            StoreSourceFilter.COMMUNITY -> "社区"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
        }
    }
}

/**
 * 来源标签行：官方渠道用引擎品牌色「官方·引擎名」，其余显示来源名（社区/自定义）——
 * 让用户一眼区分资源来自哪个引擎生态、是否官方出品。
 */
@Composable
private fun OriginSourceBadge(item: StoreItem) {
    val isOfficial = item.origin == StoreOrigin.OFFICIAL
    val engine = item.engineOrigin?.let { id -> EngineTarget.entries.firstOrNull { it.cli == id } }
    if (!isOfficial && item.sourceName.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        val (label, accent) = when {
            isOfficial && engine != null -> "官方·${engineShortLabel(engine)}" to engineBrandColor(engine)
            isOfficial -> "官方" to Color(0xFF4CAF50)
            else -> item.sourceName to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = "●",
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isOfficial) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 插件卡来源标签（官方渠道用引擎品牌色）。 */
@Composable
private fun PluginOriginBadge(plugin: PluginStoreItem) {
    val engine = plugin.engineOrigin?.let { id -> EngineTarget.entries.firstOrNull { it.cli == id } }
    val (label, accent) = when {
        plugin.origin == StoreOrigin.OFFICIAL && engine != null ->
            "官方·${engineShortLabel(engine)}" to engineBrandColor(engine)
        plugin.origin == StoreOrigin.OFFICIAL -> "官方" to Color(0xFF4CAF50)
        else -> return
    }
    Text(
        text = "● $label",
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/** 单条商店卡片：名称/作者/版本/描述 + 安装按钮 + 来源链接。 */
@Composable
private fun StoreItemCard(
    item: StoreItem,
    translation: StoreTranslation?,
    installed: Boolean,
    installing: Boolean,
    installBusy: Boolean,
    installedEngines: Set<EngineTarget> = emptySet(),
    onInstall: (EngineTarget?) -> Unit,
    onUninstall: (EngineTarget) -> Unit = {},
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.md, top = 12.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nameZh = translation?.nameZh.orEmpty()
                Text(
                    // 中文界面优先显示译文，不再「英文（中文）」并列（2026-11-16 反馈）。
                    text = nameZh.ifBlank { item.name },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.version.isNotBlank()) {
                    Text(
                        text = "v${item.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val subtitle = listOfNotNull(
                item.author.takeIf { it.isNotBlank() },
                item.category.takeIf { it.isNotBlank() },
                item.transportType.takeIf { it.isNotBlank() && item.kind == StoreKind.MCP }
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OriginSourceBadge(item = item)
            val descriptionZh = translation?.descriptionZh.orEmpty()
            if (descriptionZh.isNotBlank()) {
                // 中文界面本土化硬规则：有中文译文时不再追加英文原文，避免双语摘要混排。
                Spacer(Modifier.size(4.dp))
                Text(
                    text = descriptionZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (item.description.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(6.dp))
            // MCP：插件页同构的 per-engine 安装状态（每 chip = 该引擎的已装/安装/卸载）；
            // 技能：单一存放点全引擎可用，不分引擎（不再展示目标选择 chips）；
            // 专家团：仅内置与 CC 支持定义文件写入。
            when {
                item.stdinUnsupported() -> Unit
                item.kind == StoreKind.MCP -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PluginEngineChip(
                            label = engineShortLabel(null),
                            selected = installed,
                            onClick = { if (!installed) onInstall(null) },
                            enabled = !installBusy && !installed,
                            accent = engineBrandColor(null)
                        )
                        EngineTarget.entries.forEach { engine ->
                            val installedHere = engine in installedEngines
                            PluginEngineChip(
                                label = engineShortLabel(engine),
                                selected = installedHere,
                                onClick = {
                                    if (installedHere) onUninstall(engine) else onInstall(engine)
                                },
                                enabled = !installBusy,
                                accent = engineBrandColor(engine)
                            )
                        }
                    }
                    Spacer(Modifier.size(2.dp))
                }
                item.kind == StoreKind.EXPERT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PluginEngineChip(
                            label = engineShortLabel(null),
                            selected = true,
                            onClick = { if (!installed) onInstall(null) },
                            enabled = !installBusy && !installed,
                            accent = engineBrandColor(null)
                        )
                        PluginEngineChip(
                            label = engineShortLabel(EngineTarget.CLAUDE_CODE),
                            selected = false,
                            onClick = { onInstall(EngineTarget.CLAUDE_CODE) },
                            enabled = !installBusy,
                            accent = engineBrandColor(EngineTarget.CLAUDE_CODE)
                        )
                    }
                    Spacer(Modifier.size(2.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.detailUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(item.detailUrl))
                                )
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Spacing.xs, vertical = 0.dp
                        )
                    ) {
                        Icon(FeatherIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.store_open_source), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.weight(1f))
                when {
                    installed -> Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = if (item.kind == StoreKind.SKILL) {
                                stringResource(R.string.store_installed_all_engines)
                            } else {
                                stringResource(R.string.store_installed)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    item.stdinUnsupported() -> Text(
                        text = stringResource(R.string.store_mcp_stdio_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // MCP 由上方引擎 chips 直接安装，不再需要单独按钮；技能/专家团的「内置」需按钮入口
                    else -> TextButton(
                        onClick = { onInstall(null) },
                        enabled = !installBusy && item.kind != StoreKind.MCP
                    ) {
                        if (installing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.store_installing))
                        } else {
                            Icon(FeatherIcons.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.store_install))
                        }
                    }
                }
            }
        }
    }
}

/** MCP 条目仅提供本地包（无远程地址）时不支持一键安装。 */
private fun StoreItem.stdinUnsupported(): Boolean = kind == StoreKind.MCP && stdioOnly

/** 专家团 tab：内置团队包列表（名称/简介/成员/安装状态）；点击卡片进详情。 */
@Composable
private fun TeamList(
    state: StoreUiState,
    onInstall: (TeamStoreItem, EngineTarget?) -> Unit,
    onOpen: (TeamStoreItem) -> Unit
) {
    if (state.teams.isEmpty()) {
        Text(
            text = stringResource(R.string.store_experts_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 24.dp)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.xs,
            bottom = Spacing.xl
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.teams, key = { it.fileName }) { team ->
            val teamTranslation = state.translations[team.storeTranslationKey()]
            val nameZh = teamTranslation?.nameZh.orEmpty()
            val descZh = teamTranslation?.descriptionZh.orEmpty()
            val displayName = team.displayName?.takeIf { it.containsCjk() }
                ?: nameZh.takeIf { it.containsCjk() }
                ?: team.name
            val displayDescription = when {
                team.description.containsCjk() -> team.description
                descZh.containsCjk() -> descZh
                else -> stringResource(R.string.team_description_unavailable)
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.semanticColors.cardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(team) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (displayDescription.isNotBlank()) {
                            Text(
                                text = displayDescription,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (team.members.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.store_team_members, team.members.joinToString("、")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        } else if (team.online) {
                            Text(
                                text = stringResource(R.string.store_team_online_source),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    if (team.installed) {
                        Text(
                            text = stringResource(R.string.store_expert_installed_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        var teamTarget by remember(team.fileName) { mutableStateOf<EngineTarget?>(null) }
                        Column(horizontalAlignment = Alignment.End) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PluginEngineChip(
                                    label = engineShortLabel(null),
                                    selected = teamTarget == null,
                                    onClick = { teamTarget = null },
                                    accent = engineBrandColor(null)
                                )
                                PluginEngineChip(
                                    label = engineShortLabel(EngineTarget.CLAUDE_CODE),
                                    selected = teamTarget == EngineTarget.CLAUDE_CODE,
                                    onClick = { teamTarget = EngineTarget.CLAUDE_CODE },
                                    accent = engineBrandColor(EngineTarget.CLAUDE_CODE)
                                )
                            }
                            TextButton(
                                onClick = { onInstall(team, teamTarget) },
                                enabled = state.installingId == null
                            ) {
                                Text(stringResource(R.string.store_expert_install))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 引擎插件列表：引擎筛选胶囊 + 按条目可选引擎给出安装按钮。
 * 一个插件可同时支持多个引擎，安装时按引擎分别下发各自的 CLI 命令。
 */
@Composable
private fun PluginList(
    state: StoreUiState,
    onInstall: (PluginStoreItem, EngineTarget) -> Unit,
    onUninstall: (PluginStoreItem, EngineTarget) -> Unit,
    onEngineFilter: (EngineTarget?) -> Unit,
    onSourceFilter: (StoreSourceFilter) -> Unit,
    onInstallToAll: (PluginStoreItem) -> Unit
) {
    val filtered = state.plugins
        .filter { t -> state.pluginEngineFilter?.let { it in t.engines } ?: true }
        .filter {
            when (state.sourceFilter) {
                StoreSourceFilter.ALL -> true
                StoreSourceFilter.OFFICIAL -> it.origin == StoreOrigin.OFFICIAL
                StoreSourceFilter.COMMUNITY -> it.origin != StoreOrigin.OFFICIAL
            }
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            PluginEngineChip(
                label = stringResource(R.string.store_plugin_all_engines),
                selected = state.pluginEngineFilter == null,
                onClick = { onEngineFilter(null) }
            )
            EngineTarget.entries.forEach { target ->
                PluginEngineChip(
                    label = engineShortLabel(target),
                    selected = state.pluginEngineFilter == target,
                    onClick = { onEngineFilter(target) },
                    accent = engineBrandColor(target)
                )
            }
        }
        SourceFilterRow(
            selected = state.sourceFilter,
            onSelect = onSourceFilter
        )

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.store_plugins_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 24.dp)
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.xs,
                bottom = Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            officialFirstGroups(
                items = filtered,
                isOfficial = { it.origin == StoreOrigin.OFFICIAL },
                key = { it.id }
            ) { plugin ->
                val translation = state.translations[
                    StoreItem(
                        id = plugin.id,
                        kind = StoreKind.PLUGIN,
                        name = plugin.name,
                        description = plugin.description,
                        category = "",
                        popularity = 0L,
                        detailUrl = ""
                    ).storeTranslationKey()
                ]
                val nameZh = translation?.nameZh.orEmpty()
                val descZh = translation?.descriptionZh.orEmpty()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.semanticColors.cardSurface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (nameZh.isBlank()) plugin.name else nameZh,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (plugin.version.isNotBlank()) {
                                Text(
                                    text = "v${plugin.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (plugin.description.isNotBlank()) {
                            Text(
                                text = if (descZh.isBlank()) plugin.description else descZh,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (plugin.origin == StoreOrigin.OFFICIAL) {
                            PluginOriginBadge(plugin = plugin)
                        }
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (plugin.engines.size >= 2 && state.installingId == null) {
                                Text(
                                    text = "装到全部（${plugin.engines.size}）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { onInstallToAll(plugin) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            plugin.engines.forEach { target ->
                                val installed = target in state.installedPluginEngines[plugin.id].orEmpty()
                                PluginEngineChip(
                                    label = if (installed) engineShortLabel(target) + " ✓" else engineShortLabel(target),
                                    selected = installed,
                                    onClick = {
                                        if (installed) {
                                            onUninstall(plugin, target)
                                        } else {
                                            onInstall(plugin, target)
                                        }
                                    },
                                    enabled = state.installingId == null,
                                    accent = engineBrandColor(target)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 引擎胶囊：既做筛选也做安装入口（插件卡片内按引擎逐个给出按钮）。选中态用引擎品牌色。 */
@Composable
private fun PluginEngineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Color? = null
) {
    val brand = accent ?: MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            selected && accent != null -> brand
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.semanticColors.cardSurface
        },
        contentColor = when {
            selected && accent != null -> Color.White
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/**
 * 商店源管理弹层：列出全部源，支持启停、删除非内置源、按 claudemarket 兼容协议新增。
 * 内置源只允许启停（baseUrl 与可信标记由代码维护，防篡改）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StoreSourcesSheet(
    sources: List<com.aicode.feature.store.data.StoreSource>,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (com.aicode.feature.store.data.StoreSource) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.store_sources_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.store_sources_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            sources.forEach { src ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = src.name + if (src.builtIn) {
                                " · " + stringResource(R.string.store_sources_builtin)
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = src.baseUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!src.trusted) {
                            Text(
                                text = stringResource(R.string.store_sources_untrusted),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    AppSwitch(checked = src.enabled, onCheckedChange = { onToggle(src.id, it) })
                    if (!src.builtIn) {
                        TextButton(onClick = { onRemove(src.id) }) {
                            Text(stringResource(R.string.common_delete))
                        }
                    }
                }
                HorizontalDivider()
            }
            if (!adding) {
                TextButton(onClick = { adding = true }) {
                    Text(stringResource(R.string.store_sources_add))
                }
            } else {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.store_sources_name),
                    singleLine = true
                )
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.store_sources_url),
                    supportingText = { Text(stringResource(R.string.store_sources_url_hint)) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { adding = false; name = ""; url = "" }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    TextButton(
                        onClick = {
                            val trimmedUrl = url.trim().trimEnd('/')
                            onAdd(
                                com.aicode.feature.store.data.StoreSource(
                                    id = "custom-" + trimmedUrl.hashCode().toUInt().toString(),
                                    name = name.trim(),
                                    baseUrl = trimmedUrl,
                                    priority = 0,
                                    trusted = false,
                                    enabled = true,
                                    builtIn = false
                                )
                            )
                            adding = false; name = ""; url = ""
                        },
                        enabled = name.isNotBlank() && url.trim().startsWith("http")
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    }
}
