package com.aicode.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import kotlin.math.roundToInt

/** 锚点菜单的一个操作项。 */
data class AnchorMenuEntry(
    val icon: ImageVector,
    val labelRes: Int,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/** 卡片宽度（dp）。 */
private val MENU_CARD_WIDTH = 240.dp

/** 卡片最大高度（dp）：条目多时卡片内滚动。 */
private val MENU_MAX_HEIGHT = 320.dp

/** 条目行高（dp）。 */
private val MENU_ROW_HEIGHT = 38.dp

/** 锚点与卡片之间的间距（dp）。 */
private val MENU_ANCHOR_GAP = 8.dp

/** 卡片与容器边缘的安全边距（dp）。 */
private val MENU_EDGE_MARGIN = 8.dp

/** 入场动画时长（ms）与初始缩放：克制淡入 + 轻缩放。 */
private const val MENU_APPEAR_ANIM_MS = 150
private const val MENU_APPEAR_SCALE_START = 0.92f

/**
 * 通用锚点小卡片菜单：紧贴锚点的紧凑卡片（非全宽弹层），优先在锚点上方升起、
 * 空间不足放下方，水平/垂直 clamp 在容器内；点卡片外关闭。
 *
 * 与消息长按的 mini 浮窗同款形态（同一套定位与入场参数；消息浮窗是它的消息特化版）。
 * 菜单只收集意图，具体动作由调用方在 [AnchorMenuEntry.action] 里执行；点击条目先关闭再执行。
 *
 * @param anchorInWindow 锚点矩形（窗口坐标；零尺寸矩形 = 触点）
 * @param containerOriginInWindow 容器（覆盖全屏的根 Box）在窗口中的左上角
 */
@Composable
fun AnchorMenuPopup(
    title: String,
    entries: List<AnchorMenuEntry>,
    anchorInWindow: Rect,
    containerOriginInWindow: Offset,
    onDismiss: () -> Unit,
) {
    if (entries.isEmpty()) return

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = MENU_APPEAR_ANIM_MS),
        label = "anchor-menu-appear"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val gapPx = with(density) { MENU_ANCHOR_GAP.toPx() }
        val marginPx = with(density) { MENU_EDGE_MARGIN.toPx() }
        val cardWidthPx = minOf(
            with(density) { MENU_CARD_WIDTH.toPx() },
            (containerWidthPx - marginPx * 2).coerceAtLeast(0f)
        )
        val maxCardHeight = minOf(
            MENU_MAX_HEIGHT,
            with(density) { (containerHeightPx - marginPx * 2).coerceAtLeast(0f).toDp() }
        )

        // 锚点转为容器局部坐标（容器即根 Box，均为窗口坐标，相减即得）。
        val anchorLeft = anchorInWindow.left - containerOriginInWindow.x
        val anchorTop = anchorInWindow.top - containerOriginInWindow.y
        val anchorBottom = anchorInWindow.bottom - containerOriginInWindow.y

        // 卡片高度首帧未知（按条目数先估）：入场动画期间用实测高度校正，用户无感。
        // 估算不超过卡片最大高度（条目多时卡片封顶内滚），否则首帧 dropUp 判断与实测不一致会跳位。
        var cardHeightPx by remember { mutableStateOf(0) }
        val estimatedHeightPx = with(density) {
            (MENU_ROW_HEIGHT * entries.size + Spacing.xs * 2).toPx().coerceAtMost(maxCardHeight.toPx())
        }
        val effectiveHeightPx = if (cardHeightPx > 0) cardHeightPx.toFloat() else estimatedHeightPx

        // 垂直方向：优先锚点上方；上方放不下放下方；都放不下时贴边 clamp。
        val dropUp = anchorTop - gapPx - effectiveHeightPx >= marginPx
        val yPx = (if (dropUp) anchorTop - gapPx - effectiveHeightPx else anchorBottom + gapPx)
            .coerceIn(marginPx, (containerHeightPx - effectiveHeightPx - marginPx).coerceAtLeast(marginPx))
        // 水平方向：对齐锚点左缘，clamp 在容器内。
        val xPx = anchorLeft
            .coerceIn(marginPx, (containerWidthPx - cardWidthPx - marginPx).coerceAtLeast(marginPx))

        // scrim：点卡片外任一处关闭（半透明，不转移视觉焦点）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                .width(with(density) { cardWidthPx.toDp() })
                .graphicsLayer {
                    alpha = appearProgress
                    val scale = MENU_APPEAR_SCALE_START + (1f - MENU_APPEAR_SCALE_START) * appearProgress
                    scaleX = scale
                    scaleY = scale
                    // 从贴近锚点的一侧生长（上弹贴底、下弹贴顶）。
                    transformOrigin = TransformOrigin(0.5f, if (dropUp) 1f else 0f)
                }
                .onSizeChanged { cardHeightPx = it.height }
                .semantics { paneTitle = title }
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = Spacing.xs)
                    .heightIn(max = maxCardHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                entries.forEach { entry ->
                    AnchorMenuItem(entry = entry) {
                        onDismiss()
                        entry.action()
                    }
                }
            }
        }
    }
}

/** 单个操作条目：图标 + 标签，紧凑单行。 */
@Composable
private fun AnchorMenuItem(entry: AnchorMenuEntry, onClick: () -> Unit) {
    val color = if (entry.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md)
            .height(MENU_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(entry.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
