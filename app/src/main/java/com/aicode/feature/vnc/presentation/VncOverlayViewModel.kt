package com.aicode.feature.vnc.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.vnc.data.VncOverlayPrefsRepository
import com.aicode.feature.vnc.domain.VncServiceController
import com.aicode.feature.vnc.domain.VncServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VNC 悬浮小窗的状态中枢：窗口几何/钉住/最小化/全屏 + 容器服务状态 + 自动弹出轮询。
 * 作用域为聊天页 NavBackStackEntry（与 WorkbenchPane 同惯例）；轮询在聊天页存续期间运行
 * （每 5s 一次本地 TCP 端口探测，毫秒级且不唤起容器，本地模式之外直接短路）；
 * 几何与钉住状态经 DataStore 跨会话记忆，手动收起后 [AUTO_POPUP_COOLDOWN_MS] 内不再自动弹出。
 */
@HiltViewModel
class VncOverlayViewModel @Inject constructor(
    private val serviceController: VncServiceController,
    private val prefsRepository: VncOverlayPrefsRepository,
) : ViewModel() {

    data class WindowState(
        val visible: Boolean = false,
        val minimized: Boolean = false,
        val fullscreen: Boolean = false,
        val pinned: Boolean = false,
        /** dp 坐标；Float.MIN_VALUE 表示尚未设置，UI 层落默认位置。 */
        val offsetX: Float = Float.MIN_VALUE,
        val offsetY: Float = Float.MIN_VALUE,
        /** 0 表示未自定义过，UI 层取默认尺寸。 */
        val widthDp: Float = 0f,
        val heightDp: Float = 0f,
    )

    private val _window = MutableStateFlow(WindowState())
    val window: StateFlow<WindowState> = _window.asStateFlow()

    val serviceState: StateFlow<VncServiceState> = serviceController.state

    /** 悬浮小窗是否可用（仅本地容器模式），随执行模式切换更新。 */
    val supported: Flow<Boolean> = serviceController.supported

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 标题栏「粘贴到 VNC」请求计数，面板侧观察增量触发 WebView 剪贴板推送。 */
    private val _pasteRequests = MutableStateFlow(0)
    val pasteRequests: StateFlow<Int> = _pasteRequests.asStateFlow()

    /** 手动重连请求计数。 */
    private val _reconnectRequests = MutableStateFlow(0)
    val reconnectRequests: StateFlow<Int> = _reconnectRequests.asStateFlow()

    private var autoPopupBlockedUntilMs = 0L

    init {
        viewModelScope.launch {
            val saved = prefsRepository.prefs.first()
            _window.update {
                it.copy(
                    pinned = saved.pinned,
                    offsetX = saved.offsetX,
                    offsetY = saved.offsetY,
                    widthDp = saved.widthDp,
                    heightDp = saved.heightDp,
                )
            }
            val sinceDismissed = System.currentTimeMillis() - saved.dismissedAtMs
            if (sinceDismissed in 0 until AUTO_POPUP_COOLDOWN_MS) {
                autoPopupBlockedUntilMs = saved.dismissedAtMs + AUTO_POPUP_COOLDOWN_MS
            }
        }
        // 自动弹出：聊天页存续期间探测容器图形服务，从无到有即弹窗（AI 跑图形测试/用户手动起服务都会触发）。
        // 指数退避到 PROBE_MAX_INTERVAL_MS：没起服务时是最常见的长稳态，固定 5s 一轮等于白白唤醒
        // 容器探测；一旦探测到服务在跑就重置回短间隔，保证「刚起来就能弹」的跟手感不变。
        viewModelScope.launch {
            var interval = PROBE_INTERVAL_MS
            while (coroutineContext.isActive) {
                delay(interval)
                if (_window.value.visible) {
                    interval = PROBE_INTERVAL_MS
                    continue
                }
                if (System.currentTimeMillis() < autoPopupBlockedUntilMs) continue
                val running = try {
                    serviceController.probeRunning()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    false
                }
                if (running) {
                    interval = PROBE_INTERVAL_MS
                    show()
                } else {
                    interval = (interval * 2).coerceAtMost(PROBE_MAX_INTERVAL_MS)
                }
            }
        }
    }

    fun isSupported(): Boolean = serviceController.isSupported()

    /** 弹出小窗并幂等拉起容器内 VNC 服务。 */
    fun show() {
        _connected.value = false
        _window.update { it.copy(visible = true, minimized = false) }
        viewModelScope.launch { serviceController.ensureRunning() }
    }

    /** 头部按钮单击：未开则弹，最小化中则还原。 */
    fun onHeaderSingleTap() {
        val w = _window.value
        when {
            !w.visible -> show()
            w.minimized -> setMinimized(false)
        }
    }

    /** 头部按钮双击：收起小窗（用户主动，进入自动弹出冷却）。 */
    fun onHeaderDoubleTap() {
        if (_window.value.visible) dismissByUser()
    }

    fun dismissByUser() {
        _window.update { it.copy(visible = false, fullscreen = false) }
        _connected.value = false
        val now = System.currentTimeMillis()
        autoPopupBlockedUntilMs = now + AUTO_POPUP_COOLDOWN_MS
        viewModelScope.launch { prefsRepository.saveDismissedAt(now) }
    }

    fun setMinimized(minimized: Boolean) =
        _window.update { it.copy(minimized = minimized, fullscreen = false) }

    fun setFullscreen(fullscreen: Boolean) =
        _window.update { it.copy(fullscreen = fullscreen, minimized = false) }

    fun setPinned(pinned: Boolean) {
        _window.update { it.copy(pinned = pinned) }
        viewModelScope.launch { prefsRepository.savePinned(pinned) }
    }

    fun requestPaste() = _pasteRequests.update { it + 1 }

    fun requestReconnect() = _reconnectRequests.update { it + 1 }

    /** 失败态重试：重跑容器内 VNC 服务拉起（幂等）；成功后画面由 serviceState 观察方自动重连。 */
    fun retryStart() {
        _connected.value = false
        viewModelScope.launch { serviceController.ensureRunning() }
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    /**
     * 首次组合/父容器尺寸变化时把默认几何写进状态，之后拖动才能在有效值上累加。
     * 已被用户改过（含跨会话恢复）的值不覆盖。
     */
    fun ensureGeometry(defaultXDp: Float, defaultYDp: Float, defaultWDp: Float, defaultHDp: Float) {
        _window.update {
            it.copy(
                offsetX = if (it.offsetX == Float.MIN_VALUE) defaultXDp else it.offsetX,
                offsetY = if (it.offsetY == Float.MIN_VALUE) defaultYDp else it.offsetY,
                widthDp = if (it.widthDp <= 0f) defaultWDp else it.widthDp,
                heightDp = if (it.heightDp <= 0f) defaultHDp else it.heightDp,
            )
        }
    }

    /** 拖动过程：只改内存态，拖完落盘一次，避免每帧写 DataStore。 */
    fun dragBy(deltaXDp: Float, deltaYDp: Float) = _window.update {
        it.copy(offsetX = it.offsetX + deltaXDp, offsetY = it.offsetY + deltaYDp)
    }

    fun resizeBy(deltaWDp: Float, deltaHDp: Float) = _window.update {
        it.copy(
            widthDp = (it.widthDp + deltaWDp).coerceAtLeast(MIN_WIDTH_DP),
            heightDp = (it.heightDp + deltaHDp).coerceAtLeast(MIN_HEIGHT_DP),
        )
    }

    fun persistGeometry() {
        val w = _window.value
        viewModelScope.launch {
            prefsRepository.saveGeometry(w.offsetX, w.offsetY, w.widthDp, w.heightDp)
        }
    }

    companion object {
        private const val PROBE_INTERVAL_MS = 5_000L

        /** 退避上限：服务没起时最长的探测间隔。 */
        private const val PROBE_MAX_INTERVAL_MS = 30_000L
        private const val AUTO_POPUP_COOLDOWN_MS = 10 * 60_000L
        const val MIN_WIDTH_DP = 200f
        const val MIN_HEIGHT_DP = 150f
    }
}
