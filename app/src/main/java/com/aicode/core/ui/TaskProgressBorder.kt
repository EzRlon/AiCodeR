package com.aicode.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
 * 弧长按任务进度点亮，节点均匀分布其上
 * （已完成实心、进行中呼吸、未开始描边）。
 *
 * 纯绘制组件、不参与触摸命中（本层为全尺寸覆盖，自行挂手势会独占卡片内交互；
 * 节点点击由上层用独立小热点实现，见 ChatInputBar）。空闲时上层直接不组合即可。
 */
@Composable
fun TaskProgressBorder(
    progress: Float,
    nodes: List<ProgressNode>,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    activeColor: Color,
    completedColor: Color,
    pendingColor: Color,
    trackColor: Color
) {
    // 进度变化时弧长平滑过渡
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
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

    Canvas(modifier = modifier) {
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
        // 弧长 = 完成比例，从路径起点顺时针点亮（无 todo 时调用方不渲染本组件，不做假进度）
        val seg = Path()
        if (animatedProgress > 0.001f) {
            measure.getSegment(0f, total * animatedProgress, seg, true)
            drawPath(seg, completedColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        }

        // 节点：均匀内缩分布在整圈轨迹上（点击由上层热点层实现）
        if (nodes.isNotEmpty()) {
            nodes.forEachIndexed { index, node ->
                val distance = total * (index + 1f) / (nodes.size + 1f)
                val pos = measure.getPosition(distance)
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
        }
    }
}
