package com.aicode.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 进度环上节点的状态。 */
enum class ProgressNodeState { COMPLETED, ACTIVE, PENDING }

/** 进度环节点：state 决定画法，label 供点击后的悬浮提示使用。 */
data class ProgressNode(
    val state: ProgressNodeState,
    val label: String
)

/**
 * 依附于容器外围的「任务进度线框」：在容器边框外侧画一圈圆角矩形描边，
 * 弧长按任务进度点亮（progress 为 null 时表现为流动光带），节点均匀分布其上
 * （已完成实心、进行中呼吸、未开始描边），点击节点回调 [onNodeClick]。
 *
 * 纯绘制组件、无文字无背景，空闲时上层直接不组合即可，不影响容器本身的整洁。
 */
@Composable
fun TaskProgressBorder(
    progress: Float?,
    nodes: List<ProgressNode>,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    activeColor: Color,
    completedColor: Color,
    pendingColor: Color,
    trackColor: Color,
    onNodeClick: ((index: Int) -> Unit)? = null
) {
    // 进度变化时弧长平滑过渡
    val animatedProgress by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "task-progress-arc"
    )
    val breath = rememberInfiniteTransition(label = "task-progress-breath")
    val breathAlpha by breath.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "task-progress-breath-alpha"
    )
    val flowHead by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "task-progress-flow-head"
    )

    // 最近一次绘制出的节点像素位置：点击命中用
    var nodePositions by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // 回调每次重组都是新 lambda 实例；经 rememberUpdatedState 引用，手势协程无需因它重启
    val currentOnNodeClick by rememberUpdatedState(onNodeClick)

    Canvas(
        modifier = modifier.pointerInput(nodes) {
            // 只在命中节点时消费 up 并回调：未命中的点击原样穿透到容器内部控件；
            // 命中时消费 up 可避免同一次点击又触发下层按钮/输入框（双动作）
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                val tap = up.position
                val hit = nodePositions.withIndex().minByOrNull { (_, p) ->
                    (p - tap).getDistance()
                } ?: return@awaitEachGesture
                if ((hit.value - tap).getDistance() <= 28.dp.toPx()) {
                    up.consume()
                    currentOnNodeClick?.invoke(hit.index)
                }
            }
        }
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // 描边画在容器外侧 OUTSET 处，不与容器自身的 border 重叠
        val outset = 3.dp.toPx()
        val radiusPx = cornerRadius.toPx() + outset
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        left = -outset,
                        top = -outset,
                        right = size.width + outset,
                        bottom = size.height + outset
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )
            )
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val total = measure.length

        // 底轨：一圈淡色细线，标示进度环的完整轨迹
        drawPath(path, trackColor, style = Stroke(1.5.dp.toPx()))

        val strokeWidth = 2.5.dp.toPx()
        if (progress == null) {
            // 无 todo：一段流动光带（indeterminate）
            val arcLen = (total * 0.18f).coerceAtMost(200.dp.toPx())
            val seg = Path()
            val start = total * flowHead
            if (start + arcLen <= total) {
                measure.getSegment(start, start + arcLen, seg, true)
            } else {
                measure.getSegment(start, total, seg, true)
                measure.getSegment(0f, (start + arcLen) - total, seg, true)
            }
            drawPath(seg, activeColor.copy(alpha = 0.9f), style = Stroke(strokeWidth, cap = StrokeCap.Round))
        } else {
            // 有 todo：弧长 = 完成比例，从路径起点顺时针点亮
            val seg = Path()
            if (animatedProgress > 0.001f) {
                measure.getSegment(0f, total * animatedProgress, seg, true)
                drawPath(seg, completedColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            }
        }

        // 节点：均匀内缩分布在整圈轨迹上；点击命中位置同步缓存
        if (nodes.isNotEmpty()) {
            val positions = mutableListOf<Offset>()
            nodes.forEachIndexed { index, node ->
                val distance = total * (index + 1f) / (nodes.size + 1f)
                val pos = measure.getPosition(distance)
                positions += pos
                when (node.state) {
                    ProgressNodeState.COMPLETED -> drawCircle(
                        color = completedColor,
                        radius = 3.5.dp.toPx(),
                        center = pos
                    )
                    ProgressNodeState.ACTIVE -> {
                        drawCircle(
                            color = activeColor.copy(alpha = breathAlpha * 0.35f),
                            radius = 8.dp.toPx(),
                            center = pos
                        )
                        drawCircle(
                            color = activeColor,
                            radius = 4.dp.toPx(),
                            center = pos
                        )
                    }
                    ProgressNodeState.PENDING -> drawCircle(
                        color = pendingColor,
                        radius = 3.dp.toPx(),
                        center = pos,
                        style = Stroke(1.5.dp.toPx())
                    )
                }
            }
            nodePositions = positions
        } else {
            nodePositions = emptyList()
        }
    }
}
