package com.aicode.feature.vnc.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.vnc.domain.VncServiceState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Maximize2
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.X
import kotlin.math.roundToInt

/**
 * 对话页 VNC 悬浮小窗（应用内自由小窗）：置顶于聊天内容，可拖动、拖角缩放、
 * 钉住、最小化成球、一键全屏；挂载在聊天页布局最外层，离开聊天页随组合消失、
 * 状态由 [VncOverlayViewModel] 记忆。视觉全部取自 AIEditorTheme 的语义 token。
 */
@Composable
internal fun VncOverlayWindow(
    viewModel: VncOverlayViewModel,
    modifier: Modifier = Modifier,
) {
    val window by viewModel.window.collectAsStateWithLifecycle()
    if (!window.visible) return

    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (window.fullscreen) {
            BackHandler { viewModel.setFullscreen(false) }
            VncWindowSurface(
                viewModel = viewModel,
                window = window,
                serviceState = serviceState,
                connected = connected,
                fullscreen = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (window.minimized) {
            MinimizedBall(
                viewModel = viewModel,
                window = window,
                serviceState = serviceState,
                connected = connected,
                parentWidth = maxWidth,
                parentHeight = maxHeight,
            )
        } else {
            FloatingVncWindow(
                viewModel = viewModel,
                window = window,
                serviceState = serviceState,
                connected = connected,
                parentWidth = maxWidth,
                parentHeight = maxHeight,
            )
        }
    }
}

@Composable
private fun FloatingVncWindow(
    viewModel: VncOverlayViewModel,
    window: VncOverlayViewModel.WindowState,
    serviceState: VncServiceState,
    connected: Boolean,
    parentWidth: Dp,
    parentHeight: Dp,
) {
    val defaultWidth = (parentWidth - 24.dp).coerceAtMost(420.dp)
    val defaultHeight = parentHeight * 0.42f
    LaunchedEffect(parentWidth, parentHeight) {
        viewModel.ensureGeometry(
            defaultXDp = (parentWidth - defaultWidth - 12.dp).value,
            defaultYDp = (parentHeight * 0.18f).value,
            defaultWDp = defaultWidth.value,
            defaultHDp = defaultHeight.value,
        )
    }
    val winWidth = (if (window.widthDp > 0f) window.widthDp.dp else defaultWidth)
        .coerceIn(VncOverlayViewModel.MIN_WIDTH_DP.dp, parentWidth - 24.dp)
    val winHeight = (if (window.heightDp > 0f) window.heightDp.dp else defaultHeight)
        .coerceIn(VncOverlayViewModel.MIN_HEIGHT_DP.dp, parentHeight - 48.dp)
    val offX = (if (window.offsetX == Float.MIN_VALUE) (parentWidth - winWidth - 12.dp).value else window.offsetX)
        .dp.coerceIn(-(winWidth - 56.dp), parentWidth - 56.dp)
    val offY = (if (window.offsetY == Float.MIN_VALUE) (parentHeight * 0.18f).value else window.offsetY)
        .dp.coerceIn(0.dp, parentHeight - 56.dp)

    Box(
        modifier = Modifier
            .offset { IntOffset(offX.roundToPx(), offY.roundToPx()) }
            .size(winWidth, winHeight)
            .shadow(8.dp, RoundedCornerShape(Radius.lg))
            .clip(RoundedCornerShape(Radius.lg)),
    ) {
        VncWindowSurface(
            viewModel = viewModel,
            window = window,
            serviceState = serviceState,
            connected = connected,
            fullscreen = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 窗体内容（浮动与全屏共用）：标题栏 + VNC 画面 + 失败提示覆盖层。 */
@Composable
private fun VncWindowSurface(
    viewModel: VncOverlayViewModel,
    window: VncOverlayViewModel.WindowState,
    serviceState: VncServiceState,
    connected: Boolean,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(if (fullscreen) 0.dp else Radius.lg)
    Column(
        modifier = modifier
            .shadow(if (fullscreen) 0.dp else 8.dp, shape)
            .clip(shape)
            .background(MaterialTheme.semanticColors.cardSurface)
            .border(1.dp, MaterialTheme.semanticColors.subtleBorder, shape),
    ) {
        VncTitleBar(
            viewModel = viewModel,
            window = window,
            serviceState = serviceState,
            connected = connected,
            fullscreen = fullscreen,
        )
        Box(modifier = Modifier.weight(1f)) {
            VncWebViewPanel(
                serviceState = serviceState,
                pasteRequests = viewModel.pasteRequests.collectAsStateWithLifecycle().value,
                reconnectRequests = viewModel.reconnectRequests.collectAsStateWithLifecycle().value,
                onConnectedChange = viewModel::setConnected,
                modifier = Modifier.fillMaxSize(),
            )
            if (!fullscreen && !window.pinned) {
                ResizeCornerHandle(
                    onResize = { dx, dy -> viewModel.resizeBy(dx, dy) },
                    onResizeEnd = viewModel::persistGeometry,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            val failed = serviceState as? VncServiceState.Failed
            val preparing = serviceState is VncServiceState.Preparing
            if (failed != null || preparing) {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.semanticColors.pageBackground.copy(alpha = 0.94f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (failed == null) {
                        // 安装/重试期间保留「准备中」覆盖：避免重试后露出空白 WebView（装包可达数分钟）。
                        Text(
                            text = stringResource(R.string.vnc_preparing_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.vnc_failed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                        Text(
                            text = failed.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                        TextButton(onClick = viewModel::retryStart) {
                            Text(text = stringResource(R.string.vnc_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VncTitleBar(
    viewModel: VncOverlayViewModel,
    window: VncOverlayViewModel.WindowState,
    serviceState: VncServiceState,
    connected: Boolean,
    fullscreen: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (fullscreen || window.pinned) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                viewModel.dragBy(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                            },
                            onDragEnd = { viewModel.persistGeometry() },
                        )
                    }
                }
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusTint(serviceState, connected))
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = stringResource(statusTextRes(serviceState, connected)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        VncTitleAction(
            icon = FeatherIcons.Lock,
            contentDescription = if (window.pinned) R.string.vnc_unpin else R.string.vnc_pin,
            active = window.pinned,
            onClick = { viewModel.setPinned(!window.pinned) },
        )
        VncTitleAction(
            icon = FeatherIcons.Clipboard,
            contentDescription = R.string.vnc_paste,
            onClick = viewModel::requestPaste,
        )
        VncTitleAction(
            icon = FeatherIcons.ChevronDown,
            contentDescription = R.string.vnc_minimize,
            onClick = { viewModel.setMinimized(true) },
        )
        VncTitleAction(
            icon = if (fullscreen) FeatherIcons.Minimize2 else FeatherIcons.Maximize2,
            contentDescription = if (fullscreen) R.string.vnc_exit_fullscreen else R.string.vnc_fullscreen,
            onClick = { viewModel.setFullscreen(!fullscreen) },
        )
        VncTitleAction(
            icon = FeatherIcons.X,
            contentDescription = R.string.vnc_close,
            onClick = viewModel::dismissByUser,
        )
    }
}

@Composable
private fun VncTitleAction(
    icon: ImageVector,
    contentDescription: Int,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescription),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 右下角缩放手柄：拖动改变小窗尺寸；视觉为双斜线的经典拖角标记。 */
@Composable
private fun ResizeCornerHandle(
    onResize: (Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Box(
        modifier = modifier
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onResize(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                    },
                    onDragEnd = onResizeEnd,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val strokeWidth = 1.5.dp.toPx()
            drawLine(handleColor, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth, StrokeCap.Round)
            drawLine(
                handleColor,
                Offset(size.width, size.height * 0.45f),
                Offset(size.width * 0.45f, size.height),
                strokeWidth,
                StrokeCap.Round,
            )
        }
    }
}

/** 最小化形态：一枚可点回的悬浮球，位置沿用窗口记忆坐标。 */
@Composable
private fun MinimizedBall(
    viewModel: VncOverlayViewModel,
    window: VncOverlayViewModel.WindowState,
    serviceState: VncServiceState,
    connected: Boolean,
    parentWidth: Dp,
    parentHeight: Dp,
) {
    val ballSize = 56.dp
    val ballX = (if (window.offsetX == Float.MIN_VALUE) (parentWidth - ballSize - 12.dp).value else window.offsetX)
        .dp.coerceIn(0.dp, parentWidth - ballSize)
    val ballY = (if (window.offsetY == Float.MIN_VALUE) (parentHeight - ballSize - 96.dp).value else window.offsetY)
        .dp.coerceIn(0.dp, parentHeight - ballSize)
    Box(
        modifier = Modifier
            .offset { IntOffset(ballX.roundToPx(), ballY.roundToPx()) }
            .size(ballSize)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.semanticColors.cardSurface)
            .border(1.dp, MaterialTheme.semanticColors.subtleBorder, CircleShape)
            .clickable { viewModel.setMinimized(false) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = FeatherIcons.Monitor,
            contentDescription = stringResource(R.string.vnc_minimized_ball),
            tint = statusTint(serviceState, connected),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun statusTint(serviceState: VncServiceState, connected: Boolean): Color = when {
    serviceState is VncServiceState.Failed -> MaterialTheme.colorScheme.error
    serviceState is VncServiceState.Preparing -> MaterialTheme.semanticColors.warning
    connected && serviceState is VncServiceState.Ready -> MaterialTheme.semanticColors.success
    serviceState is VncServiceState.Ready -> MaterialTheme.semanticColors.info
    else -> MaterialTheme.semanticColors.subtleText
}

private fun statusTextRes(serviceState: VncServiceState, connected: Boolean): Int = when {
    serviceState is VncServiceState.Failed -> R.string.vnc_status_failed
    serviceState is VncServiceState.Preparing -> R.string.vnc_status_preparing
    connected -> R.string.vnc_status_ready
    else -> R.string.vnc_status_connecting
}
