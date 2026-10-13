package com.aicode.feature.memorygraph.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.AgentUIState

/** 项目（工作区）节点固定 id：不归任何会话，仅作根节点。 */
private const val PROJECT_NODE_ID = "__project__"

private val NodeWidth = 152.dp
private val NodeHeight = 56.dp
private val ColumnGap = 24.dp
private val SubGap = 16.dp
private val LevelGap = 104.dp

private enum class GraphNodeKind { PROJECT, SESSION, SUBAGENT }

private data class GraphNode(
    val id: String,
    val kind: GraphNodeKind,
    val title: String,
    val subtitle: String?,
    val sessionId: String?,
    val running: Boolean,
    val x: Dp,
    val y: Dp,
)

private data class GraphLayout(
    val nodes: List<GraphNode>,
    val edges: List<Pair<String, String>>,
    val width: Dp,
    val height: Dp,
)

/**
 * 记忆图谱（P1 精简版）：用现有会话数据把「项目 → 会话 → 子代理」画成一张可点选的图。
 *
 * 纯只读视图，不落库、不调用模型——数据全部来自主会话已加载的 [ChatSession] 与运行状态。点击会话/子代理
 * 节点回调 [onOpenSession] 切回对应会话。
 */
@Composable
fun MemoryGraphScreen(
    workspacePath: String,
    sessions: List<ChatSession>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    agentStates: Map<String, AgentUIState>,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    var runningOnly by rememberSaveable { mutableStateOf(true) }

    val layout = remember(sessions, subSessionsByParent, agentStates, workspacePath, runningOnly) {
        buildLayout(workspacePath, sessions, subSessionsByParent, agentStates, runningOnly)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        MemoryGraphHeader(
            workspacePath = workspacePath,
            runningOnly = runningOnly,
            sessionCount = layout.nodes.count { it.kind == GraphNodeKind.SESSION },
            childCount = layout.nodes.count { it.kind == GraphNodeKind.SUBAGENT },
            onToggleRunningOnly = { runningOnly = !runningOnly },
            onBack = onBack,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (layout.nodes.none { it.sessionId != null }) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.memory_graph_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll)
            ) {
                Box(modifier = Modifier.size(layout.width, layout.height)) {
                    val edgeColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        layout.edges.forEach { (fromId, toId) ->
                            val from = layout.nodes.firstOrNull { it.id == fromId } ?: return@forEach
                            val to = layout.nodes.firstOrNull { it.id == toId } ?: return@forEach
                            drawLine(
                                color = edgeColor,
                                start = Offset(from.x.toPx() + NodeWidth.toPx() / 2f, from.y.toPx() + NodeHeight.toPx()),
                                end = Offset(to.x.toPx() + NodeWidth.toPx() / 2f, to.y.toPx()),
                                strokeWidth = 2f,
                            )
                        }
                    }
                    layout.nodes.forEach { node ->
                        GraphNodeCard(
                            node = node,
                            selected = node.sessionId != null && node.sessionId == currentSessionId,
                            modifier = Modifier
                                .offset(x = node.x, y = node.y)
                                .size(NodeWidth, NodeHeight),
                            onClick = { node.sessionId?.let(onOpenSession) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryGraphHeader(
    workspacePath: String,
    runningOnly: Boolean,
    sessionCount: Int,
    childCount: Int,
    onToggleRunningOnly: () -> Unit,
    onBack: () -> Unit,
) {
    val displayName = workspacePath.substringAfterLast('/').ifBlank { workspacePath }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                FeatherIcons.ArrowLeft,
                contentDescription = stringResource(R.string.memory_graph_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.memory_graph_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = displayName.ifBlank { stringResource(R.string.memory_graph_project) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "$sessionCount · $childCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        FilterChip(
            selected = runningOnly,
            onClick = onToggleRunningOnly,
            label = { Text(stringResource(R.string.memory_graph_running_only)) },
        )
    }
}

@Composable
private fun GraphNodeCard(
    node: GraphNode,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        node.running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier.clickable(enabled = node.sessionId != null, onClick = onClick),
        shape = RoundedCornerShape(Radius.md),
        color = if (node.kind == GraphNodeKind.PROJECT) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.running) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.title.ifBlank { stringResource(R.string.memory_graph_session) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                node.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 由会话数据推导节点坐标：项目居中置顶，每个根会话占一个横排「块」（宽度随子代理数量变化），
 * 子代理排在其父会话下方。不做力导向，保证布局稳定、可预测。
 */
private fun buildLayout(
    workspacePath: String,
    sessions: List<ChatSession>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    agentStates: Map<String, AgentUIState>,
    runningOnly: Boolean,
): GraphLayout {
    fun isRunning(id: String): Boolean {
        val state = agentStates[id]
        return state is AgentUIState.Loading || state is AgentUIState.Streaming
    }

    val visibleSessions = sessions.mapNotNull { root ->
        val subs = subSessionsByParent[root.id].orEmpty().filter { !runningOnly || isRunning(it.id) }
        val visible = !runningOnly || isRunning(root.id) || subs.isNotEmpty()
        if (visible) root to subs else null
    }

    val displayName = workspacePath.substringAfterLast('/').ifBlank { workspacePath }

    val nodes = mutableListOf<GraphNode>()
    val edges = mutableListOf<Pair<String, String>>()

    // 先横向排布每个根会话的块，累加出总宽；项目节点再整体居中。
    var cursor = ColumnGap
    val blockStartX = mutableListOf<Dp>()
    visibleSessions.forEach { (_, subs) ->
        blockStartX.add(cursor)
        val columns = maxOf(1, subs.size)
        cursor += NodeWidth * columns + SubGap * (columns - 1) + ColumnGap
    }
    val totalWidth = maxOf(cursor, NodeWidth + ColumnGap * 2)
    val projectX = (totalWidth - NodeWidth) / 2

    val sessionY = Spacing.lg + NodeHeight + LevelGap
    val subagentY = sessionY + NodeHeight + LevelGap

    nodes.add(
        GraphNode(
            id = PROJECT_NODE_ID,
            kind = GraphNodeKind.PROJECT,
            title = displayName.ifBlank { workspacePath },
            subtitle = null,
            sessionId = null,
            running = false,
            x = projectX,
            y = Spacing.lg,
        )
    )

    visibleSessions.forEachIndexed { index, (root, subs) ->
        val blockStart = blockStartX[index]
        val columns = maxOf(1, subs.size)
        val blockWidth = NodeWidth * columns + SubGap * (columns - 1)
        val sessionX = blockStart + (blockWidth - NodeWidth) / 2
        nodes.add(
            GraphNode(
                id = root.id,
                kind = GraphNodeKind.SESSION,
                title = root.title,
                subtitle = root.model,
                sessionId = root.id,
                running = isRunning(root.id),
                x = sessionX,
                y = sessionY,
            )
        )
        edges.add(PROJECT_NODE_ID to root.id)
        subs.forEachIndexed { subIndex, sub ->
            nodes.add(
                GraphNode(
                    id = sub.id,
                    kind = GraphNodeKind.SUBAGENT,
                    title = sub.title,
                    subtitle = sub.subagentType,
                    sessionId = sub.id,
                    running = isRunning(sub.id),
                    x = blockStart + (NodeWidth + SubGap) * subIndex,
                    y = subagentY,
                )
            )
            edges.add(root.id to sub.id)
        }
    }

    val height = if (visibleSessions.isEmpty()) {
        Spacing.lg + NodeHeight + Spacing.lg
    } else {
        subagentY + NodeHeight + Spacing.lg
    }
    return GraphLayout(nodes = nodes, edges = edges, width = totalWidth, height = height)
}
