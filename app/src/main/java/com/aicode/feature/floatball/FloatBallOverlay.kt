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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import compose.icons.feathericons.Zap
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
        when (min) {
            distLeft -> { x = 0; y = (params.y).coerceIn(0, screenHeight - peekHeight) }
            distRight -> { x = screenWidth - peekWidth; y = (params.y).coerceIn(0, screenHeight - peekHeight) }
            distTop -> { y = 0; x = (params.x).coerceIn(0, screenWidth - peekWidth) }
            else -> { y = screenHeight - peekHeight; x = (params.x).coerceIn(0, screenWidth - peekWidth) }
        }
        animateWindow(peekWidth, peekHeight, x, y)
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
        // 悬浮窗宿主是 Service（同样被语言 wrapper 包裹），isSystemInDarkTheme() 读冻结值，
        // 改读系统深浅实时镜像流（AIEditorApp.onConfigurationChanged 驱动）。
        val systemDark by themeSettings.systemDark.collectAsState(initial = false)
        val darkTheme = when (mode) {
            AppThemeMode.AUTO -> systemDark
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
                    .graphicsLayer { alpha = breathState?.value ?: 1f }
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
