package com.aicode.feature.floatball

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.theme.AppThemePreset
import com.aicode.core.theme.Radius
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.X
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.presentation.component.toolRunningLabelRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮精灵球（系统级悬浮窗，WindowManager 渲染）。
 *
 * 形态：可爱的精灵球——会呼吸、眨眼、眼睛跟随拖动方向；贴边时「骑」在屏幕边缘线上
 * （球心正好压在边缘，露出一半）。可拖到屏幕任意位置，松手吸附最近的一侧（上下左右）。
 *
 * 交互：点球展开面板（关键信息 + 关键操作：任务状态、正在做什么、待办进度、回复预览、
 * 待审批的允许/拒绝、快速发送、停止任务、回到 App）。面板点外部或完成操作后自动收起，
 * 精灵球常驻可无限次打开；只有面板「关闭悬浮球」或设置里关掉开关才彻底关闭。
 *
 * 窗口实现：球与面板各自为 WindowManager 直连窗口（窗口根=自有容器，Compose 的
 * ViewTree 三件套用资源 tag 方式挂载）。位置只由 LayoutParams 承载，内容不做平移。
 */
class FloatBallOverlay(
    private val context: Context,
    private val stateStore: FloatBallStateStore,
    private val themeSettings: ThemeSettingsRepository,
    private val onClose: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var ballView: ComposeView? = null
    private var panelWindow: android.view.View? = null
    private var panelOpen = false

    /** 屏幕尺寸缓存：拖动每帧都要用，避免每次都走 WindowManager 的系统调用（IPC）。 */
    private var cachedScreenSize: Pair<Int, Int>? = null

    /** 屏幕尺寸：Android 11+ 用 maximumWindowMetrics（分屏下仍取全屏），低版本回退 realSize。 */
    private val screenSize: Pair<Int, Int>
        get() = cachedScreenSize ?: readScreenSize().also { cachedScreenSize = it }
    private fun readScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val point = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
            point.x to point.y
        }
    private val screenWidth: Int get() = screenSize.first
    private val screenHeight: Int get() = screenSize.second

    /** 球的像素直径（44dp）。 */
    private val ballSize: Int = (44 * context.resources.displayMetrics.density).roundToInt()

    /** 球窗口与面板窗口各自的 Compose 宿主 owner。 */
    private var ballComposeOwner: OverlayComposeOwner? = null
    private var panelComposeOwner: OverlayComposeOwner? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "FloatBallOverlay"
    }

    /** Service 窗口里的 ComposeView 需要 ViewTree 三件套，否则组合在 attach 时抛异常。 */
    private class OverlayComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        init {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    /** 悬浮窗 ComposeView 统一挂 ViewTree 三件套（资源 tag 方式，避开源码层隐藏的静态符号）。 */
    private fun <T : android.view.View> T.withOverlayOwners(owner: OverlayComposeOwner): T = apply {
        fun tagId(name: String) = resources.getIdentifier(name, "id", context.packageName)
        tagId("view_tree_lifecycle_owner").takeIf { it != 0 }?.let { setTag(it, owner) }
            ?: com.aicode.core.util.FileLogger.w(TAG, "资源 id 解析失败：view_tree_lifecycle_owner")
        tagId("view_tree_view_model_store_owner").takeIf { it != 0 }?.let { setTag(it, owner) }
            ?: com.aicode.core.util.FileLogger.w(TAG, "资源 id 解析失败：view_tree_view_model_store_owner")
        tagId("view_tree_saved_state_registry_owner").takeIf { it != 0 }?.let { setTag(it, owner) }
            ?: com.aicode.core.util.FileLogger.w(TAG, "资源 id 解析失败：view_tree_saved_state_registry_owner")
    }

    private val configurationListener = object : android.content.ComponentCallbacks {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onConfigurationChanged(newConfig: Configuration) {
            // 面板的尺寸/位置是按旧屏幕算的，旋转或分屏变化后收起，避免错位。
            if (panelOpen) dismissPanel()
            cachedScreenSize = null
            clampToScreen()
        }

        override fun onLowMemory() = Unit
    }

    fun attach() {
        if (ballView != null) return
        var owner: OverlayComposeOwner? = null
        try {
            owner = OverlayComposeOwner()
            ballComposeOwner = owner
            val view = ComposeView(context).withOverlayOwners(owner)
            view.setContent { OverlayTheme { SpriteContent() } }
            val params = WindowManager.LayoutParams(
                ballSize, ballSize,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 默认骑在右缘、屏幕 1/3 高处
                x = screenWidth - ballSize / 2
                y = screenHeight / 3
            }
            windowManager.addView(view, params)
            ballView = view
            context.registerComponentCallbacks(configurationListener)
            com.aicode.core.util.FileLogger.i(TAG, "悬浮球已挂载 x=${params.x} y=${params.y} size=$ballSize")
        } catch (e: Throwable) {
            com.aicode.core.util.FileLogger.w(TAG, "悬浮球挂载失败：${e.javaClass.simpleName}: ${e.message}")
            owner?.destroy()
            ballComposeOwner = null
            onClose()
        }
    }

    fun detach() {
        dismissPanel()
        runCatching { context.unregisterComponentCallbacks(configurationListener) }
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        ballComposeOwner?.destroy()
        ballComposeOwner = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 输入框获得焦点（键盘即将弹出）时把面板移到顶部，避免键盘遮住快速输入。 */
    private fun movePanelToTop() {
        val view = panelWindow ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val top = dp(12)
        if (params.y == top) return
        params.y = top
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun dismissPanel() {
        panelWindow?.let { runCatching { windowManager.removeView(it) } }
        panelWindow = null
        panelOpen = false
        panelComposeOwner?.destroy()
        panelComposeOwner = null
    }

    private fun currentParams(): WindowManager.LayoutParams? =
        ballView?.layoutParams as? WindowManager.LayoutParams

    private fun moveTo(x: Int, y: Int) {
        val view = ballView ?: return
        val params = currentParams() ?: return
        params.x = x
        params.y = y
        windowManager.updateViewLayout(view, params)
    }

    /** 拖动：球心保持在屏内（[-r, screenW-r]），松手由 [snapToEdge] 贴边。 */
    private fun dragBy(dx: Int, dy: Int) {
        val params = currentParams() ?: return
        val r = ballSize / 2
        moveTo(
            (params.x + dx).coerceIn(-r, screenWidth - r),
            (params.y + dy).coerceIn(-r, screenHeight - r)
        )
    }

    /** 松手/旋转后就近贴边：球心骑在最近一条边缘线上（露出一半球体）。 */
    private fun snapToEdge() {
        val params = currentParams() ?: return
        val r = ballSize / 2
        val cx = params.x + r
        val cy = params.y + r
        val distLeft = cx
        val distRight = screenWidth - cx
        val distTop = cy
        val distBottom = screenHeight - cy
        val min = minOf(distLeft, distRight, distTop, distBottom)
        var x = params.x
        var y = params.y
        when (min) {
            distLeft -> x = -r
            distRight -> x = screenWidth - r
            distTop -> y = -r
            else -> y = screenHeight - r
        }
        moveTo(x, y)
    }

    private fun clampToScreen() = snapToEdge()

    /** 面板：从球所在侧滑入，展示关键信息与操作。 */
    private fun togglePanel(ballX: Int, ballY: Int) {
        if (panelOpen) {
            dismissPanel()
            return
        }
        val r = ballSize / 2
        // 小屏/横屏下收缩面板，避免固定尺寸超出屏幕（anchor 反算 min>max 会抛异常）。
        val margin = dp(6)
        val panelWidth = minOf(dp(300), screenWidth - margin * 2).coerceAtLeast(dp(220))
        val panelHeight = minOf(dp(372), screenHeight - dp(24)).coerceAtLeast(dp(200))
        val cx = ballX + r
        val onLeft = cx < screenWidth / 2
        val anchorX = (if (onLeft) margin else screenWidth - panelWidth - margin)
            .coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0))
        val anchorY = (ballY + r - panelHeight / 2)
            .coerceIn(dp(12), (screenHeight - panelHeight - dp(12)).coerceAtLeast(dp(12)))
        var panelOwner: OverlayComposeOwner? = null
        try {
            panelOwner = OverlayComposeOwner()
            panelComposeOwner = panelOwner
            val compose = ComposeView(context).withOverlayOwners(panelOwner)
            compose.setContent {
                OverlayTheme {
                    PanelContent(
                        fromLeft = onLeft,
                        onDismiss = { dismissPanel() },
                        onInputFocused = { movePanelToTop() }
                    )
                }
            }
            // 直连窗口：窗口根即自有容器（平台 PopupWindow 的装饰层无法挂 ViewTree owner）。
            val host = object : android.widget.FrameLayout(context) {
                override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                        dismissPanel()
                        return true
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }
            host.withOverlayOwners(panelOwner)
            host.addView(
                compose,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val params = WindowManager.LayoutParams(
                panelWidth, panelHeight,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = anchorX
                y = anchorY
            }
            windowManager.addView(host, params)
            panelWindow = host
            panelOpen = true
        } catch (e: Throwable) {
            com.aicode.core.util.FileLogger.w(TAG, "悬浮面板创建失败：${e.javaClass.simpleName}: ${e.message}")
            panelOwner?.destroy()
            panelComposeOwner = null
            panelOpen = false
        }
    }

    /** 悬浮窗独立于 Activity 的组合树，需自建主题才能跟随 App 的深浅/配色/动态取色。 */
    @Composable
    private fun OverlayTheme(content: @Composable () -> Unit) {
        val mode by themeSettings.themeModeFlow.catch { emit(AppThemeMode.AUTO) }
            .collectAsState(initial = AppThemeMode.AUTO)
        val presetId by themeSettings.themePresetIdFlow.catch { emit(null) }
            .collectAsState(initial = null)
        val dynamicColor by themeSettings.dynamicColorFlow.catch { emit(false) }
            .collectAsState(initial = false)
        val darkTheme = when (mode) {
            AppThemeMode.AUTO -> isSystemInDarkTheme()
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
        AIEditorTheme(
            darkTheme = darkTheme,
            preset = AppThemePreset.findById(presetId),
            dynamicColor = dynamicColor,
            content = content
        )
    }

    // ── 精灵球 UI ──────────────────────────────────────────────

    @Composable
    private fun SpriteContent() {
        val state by stateStore.state.collectAsState()

        // 呼吸（仅忙碌态，克制）；保留 State，在绘制阶段读取，避免每帧重组整个精灵球。
        val breathScale = if (state.busy) {
            val t = rememberInfiniteTransition(label = "sprite-breath")
            t.animateFloat(
                initialValue = 0.97f, targetValue = 1.05f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath"
            )
        } else null

        // 眨眼（3.4s 一次，动态精灵感；Compose 动画，成本可忽略）
        var blink by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(3400)
                blink = true
                delay(130)
                blink = false
            }
        }

        // 眼睛看向的方向（拖动时跟手，松手回正）
        var lookX by remember { mutableStateOf(0f) }
        var lookY by remember { mutableStateOf(0f) }

        val statusColor = when {
            state.failed -> MaterialTheme.colorScheme.error
            state.busy -> MaterialTheme.semanticColors.success
            else -> MaterialTheme.semanticColors.subtleText
        }
        val skinTop by animateColorAsState(
            targetValue = lerp(statusColor, Color.White, 0.32f),
            animationSpec = tween(260), label = "skin-top"
        )
        val skinBottom by animateColorAsState(
            targetValue = statusColor,
            animationSpec = tween(260), label = "skin-bottom"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            // 进度环（待办完成比例；todo 驱动，无 todo 不画）
            if (state.todoTotal > 0) {
                val progress = state.todoDone.toFloat() / state.todoTotal
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = breathScale?.value ?: 1f
                            scaleX = scale
                            scaleY = scale
                        }
                ) {
                    val stroke = size.minDimension * 0.07f
                    val inset = stroke / 2
                    drawArc(
                        color = Color.White.copy(alpha = 0.28f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }

            // 球体
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .graphicsLayer {
                        val scale = breathScale?.value ?: 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(skinTop, skinBottom)))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (panelOpen) dismissPanel()
                                // 眼睛看向拖动方向（归一化 -1..1）
                                val ax = abs(dragAmount.x)
                                val ay = abs(dragAmount.y)
                                if (ax > 0.2f || ay > 0.2f) {
                                    lookX = (dragAmount.x / 26f).coerceIn(-1f, 1f)
                                    lookY = (dragAmount.y / 26f).coerceIn(-1f, 1f)
                                }
                                dragBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                            },
                            onDragEnd = {
                                lookX = 0f
                                lookY = 0f
                                snapToEdge()
                            },
                            onDragCancel = {
                                lookX = 0f
                                lookY = 0f
                                snapToEdge()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            val params = currentParams() ?: return@detectTapGestures
                            togglePanel(params.x, params.y)
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                // 高光
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .size(width = 20.dp, height = 9.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.35f))
                )
                // 眼睛（跟随方向 + 眨眼）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpriteEye(lookX = lookX, lookY = lookY, blink = blink)
                    SpriteEye(lookX = lookX, lookY = lookY, blink = blink)
                }
                // 状态嘴型：busy 小微笑 / failed 皱眉 / idle 淡淡微笑
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 9.dp)
                        .size(width = 14.dp, height = 7.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    if (state.failed) {
                        // 皱眉：上拱
                        drawArc(
                            color = Color.White,
                            startAngle = 200f, sweepAngle = 140f, useCenter = false,
                            topLeft = Offset(0f, -h * 0.4f),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                        )
                    } else {
                        // 微笑
                        drawArc(
                            color = Color.White,
                            startAngle = 15f, sweepAngle = 150f, useCenter = false,
                            topLeft = Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SpriteEye(lookX: Float, lookY: Float, blink: Boolean) {
        val openness by animateFloatAsState(
            targetValue = if (blink) 0.12f else 1f,
            animationSpec = tween(90), label = "eye-open"
        )
        Box(
            modifier = Modifier
                .size(width = 11.dp, height = 13.dp)
                .graphicsLayer { scaleY = openness }
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .offset(x = (lookX * 2.2f).dp, y = (lookY * 2.2f).dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF263238))
            )
        }
    }

    // ── 面板 UI ──────────────────────────────────────────────

    @Composable
    private fun PanelContent(
        fromLeft: Boolean,
        onDismiss: () -> Unit,
        onInputFocused: () -> Unit
    ) {
        val state by stateStore.state.collectAsState()
        var input by remember { mutableStateOf("") }
        var hint by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }

        // 提示气泡 1.6s 自动清空
        LaunchedEffect(hint) {
            val msg = hint ?: return@LaunchedEffect
            delay(1600)
            if (hint == msg) hint = null
        }

        // 滑入/滑出：进场从球侧滑入；操作完成后滑出并真正销毁窗口
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val slide = animateFloatAsState(
            targetValue = if (closing) 1f else if (entered) 0f else 1f,
            animationSpec = tween(200), label = "panel-slide"
        )
        val alpha = animateFloatAsState(
            targetValue = if (closing) 0f else 1f,
            animationSpec = tween(180), label = "panel-alpha"
        )
        LaunchedEffect(closing) {
            if (!closing) return@LaunchedEffect
            delay(210)
            onDismiss()
        }

        // 操作完成后的自动收起（观看/处理完关键信息即收回，可无限次再打开）。
        var collapseTick by remember { mutableStateOf(0) }
        LaunchedEffect(collapseTick) {
            if (collapseTick > 0) {
                delay(800)
                closing = true
            }
        }

        val slideOffset = (if (fromLeft) -1f else 1f) * 32f * slide.value

        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.semanticColors.cardSurface,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxSize()
                .offset(x = slideOffset.dp)
                .graphicsLayer { this.alpha = alpha.value }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // 1) 状态行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.failed -> MaterialTheme.colorScheme.error
                                    state.busy -> MaterialTheme.semanticColors.success
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                }
                            )
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = when {
                            state.failed -> stringResource(R.string.float_panel_title_failed)
                            state.busy -> if (state.runningCount > 1) {
                                stringResource(R.string.float_panel_running_count, state.runningCount)
                            } else stringResource(R.string.float_panel_title_busy)
                            else -> stringResource(R.string.float_panel_title_idle)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.queuedCount > 0) {
                        Text(
                            text = stringResource(R.string.float_panel_queued_suffix, state.queuedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(R.string.float_panel_collapse_panel),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2) 正在做什么
                if (state.busy && state.activeToolName != null) {
                    Text(
                        text = stringResource(toolRunningLabelRes(state.activeToolName)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 3) 待办进度
                if (state.todoTotal > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = {
                                (state.todoDone.toFloat() / state.todoTotal).coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.float_panel_todo_progress,
                                state.todoDone,
                                state.todoTotal
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.todoActiveTitle?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 4) 回复预览（关键信息）
                state.replyPreview?.let { preview ->
                    Surface(
                        shape = RoundedCornerShape(Radius.sm),
                        color = MaterialTheme.semanticColors.mutedSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                        )
                    }
                }

                // 5) 待审批（关键阻塞直达处理）
                state.pendingKind?.let { kind ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                            Text(
                                text = when (kind) {
                                    "permission" -> state.pendingTitle?.let {
                                        "${stringResource(R.string.float_panel_pending_permission)} · $it"
                                    } ?: stringResource(R.string.float_panel_pending_permission)
                                    "plan" -> stringResource(R.string.float_panel_pending_plan)
                                    else -> stringResource(R.string.float_panel_pending_question)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (kind == "permission") {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        onClick = {
                                            val resolver = FloatBallCommandCenter.resolveApproval
                                            val handled = resolver?.invoke(PermissionChoice.REJECT) == true
                                            hint = context.getString(
                                                when {
                                                    resolver == null -> R.string.float_panel_send_need_app
                                                    handled -> R.string.float_panel_deny
                                                    else -> R.string.float_panel_action_unavailable
                                                }
                                            )
                                            if (handled) collapseTick++
                                        }
                                    ) { Text(stringResource(R.string.float_panel_deny)) }
                                    TextButton(
                                        onClick = {
                                            val resolver = FloatBallCommandCenter.resolveApproval
                                            val handled = resolver?.invoke(PermissionChoice.ONCE) == true
                                            hint = context.getString(
                                                when {
                                                    resolver == null -> R.string.float_panel_send_need_app
                                                    handled -> R.string.float_panel_allow
                                                    else -> R.string.float_panel_action_unavailable
                                                }
                                            )
                                            if (handled) collapseTick++
                                        }
                                    ) { Text(stringResource(R.string.float_panel_allow)) }
                                }
                            } else {
                                TextButton(onClick = {
                                    FloatBallCommandCenter.openApp?.invoke()
                                    onDismiss()
                                }) { Text(stringResource(R.string.float_panel_open_app)) }
                            }
                        }
                    }
                }

                // 6) 快速发送
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.float_panel_input_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { if (it.isFocused) onInputFocused() }
                            .background(
                                MaterialTheme.semanticColors.mutedSurface,
                                RoundedCornerShape(Radius.sm)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@TextButton
                            val sender = FloatBallCommandCenter.send
                            when {
                                sender == null -> hint = context.getString(R.string.float_panel_send_need_app)
                                sender(text) -> {
                                    input = ""
                                    hint = context.getString(R.string.float_panel_sent)
                                    collapseTick++
                                }
                                else -> hint = context.getString(R.string.float_panel_send_paused)
                            }
                        },
                        enabled = input.isNotBlank()
                    ) { Text(stringResource(R.string.float_panel_send)) }
                }

                // 7) 操作排
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (state.canStop) {
                        TextButton(
                            onClick = {
                                val result = FloatBallCommandCenter.stop?.invoke()
                                hint = context.getString(
                                    when (result) {
                                        true -> R.string.float_panel_stopped
                                        false -> R.string.float_panel_nothing_running
                                        null -> R.string.float_panel_send_need_app
                                    }
                                )
                                if (result == true) collapseTick++
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.float_panel_stop),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    TextButton(onClick = {
                        FloatBallCommandCenter.openApp?.invoke()
                        onDismiss()
                    }) { Text(stringResource(R.string.float_panel_open_app)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        onDismiss()
                        this@FloatBallOverlay.onClose()
                    }) {
                        Text(
                            text = stringResource(R.string.float_panel_close_ball),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                hint?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
