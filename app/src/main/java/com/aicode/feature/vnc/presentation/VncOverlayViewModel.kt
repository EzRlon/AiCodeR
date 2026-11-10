package com.aicode.feature.vnc.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.vnc.data.VncOverlayPrefsRepository
import com.aicode.feature.vnc.domain.VncServiceController
import com.aicode.feature.vnc.domain.VncServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val mcpManager: com.aicode.feature.agent.domain.mcp.McpManager,
    private val mcpConfigRepository: com.aicode.feature.agent.domain.mcp.McpConfigRepository,
    private val generalSettingsRepository: com.aicode.feature.settings.data.repository.GeneralSettingsRepository,
) : ViewModel() {

    /** 画面旋转方式：跟随系统；或锁定横屏 / 竖屏。全屏时 AUTO 取「自动横屏」。 */
    enum class VncRotation { AUTO, LANDSCAPE, PORTRAIT }

    data class WindowState(
        val visible: Boolean = false,
        val minimized: Boolean = false,
        val fullscreen: Boolean = false,
        val pinned: Boolean = false,
        /** 画面旋转方式；取值见 [VncRotation]。 */
        val rotation: VncRotation = VncRotation.AUTO,
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

    /** 常驻服务会话句柄：小窗关闭时取消，让 proot 退出并回收服务进程。 */
    private var serviceJob: Job? = null

    /** 就绪后已自动打开过浏览器（一次会话只开一次，避免反复刷新页面）。 */
    private var autoBrowserOpened = false
    // :1 上的 X 服务器由 multi-llm 的 MCP 会话持有（不随本会话续接消亡）时为 true。
    private var browserOwnsDisplay = false

    init {
        viewModelScope.launch {
            val saved = prefsRepository.prefs.first()
            _window.update {
                it.copy(
                    pinned = saved.pinned,
                    rotation = VncRotation.entries.getOrElse(saved.rotation) { VncRotation.AUTO },
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
        // 容器探测。代价是服务新起后最长要等 30s 才弹窗（原来的上限是 5s）——这是刻意的取舍：
        // 自动弹窗是辅助入口，用户随时可手动打开，不值得为它每秒级轮询。
        viewModelScope.launch {
            var interval = PROBE_INTERVAL_MS
            while (coroutineContext.isActive) {
                delay(interval)
                if (_window.value.visible) {
                    interval = PROBE_INTERVAL_MS
                    continue
                }
                if (System.currentTimeMillis() < autoPopupBlockedUntilMs) continue
                // 自动弹出默认关闭（设置里显式启用后才有此行为）；关闭时空转到最大间隔，不再探测。
                if (!generalSettingsRepository.vncAutoPopupEnabled()) {
                    interval = PROBE_MAX_INTERVAL_MS
                    continue
                }
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

    /** 弹出小窗并拉起容器内 VNC 服务（常驻会话，关闭小窗时取消）。 */
    fun show() {
        _connected.value = false
        _window.update { it.copy(visible = true, minimized = false) }
        startServiceSession()
    }

    /**
     * 拉起服务并持有会话。
     *
     * proot 以 --kill-on-exit 运行，「脚本起完就 return」会让服务随会话退出立即死亡，
     * 必须让一条命令一直不返回；小窗关闭时取消协程即可让 proot 退出、服务回收。
     */
    private fun startServiceSession() {
        if (serviceJob?.isActive == true) return
        autoBrowserOpened = false
        serviceJob = viewModelScope.launch {
            launch {
                // VNC 就绪即自动拉起 multi-llm 登录浏览器（画到 :1，浮窗内可见），
                // 用户不必先提问再看到画面；multi-llm 未连接时静默跳过。
                serviceController.state.collect { state ->
                    if (state is VncServiceState.Ready) {
                        if (!autoBrowserOpened) {
                            autoBrowserOpened = true
                            autoOpenLoreBrowser()
                        }
                    } else if (!browserOwnsDisplay) {
                        // 兜底路径（Xvfb 是本会话自建的）：会话续接重建的是空桌面、浏览器已随旧 X
                        // 服务器退场，下一轮就绪后必须重开，否则浮窗只剩空桌面且再也开不回来。
                        autoBrowserOpened = false
                    }
                }
            }
            // 先开浏览器、再起服务：browser_manager 会自建 Xvfb 并拉起浏览器，它挂在 multi-llm
            // 的 MCP 会话下，不受容器命令 30 分钟上限约束；随后 HOLD_SCRIPT 的 Xvfb 因 :1 已被
            // 占用而失败退出，x11vnc 直接接管同一个 X 服务器。若反过来由本会话的 Xvfb 先占住
            // :1，它会随会话续接一起被强杀，浏览器窗口跟着消失，浮窗变黑且再也开不回来。
            browserOwnsDisplay = autoOpenLoreBrowser()
            autoBrowserOpened = browserOwnsDisplay
            serviceController.startAndHold()
        }
    }

    /** 经 multi-llm MCP 调 open_browser；multi-llm 未连接时先修复配置再拉起，仍失败仅记日志。 */
    private suspend fun autoOpenLoreBrowser(): Boolean {
        val result = runCatching {
            val client = mcpManager.getConnectedClient("multi-llm")
                // multi-llm server 常由 AI 会话按需拉起；进浮窗时多半还没连，主动连一次，
                // 否则浏览器永远不开、浮窗永远黑屏（空 Xvfb 桌面）。
                ?: connectMultiLlm()
                ?: return@runCatching false
            !client.callTool(
                "open_browser",
                kotlinx.serialization.json.buildJsonObject {
                    put("model", kotlinx.serialization.json.JsonPrimitive("home"))
                }
            ).isError
        }
        result.onFailure { FileLogger.w(TAG, "自动打开浏览器失败: ${it.message}") }
        return result.getOrDefault(false)
    }

    /**
     * 拉 multi-llm；首次连接失败时把全局配置里**已存在**的条目重写为最新预设（旧版本写入的
     * 条目缺 PYTHONPATH，server.py 缺 mcp 模块秒退，表现为连接失败→黑屏），再试一次。
     *
     * 条目不存在时直接放弃：multi-llm 已改为插件管理，清单未启用/已卸载时不应由浮窗
     * 凭空写回全局配置，否则「设置 → MCP 服务器」会多出一条插件管不到的残影（新设备也会中招）。
     */
    private suspend fun connectMultiLlm(): com.aicode.feature.agent.domain.mcp.McpClient? {
        mcpManager.reloadServer(MULTI_LLM_SERVER_NAME)
        mcpManager.getConnectedClient(MULTI_LLM_SERVER_NAME)?.let { return it }
        val servers = mcpConfigRepository.getGlobalServers()
        if (servers.none { it.name == MULTI_LLM_SERVER_NAME }) return null
        mcpConfigRepository.setGlobalServers(
            servers.filterNot { it.name == MULTI_LLM_SERVER_NAME } + mcpPreset()
        )
        mcpManager.reloadServer(MULTI_LLM_SERVER_NAME)
        return mcpManager.getConnectedClient(MULTI_LLM_SERVER_NAME)
    }

    private fun mcpPreset(): com.aicode.feature.agent.domain.mcp.McpServerConfig =
        com.aicode.feature.agent.domain.mcp.McpServerConfig(
            name = MULTI_LLM_SERVER_NAME,
            command = "python3",
            args = listOf("/root/.aicode/multi-llm/server.py"),
            env = mapOf("PYTHONPATH" to "/root/.aicode/multi-llm-venv/site"),
            enabled = true
        )

    private fun stopServiceSession() {
        serviceJob?.cancel()
        serviceJob = null
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
        stopServiceSession()
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

    /** 键盘请求：页面点画面时触发，由原生输入框接收并唤起软键盘。 */
    private val _keyboardRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val keyboardRequests: SharedFlow<Unit> = _keyboardRequests.asSharedFlow()

    /** 待发送到页面的按键；页面加载完成后回填。 */
    @Volatile
    var sendKeysToPage: ((String) -> Unit)? = null

    /** 待发送到页面的单个 keysym 按下/抬起事件；页面加载完成后回填。 */
    @Volatile
    var sendKeysymToPage: ((Int, Boolean) -> Unit)? = null

    /** 待发送到页面的鼠标按键模式切换（0=左键 2=右键）；页面加载完成后回填。 */
    @Volatile
    var sendMouseButtonMode: ((Int) -> Unit)? = null

    /** 鼠标按键模式：0=左键 2=右键（虚拟机式键鼠：触点按下时按此按键上报）。 */
    private val _mouseButton = MutableStateFlow(0)
    val mouseButton: StateFlow<Int> = _mouseButton.asStateFlow()

    fun requestKeyboard() {
        _keyboardRequests.tryEmit(Unit)
    }

    fun sendKeys(text: String) {
        sendKeysToPage?.invoke(text)
    }

    /** 发送单个 X keysym 的按下/抬起（删除、回车、方向键、修饰键组合等特殊键）。 */
    fun sendKeysym(code: Int, down: Boolean) {
        sendKeysymToPage?.invoke(code, down)
    }

    fun setMouseButton(button: Int) {
        _mouseButton.value = button
        sendMouseButtonMode?.invoke(button)
    }

    fun setRotation(rotation: VncRotation) {        _window.update { it.copy(rotation = rotation) }
        viewModelScope.launch { prefsRepository.saveRotation(rotation.ordinal) }
    }

    /** 标题栏按钮：跟随系统 → 锁定横屏 → 锁定竖屏 → 跟随系统。 */
    fun cycleRotation() {
        setRotation(
            when (_window.value.rotation) {
                VncRotation.AUTO -> VncRotation.LANDSCAPE
                VncRotation.LANDSCAPE -> VncRotation.PORTRAIT
                VncRotation.PORTRAIT -> VncRotation.AUTO
            }
        )
    }

    fun requestPaste() = _pasteRequests.update { it + 1 }

    fun requestReconnect() = _reconnectRequests.update { it + 1 }

    /** 失败态重试：重开常驻会话拉起服务；成功后画面由 serviceState 观察方自动重连。 */
    fun retryStart() {
        _connected.value = false
        stopServiceSession()
        startServiceSession()
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
        private const val TAG = "VncOverlayViewModel"
        private const val MULTI_LLM_SERVER_NAME = "multi-llm"
        private const val PROBE_INTERVAL_MS = 5_000L

        /** 退避上限：服务没起时最长的探测间隔。 */
        private const val PROBE_MAX_INTERVAL_MS = 30_000L
        private const val AUTO_POPUP_COOLDOWN_MS = 10 * 60_000L
        const val MIN_WIDTH_DP = 200f
        const val MIN_HEIGHT_DP = 150f
    }
}
