package com.aicode.feature.memorygraph.presentation

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Clock
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Share2
import com.aicode.R
import com.aicode.core.ui.AppTextField
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.ui.settings.settingsPageBackground
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.core.theme.TokenStatsPalette
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.memorygraph.domain.model.GraphEdge
import com.aicode.feature.memorygraph.domain.model.GraphNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 结论时间线（默认页）：给用户的「AI 学到了什么」大白话视图。
 *
 * 为什么不做节点图：小白看不懂节点网；用户只需要三件事——看到 AI 记住了什么、
 * 跳回来源会话查证据、发现记错了一键标过时。图谱关系视图保留在「图谱」页签。
 */
@Composable
private fun ConclusionsSection(
    workspacePath: String,
    viewModel: MemoryGraphViewModel,
    onOpenSession: (String) -> Unit
) {
    val graph by viewModel.graph.collectAsStateWithLifecycle()
    val extractEnabled by viewModel.extractEnabled.collectAsStateWithLifecycle()
    var detailNode by remember { mutableStateOf<GraphNode?>(null) }
    var showOutdated by rememberSaveable { mutableStateOf(false) }

    val (nodes, edges) = graph ?: (emptyList<GraphNode>() to emptyList())
    val allConclusions = nodes.filter { it.type == "CONCLUSION" || it.type == "CUSTOM" }
    val conclusions = allConclusions
        .filter { showOutdated || it.invalidAt == null }
        .sortedByDescending { it.validFrom.takeIf { v -> v > 0 } ?: it.updatedAt }
    val outdatedCount = allConclusions.count { it.invalidAt != null }
    val totalCount = allConclusions.size

    Column(modifier = Modifier.fillMaxSize()) {
        // 抽取开关提示：默认开；仅当用户显式关闭时提示如何重开（不再主动推销）。
        if (!extractEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.graph_extract_off_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.setExtractEnabled(true) }) {
                        Text(stringResource(R.string.graph_extract_enable))
                    }
                }
            }
        }

        // 统计条：一眼看懂「记住了多少、几条过时」
        if (totalCount > 0 || outdatedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.graph_conclusion_count, totalCount, outdatedCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (outdatedCount > 0) {
                    TextButton(onClick = { showOutdated = !showOutdated }) {
                        Text(
                            text = if (showOutdated) stringResource(R.string.common_cancel) else "${stringResource(R.string.graph_conclusion_outdated)} $outdatedCount",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        if (conclusions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = Spacing.xl)
                ) {
                    Text(
                        text = stringResource(R.string.graph_conclusion_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(conclusions, key = { it.id }) { node ->
                    val outdated = node.invalidAt != null
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (outdated) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        onClick = { detailNode = node },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (outdated) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (outdated) {
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(
                                        text = stringResource(R.string.graph_conclusion_outdated),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (node.summary.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = node.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // 三键操作：跳来源会话 / 标过时 / 删除（全在详情弹层，列表不拥挤）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val whenText = formatRelative(node.validFrom.takeIf { v -> v > 0 } ?: node.updatedAt)
                                Text(
                                    text = whenText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.weight(1f))
                                if (!outdated && node.sourceSessionId != null) {
                                    TextButton(onClick = { onOpenSession(node.sourceSessionId!!) }) {
                                        Text(stringResource(R.string.graph_conclusion_jump_source), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (!outdated) {
                                    TextButton(onClick = { viewModel.markOutdated(node.id) }) {
                                        Text(
                                            text = stringResource(R.string.graph_conclusion_mark_outdated),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
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

    detailNode?.let { node ->
        GraphNodeDetailDialog(
            node = node,
            edges = edges,
            nodes = nodes,
            onDelete = {
                viewModel.deleteGraphNode(node.id)
                detailNode = null
            },
            onDismiss = { detailNode = null }
        )
    }

}

/** 相对时间：今天显示时分，本年显示月日，跨年显示年月日。 */
private fun formatRelative(ts: Long): String {
    if (ts <= 0) return ""
    if (DateUtils.isToday(ts)) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    val pattern = if (isSameYear(ts)) "MM-dd" else "yyyy-MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}

private fun isSameYear(ts: Long): Boolean {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    return now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
}

/**
 * 记忆图谱 v2 节点/边视图：三路混合检索（关键词直中 + 图谱游走 + 时序）+ 节点详情（关系与删除）。
 * 数据由 GraphRepository 双源同步（会话 + memory 工具的记忆条目）。
 */
@Composable
private fun GraphSection(
    workspacePath: String,
    viewModel: MemoryGraphViewModel
) {
    val graph by viewModel.graph.collectAsStateWithLifecycle()
    val hits by viewModel.graphHits.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var detailNode by remember { mutableStateOf<GraphNode?>(null) }

    val (nodes, edges) = graph ?: (emptyList<GraphNode>() to emptyList())
    val shown: List<GraphNode> = hits?.map { it.node } ?: nodes
    // 关系数一次性预计算（optimizer #3：避免每个 item 线性扫全边表）
    val relCounts = remember(edges) { edges.groupingBy { it.fromNodeId }.eachCount() + edges.groupingBy { it.toNodeId }.eachCount() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.searchGraph(workspacePath, it)
                },
                placeholder = stringResource(R.string.graph_search_hint),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.graph_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(shown, key = { it.id }) { node ->
                    val relCount = relCounts[node.id] ?: 0
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        onClick = { detailNode = node },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (node.summary.isNotBlank()) {
                                    Text(
                                        text = node.summary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            GraphTypeBadge(node.type, relCount)
                        }
                    }
                }
            }
        }
    }

    detailNode?.let { node ->
        GraphNodeDetailDialog(
            node = node,
            edges = edges,
            nodes = nodes,
            onDelete = {
                viewModel.deleteGraphNode(node.id)
                detailNode = null
            },
            onDismiss = { detailNode = null }
        )
    }

}

@Composable
private fun GraphTypeBadge(type: String, relCount: Int) {
    val label = when (type) {
        "SESSION" -> stringResource(R.string.graph_type_session)
        "MEMORY" -> stringResource(R.string.graph_type_memory)
        else -> stringResource(R.string.graph_type_custom)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = "$label·$relCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** 节点详情：全部关系（对端节点名 + 关系名）+ 删除。 */
@Composable
private fun GraphNodeDetailDialog(
    node: GraphNode,
    edges: List<GraphEdge>,
    nodes: List<GraphNode>,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val touching = edges.filter { it.fromNodeId == node.id || it.toNodeId == node.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name) },
        text = {
            Column {
                if (node.summary.isNotBlank()) {
                    Text(
                        text = node.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (touching.isEmpty()) {
                    Text(
                        text = stringResource(R.string.graph_no_relations),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    touching.forEach { edge ->
                        val peerId = if (edge.fromNodeId == node.id) edge.toNodeId else edge.fromNodeId
                        val peerName = nodes.firstOrNull { it.id == peerId }?.name ?: peerId.take(8)
                        val arrow = if (edge.fromNodeId == node.id) "→" else "←"
                        Text(
                            text = "$arrow ${edge.relation} · $peerName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.graph_delete_node), color = MaterialTheme.colorScheme.error)
            }
        }
    )
}


/**
 * 记忆图谱 v2：工作线索泳道。
 *
 * 调研同类实现（OpenAI Agents Tracing / agent-cockpit / claude-agent-orchestrator 泳道范式）后，
 * 把旧「静态树形节点图」重构为**垂直线索卡片流**——手机竖屏下每条工作线索一张时光卡片：
 * 状态呼吸点、持续目标与状态徽章、待办进度、消息量、token 消耗、相对时间、最后消息摘要、内嵌子代理行。
 * 点击卡片内联展开详情（目标全文 + 最后消息摘要 + 检查点数 + 打开会话）。
 * 数据由 [MemoryGraphViewModel] 聚合（IO 线程）：消息活动、待办进度、检查点、最后消息摘要。
 */
/** 记忆图谱底部页签数：结论时间线 / 会话 / 图谱 / 改动账本（与 Git 本地仓库同款底部页签）。 */
private const val MEMORY_GRAPH_TAB_COUNT = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryGraphScreen(
    workspacePath: String,
    sessions: List<ChatSession>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    agentStates: Map<String, AgentUIState>,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MemoryGraphViewModel = hiltViewModel(),
    /**
     * 第 4 个页签「账本」的内容，由调用方注入（composition root 认识两边，本 feature 不认识 changelog）。
     * 依赖倒置：既守住 feature 边界基线，也避免 memorygraph 绑死某个具体实现。
     */
    ledgerContent: (@Composable () -> Unit)? = null,
) {
    var expandedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(workspacePath) {
        viewModel.refresh()
        viewModel.syncGraph(workspacePath)
    }

    fun isRunning(id: String): Boolean =
        agentStates[id] is AgentUIState.Loading || agentStates[id] is AgentUIState.Streaming

    val roots = sessions.filter { it.parentId == null }
        .sortedByDescending { activity[it.id]?.lastActiveAt ?: it.updatedAt }
    val visibleRoots = roots

    val totalTokens = sessions.sumOf { it.totalInputTokens + it.totalOutputTokens }
    val runningCount = roots.count { isRunning(it.id) }

    // ── Git 本地仓库同款布局：Scaffold 顶栏 + 三页 Pager + 悬浮底部页签（验收反馈：与 Git 仓库页设计对齐）──
    val pagerState = rememberPagerState(initialPage = 0) { MEMORY_GRAPH_TAB_COUNT }
    // 三个 tab 的滚动状态：仅页面内容滚动时弱化底栏（与 Git 仓库页同一推导）。
    // 不含 pagerState.isScrollInProgress：点页签切页时 pager 也在滚，若纳入会把底栏淡到 0.35（验收反馈的「点击后变透明」）。
    val sessionsListState = rememberLazyListState()
    val tabsScrolling by remember {
        derivedStateOf { sessionsListState.isScrollInProgress }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory_graph_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {}
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

        // ── 统计条：线索 / 进行中 / 累计 token / 缓存命中率 ──
        SessionStatsBar(
            threads = visibleRoots.size,
            running = runningCount,
            totalInputTokens = sessions.sumOf { it.totalInputTokens },
            totalOutputTokens = sessions.sumOf { it.totalOutputTokens },
            totalCachedTokens = sessions.sumOf { it.totalCachedInputTokens },
            modifier = Modifier.padding(horizontal = Spacing.md),
        )

        // ── 三页（与 Git 本地仓库同款）：结论时间线 / 会话 / 图谱，底部悬浮页签切换 ──
        // 刷新统一改为「大幅下拉」：顶栏不再放刷新按钮（2026-11-16 用户反馈 8）。
        // PullToRefreshBox 只在内容滚到顶时可下拉，触发阈值由系统按屏高推导；
        // 共用一份刷新中标记，1.2s 后自动收起（refresh/sync 都是幂等快照刷新）。
        val pullState = rememberPullToRefreshState()
        var refreshing by remember { mutableStateOf(false) }
        LaunchedEffect(refreshing) {
            if (refreshing) {
                kotlinx.coroutines.delay(1200)
                refreshing = false
            }
        }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            if (!refreshing) {
                                refreshing = true
                                viewModel.refresh()
                                viewModel.syncGraph(workspacePath)
                            }
                        },
                        state = pullState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                    when (page) {
                        0 -> ConclusionsSection(
                            workspacePath = workspacePath,
                            viewModel = viewModel,
                            onOpenSession = onOpenSession
                        )
                        1 -> if (visibleRoots.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.memory_graph_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            var onlyRunning by rememberSaveable { mutableStateOf(false) }
                            var sortByTokens by rememberSaveable { mutableStateOf(false) }
                            val shownRoots = visibleRoots
                                .filter { !onlyRunning || isRunning(it.id) }
                                .let {
                                    if (sortByTokens) it.sortedByDescending { s -> s.totalInputTokens + s.totalOutputTokens }
                                    else it
                                }
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 分类/排序：线索多起来后，「只看进行中」与「按消耗排序」是最常用的两种切法
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LaneFilterChip(
                                        selected = !onlyRunning,
                                        label = stringResource(R.string.memory_graph_filter_all),
                                        onClick = { onlyRunning = false },
                                    )
                                    LaneFilterChip(
                                        selected = onlyRunning,
                                        label = stringResource(R.string.memory_graph_filter_running),
                                        onClick = { onlyRunning = true },
                                    )
                                    Spacer(Modifier.weight(1f))
                                    LaneFilterChip(
                                        selected = sortByTokens,
                                        label = stringResource(R.string.memory_graph_sort_tokens),
                                        onClick = { sortByTokens = !sortByTokens },
                                    )
                                }
                                LazyColumn(
                                    state = sessionsListState,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.xs, bottom = 84.dp),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                shownRoots.forEach { session ->
                    val stat = activity[session.id]
                    val subs = subSessionsByParent[session.id].orEmpty()
                    val expanded = session.id in expandedIds
                    val running = isRunning(session.id)
                    item(key = session.id) {
                        SessionLaneCard(
                            session = session,
                            stat = stat,
                            subs = subs,
                            running = running,
                            isCurrent = session.id == currentSessionId,
                            expanded = expanded,
                            detail = detail?.takeIf { it.sessionId == session.id },
                            onToggle = {
                                expandedIds = if (expanded) expandedIds - session.id
                                else expandedIds + session.id
                                if (!expanded) {
                                    viewModel.openDetail(
                                        sessionId = session.id,
                                        title = session.title,
                                        goal = session.goal,
                                        goalStatus = session.goalStatus?.name,
                                        activity = stat
                                    )
                                }
                            },
                            onOpenSession = { onOpenSession(session.id) },
                            onOpenSubSession = { onOpenSession(it) },
                            isSubRunning = ::isRunning,
                        )
                    }
                }
                                }
                            }
                        }
                        3 -> ledgerContent?.invoke()
                        else -> GraphSection(
                            workspacePath = workspacePath,
                            viewModel = viewModel
                        )
                    }
                    }
                }
            }

            // 悬浮底部页签：与 Git 本地仓库同款组件，页面内容从其下穿过被渐变遮罩。
            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Clock, stringResource(R.string.graph_timeline_title)),
                    FloatingTabItem(FeatherIcons.MessageSquare, stringResource(R.string.memory_graph_session)),
                    FloatingTabItem(FeatherIcons.Share2, stringResource(R.string.memory_graph_tab_graph)),
                    FloatingTabItem(FeatherIcons.CheckSquare, stringResource(R.string.memory_graph_tab_ledger))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 单条工作线索的时光卡片（泳道行）。 */
@Composable
private fun SessionLaneCard(
    session: ChatSession,
    stat: MemoryGraphViewModel.SessionActivity?,
    subs: List<ChatSession>,
    running: Boolean,
    isCurrent: Boolean,
    expanded: Boolean,
    detail: MemoryGraphViewModel.SessionDetail?,
    onToggle: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenSubSession: (String) -> Unit,
    isSubRunning: (String) -> Boolean,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (isCurrent) 2.dp else 1.dp,
            if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // 头行：状态点 + 标题 + 模式徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                session.goalStatus == GoalStatus.BLOCKED -> MaterialTheme.colorScheme.error
                                running -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = session.title.ifBlank { stringResource(R.string.memory_graph_session) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 进行中的线索蓝色圆点旁再加一个文字标签，不用靠颜色区分（无障碍友好）。
                if (running) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.memory_graph_filter_running),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }
            }
            // 分类标签：模式 / 执行引擎 / 子代理数量——一眼分清「哪个引擎、什么权限、有没有分身」
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LaneTag(stringResource(modeLabelRes(session.mode)))
                LaneTag(
                    AgentEngines.displayName(session.engine)
                        ?: stringResource(R.string.settings_token_stats_source_builtin)
                )
                if (subs.isNotEmpty()) {
                    LaneTag(stringResource(R.string.memory_graph_subs_tag, subs.size))
                }
            }
            // 目标行
            session.goal?.takeIf { it.isNotBlank() }?.let { goal ->
                Text(
                    text = "🎯 " + goal.replace(Regex("\\s+"), " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 待办进度
            val todoTotal = stat?.todoTotal ?: 0
            if (todoTotal > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (stat?.todoDone ?: 0).toFloat() / todoTotal },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(
                            R.string.memory_graph_detail_todo,
                            stat?.todoDone ?: 0, todoTotal
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 统计行：消息 / 总量 / 缓存命中率 / 相对时间 + token 构成条
            val cachedTokens = session.totalCachedInputTokens
            val cacheCreationTokens = session.totalCacheCreationTokens
            val freshTokens = (session.totalInputTokens - cachedTokens - cacheCreationTokens).coerceAtLeast(0)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(R.string.memory_graph_detail_messages, stat?.messageCount ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.memory_graph_stat_tokens_short,
                        formatTokens(session.totalInputTokens + session.totalOutputTokens)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.totalInputTokens > 0 && cachedTokens > 0) {
                    Text(
                        text = stringResource(
                            R.string.memory_graph_cache_rate,
                            "%.0f%%".format(
                                java.util.Locale.US,
                                (cachedTokens * 100.0 / session.totalInputTokens).coerceAtMost(100.0)
                            )
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeTime(stat?.lastActiveAt ?: session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LaneCompositionBar(
                fresh = freshTokens,
                cached = cachedTokens,
                cacheCreation = cacheCreationTokens,
                output = session.totalOutputTokens
            )
            // 子代理行（内嵌泳道层级）
            subs.forEach { sub ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.lg)
                        .clickable { onOpenSubSession(sub.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSubRunning(sub.id)) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = sub.subagentType ?: stringResource(R.string.memory_graph_session),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = Spacing.xs),
                    )
                    Text(
                        text = sub.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 内联展开详情
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (detail != null) {
                    detail.goal?.takeIf { it.isNotBlank() }?.let { goal ->
                        Text(
                            text = stringResource(R.string.memory_graph_detail_goal) + "：" + goal,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    detail.lastMessagePreview?.takeIf { it.isNotBlank() }?.let { preview ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.memory_graph_detail_last_msg) + "：" + preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        Text(
                            text = stringResource(R.string.memory_graph_detail_checkpoints, detail.checkpointCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        detail.goalStatus?.let {
                            Text(
                                text = "goal: $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSession),
                ) {
                    Text(
                        text = stringResource(R.string.memory_graph_detail_open),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 图谱顶部的四格统计：线索数、进行中、累计 token（输入+输出）、缓存命中率。
 *
 * 缓存率单独浮现，是因为「累计 token」现在含缓存命中；不展示命中率的话，
 * 用户会把百万级缓存读取误读成同样量级的真实消耗。
 */
@Composable
private fun SessionStatsBar(
    threads: Int,
    running: Int,
    totalInputTokens: Int,
    totalOutputTokens: Int,
    totalCachedTokens: Int,
    modifier: Modifier = Modifier,
) {
    val cacheRate = if (totalInputTokens > 0) {
        "%.0f%%".format(
            java.util.Locale.US,
            (totalCachedTokens * 100.0 / totalInputTokens).coerceAtMost(100.0)
        )
    } else {
        "-"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatCell(
                value = threads.toString(),
                label = stringResource(R.string.memory_graph_stat_threads_label),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = running.toString(),
                label = stringResource(R.string.memory_graph_stat_running_label),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = formatTokens(totalInputTokens + totalOutputTokens),
                label = stringResource(R.string.memory_graph_stat_tokens_label),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                value = cacheRate,
                label = stringResource(R.string.memory_graph_stat_cache_rate_label),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 线索卡上的轻量标签：模式 / 引擎 / 子代理数。 */
@Composable
private fun LaneTag(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun LaneFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** 单条会话的 token 构成：全价输入 / 缓存读取 / 缓存写入 / 输出。 */
@Composable
private fun LaneCompositionBar(fresh: Int, cached: Int, cacheCreation: Int, output: Int) {
    val segments = listOf(
        fresh to TokenStatsPalette.Input,
        cached to TokenStatsPalette.Cache,
        cacheCreation to TokenStatsPalette.CacheWrite,
        output to TokenStatsPalette.Output,
    ).filter { it.first > 0 }
    if (segments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp)),
    ) {
        segments.forEach { (value, color) ->
            Box(
                modifier = Modifier
                    .weight(value.toFloat())
                    .fillMaxHeight()
                    .background(color)
            )
        }
    }
}

private fun modeLabelRes(mode: AgentMode): Int = when (mode) {
    AgentMode.BUILD -> R.string.agent_mode_build
    AgentMode.PLAN -> R.string.agent_mode_plan
    AgentMode.AUTO -> R.string.agent_mode_auto
}

@Composable
/** token 数缩写：1234 → 1.2k，1234567 → 1.2M。 */
internal fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, tokens / 1_000_000f)
    tokens >= 1_000 -> "%.1fk".format(java.util.Locale.US, tokens / 1_000f)
    else -> tokens.toString()
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / MM-dd。 */
internal fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}
