package com.aicode.feature.vnc.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** VNC 容器侧服务的生命周期状态，供小窗状态灯与提示文案使用。 */
sealed interface VncServiceState {
    data object Idle : VncServiceState
    data object Preparing : VncServiceState
    data object Ready : VncServiceState
    data class Failed(val message: String) : VncServiceState
}

/**
 * 容器内 VNC 服务（Xvfb :1 + x11vnc :5901 + websockify :6080）的幂等拉起与探测。
 *
 * 仅本地 PRoot 容器模式可用：PRoot 与 App 共享网络命名空间，App 内 WebView 直接连
 * 127.0.0.1:6080 即可拿到容器画面；远程 SSH 模式下 127.0.0.1 是服务器侧，画面回不到手机。
 * 端口约定（Xvfb :1 / x11vnc :5901 / websockify :6080）与容器内其他图形工具链路兼容，避免端口冲突；
 * Xvfb -nolock 与 x11vnc -noshm 是 PRoot 环境的硬性参数（无 /tmp 锁文件与 SysV shm）。
 * websockify 与 x11vnc 均只绑本机回环，画面流不暴露给局域网。
 */
@Singleton
class VncServiceController @Inject constructor(
    private val commandEngine: CommandEngine,
    private val executionModeHolder: ExecutionModeHolder,
) {
    private val _state = MutableStateFlow<VncServiceState>(VncServiceState.Idle)
    val state: StateFlow<VncServiceState> = _state.asStateFlow()

    private val ensureMutex = Mutex()

    /** 悬浮小窗是否可用：仅本地容器模式支持；随执行模式切换更新，供 UI 可观察订阅。 */
    val supported: Flow<Boolean> = executionModeHolder.mode
        .map { it == ExecutionMode.LOCAL_PROOT }

    /** 同步版判断，供轮询等非组合上下文使用。 */
    fun isSupported(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.LOCAL_PROOT

    /**
     * 轻量探测服务是否在跑（websockify 活着即认为整套可用）。容器未就绪/远程模式
     * 返回 false，不会触发 rootfs 解压等初始化，供自动弹出轮询高频调用。
     */
    suspend fun probeRunning(): Boolean {
        if (!isSupported()) return false
        val result = commandEngine.runCommandSyncIfReady(command = PROBE_COMMAND)
        return result?.exitCode == 0
    }

    /** 幂等拉起整套 VNC 服务；首次按容器包管理器补装 xvfb/x11vnc/websockify，耗时视网络而定。 */
    suspend fun ensureRunning() {
        if (!isSupported()) return
        ensureMutex.withLock {
            if (_state.value == VncServiceState.Ready) return
            _state.value = VncServiceState.Preparing
            val result = commandEngine.runCommandSyncWithExit(
                command = ENSURE_SCRIPT,
                timeoutMs = ENSURE_TIMEOUT_MS,
            )
            if (result.exitCode == 0 && result.output.contains(READY_MARKER)) {
                _state.value = VncServiceState.Ready
            } else {
                FileLogger.e(TAG, "VNC 服务拉起失败 exit=${result.exitCode}")
                _state.value = VncServiceState.Failed(
                    message = result.output.takeLast(OUTPUT_TAIL_CHARS).ifBlank { "exit=${result.exitCode}" }
                )
            }
        }
    }

    companion object {
        private const val TAG = "VncService"
        private const val READY_MARKER = "VNC_READY"
        private const val OUTPUT_TAIL_CHARS = 300
        private const val ENSURE_TIMEOUT_MS = 600_000L

        // pgrep -f 模式用 [w] 方括号技巧，避免匹配到承载本脚本的 shell 自身。
        private const val PROBE_COMMAND = "pgrep -f \"[w]ebsockify\" >/dev/null 2>&1"
        private val ENSURE_SCRIPT = """
            pgrep -f "[w]ebsockify" >/dev/null 2>&1 && echo $READY_MARKER && exit 0
            if ! command -v websockify >/dev/null 2>&1; then
                if command -v apk >/dev/null 2>&1; then
                    apk add --no-cache xvfb x11vnc websockify
                elif command -v apt-get >/dev/null 2>&1; then
                    apt-get update -y >/dev/null 2>&1
                    DEBIAN_FRONTEND=noninteractive apt-get install -y xvfb x11vnc python3-websockify
                fi
            fi
            pgrep -f "[X]vfb" >/dev/null 2>&1 || (nohup Xvfb :1 -screen 0 1152x720x24 -nolock -nolisten tcp >/dev/null 2>&1 &)
            sleep 1
            pgrep -f "[x]11vnc" >/dev/null 2>&1 || (nohup x11vnc -display :1 -rfbport 5901 -forever -shared -nopw -localhost -noshm -quiet >/dev/null 2>&1 &)
            sleep 1
            pgrep -f "[w]ebsockify" >/dev/null 2>&1 || (nohup websockify localhost:6080 localhost:5901 >/dev/null 2>&1 &)
            i=0
            while [ ${'$'}i -lt 15 ]; do
                pgrep -f "[w]ebsockify" >/dev/null 2>&1 && echo $READY_MARKER && exit 0
                sleep 1
                i=$(( ${'$'}i + 1 ))
            done
            echo VNC_TIMEOUT
        """.trimIndent()
    }
}
