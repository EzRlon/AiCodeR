package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.semanticColors
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** 时间线节点类型（v1：提问 / 工具调用 / 结果 / 验收报告；「方案、进度汇报」节点留 v1.1）。 */
internal enum class TimelineNodeType { USER, TOOL, ASSISTANT_RESULT, VERIFICATION }

/** 时间线节点：[itemIndex] 为对应 chatItems 下标（点击跳转定位用）。 */
internal data class TimelineNode(val itemIndex: Int, val type: TimelineNodeType)

/**
 * 时间线触摸热区宽度（dp）：整条可点，与列表内容区右侧留白（contentPadding end = 16dp）等宽——
 * 热区不越入内容区，避免接管右对齐消息操作按钮区域的点击与竖向拖拽；视觉元素居中于留白区。
 */
private val TIMELINE_TOUCH_WIDTH = 16.dp

/** 高密降级阈值（dp）：节点间距低于第一档只渲染「提问」节点，低于第二档再抽稀（保头尾）。 */
private val TIMELINE_MIN_SPACING = 3.dp
private val TIMELINE_TIGHT_SPACING = 1.5.dp

/** 点击节点后的短暂高亮时长（ms）：一次性过渡，不循环。 */
private const val TIMELINE_CLICK_HIGHLIGHT_MS = 450L

/**
 * 右侧时间线 / 对话鸟瞰：覆在聊天列表容器右缘的窄条 overlay。
 *
 * 静态绘制 + 滚动轻量更新（snapshotFlow 观察可见区间，无动画循环、无轮询）；
 * 键盘弹起 / 横竖屏 / 右栏开合均随列表容器高度自适应（由调用方以 fillMaxHeight 挂载）。
 *
 * @param nodes 关键节点（itemIndex 升序，与列表从上到下一致）
 * @param listState 聊天列表状态：点击节点跳转定位、可见区间高亮
 * @param onNodeClick 点击回传（调用方执行 animateScrollToItem）
 */
