package com.aicode.feature.agent.presentation.component

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Square
import kotlinx.coroutines.delay

/** 轨道高度：与输入卡片相比省掉约 60dp，整条底栏只剩一行摘要。 */
private val RAIL_HEIGHT = 44.dp

/** 停止键热区。用 Box 而非 IconButton：后者有 48dp 最小交互尺寸，会把轨道撑高。 */
private val RAIL_STOP_SLOT = 32.dp

/** 上滑多少距离算「展开」手势。用固定的 24dp 而不是速度阈值：轨道很矮，靠速度判断太灵敏。 */
private val RAIL_SWIPE_THRESHOLD = 24.dp

/** 连续多久没有任何新活动就提示可能卡住。只提示，不自动展开/收起。 */
internal const val STALL_WARN_MS = 120_000L

/** 轨道告警状态：是否判定为「可能卡住」，以及要显示的秒数。 */
internal data class RailStallState(val stalled: Boolean, val idleSeconds: Int)

/**
 * 把「距上次活动过了多久」换算成轨道展示状态。
 *
 * 抽成纯函数是为了能单测边界：120 秒整到底算不算告警、负数/时钟回拨怎么处理。
 * 时钟回拨（[idleMs] 为负）按 0 处理——宁可晚一秒报警，也不要显示「已 -3 秒无输出」。
 */
internal fun railStallState(idleMs: Long): RailStallState {
    val safe = idleMs.coerceAtLeast(0L)
    return RailStallState(
        stalled = safe >= STALL_WARN_MS,
        idleSeconds = (safe / 1000L).toInt()
    )
}

/** 计时刷新间隔。5 秒足够让「已 N 秒无输出」跳动得自然，又不至于让轨道每帧重组。 */
private const val STALL_TICK_MS = 5_000L

/**
 * 长任务进行中、用户手动收起底栏后显示的「任务轨道」。
 *
 * 收起后不是什么都不给，而是把**最需要一眼看到的三件事**压成一行——
 * 在跑什么、跑到哪、以及随时可用的停止键。点任意位置展开，**上滑也能展开**。
 *
 * ### 卡住告警
 *
 * 连续 [STALL_WARN_MS] 没有任何新活动（新消息、流式文本增长、切换工具、待办变更）时，
 * 呼吸点转成告警色、文案换成「已 N 秒无输出」。**只改颜色和文案，不自动展开**——
 * 自动展开会打断用户正在做的事，而「可能卡住」只是个推测。
 *
 * 活动信号由宿主以 [activityKey] 传入：它变化一次就重置计时。用「不变的键」而不是
 * 每帧的实时值，避免长任务把轨道刷成高频重组。
 */
@Composable
internal fun TaskRail(
    items: List<TodoItem>,
    queuedCount: Int,
    activityKey: Any?,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalCount = items.size
    val completedCount = items.count { it.status == TodoStatus.COMPLETED }
    // 优先展示「正在做的那一条」；没有进行中的就回落到最后一条，避免轨道变成空格子。
    val focusItem = items.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
        ?: items.lastOrNull()

    // ── 卡住告警计时 ──
    var lastActivityAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(activityKey) {
        lastActivityAt = SystemClock.elapsedRealtime()
    }
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STALL_TICK_MS)
            nowMs = SystemClock.elapsedRealtime()
        }
    }
    val stall = railStallState(nowMs - lastActivityAt)
    val stalled = stall.stalled
    val stalledSeconds = stall.idleSeconds

    // 呼吸点：执行中 = 橙（沿用任务外框语义），卡住时转告警色并加快呼吸。
    val dotColor = if (stalled) MaterialTheme.colorScheme.error else Brand.Orange
    val breath = rememberInfiniteTransition(label = "task-rail-dot")
    val dotAlpha by breath.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (stalled) 700 else 1400),
            RepeatMode.Reverse
        ),
        label = "task-rail-dot-alpha"
    )

    // 描边与卡片底色完全一致：收起后仍能靠形状/渐变蒙版分辨边界，但不露出一圈色差线框，
    // 与展开态输入卡（ChatInputBar 的同色描边）保持一致，任何主题下都融为一体。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RAIL_HEIGHT)
                .clickable(onClick = onExpand)
                // 上滑展开：与「点一下就开」互补，手在屏幕下沿时更顺手。
                .pointerInput(Unit) {
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDy = 0f },
                        onDragEnd = {
                            if (totalDy < -RAIL_SWIPE_THRESHOLD.toPx()) onExpand()
                        },
                        onDragCancel = { totalDy = 0f },
                        onVerticalDrag = { change, dy ->
                            totalDy += dy
                            change.consume()
                        }
                    )
                }
                .padding(start = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = dotAlpha))
            )

            Text(
                text = if (stalled) {
                    // 超过一分钟改用分钟，避免出现「已 137 秒无输出」这种难读的数字。
                    if (stalledSeconds >= 60) {
                        stringResource(R.string.bottom_bar_rail_stalled_minutes, stalledSeconds / 60)
                    } else {
                        stringResource(R.string.bottom_bar_rail_stalled_seconds, stalledSeconds)
                    }
                } else {
                    focusItem?.subject ?: stringResource(R.string.bottom_bar_rail_running)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (stalled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (totalCount > 0) {
                Surface(
                    shape = RoundedCornerShape(Radius.xs),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = stringResource(
                            R.string.bottom_bar_rail_progress,
                            completedCount,
                            totalCount
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }

            if (queuedCount > 0) {
                Text(
                    text = stringResource(R.string.bottom_bar_rail_queued, queuedCount),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 停止键独立热区：点它只停任务、不展开（用户可能正想让它停下来但不想看长列表）。
            Box(
                modifier = Modifier
                    .size(RAIL_STOP_SLOT)
                    .clip(CircleShape)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Square,
                    contentDescription = stringResource(R.string.bottom_bar_rail_stop),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }

            Icon(
                FeatherIcons.ChevronUp,
                contentDescription = stringResource(R.string.bottom_bar_rail_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(2.dp))
        }
    }
}
