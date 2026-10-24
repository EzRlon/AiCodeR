package com.aicode.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 底栏外框表达的任务危险程度。 */
enum class TaskDangerLevel {
    /** 阻塞等待用户裁决（授权/提问/计划审批）：最高危险，红色快脉冲。 */
    AWAITING_APPROVAL,

    /** 任务执行中：跟随发送按钮的忙碌色，慢流动。 */
    RUNNING
}

/**
 * 依附于聊天底栏卡片外围的「任务危险程度动态外框」。
 *
 * 业务语义（用户 2026-10-24 定）：**不做任务进度**（待办已有自己的展示位），外框只表达
 * 当前任务的危险程度——等待审批=红色快脉冲+快流动（最高危，阻塞等用户）；执行中=跟随
 * 发送按钮的忙碌色（Brand.Orange）慢呼吸+慢流动。颜色随发送按钮状态语义一起变化。
 *
 * 视觉：极细描边（主弧约 0.55px、底轨约 0.35px，用户反馈原 1.6px 至少减 2/3）+
 * 流动亮段绕框跑动，全程动态动画。
 *
 * 纯绘制、不参与触摸命中；空闲时上层直接不组合。
 */
@Composable
fun TaskStateBorder(
    danger: TaskDangerLevel,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    runningColor: Color,
    awaitingColor: Color,
    trackColor: Color
) {
    val breath = rememberInfiniteTransition(label = "task-state-border")
    val pulseAlpha by breath.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (danger == TaskDangerLevel.AWAITING_APPROVAL) 750 else 1900),
            RepeatMode.Reverse
        ),
        label = "task-state-border-alpha"
    )
    // 流动亮段：一段短弧沿外框恒向跑动；等待审批时跑得更快（紧迫感）
    val flow by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                durationMillis = if (danger == TaskDangerLevel.AWAITING_APPROVAL) 2600 else 6000,
                easing = LinearEasing
            )
        ),
        label = "task-state-border-flow"
    )
    val color = if (danger == TaskDangerLevel.AWAITING_APPROVAL) awaitingColor else runningColor

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
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

        // 描边粗细以像素直取（不随密度换算回 dp），精确满足「1.6px 至少减 2/3」→ 约 0.5px
        val strokePx = 0.55f
        val trackPx = 0.35f

        // 底轨：一圈极细淡线
        drawPath(path, trackColor, style = Stroke(trackPx))
        // 主线：呼吸 alpha
        drawPath(path, color.copy(alpha = pulseAlpha), style = Stroke(strokePx, cap = StrokeCap.Round))
        // 流动亮段：短弧绕框跑
        val seg = Path()
        if (measure.getSegment(total * flow, total * flow + total * 0.12f, seg, true)) {
            drawPath(seg, color, style = Stroke(strokePx * 1.8f, cap = StrokeCap.Round))
        }
    }
}
