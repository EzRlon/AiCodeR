@file:OptIn(ExperimentalFoundationApi::class)

package com.aicode.feature.floatball

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import compose.icons.feathericons.Feather
import compose.icons.feathericons.X
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Bookmark
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Mic
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Square
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Zap
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.presentation.component.toolRunningLabelRes

/**
 * 悬浮助手球（系统级悬浮窗，WindowManager 渲染）。
 *
 * 形态（无脸几何设计，色彩全部走主题语义 token）：
 * - 待机 peek：贴边停靠的 14×28dp 小胶囊，底色即状态色（idle=primaryContainer / busy=success /
 *   failed=error），busy 时仅透明度呼吸，待机零重绘——“犹抱琵琶半遮面”；
 * - 唤起 sphere：点击 peek 原地展开为 40dp 圆球（中央模式符号或待办进度数字，外圈待办进度弧），
 *   可拖拽换边，松手/面板关闭后动画缩回 peek。
 *
 * 交互：peek 点击=展开球并弹面板（关键信息+关键操作：任务状态、待办进度、回复预览、
 * 待审批允许/拒绝、快速发送、停止任务、回到 App）；拖拽=自由移动，松手贴边回待机。
 * 只有面板「关闭悬浮球」或设置里关掉开关才彻底关闭。
 *
 * 窗口实现：球与面板各自为 WindowManager 直连窗口（窗口根=自有容器，Compose 的
 * ViewTree 三件套用资源 tag 方式挂载）。位置与尺寸只由 LayoutParams 承载，
 * 形态迁移用 200ms 尺寸/位置动画（内容随窗口裁剪自然形成吸入形变）。
 */
