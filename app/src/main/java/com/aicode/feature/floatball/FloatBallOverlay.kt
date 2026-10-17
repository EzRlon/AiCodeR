package com.aicode.feature.floatball

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 悬浮球与迷你面板（系统级悬浮窗，WindowManager 渲染）。
 * 小球：可拖动、松手贴边；球色三态（工作中=主色呼吸环 / 失败=红 / 空闲=灰）。
 * 点击球展开面板：任务状态 + 快速输入发送 + 回到 App + 关闭悬浮球。
 *
 * 位置只由窗口 LayoutParams 承载（窗口与球同尺寸），Compose 内容不做任何平移，
 * 否则内容会被窗口 surface 裁剪（悬浮球一度不可见即此因）。
 */
class FloatBallOverlay(
    private val context: Context,
    private val stateStore: FloatBallStateStore,
    private val onClose: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var ballView: ComposeView? = null
    private var panelWindow: PopupWindow? = null
    private var panelOpen = false

    /** 屏幕尺寸：Android 11+ 用 maximumWindowMetrics（分屏下仍取全屏），低版本回退 realSize。 */
    private val screenSize: Pair<Int, Int>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val point = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
            point.x to point.y
        }
    private val screenWidth: Int get() = screenSize.first
    private val screenHeight: Int get() = screenSize.second

    private val ballSize = 112 // px ≈ 40dp @2.75x，实际按 dp 换算

    /** 球窗口与面板窗口各自的 Compose 宿主 owner（attach 创建、detach/dismiss 销毁）。 */
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

    /** 悬浮窗 ComposeView 统一挂 ViewTree 三件套（Service 上下文无 Activity 提供的 owner）。
     *  按资源名解析 id 后写入对应的 View tag：与 androidx 内部读取的是同一个 tag（资源合并后 id 稳定），
     *  避开 2.9 起在源码层被隐藏的 ViewTree* 符号。 */
    private fun ComposeView.withOverlayOwners(owner: OverlayComposeOwner): ComposeView = apply {
        fun tagId(name: String) = resources.getIdentifier(name, "id", context.packageName)
        tagId("view_tree_lifecycle_owner").takeIf { it != 0 }?.let { setTag(it, owner) }
            ?: com.aicode.core.util.FileLogger.w(TAG, "资源 id 解析失败：view_tree_lifecycle_owner")
        tagId("view_tree_view_model_store_owner").takeIf { it != 0 }?.let { setTag(it, owner) }
            ?: com.aicode.core.util.FileLogger.w(TAG, "资源 id 解析失败：view_tree_view_model_store_owner")
        tagId("view_tree_saved_state_registry_owner").takeIf { it != 0 }?.let { setTag(it, owner) }
            ?: com.aicode.core.util.FileLogger.w(TAG, "资源 id 解析失败：view_tree_saved_state_registry_owner")
    }

    private val configurationListener = object : android.content.ComponentCallbacks {
        @Deprecated("Deprecated in Java")
        override fun onConfigurationChanged(newConfig: Configuration) {
            // 旋转/分辨率变化后旧坐标可能落在屏幕外：重新贴边收敛到可视区域。
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
            view.setContent { BallContent() }
            val params = WindowManager.LayoutParams(
                ballSize, ballSize,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - ballSize - dp(8)
                y = screenHeight / 3
            }
            windowManager.addView(view, params)
            ballView = view
            context.registerComponentCallbacks(configurationListener)
            com.aicode.core.util.FileLogger.i(TAG, "悬浮球已挂载 x=${params.x} y=${params.y} size=$ballSize")
        } catch (e: Throwable) {
            // 无权限/窗口被禁/组合异常等：记日志、销毁 owner 后关闭服务，避免反复崩溃与泄漏。
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

    private fun dismissPanel() {
        panelWindow?.dismiss()
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

    /** 拖动中跟随手指：横向上至少留半个球在屏内，保证还能抓回来。 */
    private fun dragBy(dx: Int, dy: Int) {
        val params = currentParams() ?: return
        moveTo(
            (params.x + dx).coerceIn(-ballSize / 2, screenWidth - ballSize / 2),
            (params.y + dy).coerceIn(0, screenHeight - ballSize)
        )
    }

    /** 松手/旋转后：横向就近贴左/右缘，纵向收敛进可视区域。 */
    private fun clampToScreen() {
        val params = currentParams() ?: return
        val snapLeft = params.x + ballSize / 2 < screenWidth / 2
        moveTo(
            if (snapLeft) dp(4) else screenWidth - ballSize - dp(4),
            params.y.coerceIn(dp(24), screenHeight - ballSize - dp(24))
        )
    }

    /** 球点击（无拖动）时切换面板；面板定位在球所在侧。 */
    private fun togglePanel(ballX: Int, ballY: Int) {
        if (panelOpen) {
            dismissPanel()
            return
        }
        val onLeft = ballX + ballSize / 2 < screenWidth / 2
        val panelWidth = dp(264)
        val panelHeight = dp(208)
        val anchorX = if (onLeft) ballX + ballSize + dp(6) else ballX - panelWidth - dp(6)
        val anchorY = (ballY - dp(60)).coerceIn(dp(24), screenHeight - panelHeight - dp(24))
        var panelOwner: OverlayComposeOwner? = null
        try {
            panelOwner = OverlayComposeOwner()
            panelComposeOwner = panelOwner
            val panelView = ComposeView(context).withOverlayOwners(panelOwner)
            panelView.setContent { PanelContent(onClose = { dismissPanel() }) }
            val popup = PopupWindow(panelView, panelWidth, panelHeight, true)
            popup.isClippingEnabled = false
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popup.setOnDismissListener {
                panelWindow = null
                panelOpen = false
                panelComposeOwner?.destroy()
                panelComposeOwner = null
            }
            popup.showAtLocation(ballView, Gravity.TOP or Gravity.START, anchorX, anchorY)
            panelWindow = popup
            panelOpen = true
        } catch (e: Throwable) {
            // 面板创建/弹出失败（窗口被禁等）：记日志并清理 owner，不让点击回调崩溃
            com.aicode.core.util.FileLogger.w(TAG, "悬浮面板创建失败：${e.javaClass.simpleName}: ${e.message}")
            panelOwner?.destroy()
            panelComposeOwner = null
            panelOpen = false
        }
    }

    @Composable
    private fun BallContent() {
        val state by stateStore.state.collectAsState()

        // busy 时呼吸环动画；空闲/失败不运行动画（性能功耗纪律：动画只存在于工作态）。
        val pulse = if (state.busy) {
            val transition = rememberInfiniteTransition(label = "ball-pulse")
            val alpha by transition.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
            )
            alpha
        } else 1f

        val ballColor by animateColorAsState(
            targetValue = when {
                state.failed -> Color(0xFFD9534F)
                state.busy -> Color(0xFF4CAF50)
                else -> Color(0xFF9E9E9E)
            },
            animationSpec = tween(220), label = "ball-color"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (panelOpen) dismissPanel()
                            dragBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        },
                        onDragEnd = { clampToScreen() },
                        onDragCancel = { clampToScreen() }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .padding(dp(2).dp)
                    .fillMaxSize()
                    .shadow(dp(4).dp, CircleShape)
                    .clip(CircleShape)
                    .background(ballColor.copy(alpha = if (state.busy) pulse else 0.92f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            val params = currentParams() ?: return@detectTapGestures
                            togglePanel(params.x, params.y)
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.busy) "●" else if (state.failed) "!" else "○",
                    color = Color.White,
                    fontSize = (if (state.busy) 15 else 14).sp,
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun PanelContent(onClose: () -> Unit) {
        val state by stateStore.state.collectAsState()
        var input by remember { mutableStateOf("") }
        var sendState by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(sendState) {
            val msg = sendState ?: return@LaunchedEffect
            delay(1600)
            if (sendState == msg) sendState = null
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        state.failed -> "最近任务失败，点球返回 App 查看"
                        state.busy -> if (state.runningCount > 1) "${state.runningCount} 个任务进行中" else "任务进行中"
                        else -> "空闲"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@TextButton
                            val sender = FloatBallCommandCenter.send
                            when {
                                sender == null -> sendState = "请先打开 App 再发送"
                                sender(text) -> {
                                    input = ""
                                    sendState = "已发送"
                                }
                                else -> sendState = "会话已暂停，先回 App 处理"
                            }
                        },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("发送") }
                    TextButton(
                        onClick = {
                            FloatBallCommandCenter.openApp?.invoke()
                            onClose()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("回到 App") }
                }
                Text(
                    text = sendState ?: "发送后 AI 会在当前会话继续工作",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                TextButton(
                    onClick = {
                        onClose()
                        this@FloatBallOverlay.onClose()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭悬浮球", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