@Composable
internal fun TimelineRail(
    nodes: List<TimelineNode>,
    listState: LazyListState,
    onNodeClick: (TimelineNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nodes.isEmpty()) return
    val density = LocalDensity.current
    val minSpacingPx = with(density) { TIMELINE_MIN_SPACING.toPx() }
    val tightSpacingPx = with(density) { TIMELINE_TIGHT_SPACING.toPx() }

    // 视口区间高亮：滚动时轻量更新（布局信息由列表提供，不额外测量）。
    var visibleRange by remember(listState) { mutableStateOf(IntRange.EMPTY) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.firstVisibleItemIndex
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            first to last
        }.distinctUntilChanged().collect { (first, last) ->
            visibleRange = if (last >= first) first..last else IntRange.EMPTY
        }
    }

    // 点击后的短暂高亮：延时自动熄灭。tick 保证连点同一节点也能重新计时。
    var clickedItemIndex by remember(listState) { mutableStateOf<Int?>(null) }
    var clickTick by remember(listState) { mutableIntStateOf(0) }
    LaunchedEffect(clickedItemIndex, clickTick) {
        if (clickedItemIndex == null) return@LaunchedEffect
        delay(TIMELINE_CLICK_HIGHLIGHT_MS)
        clickedItemIndex = null
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(TIMELINE_TOUCH_WIDTH)
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        // 高密降级：随容器高度逐级收敛（间距不足先只留提问，再不足抽稀保头尾）。
        val displayNodes = remember(nodes, heightPx, minSpacingPx, tightSpacingPx) {
            collapseTimelineNodes(nodes, heightPx, minSpacingPx, tightSpacingPx)
        }

        val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        val userColor = MaterialTheme.colorScheme.primary
        val toolColor = MaterialTheme.colorScheme.tertiary
        val resultColor = MaterialTheme.colorScheme.onSurfaceVariant
        val verificationColor = MaterialTheme.semanticColors.warning
        val viewportColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        val clickHaloColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        val railDescription = stringResource(R.string.timeline_rail_desc)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(displayNodes) {
                    detectTapGestures { offset ->
                        val count = displayNodes.size
                        val h = size.height.toFloat()
                        if (count <= 0 || h <= 0f) return@detectTapGestures
                        // 节点 i 的中心 y = h*(i+1)/(n+1)：按 y 反推最近节点下标。
                        val idx = ((offset.y * (count + 1) / h) - 1f).roundToInt()
                            .coerceIn(0, count - 1)
                        val node = displayNodes[idx]
                        clickedItemIndex = node.itemIndex
                        clickTick++
                        onNodeClick(node)
                    }
                }
                .semantics { contentDescription = railDescription }
        ) {
            val count = displayNodes.size
            if (count == 0) return@Canvas
            val spacing = size.height / (count + 1)
            val cx = size.width / 2
            fun yAt(index: Int): Float = spacing * (index + 1)

            // 细竖线：两端收在首尾节点外侧半个间距，不顶到容器边缘。
            drawLine(
                color = lineColor,
                start = Offset(cx, (yAt(0) - spacing / 2).coerceAtLeast(0f)),
                end = Offset(cx, (yAt(count - 1) + spacing / 2).coerceAtMost(size.height)),
                strokeWidth = 1.dp.toPx()
            )

            // 视口胶囊：当前可见区间覆盖的节点范围（滚动时轻量跟随，停止后保持）。宽度收敛到略粗于节点，
            // 不再喧宾夺主（验收反馈：绿线过粗）。
            val highlighted = displayNodes.withIndex().filter { it.value.itemIndex in visibleRange }
            if (highlighted.isNotEmpty()) {
                val top = (yAt(highlighted.first().index) - spacing / 2).coerceAtLeast(0f)
                val bottom = (yAt(highlighted.last().index) + spacing / 2).coerceAtMost(size.height)
                val capsuleWidth = 4.dp.toPx()
                if (bottom > top) {
                    drawRoundRect(
                        color = viewportColor,
                        topLeft = Offset(cx - capsuleWidth / 2, top),
                        size = Size(capsuleWidth, bottom - top),
                        cornerRadius = CornerRadius(capsuleWidth / 2, capsuleWidth / 2)
                    )
                }
            }

            // 节点圆点：提问=主色实心、工具=第三色细点、结果=弱色细点、验收报告=警告色特标（验收反馈：整体调小一档）。
            displayNodes.forEachIndexed { index, node ->
                val y = yAt(index)
                val diameter = when (node.type) {
                    TimelineNodeType.USER -> 2.5.dp.toPx()
                    TimelineNodeType.TOOL -> 2.dp.toPx()
                    TimelineNodeType.ASSISTANT_RESULT -> 2.dp.toPx()
                    TimelineNodeType.VERIFICATION -> 3.dp.toPx()
                }
                val color = when (node.type) {
                    TimelineNodeType.USER -> userColor
                    TimelineNodeType.TOOL -> toolColor
                    TimelineNodeType.ASSISTANT_RESULT -> resultColor
                    TimelineNodeType.VERIFICATION -> verificationColor
                }
                if (node.itemIndex == clickedItemIndex) {
                    drawCircle(
                        color = clickHaloColor,
                        radius = diameter / 2 + 2.dp.toPx(),
                        center = Offset(cx, y)
                    )
                }
                drawCircle(color = color, radius = diameter / 2, center = Offset(cx, y))
            }
        }
    }
}

/**
 * 高密降级：节点间距不足时逐级收敛显示。
 *
 * 1）间距 < [minSpacingPx]：只留「提问」节点（最能代表会话骨架，工具/结果/验收一并剪掉；若一个提问都没有则保留原列表）；
 * 2）仍 < [tightSpacingPx]：等距抽稀（保头尾），控制堆叠。
 */
private fun collapseTimelineNodes(
    nodes: List<TimelineNode>,
    heightPx: Float,
    minSpacingPx: Float,
    tightSpacingPx: Float,
): List<TimelineNode> {
    if (nodes.size <= 1 || heightPx <= 0f) return nodes
    var display = nodes
    if (heightPx / (display.size + 1) < minSpacingPx) {
        val users = display.filter { it.type == TimelineNodeType.USER }
        if (users.isNotEmpty()) display = users
    }
    if (display.size > 1 && heightPx / (display.size + 1) < tightSpacingPx) {
        val maxCount = ((heightPx / tightSpacingPx).toInt() - 1).coerceAtLeast(2)
        val step = ceil(display.size.toFloat() / maxCount).toInt().coerceAtLeast(1)
        display = display.filterIndexed { index, _ -> index % step == 0 || index == display.lastIndex }
    }
    return display
}