class FloatBallOverlay(
    private val context: Context,
    private val stateStore: FloatBallStateStore,
    private val themeSettings: ThemeSettingsRepository,
    private val sessionHub: FloatBallSessionHub,
    private val onClose: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var ballView: ComposeView? = null
    private var panelWindow: android.view.View? = null
    private var panelOpen = false

    /** 面板钉住（反馈5）：钉住时不自动收起、不可拖动、点外部不关闭；由面板 UI 回写，点外部判定在 Compose 之外。 */
    private var panelPinned = false

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

    /** 球体像素直径（唤起态 40dp；待机为骑边 peek 胶囊 14×28dp）。 */
    private val ballSize: Int = (40 * context.resources.displayMetrics.density).roundToInt()
    private val peekWidth: Int = (14 * context.resources.displayMetrics.density).roundToInt()
    private val peekHeight: Int = (28 * context.resources.displayMetrics.density).roundToInt()

    /** 当前形态：待机 peek（贴边小胶囊）→ 点击展开 sphere → 面板关闭后回缩。须为 Compose State：窗口尺寸动画走 LayoutParams 不触发重组，形态切换的内容分支全靠它驱动。 */
    private var form by mutableStateOf(BallForm.PEEK)

    private enum class BallForm { PEEK, SPHERE }

    /** 球窗口与面板窗口各自的 Compose 宿主 owner。 */
    private var ballComposeOwner: OverlayComposeOwner? = null
    private var panelComposeOwner: OverlayComposeOwner? = null

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "FloatBallOverlay"
        const val PANEL_PREFS = "floatball_panel"
        const val PANEL_KEY_W = "panel_w_dp"
        const val PANEL_KEY_H = "panel_h_dp"
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
                peekWidth, peekHeight,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 默认以 peek 待机态贴右缘、屏幕 1/3 高处（犹抱琵琶半遮面：待机只露一枚小胶囊）
                x = screenWidth - peekWidth
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
        panelPinned = false
        panelComposeOwner?.destroy()
        panelComposeOwner = null
        // 面板关闭后回缩 peek 待机（延迟到面板滑出动画结束后，避免视觉叠影）。
        if (form == BallForm.SPHERE) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { if (!panelOpen) snapAndCollapse() }, 420
            )
        }
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

    /** 拖动：窗口整体保持在屏内（peek/球各自按当前尺寸约束），松手由 [snapAndCollapse] 贴边回待机。 */
    private fun dragBy(dx: Int, dy: Int) {
        val params = currentParams() ?: return
        moveTo(
            (params.x + dx).coerceIn(0, screenWidth - params.width),
            (params.y + dy).coerceIn(0, screenHeight - params.height)
        )
    }

    /** 窗口尺寸/位置 200ms 动画：peek↔球形态迁移的过渡（吸入/滑出感）。 */
    private fun animateWindow(targetW: Int, targetH: Int, targetX: Int, targetY: Int) {
        val view = ballView ?: return
        val p = currentParams() ?: return
        val fromW = p.width
        val fromH = p.height
        val fromX = p.x
        val fromY = p.y
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                p.width = lerpInt(fromW, targetW, t)
                p.height = lerpInt(fromH, targetH, t)
                p.x = lerpInt(fromX, targetX, t)
                p.y = lerpInt(fromY, targetY, t)
                runCatching { windowManager.updateViewLayout(view, p) }
            }
            start()
        }
    }

    private fun lerpInt(from: Int, to: Int, t: Float) =
        (from + (to - from) * t).roundToInt()

    /**
     * 松手/旋转/面板关闭后的归宿：就近贴边并动画缩回 peek 待机胶囊。
     * peek 整体贴边停靠（不骑缝）：右缘 x=screenW-w，左缘 x=0，上下同理——
     * 待机时只露一枚小胶囊，“犹抱琵琶半遮面”。
     */
    private fun snapAndCollapse() {
        val params = currentParams() ?: return
        form = BallForm.PEEK
        val cx = params.x + params.width / 2
        val cy = params.y + params.height / 2
        val distLeft = cx
        val distRight = screenWidth - cx
        val distTop = cy
        val distBottom = screenHeight - cy
        val min = minOf(distLeft, distRight, distTop, distBottom)
        var x = params.x
        var y = params.y
        // 待机胶囊的朝向跟随停靠边：贴左/右用竖条，贴上/下用横条，否则贴上下边时会露出一条竖向残影。
        val (peekW, peekH) = if (min == distLeft || min == distRight) {
            peekWidth to peekHeight
        } else {
            peekHeight to peekWidth
        }
        when (min) {
            distLeft -> { x = 0; y = (params.y).coerceIn(0, screenHeight - peekH) }
            distRight -> { x = screenWidth - peekW; y = (params.y).coerceIn(0, screenHeight - peekH) }
            distTop -> { y = 0; x = (params.x).coerceIn(0, screenWidth - peekW) }
            else -> { y = screenHeight - peekH; x = (params.x).coerceIn(0, screenWidth - peekW) }
        }
        animateWindow(peekW, peekH, x, y)
    }

    /** peek 点击：动画展开为全球（在贴边位置原地长大），随后由调用方开面板。 */
    private fun expandToSphere() {
        val params = currentParams() ?: return
        form = BallForm.SPHERE
        val cx = params.x + params.width / 2
        val cy = params.y + params.height / 2
        val distLeft = cx
        val distRight = screenWidth - cx
        val distTop = cy
        val distBottom = screenHeight - cy
        val min = minOf(distLeft, distRight, distTop, distBottom)
        var x = params.x
        var y = params.y
        when (min) {
            distLeft -> { x = 0; y = (cy - ballSize / 2).coerceIn(0, screenHeight - ballSize) }
            distRight -> { x = screenWidth - ballSize; y = (cy - ballSize / 2).coerceIn(0, screenHeight - ballSize) }
            distTop -> { y = 0; x = (cx - ballSize / 2).coerceIn(0, screenWidth - ballSize) }
            else -> { y = screenHeight - ballSize; x = (cx - ballSize / 2).coerceIn(0, screenWidth - ballSize) }
        }
        animateWindow(ballSize, ballSize, x, y)
    }

    private fun clampToScreen() = snapAndCollapse()

    /** 面板：从球所在侧滑入，展示关键信息与操作。 */
    private fun togglePanel(ballX: Int, ballY: Int) {
        if (panelOpen) {
            dismissPanel()
            return
        }
        val r = ballSize / 2
        // mini 会话面板尺寸记忆（FB1）：默认 320×460dp，拖拽把手调整后持久化，下次打开沿用
        val prefs = context.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE)
        val margin = dp(6)
        val maxW = screenWidth - margin * 2
        val maxH = screenHeight - dp(24)
        val panelWidth = dp(prefs.getInt(PANEL_KEY_W, 320)).coerceIn(dp(240), minOf(dp(520), maxW))
        // 横屏/矮屏上 maxH 可能小于最小值 300dp，coerceIn(min>max) 会直接抛异常，先把下限压到可用高度内
        val panelHeight = dp(prefs.getInt(PANEL_KEY_H, 460)).coerceIn(minOf(dp(300), maxH), maxH)
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
                        if (!panelPinned) dismissPanel()
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

    /** mini 会话面板拖动挪位置：更新窗口 x/y 并钳制在屏内（边距与 resize 同源）。 */
    fun movePanelBy(deltaXPx: Int, deltaYPx: Int) {
        val host = panelWindow ?: return
        val params = host.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = (params.x + deltaXPx).coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = (params.y + deltaYPx).coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(host, params) }
    }

    /** mini 会话面板拖拽调大小：更新窗口尺寸并持久化（dp 记忆，下次打开沿用）。 */
    fun resizePanelBy(deltaXPx: Int, deltaYPx: Int) {
        val host = panelWindow ?: return
        val params = host.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = context.resources.displayMetrics
        params.width = (params.width + deltaXPx).coerceIn(dp(240), screenWidth - dp(12))
        params.height = (params.height + deltaYPx).coerceIn(dp(300), screenHeight - dp(24))
        runCatching { windowManager.updateViewLayout(host, params) }
        context.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(PANEL_KEY_W, (params.width / dm.density).toInt())
            .putInt(PANEL_KEY_H, (params.height / dm.density).toInt())
            .apply()
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
        // 悬浮窗宿主是 Service（同样被语言 wrapper 包裹），isSystemInDarkTheme() 读冻结值，
        // 改读系统深浅实时镜像流（AIEditorApp.onConfigurationChanged 驱动）。
        val systemDark by themeSettings.systemDark.collectAsState(initial = false)
        // SCHEDULED 的时段解析是挂起调用（DataStore 读取），用 produceState 承接；
        // 模式或系统深浅变化时重算，时段边界内的自动翻转由仓库解析流的 30s 重算兜底。
        val darkTheme = when (mode) {
            AppThemeMode.AUTO -> systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.BLACK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SCHEDULED ->
                produceState(initialValue = systemDark, mode, systemDark) {
                    value = runCatching { themeSettings.resolveInitialDark() }.getOrDefault(systemDark)
                }.value
        }
        AIEditorTheme(
            darkTheme = darkTheme,
            preset = AppThemePreset.findById(presetId),
            dynamicColor = dynamicColor,
            amoledBlack = mode == AppThemeMode.BLACK,
            content = content
        )
    }

    // ── 精灵球 UI ──────────────────────────────────────────────

    @Composable
    private fun SpriteContent() {
        val state by stateStore.state.collectAsState()

        // 忙碌呼吸（仅 busy；待机完全静止零重绘，功耗纪律）。呼吸只作用于透明度，不再缩放整球。
        // State 留到 graphicsLayer（draw 阶段）才读值：动画每帧只重绘不重组。
        val breathState = if (state.busy) {
            rememberInfiniteTransition(label = "peek-breath").animateFloat(
                initialValue = 0.82f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "peek-alpha"
            )
        } else null

        // 状态底色：idle=primaryContainer（融入主题）、busy=success、failed=error。
        // 无脸设计：生命感交给状态色与形态，不画五官（避免拟人恐怖谷，用户反馈）。
        val pillColor by animateColorAsState(
            targetValue = when {
                state.failed -> MaterialTheme.colorScheme.error
                state.busy -> MaterialTheme.semanticColors.success
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            animationSpec = tween(220), label = "pill-color"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            // 进度弧（仅唤起态：待办完成比例，todo 驱动；待机 peek 太小不画）。
            // 注意：DrawScope 非 @Composable 上下文，颜色必须在 Canvas 外取好再传入。
            if (form == BallForm.SPHERE && state.todoTotal > 0) {
                val progress = state.todoDone.toFloat() / state.todoTotal
                val arcColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = size.minDimension * 0.09f
                    val inset = stroke / 2
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }

            // 主体：唤起态=主题色圆球；待机态=贴边小胶囊（犹抱琵琶半遮面）。
            // 内容随形态切换、窗口尺寸动画过渡，中间态自然形成“球被吸入边缘”的形变。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 呼吸只在执行中挂层；待机摘层，避免浮球内文字缓存旧主题色。
                    .then(
                        if (breathState != null) Modifier.graphicsLayer { alpha = breathState?.value ?: 1f } else Modifier
                    )
                    .then(
                        if (form == BallForm.SPHERE) {
                            Modifier
                                .shadow(6.dp, CircleShape)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(pillColor)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        }
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (panelOpen) dismissPanel()
                                dragBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                            },
                            onDragEnd = { snapAndCollapse() },
                            onDragCancel = { snapAndCollapse() }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            if (form == BallForm.PEEK) expandToSphere()
                            val params = currentParams() ?: return@detectTapGestures
                            togglePanel(params.x, params.y)
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                // 高光
                // 中心内容（无脸）：待办进度数字 / 模式符号；待审批时顶部小徽标。
                if (form == BallForm.SPHERE) {
                    if (state.todoTotal > 0) {
                        Text(
                            text = "${state.todoDone}/${state.todoTotal}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    } else {
                        Icon(
                            imageVector = FeatherIcons.Zap,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (state.pendingKind != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 3.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
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
        // FB2：输入草稿与 App 底栏同源（同一份 inputDraft，App/悬浮窗实时同步）
        val hub by sessionHub.state.collectAsState()
        var input by remember { mutableStateOf("") }
        input = hub.draft
        val updateInput: (String) -> Unit = { text ->
            input = text
            FloatBallCommandCenter.updateDraft?.invoke(text)
        }
        // 悬浮窗内嵌模型选择：数据来自 App 模型列表快照，选中走同一落点
        var panelView by remember { mutableStateOf("chat") } // chat / modelPicker（悬浮窗内弹 Dialog 必崩 BadTokenException，模型选择改纯 Compose 视图切换）
        var modelPickerSnapshot by remember { mutableStateOf<FloatBallCommandCenter.FloatModelPickerData?>(null) }
        var modelQuery by remember { mutableStateOf("") }
        var hint by remember { mutableStateOf<String?>(null) }
        var closing by remember { mutableStateOf(false) }
        // 反馈5：钉住后不自动收起/不可拖动/点外部不关闭；同步到 service 字段供 Compose 外的 ACTION_OUTSIDE 判定
        var pinned by remember { mutableStateOf(false) }
        // 反馈1：输入窗默认收起为底栏图标，点击向上展开；发送后自动收起（反馈2）
        var inputOpen by remember { mutableStateOf(false) }
        val inputFocus = remember { FocusRequester() }

        var collapseTick by remember { mutableStateOf(0) }
        // FB2：发送链（键盘 onSend / 发送按钮共用；发送后清空同步草稿流）
        val doSend: () -> Unit = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                val sender = FloatBallCommandCenter.send
                when {
                    sender == null -> hint = context.getString(R.string.float_panel_send_need_app)
                    sender(text) -> {
                        updateInput("")
                        inputOpen = false
                        hint = context.getString(R.string.float_panel_sent)
                        collapseTick++
                    }
                    else -> hint = context.getString(R.string.float_panel_send_paused)
                }
            }
        }

        // 提示气泡 1.6s 自动清空
        LaunchedEffect(hint) {
            val msg = hint ?: return@LaunchedEffect
            delay(1600)
            if (hint == msg) hint = null
        }

        // 进出场：单一进度驱动 alpha/scale/位移三通道。进场用轻微过冲 spring（0.88→1 缩放 +
        // 从球侧 28dp 滑入 + 淡入，锚点 TransformOrigin 钉在球侧，观感是「从球里丝滑长出来」）；
        // 出场对称回缩（tween 保证在 210ms 销毁延迟内走完）。
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val appear by animateFloatAsState(
            targetValue = if (closing) 0f else if (entered) 1f else 0f,
            animationSpec = if (closing) tween(200) else spring(dampingRatio = 0.82f, stiffness = 380f),
            label = "panel-appear"
        )
        LaunchedEffect(closing) {
            if (!closing) return@LaunchedEffect
            delay(210)
            onDismiss()
        }

        // 操作完成后的自动收起（观看/处理完关键信息即收回，可无限次再打开）。
        // 钉住判定放在 delay 之后复查：发送后 800ms 窗口内点钉住，不能被已排期的收起抢掉（qa/optimizer 双点名的竞态）。
        LaunchedEffect(collapseTick) {
            if (collapseTick > 0) {
                delay(800)
                if (!pinned) closing = true
            }
        }

        val slideOffset = (if (fromLeft) -1f else 1f) * 28f * (1f - appear)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 沉浸式关闭按钮尺寸：随面板宽度比例伸缩（用户反馈：原 30dp 太小）
        val closeSize = (maxWidth * 0.11f).coerceIn(36.dp, 54.dp)
        // 气泡宽度上限（BoxWithConstraints 直接作用域内物化：嵌套 Column/item lambda 拿不到该 receiver）
        val bubbleMaxWidth = maxWidth * 0.88f
        if (panelView == "modelPicker") {
            // 模型选择视图：纯 Compose 渲染（悬浮窗内弹 Dialog/ModalBottomSheet 必崩 BadTokenException）
            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.semanticColors.cardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                modelPickerSnapshot?.let { data ->
                    ModelPickerView(
                        data = data,
                        query = modelQuery,
                        onQueryChange = { modelQuery = it },
                        onSelect = { p, m ->
                            sessionHub.selectModel(p, m)
                            panelView = "chat"
                        },
                        onBack = { panelView = "chat" }
                    )
                }
            }
        } else {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.semanticColors.cardSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxSize()
                .offset(x = slideOffset.dp)
                // 进出场结束后摘掉图层：常驻 graphicsLayer 的离屏缓存不随主题切换刷新，
                // 会让悬浮窗里的文字停在旧配色（与其它页面同一根因）。
                .then(
                    if (appear >= 1f) Modifier else Modifier.graphicsLayer {
                        alpha = appear.coerceIn(0f, 1f)
                        val scale = 0.88f + 0.12f * appear
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(if (fromLeft) 0f else 1f, 0.5f)
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // 1) 状态行（整行即拖拽把手：按住头部任意空白处即可挪动面板位置；图标点击不受影响）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(pinned) {
                            // 钉住时头部拖拽把手失效（反馈5）
                            if (!pinned) detectDragGestures { change, dragAmount ->
                                change.consume()
                                movePanelBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                            }
                        }
                ) {
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
                    // 钉住（反馈5）：开启后面板不自动收起、不可拖动、点外部不关闭
                    IconButton(
                        onClick = {
                            pinned = !pinned
                            panelPinned = pinned
                            if (pinned) closing = false
                        },
                        modifier = Modifier.size(closeSize * 0.7f)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Bookmark,
                            contentDescription = stringResource(R.string.float_panel_pin),
                            modifier = Modifier.size(closeSize * 0.4f),
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 新建会话：复用 App 内「新会话」逻辑（空会话直接复用不堆积），新会话后留在面板继续对话
                    IconButton(
                        onClick = {
                            val creator = FloatBallCommandCenter.newSession
                            if (creator == null) {
                                hint = context.getString(R.string.float_panel_send_need_app)
                            } else {
                                creator()
                                hint = context.getString(R.string.float_panel_session_created)
                            }
                        },
                        modifier = Modifier.size(closeSize * 0.7f)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Edit3,
                            contentDescription = stringResource(R.string.chat_new_session),
                            modifier = Modifier.size(closeSize * 0.4f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // FB2：回 App / 关闭悬浮球收纳进头部（原底部操作排移除）
                    IconButton(
                        onClick = {
                            FloatBallCommandCenter.openApp?.invoke()
                            onDismiss()
                        },
                        modifier = Modifier.size(closeSize * 0.7f)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.ArrowUpRight,
                            contentDescription = stringResource(R.string.float_panel_open_app),
                            modifier = Modifier.size(closeSize * 0.4f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 沉浸式关闭（第六条反馈）：无底框融入面板，尺寸随窗口比例放大
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(closeSize)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(R.string.float_panel_collapse_panel),
                            modifier = Modifier.size(closeSize * 0.45f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── 消息流（FB1 mini 会话窗口）：最近 30 条快照，最新在底 ──
                val chatListState = rememberLazyListState()
                // 反馈7：仅当用户本就停在底部时才自动滚底，不拽回正在上翻阅读的用户
                val atBottom by remember {
                    derivedStateOf {
                        val li = chatListState.layoutInfo
                        li.totalItemsCount == 0 ||
                            li.visibleItemsInfo.lastOrNull()?.index == li.totalItemsCount - 1
                    }
                }
                LaunchedEffect(hub.messages.size, hub.sessionId) {
                    if (hub.messages.isNotEmpty() && atBottom) chatListState.scrollToItem(hub.messages.lastIndex)
                }
                Text(
                    text = hub.sessionTitle?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.float_panel_untitled_session),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hub.messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.float_panel_chat_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 反馈7：会话区叠加一键回底按钮，不在底部时显示
                    val listScope = rememberCoroutineScope()
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            state = chatListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            // HubMessage 无 id，key 用「角色 + 内容 + 位置」：内容变了 key 跟着变，
                            // 比无 key 时按位置复用更准，又不会因两条内容相同的消息撞 key。
                            itemsIndexed(
                                items = hub.messages,
                                key = { index, msg -> "$index:${msg.role}:${msg.text.hashCode()}" },
                                contentType = { _, msg -> msg.role }
                            ) { _, msg ->
                                val isUser = msg.role == "USER"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = when {
                                            isUser -> MaterialTheme.colorScheme.primaryContainer
                                            msg.isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier.widthIn(max = bubbleMaxWidth)
                                    ) {
                                        // 折叠态超 6 行收起；remember 存在 item 组合内，条目滚出视口销毁后
                                        // 自动回到折叠——「划走后一段时间自动折叠」用这个机制天然满足。
                                        val foldable = msg.text.length > 160 || msg.text.count { it == '\n' } >= 6
                                        var expanded by remember(msg.text) { mutableStateOf(false) }
                                        Text(
                                            text = msg.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = if (!foldable || expanded) Int.MAX_VALUE else 6,
                                            overflow = if (!foldable || expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .then(
                                                    if (foldable) {
                                                        Modifier.clickable { expanded = !expanded }
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !atBottom,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(150)),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 2.dp, bottom = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable {
                                        if (hub.messages.isNotEmpty()) {
                                            listScope.launch {
                                                // 协程启动到执行之间可能又有新消息插入，届时旧 lastIndex 会越界。
                                                val target = chatListState.layoutInfo.totalItemsCount - 1
                                                if (target >= 0) chatListState.animateScrollToItem(target)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.ArrowDown,
                                    contentDescription = stringResource(R.string.float_panel_scroll_bottom),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 2) 正在做什么
                if (state.busy && state.activeToolName != null) {
                    Text(
                        text = stringResource(toolRunningLabelRes(state.activeToolName, running = true)),
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

                // 4) （回复预览已由消息流取代——FB1 后面板直接看得到完整最近对话）

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

                // 6) 展开式输入窗（反馈1/2/3/4）：底栏只留入口图标，点击向上展开；发送后自动收起；
                //    展开时与工具排之间以细分隔线分界；无占位提示文字（悬浮窗用户熟悉软件逻辑）
                AnimatedVisibility(
                    visible = inputOpen,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column {
                        LaunchedEffect(Unit) { inputFocus.requestFocus() }
                        TextField(
                            value = input,
                            onValueChange = updateInput,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp, max = 110.dp)
                                .focusRequester(inputFocus)
                                .onFocusChanged { if (it.isFocused && !pinned) onInputFocused() },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        )
                    }
                }

                // 7) 工具排（FB2）：模式循环 / 模型切换 / 输入入口 / 润色 / 发送，间隔均分
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentMode = hub.mode ?: com.aicode.feature.agent.domain.model.AgentMode.BUILD
                    val modeEntries = com.aicode.feature.agent.domain.model.AgentMode.entries
                    val nextMode = modeEntries[(modeEntries.indexOf(currentMode) + 1) % modeEntries.size]
                    // 1) 模式循环：构建 → 计划 → 自动 → 构建（App 内三按钮合并为一个循环切换，运行中即时生效）
                    IconButton(
                        onClick = {
                            val setter = FloatBallCommandCenter.setMode
                            if (setter == null) hint = context.getString(R.string.float_panel_send_need_app)
                            else setter(nextMode)
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = when (currentMode) {
                                com.aicode.feature.agent.domain.model.AgentMode.BUILD -> FeatherIcons.Tool
                                com.aicode.feature.agent.domain.model.AgentMode.PLAN -> FeatherIcons.Clipboard
                                else -> FeatherIcons.Zap
                            },
                            contentDescription = currentMode.name,
                            tint = when (currentMode) {
                                com.aicode.feature.agent.domain.model.AgentMode.BUILD -> MaterialTheme.semanticColors.success
                                com.aicode.feature.agent.domain.model.AgentMode.PLAN -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 2) 模型选择：悬浮窗内嵌同款模型列表（Hub 自足提供数据与写回，不依赖页面存活）
                    IconButton(
                        onClick = {
                            val data = sessionHub.modelData()
                            if (data == null) hint = context.getString(R.string.float_panel_send_need_app)
                            else {
                                modelPickerSnapshot = data
                                modelQuery = ""
                                panelView = "modelPicker"
                            }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Cpu,
                            contentDescription = stringResource(R.string.float_panel_pick_model),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 输入入口（反馈1）：文本输入收进底栏中部图标，点击在工具排上方展开输入窗
                    IconButton(
                        onClick = { inputOpen = !inputOpen },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.MessageSquare,
                            contentDescription = stringResource(R.string.float_panel_input),
                            tint = if (inputOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 润色（FB3）：点击润色输入框文本（复用会话输入框润色链路），长按恢复润色前原文
                    var ballPolishing by remember { mutableStateOf(false) }
                    var ballPrePolish by remember { mutableStateOf<String?>(null) }
                    val polishScope = rememberCoroutineScope()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .combinedClickable(
                                enabled = !ballPolishing,
                                onClick = {
                                    val text = input
                                    if (text.isBlank()) {
                                        hint = context.getString(R.string.chat_polish_empty)
                                        return@combinedClickable
                                    }
                                    val bridge = FloatBallCommandCenter.polishText
                                    if (bridge == null) {
                                        hint = context.getString(R.string.float_panel_send_need_app)
                                        return@combinedClickable
                                    }
                                    ballPolishing = true
                                    polishScope.launch {
                                        // 异常安全：润色请求失败（网络/供应商错误）必须复位按钮，否则永久转圈禁用。
                                        val polished = try {
                                            bridge(text)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (t: Throwable) {
                                            null
                                        }
                                        ballPolishing = false
                                        if (polished.isNullOrBlank() || polished == text) {
                                            hint = context.getString(R.string.chat_polish_failed)
                                        } else {
                                            ballPrePolish = text
                                            input = polished
                                            FloatBallCommandCenter.updateDraft?.invoke(polished)
                                            hint = context.getString(R.string.chat_polish_done)
                                        }
                                    }
                                },
                                onLongClick = {
                                    val prev = ballPrePolish ?: return@combinedClickable
                                    ballPrePolish = null
                                    input = prev
                                    FloatBallCommandCenter.updateDraft?.invoke(prev)
                                    hint = context.getString(R.string.chat_polish_undo)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ballPolishing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = FeatherIcons.Feather,
                                contentDescription = stringResource(R.string.chat_polish),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // 发送（App 内 SendButton 同款 36dp 圆钮，箭头恒白；忙时空输入=停止）
                    val canSend = input.isNotBlank()
                    val showStop = state.busy && !canSend
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    showStop -> MaterialTheme.colorScheme.error
                                    state.busy -> com.aicode.core.theme.Brand.Orange
                                    canSend -> MaterialTheme.semanticColors.success
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            .clickable {
                                if (showStop) {
                                    val result = FloatBallCommandCenter.stop?.invoke()
                                    hint = context.getString(
                                        when (result) {
                                            true -> R.string.float_panel_stopped
                                            false -> R.string.float_panel_nothing_running
                                            null -> R.string.float_panel_send_need_app
                                        }
                                    )
                                } else if (input.isBlank() && !inputOpen) {
                                    // 输入窗收起时空点发送：展开输入窗而不是静默无响应（qa P2）
                                    inputOpen = true
                                } else doSend()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showStop) FeatherIcons.Square else FeatherIcons.ArrowUp,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
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
            // 右下角拖拽调大小把手（三道斜线）：拖动即实时改窗口尺寸并持久化
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(26.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            resizePanelBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // DrawScope 非 @Composable 上下文：颜色必须先在外面取好再传入（项目内既有坑）
                val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
                    for (i in 1..3) {
                        val off = i * (size.width / 4f)
                        drawLine(
                            color = handleColor,
                            start = Offset(size.width - off, size.height),
                            end = Offset(size.width, size.height - off),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * 模型选择视图（FB2）：悬浮窗内的纯 Compose 列表——provider 分组 + 搜索过滤，
 * 选中走与 App 内模型 sheet 同一落点（[FloatBallCommandCenter.selectModel]）。
 * 不能用 ModalBottomSheet/Dialog：悬浮窗窗口无 Activity token，弹窗必崩（BadTokenException）。
 */
@Composable
private fun ModelPickerView(
    data: FloatBallCommandCenter.FloatModelPickerData,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = FeatherIcons.ArrowLeft,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = stringResource(R.string.float_panel_pick_model),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.float_panel_model_search), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val q = query.trim()
            val providers = data.providers.filter { it.isEnabled }
            val hitProviders = providers.filter { p ->
                q.isBlank() || p.name.contains(q, ignoreCase = true) ||
                    p.models.any { it.contains(q, ignoreCase = true) }
            }
            if (hitProviders.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.float_panel_model_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
            hitProviders.forEach { p ->
                val models = if (q.isBlank()) p.models
                else p.models.filter { it.contains(q, ignoreCase = true) }
                item(key = "provider-${p.id}") {
                    Text(
                        text = p.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                itemsIndexed(models, key = { _, model -> model }) { idx, model ->
                    val selected = p.id == data.currentProviderId && model == data.currentModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(p.id, model) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
