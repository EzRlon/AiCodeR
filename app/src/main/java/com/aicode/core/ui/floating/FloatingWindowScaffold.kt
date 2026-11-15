package com.aicode.core.ui.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Maximize2
import compose.icons.feathericons.Minus
import compose.icons.feathericons.X
import kotlin.math.roundToInt

/** 浮窗几何信息，单位 dp；用于在进程重启后恢复位置与尺寸。 */
data class FloatingWindowGeometry(
    val xDp: Float,
    val yDp: Float,
    val widthDp: Float,
    val heightDp: Float
)

/**
 * 应用内自由小窗的几何状态。
 *
 * 行为对齐 VNC 悬浮小窗：标题栏拖动移动、右下角手柄缩放、最小尺寸 220×180dp、
 * 位置至少留 56dp 在屏内、可钉住（锁定拖动与缩放）、可最小化成球。
 */
@Stable
class FloatingWindowState(initial: FloatingWindowGeometry) {
    var offsetXDp by mutableFloatStateOf(initial.xDp)
    var offsetYDp by mutableFloatStateOf(initial.yDp)
    var widthDp by mutableFloatStateOf(initial.widthDp)
    var heightDp by mutableFloatStateOf(initial.heightDp)
    var pinned by mutableStateOf(false)
    var minimized by mutableStateOf(false)

    fun snapshot() = FloatingWindowGeometry(offsetXDp, offsetYDp, widthDp, heightDp)
}

/**
 * 可拖动 / 可缩放的通用浮窗容器。
 *
 * 只负责几何与外壳（标题栏、最小化球、缩放手柄），内容通过 [content] 注入；
 * 调用方负责在组合外持久化 [FloatingWindowGeometry]，并在宿主根布局里挂载（盖住导航内容）。
 */
@Composable
fun FloatingWindowScaffold(
    title: String,
    state: FloatingWindowState,
    onClose: () -> Unit,
    onGeometryCommitted: (FloatingWindowGeometry) -> Unit,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parentWidth = maxWidth
        val parentHeight = maxHeight
        val minWidth = 220.dp
        val minHeight = 180.dp
        val maxWidthBound = (parentWidth - 16.dp).coerceAtLeast(minWidth)
        val maxHeightBound = (parentHeight - 48.dp).coerceAtLeast(minHeight)

        // 键盘弹出时把窗口顶到键盘之上（宿主 ADJUST_NOTHING，不会自动缩放）。
        // 只按「窗口与键盘的重叠量」上移，不做整体减 IME 高度：否则键盘较矮时窗口也会被
        // 推得很高，观感突兀。
        val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
        val width = state.widthDp.dp.coerceIn(minWidth, maxWidthBound)
        val heightBase = state.heightDp.dp.coerceIn(minHeight, maxHeightBound)
        val height = if (imeDp > 0.dp) {
            heightBase.coerceAtMost((parentHeight - imeDp - 16.dp).coerceAtLeast(minHeight))
        } else {
            heightBase
        }
        // 顶部边界取状态栏下沿：edge-to-edge 下若允许拖到 0，标题栏会落进状态栏触摸区，
        // 按钮点不到、窗口也拖不回来（几何还会被持久化）。
        val statusBarDp = with(density) { WindowInsets.statusBars.getBottom(density).toDp() }
        val maxX = (parentWidth - 56.dp).coerceAtLeast(0.dp)
        val minY = statusBarDp.coerceAtMost((parentHeight - 56.dp).coerceAtLeast(0.dp))
        val maxY = (parentHeight - 56.dp).coerceAtLeast(minY)
        val x = state.offsetXDp.dp.coerceIn(0.dp, maxX)
        val baseY = state.offsetYDp.dp.coerceIn(minY, maxY)
        val overlap = if (imeDp > 0.dp) {
            (baseY + height - (parentHeight - imeDp)).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        val y = (baseY - overlap).coerceAtLeast(0.dp)

        if (state.minimized) {
            MinimizedBall(
                x = x,
                y = y,
                onRestore = { state.minimized = false }
            )
            return@BoxWithConstraints
        }

        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToPx(), y.roundToPx()) }
                .size(width, height),
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(state.pinned, parentWidth, parentHeight) {
                            if (state.pinned) return@pointerInput
                            detectDragGestures(
                                onDragEnd = { onGeometryCommitted(state.snapshot()) },
                                onDragCancel = { onGeometryCommitted(state.snapshot()) }
                            ) { change, dragAmount ->
                                change.consume()
                                state.offsetXDp = (state.offsetXDp + dragAmount.x / density.density)
                                    .coerceIn(0f, (parentWidth - 56.dp).value)
                                state.offsetYDp = (state.offsetYDp + dragAmount.y / density.density)
                                    .coerceIn(statusBarDp.value, (parentHeight - 56.dp).value)
                            }
                        }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    headerActions()
                    IconButton(onClick = { state.minimized = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            FeatherIcons.Minus,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(
                            FeatherIcons.X,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Box(modifier = Modifier.weight(1f)) {
                    content()
                    ResizeHandle(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        pinned = state.pinned,
                        parentWidth = parentWidth,
                        parentHeight = parentHeight,
                        minWidth = minWidth,
                        minHeight = minHeight,
                        onResize = { dxDp, dyDp ->
                            state.widthDp = (state.widthDp + dxDp).coerceIn(minWidth.value, maxWidthBound.value)
                            state.heightDp = (state.heightDp + dyDp).coerceIn(minHeight.value, maxHeightBound.value)
                        },
                        onCommit = { onGeometryCommitted(state.snapshot()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    pinned: Boolean,
    parentWidth: androidx.compose.ui.unit.Dp,
    parentHeight: androidx.compose.ui.unit.Dp,
    minWidth: androidx.compose.ui.unit.Dp,
    minHeight: androidx.compose.ui.unit.Dp,
    onResize: (Float, Float) -> Unit,
    onCommit: () -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .size(28.dp)
            .pointerInput(pinned, parentWidth, parentHeight, minWidth, minHeight) {
                if (pinned) return@pointerInput
                detectDragGestures(
                    onDragEnd = onCommit,
                    onDragCancel = onCommit
                ) { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount.x / density.density, dragAmount.y / density.density)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            FeatherIcons.Maximize2,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun MinimizedBall(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, onRestore: () -> Unit) {
    Surface(
        modifier = Modifier
            .offset { IntOffset(x.roundToPx(), y.roundToPx()) }
            .size(48.dp)
            .clickable(onClick = onRestore),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                FeatherIcons.FileText,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
