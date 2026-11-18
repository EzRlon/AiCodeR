package com.aicode.feature.vnc.presentation

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.vnc.domain.VncServiceState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Crosshair
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Maximize2
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.MousePointer
import compose.icons.feathericons.Command
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.X
import kotlin.math.roundToInt

/** X keysym 值：原生侧把 Android 按键转成这些码发给远端（noVNC 的 sendKey 只认 keysym）。 */
private const val XK_SPACE = 0x20
private const val XK_BACKSPACE = 0xFF08
private const val XK_TAB = 0xFF09
private const val XK_RETURN = 0xFF0D
private const val XK_ESCAPE = 0xFF1B
private const val XK_HOME = 0xFF50
private const val XK_LEFT = 0xFF51
private const val XK_UP = 0xFF52
private const val XK_RIGHT = 0xFF53
private const val XK_DOWN = 0xFF54
private const val XK_PAGE_UP = 0xFF55
private const val XK_PAGE_DOWN = 0xFF56
private const val XK_END = 0xFF57
private const val XK_SHIFT = 0xFFE1
private const val XK_CTRL = 0xFFE3
private const val XK_ALT = 0xFFE9
private const val XK_A = 'a'.code
private const val XK_C = 'c'.code
private const val XK_V = 'v'.code
private const val XK_X = 'x'.code
private const val XK_DELETE = 0xFFFF

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

    // 全屏时把状态栏/导航栏一并隐藏，纵向空间全给画面；退出全屏恢复。
    // 用 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：从边缘下拖仍能临时唤出系统栏，不会被锁死。
    val view = LocalView.current
    DisposableEffect(window.visible, window.fullscreen) {
        val windowRef = (view.context as? Activity)?.window
        if (windowRef == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(windowRef, view)
        if (window.visible && window.fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 旋转控制：远端桌面是横屏 1280x800，全屏时竖屏只能看到中间一条窄带，
    // 所以 AUTO 在全屏下直接锁横屏；用户可用标题栏按钮锁定竖屏或跟随系统。
    val activity = LocalContext.current as? Activity
    LaunchedEffect(window.visible, window.fullscreen, window.rotation) {
        val target = when {
            // 方向只作用于全屏：浮窗状态下改 requestedOrientation 会把整个宿主界面一起转过去。
            !window.visible || !window.fullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window.rotation == VncOverlayViewModel.VncRotation.LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            window.rotation == VncOverlayViewModel.VncRotation.PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 全屏默认横屏：与远端桌面同向，画面才能铺满（用户反馈：最大化后仍是竖屏）。
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (activity != null && activity.requestedOrientation != target) {
            activity.requestedOrientation = target
        }
    }
    // 离开该界面时恢复系统旋转，避免把宿主 Activity 一直锁在某个方向。
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

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
                // 全屏已隐藏系统栏（沉浸式），再加内边距会白白缩一圈、画面变小。
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
    // 默认尺寸更贴合屏幕：宽度占满（留 16dp 边），高度约占 62%，画面等比铺满。
    // 远端分辨率会跟随这个宽高比，因此不会出现大片黑边。
    val defaultWidth = (parentWidth - 16.dp).coerceAtMost(560.dp)
    val defaultHeight = (parentHeight * 0.62f).coerceIn(240.dp, (parentHeight - 96.dp).coerceAtLeast(240.dp))
    LaunchedEffect(parentWidth, parentHeight) {
        viewModel.ensureGeometry(
            defaultXDp = ((parentWidth - defaultWidth) / 2).value,
            defaultYDp = (parentHeight * 0.16f).value,
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
    // 关键禁令：窗口顶部永远不允许进入状态栏区域（此前允许 y=0，窗口标题栏被状态栏盖住，
    // 连重置按钮都点不到）。竖屏/横屏都用实时 inset 计算；横屏状态栏在侧边时最小 Y 回到 8dp。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val minYDp = statusTopDp + 8.dp
    val maxYDp = (parentHeight - 56.dp).coerceAtLeast(minYDp)
    val offY = (if (window.offsetY == Float.MIN_VALUE) (parentHeight * 0.18f).value else window.offsetY)
        .dp.coerceIn(minYDp, maxYDp)
    // 注意：不要在这里「发现 offsetY < minY 就 resetGeometry」——拖动过程中 raw 值
    // 会短暂低于最小值，自动重置会和用户拖动打架（真机反馈“位置挪不动”）。
    // 显示层已经用 coerceIn 把窗口夹在状态栏下方；手动兜底是标题栏的重置按钮。

    Box(
        modifier = Modifier
            .offset { IntOffset(offX.roundToPx(), offY.roundToPx()) }
            .size(winWidth, winHeight)
            // 用户要求接近直角的窗体：只留 2dp，视觉上仍是圆角但不占空间。
            .shadow(6.dp, RoundedCornerShape(2.dp))
            .clip(RoundedCornerShape(2.dp)),
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
    val shape = RoundedCornerShape(if (fullscreen) 0.dp else 2.dp)
    val mouseButton by viewModel.mouseButton.collectAsStateWithLifecycle()
    var specialKeys by remember { mutableStateOf(false) }
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
            specialKeys = specialKeys,
            onKeyboardClick = {
                specialKeys = !specialKeys
                if (specialKeys) viewModel.requestKeyboard()
            },
        )
        if (specialKeys && connected) {
            VncSpecialKeyBar(onKey = viewModel::sendKeysym)
        }
        Box(modifier = Modifier.weight(1f)) {
            VncWebViewPanel(
                serviceState = serviceState,
                pasteRequests = viewModel.pasteRequests.collectAsStateWithLifecycle().value,
                reconnectRequests = viewModel.reconnectRequests.collectAsStateWithLifecycle().value,
                onConnectedChange = viewModel::setConnected,
                onKeyboardRequest = viewModel::requestKeyboard,
                onPageReady = { channel ->
                    // 页面每次重载都要回填按键与鼠标按键模式通道。
                    viewModel.sendKeysToPage = { text -> channel.sendKeys(text) }
                    viewModel.sendKeysymToPage = { code, down -> channel.sendKeysym(code, down) }
                    viewModel.sendMouseButtonMode = { b -> channel.setMouseButton(b) }
                    viewModel.sendClickToPage = { b -> channel.click(b) }
                    viewModel.sendScrollToPage = { d -> channel.scroll(d) }
                    viewModel.sendPointerModeToPage = { m -> channel.setPointerMode(m) }
                    viewModel.sendMouseButtonMode?.invoke(viewModel.mouseButton.value)
                    viewModel.sendPointerModeToPage?.invoke(viewModel.pointerMode.value)
                },
                mouseButton = mouseButton,
                modifier = Modifier.fillMaxSize(),
            )
            // 原生输入框：WebView 内的隐藏 input 拿不到真正的输入连接（日志里
            // showSoftInput 返回 true 但键盘不弹），所以改由真正的原生 EditText 承接系统键盘。
            VncKeyboardSink(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            // 键盘拉起按钮已收进标题栏（VncTitleBar 里锁定按钮左侧），不再做悬浮按钮。
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
        // 鼠标按键条：原生绘制在画面之外（窗口底部），不压在 VNC 内容上。
        if (connected) {
            VncMouseBar(viewModel = viewModel)
        }
    }
}

/**
 * 原生鼠标按键条（窗口内、画面外）：左键 / 中键 / 右键 / 滚轮上下 / 指针模式。
 *
 * 这些动作以前画在 WebView 里，既压住 VNC 内容、又只有右键和中键两项。
 * 现在改为原生浮窗控件，按钮语义与 DOM `MouseEvent.button` 对齐，
 * 点击作用于「当前指针位置」（直触=最后触点，触控板=虚拟指针）。
 */
@Composable
private fun VncMouseBar(
    viewModel: VncOverlayViewModel,
) {
    val mode by viewModel.pointerMode.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface)
            .padding(horizontal = Spacing.xs, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MouseBarButton(
            label = stringResource(R.string.vnc_bar_left),
            active = false,
            onClick = { viewModel.clickMouse(0) },
        )
        MouseBarButton(
            label = stringResource(R.string.vnc_bar_middle),
            active = false,
            onClick = { viewModel.clickMouse(1) },
        )
        MouseBarButton(
            label = stringResource(R.string.vnc_bar_right),
            active = false,
            onClick = { viewModel.clickMouse(2) },
        )
        Spacer(modifier = Modifier.weight(1f))
        MouseBarButton(
            label = "▲",
            contentDescription = stringResource(R.string.vnc_bar_scroll_up),
            active = false,
            onClick = { viewModel.scrollMouse(-1) },
        )
        MouseBarButton(
            label = "▼",
            contentDescription = stringResource(R.string.vnc_bar_scroll_down),
            active = false,
            onClick = { viewModel.scrollMouse(1) },
        )
        Spacer(modifier = Modifier.width(4.dp))
        MouseBarButton(
            label = stringResource(
                if (mode == "trackpad") R.string.vnc_bar_pointer_trackpad else R.string.vnc_bar_pointer_direct
            ),
            active = mode == "trackpad",
            onClick = { viewModel.setPointerMode(if (mode == "trackpad") "direct" else "trackpad") },
        )
    }
}

@Composable
private fun MouseBarButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            // 箭头按钮的可见文字只有符号，读屏需要一段可读描述。
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun VncTitleBar(
    viewModel: VncOverlayViewModel,
    window: VncOverlayViewModel.WindowState,
    serviceState: VncServiceState,
    connected: Boolean,
    fullscreen: Boolean,
    specialKeys: Boolean,
    onKeyboardClick: () -> Unit,
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
        // 旋转是（全屏）画面专属控制，浮窗状态下不展示，避免误以为它会立刻转屏。
        if (fullscreen) {
            VncTitleAction(
                icon = when (window.rotation) {
                    VncOverlayViewModel.VncRotation.AUTO -> FeatherIcons.RefreshCw
                    VncOverlayViewModel.VncRotation.LANDSCAPE -> FeatherIcons.Monitor
                    VncOverlayViewModel.VncRotation.PORTRAIT -> FeatherIcons.Smartphone
                },
                contentDescription = when (window.rotation) {
                    VncOverlayViewModel.VncRotation.AUTO -> R.string.vnc_rotation_auto
                    VncOverlayViewModel.VncRotation.LANDSCAPE -> R.string.vnc_rotation_landscape
                    VncOverlayViewModel.VncRotation.PORTRAIT -> R.string.vnc_rotation_portrait
                },
                active = window.rotation != VncOverlayViewModel.VncRotation.AUTO,
                onClick = viewModel::cycleRotation,
            )
        }
        // 键盘按钮同时拉起输入法与特殊键条：手机输入法给不出 Esc/Tab/方向键。
        VncTitleAction(
            icon = FeatherIcons.Command,
            contentDescription = R.string.vnc_show_keyboard,
            active = specialKeys,
            onClick = onKeyboardClick,
        )
        VncTitleAction(
            icon = FeatherIcons.Lock,
            contentDescription = if (window.pinned) R.string.vnc_unpin else R.string.vnc_pin,
            active = window.pinned,
            onClick = { viewModel.setPinned(!window.pinned) },
        )
        // 位置重置：窗口被拖到状态栏后方/屏幕外时，一键回到默认位置。
        VncTitleAction(
            icon = FeatherIcons.Crosshair,
            contentDescription = R.string.vnc_reset_position,
            onClick = viewModel::resetGeometry,
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
    // 球同样不允许进状态栏：否则最小化后也点不到，用户彻底失去入口。
    val ballDensity = androidx.compose.ui.platform.LocalDensity.current
    val ballMinY = with(ballDensity) { WindowInsets.statusBars.getTop(this).toDp() } + 8.dp
    val ballY = (if (window.offsetY == Float.MIN_VALUE) (parentHeight - ballSize - 96.dp).value else window.offsetY)
        .dp.coerceIn(ballMinY, (parentHeight - ballSize).coerceAtLeast(ballMinY))
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

/**
 * 原生输入框：承接系统软键盘。
 *
 * WebView 里的隐藏 input 在 Android 上拿不到真正的输入连接（showSoftInput 会返回 true
 * 但键盘不弹），所以用一个 1dp 透明的原生 EditText 承接键盘，把字符交给页面转发到远端。
 * 必须用原生 EditText（Compose 的 BasicTextField 在 1dp 尺寸下拿不到真实焦点）。
 */
@Composable
private fun VncKeyboardSink(
    viewModel: VncOverlayViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editText = remember {
        // 用子类拦截物理键/软键盘的特殊键：noVNC 的键盘监听绑定在 canvas 上，
        // 重定向到 textarea 不生效，删除/回车/空格等只能由原生侧转成 keysym 直发。
        object : EditText(context) {
            private val heldModifiers = mutableSetOf<Int>()

            private fun sendCombo(code: Int) {
                viewModel.sendKeysym(code, true)
                viewModel.sendKeysym(code, false)
            }

            override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
                when (keyCode) {
                    // 修饰键：记录按住状态，靠按下/抬起转发给远端，后续字母键才能带上修饰。
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                        return trackModifier(keyCode, event, XK_SHIFT)
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                        return trackModifier(keyCode, event, XK_CTRL)
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT ->
                        return trackModifier(keyCode, event, XK_ALT)
                    // Ctrl+A/C/X/V：全选/复制/剪切/粘贴。粘贴前先把 Android 剪贴板同步到远端。
                    KeyEvent.KEYCODE_A -> if (XK_CTRL in heldModifiers) { sendCombo(XK_A); return true }
                    KeyEvent.KEYCODE_C -> if (XK_CTRL in heldModifiers) { sendCombo(XK_C); return true }
                    KeyEvent.KEYCODE_X -> if (XK_CTRL in heldModifiers) { sendCombo(XK_X); return true }
                    KeyEvent.KEYCODE_V -> if (XK_CTRL in heldModifiers) {
                        // 先把 Android 剪贴板同步到远端，再送 Ctrl+V，远端应用才能粘贴到最新内容。
                        viewModel.requestPaste()
                        sendCombo(XK_V)
                        return true
                    }
                    KeyEvent.KEYCODE_DEL -> { sendCombo(XK_BACKSPACE); return true }
                    KeyEvent.KEYCODE_FORWARD_DEL -> { sendCombo(XK_DELETE); return true }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { sendCombo(XK_RETURN); return true }
                    // 空格在空字段里不会触发文本变化，直接拦下发 keysym；非空时走文本路径，避免破坏输入法组字。
                    KeyEvent.KEYCODE_SPACE -> if (text.isNullOrEmpty()) { sendCombo(XK_SPACE); return true }
                    KeyEvent.KEYCODE_TAB -> { sendCombo(XK_TAB); return true }
                    KeyEvent.KEYCODE_ESCAPE -> { sendCombo(XK_ESCAPE); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { sendCombo(XK_LEFT); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { sendCombo(XK_RIGHT); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { sendCombo(XK_UP); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { sendCombo(XK_DOWN); return true }
                    KeyEvent.KEYCODE_MOVE_HOME -> { sendCombo(XK_HOME); return true }
                    KeyEvent.KEYCODE_MOVE_END -> { sendCombo(XK_END); return true }
                    KeyEvent.KEYCODE_PAGE_UP -> { sendCombo(XK_PAGE_UP); return true }
                    KeyEvent.KEYCODE_PAGE_DOWN -> { sendCombo(XK_PAGE_DOWN); return true }
                }
                return super.onKeyDown(keyCode, event)
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                when (keyCode) {
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                        return trackModifier(keyCode, event, XK_SHIFT)
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                        return trackModifier(keyCode, event, XK_CTRL)
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT ->
                        return trackModifier(keyCode, event, XK_ALT)
                }
                return super.onKeyUp(keyCode, event)
            }

            /** 修饰键按下/抬起：转发 keysym 并维护本地按住集合，返回 true 表示已消费。 */
            private fun trackModifier(keyCode: Int, event: KeyEvent?, code: Int): Boolean {
                val down = event?.repeatCount == 0 && event.action == KeyEvent.ACTION_DOWN
                if (down) {
                    if (heldModifiers.add(code)) viewModel.sendKeysym(code, true)
                } else {
                    if (heldModifiers.remove(code)) viewModel.sendKeysym(code, false)
                }
                return true
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setTextColor(android.graphics.Color.TRANSPARENT)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            // 关掉 IME 的「完成」动作，回车才会以普通按键事件进来而不是被 IME 消耗掉。
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
        }
    }

    // 收到键盘请求时聚焦并唤起系统键盘（输入指针位置由远端当前焦点决定）。
    LaunchedEffect(viewModel) {
        viewModel.keyboardRequests.collect {
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            keyboard?.show()
        }
    }

    AndroidView(
        factory = { editText },
        modifier = modifier.size(1.dp),
        update = { view ->
            view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        viewModel.sendKeys(text)
                        s?.clear()
                    }
                }
            })
        },
    )
}

/**
 * 特殊键条：手机输入法给不出 Esc/Tab/方向键。Ctrl/Alt/Shift 是粘滞的，按下后作用在下一次键位点击上，
 * 发完自动释放，避免忘记松开把后续输入全变成组合键。
 */
@Composable
private fun VncSpecialKeyBar(
    onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var active by remember { mutableStateOf(emptyList<Int>()) }
    val modifiers = listOf("Ctrl" to XK_CTRL, "Alt" to XK_ALT, "Shift" to XK_SHIFT)
    val keys = listOf(
        "Esc" to XK_ESCAPE,
        "Tab" to XK_TAB,
        "Home" to XK_HOME,
        "End" to XK_END,
        "←" to XK_LEFT,
        "↑" to XK_UP,
        "↓" to XK_DOWN,
        "→" to XK_RIGHT,
    )

    fun tap(code: Int) {
        active.forEach { onKey(it, true) }
        onKey(code, true)
        onKey(code, false)
        active.asReversed().forEach { onKey(it, false) }
        active = emptyList()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modifiers.forEach { (label, code) ->
            SpecialKeyChip(
                label = label,
                active = active.contains(code),
                onClick = { active = if (active.contains(code)) active - code else active + code },
            )
        }
        keys.forEach { (label, code) ->
            SpecialKeyChip(label = label, active = false, onClick = { tap(code) })
        }
    }
}

@Composable
private fun SpecialKeyChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}
