package com.aicode.feature.vnc.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** VNC 容器侧服务的生命周期状态，供小窗状态灯与提示文案使用。 */
sealed interface VncServiceState {
    data object Idle : VncServiceState
    data object Preparing : VncServiceState
    data object Ready : VncServiceState
    data class Failed(val message: String) : VncServiceState
}

/**
 * 容器内 VNC 服务（Xvfb :1 起 + x11vnc :5901 + websockify :6081）的幂等拉起与探测。
 *
 * 仅本地 PRoot 容器模式可用：PRoot 与 App 共享网络命名空间，App 内 WebView 直接连
 * 127.0.0.1:6081 即可拿到容器画面；远程 SSH 模式下 127.0.0.1 是服务器侧，画面回不到手机。
 * 端口约定（Xvfb :1 起 / x11vnc :5901 / websockify :6081）：websockify 必须用 6081 ——
 * PRoot 与宿主共享网络栈，宿主侧自身的 noVNC 服务占用 6080，容器内绑 6080 必然
 * EADDRINUSE（实测），且宿主 6080 会让 [probeRunning] 误报就绪；x11vnc -noshm
 * 是 PRoot 环境的硬性参数（不支持 SysV shm，shmget 直接 ENOSYS）。
 * Xvfb 显示号自适应：异常退出的 Xvfb 残留进程会永久占住抽象命名空间 socket
 * （不落盘，删锁文件无效；xtrans 在抽象 socket 绑定失败后不回退 filesystem socket，
 * 本机 Xvfb 也不支持 -nolisten abstract），Xvfb 直接退出 —— 故按号探测，
 * 绑不上且号上无活 X 就换 :2/:3，实际显示号落盘 /root/.aicode-vnc-display 供
 * [resizeDisplay] 跟随。
 * websockify 与 x11vnc 均只绑本机回环，画面流不暴露给局域网。
 */
@Singleton
class VncServiceController @Inject constructor(
    private val commandEngine: CommandEngine,
    private val executionModeHolder: ExecutionModeHolder,
) {
    private val _state = MutableStateFlow<VncServiceState>(VncServiceState.Idle)
    val state: StateFlow<VncServiceState> = _state.asStateFlow()

    /** 悬浮小窗是否可用：仅本地容器模式支持；随执行模式切换更新，供 UI 可观察订阅。 */
    val supported: Flow<Boolean> = executionModeHolder.mode
        .map { it == ExecutionMode.LOCAL_PROOT }

    /** 同步版判断，供轮询等非组合上下文使用。 */
    fun isSupported(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.LOCAL_PROOT

    /**
     * 轻量探测服务是否在跑。探测 6081（容器 websockify 专用端口；6080 已被宿主自身
     * noVNC 占用，探它会永远误报就绪），用本地 TCP 连接探测代替每轮一次的容器内
     * pgrep：语义等价，聊天页存续期间的高频自动弹出探测不再唤醒容器执行命令。
     * 容器未就绪/远程模式连不上，返回 false，不会触发 rootfs 解压等初始化。
     */
    suspend fun probeRunning(): Boolean {
        if (!isSupported()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, VNC_WS_PORT), PROBE_TIMEOUT_MS) }
            }.isSuccess
        }
    }

    /**
     * 跟随浮窗宽高比调整虚拟屏分辨率（竖屏/横屏/缩放都能铺满）。
     *
     * 只在「缩放/旋转结束」时调用（调用方已节流），命令毫秒级；失败忽略——旧分辨率仍可用，
     * 只是画面可能留黑边，不影响连接。
     */
    suspend fun resizeDisplay(width: Int, height: Int) {
        if (!isSupported()) return
        if (width < 320 || height < 320) return
        // 显示号由 HOLD_SCRIPT 落盘（Xvfb 自适应后可能是 :2/:3）；读不到回退 :1。
        val display = runCatching {
            commandEngine.runCommandSync(
                command = "cat /root/.aicode-vnc-display 2>/dev/null",
                timeoutMs = RESIZE_TIMEOUT_MS
            ).trim().takeIf { it.matches(Regex("\\d+")) }
        }.getOrNull() ?: "1"
        runCatching {
            commandEngine.runCommandSync(
                command = "DISPLAY=:$display xrandr --fb ${width}x$height",
                timeoutMs = RESIZE_TIMEOUT_MS
            )
        }.onFailure { FileLogger.w(TAG, "调整 VNC 分辨率失败: ${width}x$height", it) }
    }

    /**
     * 启动服务并保持宿主进程存活。
     *
     * PRoot 以 `--kill-on-exit` 启动：proot 进程退出的瞬间，容器内所有子进程被级联杀掉。
     * 因此「脚本里 nohup 起服务后立即 return」的写法必然失效——setsid/nohup 都无法逃逸，
     * 服务在 `runCommandSyncWithExit` 返回的同时就死了，[probeRunning] 永远连不上。
     *
     * 唯一可行的形态是让这条命令**一直不退出**：前台 exec 到 websockify，由 App 持有该会话。
     * 调用方需在 viewModelScope 里 launch，随浮窗关闭取消即可让 proot 退出、服务一并回收。
     */
    suspend fun startAndHold() {
        if (!isSupported()) return
        var everReady = false
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            _state.value = VncServiceState.Preparing
            // 先清孤儿：旧会话被强杀后 websockify 可能仍占 6081，导致本次 bind 失败。
            // 清理必须独立会话执行：HOLD_SCRIPT 以 sh -c 内联运行，命令行含 "websockify"
            // 明文，同脚本内 pkill -f "[w]ebsockify" 会命中父 shell 自杀（v5 实机验证的教训）。
            runCatching {
                commandEngine.runCommandSync(command = CLEANUP_SCRIPT, timeoutMs = CLEANUP_TIMEOUT_MS)
            }.onFailure { FileLogger.w(TAG, "VNC 孤儿清理失败: ${it.message}") }
            var becameReady = false
            var failReason: String? = null
            commandEngine.runCommandStream(
                command = HOLD_SCRIPT,
                timeoutMs = HOLD_TIMEOUT_MS,
            ).collect { event ->
                when (event) {
                    is CommandEvent.Line -> when {
                        event.text.contains(READY_MARKER) -> {
                            becameReady = true
                            everReady = true
                            _state.value = VncServiceState.Ready
                        }
                        event.text.startsWith(FAIL_MARKER) ->
                            failReason = event.text.removePrefix(FAIL_MARKER).trim().take(200)
                    }
                    is CommandEvent.Exit -> Unit
                }
            }
            if (becameReady) {
                consecutiveFailures = 0
                // 会话被看门狗 30 分钟上限强杀（或连接异常断开）：短暂停顿后续接，避免热循环
                delay(RECONNECT_DELAY_MS)
                continue
            }
            // 启动失败：透出 HOLD_SCRIPT 的原因（VNC_FAIL=…），指数退避防闪烁；
            // 连续失败达上限进终态，由用户手动重试（重试会重置计数）。
            consecutiveFailures++
            val reason = failReason ?: "服务未能启动（无诊断输出）"
            FileLogger.e(TAG, "VNC 服务第 $consecutiveFailures 次启动失败: $reason")
            if (consecutiveFailures >= MAX_START_FAILURES) {
                _state.value = VncServiceState.Failed(reason)
                return
            }
            _state.value = VncServiceState.Failed(reason)
            delay(RECONNECT_DELAY_MS shl (consecutiveFailures - 1))
        }
    }

    companion object {
        private const val TAG = "VncService"
        private const val RESIZE_TIMEOUT_MS = 5_000L
        private const val READY_MARKER = "VNC_READY"

        /** HOLD_SCRIPT 启动失败时输出诊断前缀（原因经 [startAndHold] 透出到状态层）。 */
        private const val FAIL_MARKER = "VNC_FAIL="

        /** 连续启动失败上限：达到后进 Failed 终态，避免无退避热循环导致悬浮窗闪烁。 */
        private const val MAX_START_FAILURES = 3

        /**
         * 常驻会话超时。
         *
         * 不能超过 [CommandEngine] 的 MAX_TIMEOUT_MS（30 分钟），否则会被钳到上限后强杀，
         * 画面会突然断开。取满 30 分钟，到期由 [startAndHold] 的循环自动续接下一段会话。
         */
        private const val HOLD_TIMEOUT_MS = 1_800_000L

        /** 续接前的停顿：让上一段 proot 彻底退出、端口释放。 */
        private const val RECONNECT_DELAY_MS = 500L

        /** 孤儿清理会话的超时；pkill+sleep 2s 内必完成。 */
        private const val CLEANUP_TIMEOUT_MS = 15_000L

        /** websockify 在本机回环的监听端口（App 内 WebView 直连 127.0.0.1:6081）。 */
        private const val VNC_WS_PORT = 6081
        private const val PROBE_TIMEOUT_MS = 800
        private const val LOOPBACK = "127.0.0.1"

        /**
         * 转发层孤儿清理：独立会话执行。
         *
         * 本身只含方括号模式（不含 websockify 等明文），pkill 不会命中自己；
         * 而 HOLD_SCRIPT 内联在 sh -c 里，命令行含明文，若在同一脚本内清理会自杀。
         * 1s 停顿让被杀进程释放端口。
         *
         * 只清 websockify / x11vnc，**不动 Xvfb**：常驻会话每 30 分钟会被引擎上限强杀并续接，
         * 而 :1 上的 X 服务器通常由 multi-llm 的 MCP 会话持有（见 VncOverlayViewModel 预开逻辑），
         * 它不随本会话消失。这里若一并 pkill，就会连 X 服务器及其上的浏览器一起清掉，
         * 续接后只剩空桌面。旧会话自建的 Xvfb 随会话退出自然消亡，无需清理。
         */
        private val CLEANUP_SCRIPT = """
            pkill -f "[w]ebsockify" 2>/dev/null
            pkill -f "[x]11vnc" 2>/dev/null
            sleep 1
            echo CLEANED
        """.trimIndent()

        /**
         * 常驻版：前台 exec 到 websockify，进程永不返回。
         *
         * proot 的 --kill-on-exit 使任何「后台起完就 return」的写法失效（子进程随 proot 退出被杀），
         * 所以必须让这条命令本身常驻。Xvfb / x11vnc 在同一个 proot 会话内后台起，
         * 由前台 websockify 的存活把整个会话（连同它们）一起托住。
         *
         * 不做任何 pkill/pgrep 守卫：内联执行时命令行含明文，匹配类工具会命中父 shell 自杀。
         * 改为无条件拉起——若端口已被旧实例占用，新进程绑定失败自然退出，旧实例继续服务，
         * 幂等；转发层孤儿由 [CLEANUP_SCRIPT] 在每次尝试前清理（Xvfb 除外，见该脚本注释）。
         */
        /**
         * 极简桌面配置：只有任务栏、时钟、右键菜单和纯色背景。
         * 写进 ~/.jwmrc（容器内 HOME=/root）而不是另起配置系统——jwm 是现成组件，配置只是数据。
         * 每次启动覆盖写入，保证升级后配置不会与代码版本漂移。
         */
        private val JWM_CONFIG = """
            <JWM>
              <RootMenu onroot="3">
                <Program label="Terminal">xterm -geometry 120x36 -title "AiCode Shell"</Program>
                <Program label="Files">pcmanfm</Program>
                <Separator/>
                <Program label="Restart jwm">jwm -restart</Program>
                <Exit label="Close menu"/>
              </RootMenu>
              <Desktops width="2" height="1">
                <Background type="solid">#1e1e1e</Background>
              </Desktops>
              <!-- TaskList/Clock 必须在 Tray 里才会显示；此前外层漏了 Tray，任务栏根本没画出来。 -->
              <Tray x="0" y="-1" height="32">
                <TrayButton label="Menu">root:3</TrayButton>
                <TaskList/>
                <Clock format="%H:%M"/>
              </Tray>
            </JWM>
        """.trimIndent()

        /**
         * 把 [JWM_CONFIG] 逐行加单引号，供 `printf '%s\n' '行1' '行2' …` 原样写入。
         *
         * 为什么不用 heredoc：整段启动脚本是 Kotlin 原始字符串 + `trimIndent()`，
         * 去缩进量取决于全脚本最小缩进，写死缩进的 `JWM_EOF` 终止符很可能不被 shell 识别，
         * 于是把随后的 Xvfb/x11vnc/websockify 全部当成配置内容，表现为「VNC 无画面」（已实测）。
         * 单引号包裹后 shell 不做任何展开，XML 里的 `<` `>` `#` `%` 都安全。
         */
        private val JWM_CONFIG_LINES: String = JWM_CONFIG.lines()
            .joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }

        private val HOLD_SCRIPT = """
            # 正常操作系统形态的轻量桌面（2026-11-17 用户定）：
            #   openbox（窗口管理）+ lxpanel（任务栏 + 开始菜单）+ pcmanfm（桌面/文件）
            #   + 中文环境（WenQuanYi 字体 + zh_CN.UTF-8）+ 软件包管理入口（Synaptic）。
            # 不做成 Windows 全量，但开始菜单/任务栏/右键菜单/中文/包管理都有。
            export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 LANGUAGE=zh_CN:zh
            # Alpine 的 jwm/pcmanfm 在 community 仓库，先补上（Debian 无此步）。
            if command -v apk >/dev/null 2>&1 && [ -f /etc/apk/repositories ]; then
                main_line=${'$'}(grep -m1 -E '^[^#].*/main' /etc/apk/repositories)
                if [ -n "${'$'}main_line" ]; then
                    community_line=${'$'}{main_line%/main}/community
                    grep -qxF "${'$'}community_line" /etc/apk/repositories || echo "${'$'}community_line" >> /etc/apk/repositories
                fi
            fi
            # 不再用「一次成功就永久跳过」的标记：旧版本曾在只有 jwm 时写下 marker，
            # 之后永远跳过安装，导致 lxpanel/字体/Synaptic 一直缺失（真机实测的根因）。
            # 改为每次启动校验关键组件，缺哪个补哪个；apt 已装时这条是秒级空操作。
            need_install=0
            for c in lxpanel openbox pcmanfm synaptic lxterminal locale-gen; do
                command -v "${'$'}c" >/dev/null 2>&1 || need_install=1
            done
            fc-list 2>/dev/null | grep -qiE 'CJK|WenQuanYi|Noto Sans SC' || need_install=1
            locale -a 2>/dev/null | grep -qi '^zh_CN' || need_install=1
            if [ "${'$'}need_install" = "1" ]; then
                if command -v apt-get >/dev/null 2>&1; then
                    apt-get update -y >/dev/null 2>&1 || true
                    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
                        xvfb x11vnc python3-websockify openbox lxpanel pcmanfm lxterminal \
                        lxrandr lxappearance synaptic fonts-wqy-zenhei fonts-noto-cjk fonts-dejavu-core \
                        locales dbus-x11 libglib2.0-bin x11-xserver-utils >/dev/null 2>&1 || true
                    command -v fc-cache >/dev/null 2>&1 && fc-cache -f >/dev/null 2>&1 || true
                    if [ -f /etc/locale.gen ]; then
                        grep -q '^zh_CN.UTF-8' /etc/locale.gen || echo 'zh_CN.UTF-8 UTF-8' >> /etc/locale.gen
                        command -v locale-gen >/dev/null 2>&1 && locale-gen >/dev/null 2>&1 || true
                    fi
                elif command -v apk >/dev/null 2>&1; then
                    apk add --no-cache xvfb x11vnc websockify openbox lxpanel pcmanfm lxterminal \
                        font-wqy-zenhei font-noto-cjk xrandr xsetroot >/dev/null 2>&1 || true
                fi
            fi
            # GTK 字体显式指定（字体装了但 fontconfig 没选中时仍会方块）。
            mkdir -p /root/.config/gtk-3.0 /root/.config/gtk-2.0
            printf '%s\n' '[Settings]' 'gtk-font-name=Noto Sans CJK SC 10' \
                'gtk-theme-name=Adwaita' 'gtk-icon-theme-name=Adwaita' \
                > /root/.config/gtk-3.0/settings.ini
            printf '%s\n' 'gtk-font-name = "Noto Sans CJK SC 10"' \
                > /root/.config/gtk-2.0/gtkrc
            # 开始菜单/桌面里的软件包管理：Synaptic 首次点击时安装，避免拖慢首启。
            mkdir -p /usr/share/applications /root/Desktop
            printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Package Manager' \
                'Name[zh_CN]=软件包管理' 'Comment=Install or remove software' 'Comment[zh_CN]=安装、卸载软件' \
                'Exec=synaptic' \
                'Icon=synaptic' 'Terminal=false' 'Categories=System;PackageManager;' \
                > /usr/share/applications/aicode-package-manager.desktop
            printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Settings' \
                'Name[zh_CN]=系统设置' 'Exec=sh -c "lxrandr & lxappearance"' \
                'Icon=preferences-system' 'Terminal=false' 'Categories=Settings;' \
                > /usr/share/applications/aicode-settings.desktop
            # 文件名用 ASCII，中文只放 Name[zh_CN]：避免文件系统/字体缺失时中文文件名变乱码。
            printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Terminal' 'Name[zh_CN]=终端' \
                'Exec=lxterminal' 'Icon=utilities-terminal' 'Terminal=false' \
                > /root/Desktop/Terminal.desktop
            printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Files' 'Name[zh_CN]=文件' \
                'Exec=pcmanfm /root' 'Icon=system-file-manager' 'Terminal=false' \
                > /root/Desktop/Files.desktop
            cp /usr/share/applications/aicode-package-manager.desktop /root/Desktop/PackageManager.desktop 2>/dev/null || true
            cp /usr/share/applications/aicode-settings.desktop /root/Desktop/Settings.desktop 2>/dev/null || true
            # 清理旧版中文文件名的快捷方式（它们就是乱码来源）。
            rm -f /root/Desktop/终端.desktop /root/Desktop/文件.desktop \
                  /root/Desktop/软件包管理.desktop /root/Desktop/系统设置.desktop 2>/dev/null || true
            chmod +x /root/Desktop/*.desktop 2>/dev/null || true
            # 标记为「可信启动器」：不标记时 pcmanfm 双击 .desktop 会弹 Execute File 询问框。
            command -v gio >/dev/null 2>&1 && for f in /root/Desktop/*.desktop; do
                gio set "${'$'}f" metadata::trusted true >/dev/null 2>&1 || true
            done
            # libfm：桌面双击直接执行，不再弹执行确认；桌面使用本地文件图标。
            mkdir -p /root/.config/libfm
            printf '%s\n' '[config]' 'quick_exec=1' 'single_click=0' \
                > /root/.config/libfm/libfm.conf
            # 已安装的常见图形程序（Chrome/Chromium/Firefox/VS Code 等）自动补桌面图标。
            # 之前只创建了四个内置快捷方式，用户装的浏览器不会自动出现在桌面。
            for app in google-chrome google-chrome-stable chromium chromium-browser firefox code; do
                for src in /usr/share/applications/${'$'}app.desktop /usr/share/applications/${'$'}app-*.desktop; do
                    [ -f "${'$'}src" ] && cp -f "${'$'}src" /root/Desktop/ 2>/dev/null
                done
            done
            chmod +x /root/Desktop/*.desktop 2>/dev/null || true
            # jwm 作为兜底配置（lxpanel 缺失时仍能有菜单）。
            printf '%s\n' $JWM_CONFIG_LINES > /root/.jwmrc
            # 显示号自适应：:1 优先（multi-llm 等 MCP 会话持有的活 X 直接接管，原语义不变）；
            # 但异常退出的 Xvfb 残留进程会永久占住抽象命名空间 socket（不落盘、删锁文件无效，
            # xtrans 绑定失败不回退 filesystem socket，本机 Xvfb 也不支持 -nolisten abstract），
            # Xvfb 直接退出。用「进程活着？」判定，绑不上且号上也无活 X 就换号，而非无限重试。
            XVFB_DISP=""
            for d in 1 2 3; do
                Xvfb :${'$'}d -screen 0 720x1280x24 -nolock -nolisten tcp >/dev/null 2>&1 &
                xvfb_pid=${'$'}!
                sleep 1
                if kill -0 ${'$'}xvfb_pid 2>/dev/null; then XVFB_DISP=${'$'}d; break; fi
                if DISPLAY=:${'$'}d xset -q >/dev/null 2>&1; then XVFB_DISP=${'$'}d; break; fi
            done
            if [ -z "${'$'}XVFB_DISP" ]; then
                echo "VNC_FAIL=Xvfb :1..:3 均无法建立（抽象 socket 幽灵占用或包缺失）"
                exit 1
            fi
            echo "${'$'}XVFB_DISP" > /root/.aicode-vnc-display
            i=0
            while [ ${'$'}i -lt 10 ] && [ ! -S /tmp/.X11-unix/X${'$'}XVFB_DISP ]; do sleep 0.5; i=${'$'}((i+1)); done
            command -v xsetroot >/dev/null 2>&1 && DISPLAY=:${'$'}XVFB_DISP xsetroot -solid '#1e1e1e' >/dev/null 2>&1 || true
            # noVNC 直接显示远端 X 光标；默认 24px 在手机上过大，统一缩到 16px。
            export XCURSOR_SIZE=16
            printf '%s\n' 'Xcursor.size: 16' 'Xcursor.theme: default' | DISPLAY=:${'$'}XVFB_DISP xrdb -merge >/dev/null 2>&1 || true
            # 桌面会话总线：lxpanel/pcmanfm 缺 dbus 会静默退出（表现就是没有任务栏）。
            if command -v dbus-launch >/dev/null 2>&1 && [ -z "${'$'}DBUS_SESSION_BUS_ADDRESS" ]; then
                eval "${'$'}(dbus-launch --sh-syntax)" >/dev/null 2>&1 || true
            fi
            export XDG_CURRENT_DESKTOP=LXDE
            # 任务栏 + 开始菜单：优先 lxpanel；起不来就换 jwm（自带任务栏/菜单），
            # 不能只起 openbox——那样桌面没有开始菜单也没有任务栏（用户截图实测）。
            panel_ok=0
            if command -v lxpanel >/dev/null 2>&1; then
                pgrep -x lxpanel >/dev/null 2>&1 || (DISPLAY=:${'$'}XVFB_DISP lxpanel >/dev/null 2>&1 &)
                sleep 1
                pgrep -x lxpanel >/dev/null 2>&1 && panel_ok=1
            fi
            if [ "${'$'}panel_ok" = "1" ] && command -v openbox >/dev/null 2>&1; then
                pgrep -x openbox >/dev/null 2>&1 || (DISPLAY=:${'$'}XVFB_DISP openbox >/dev/null 2>&1 &)
            elif command -v jwm >/dev/null 2>&1; then
                # 切到 jwm：它自带任务栏（TaskList/Clock）与右键菜单，保证一定有开始菜单入口。
                pgrep -x jwm >/dev/null 2>&1 || (DISPLAY=:${'$'}XVFB_DISP jwm >/dev/null 2>&1 &)
            elif command -v openbox >/dev/null 2>&1; then
                pgrep -x openbox >/dev/null 2>&1 || (DISPLAY=:${'$'}XVFB_DISP openbox >/dev/null 2>&1 &)
            elif command -v twm >/dev/null 2>&1; then
                pgrep -x twm >/dev/null 2>&1 || (DISPLAY=:${'$'}XVFB_DISP twm >/dev/null 2>&1 &)
            fi
            command -v pcmanfm >/dev/null 2>&1 && { pgrep -x pcmanfm >/dev/null 2>&1 || (DISPLAY=:${'$'}XVFB_DISP pcmanfm --desktop >/dev/null 2>&1 &); }
            # 诊断：把关键组件是否就绪写进日志文件，真机可据此判断缺什么。
            { echo "panel_ok=${'$'}panel_ok"; echo "xvfb_disp=${'$'}XVFB_DISP"; for c in lxpanel openbox jwm pcmanfm synaptic; do
                command -v "${'$'}c" >/dev/null 2>&1 && echo "${'$'}c=yes" || echo "${'$'}c=no"; done
              echo "fonts=$(fc-list 2>/dev/null | grep -ci 'CJK\\|WenQuanYi')"; } > /root/.aicode-desktop-diag.txt 2>&1
            sleep 1
            # x11vnc：PRoot 不支持 SysV shm（shmget ENOSYS），-noshm 硬性参数。
            x11vnc -display :${'$'}XVFB_DISP -rfbport 5901 -forever -shared -nopw -localhost -noshm -quiet -xrandr >/dev/null 2>&1 &
            # READY 前真实校验：5901 的 RFB 握手可达才宣告就绪（旧写法无条件 echo，
            # X 没起来时也是「就绪」，悬浮窗在准备中/就绪间反复抖闪）。
            x11vnc_ok=0; i=0
            while [ ${'$'}i -lt 20 ]; do
                if python3 -c "import socket;s=socket.create_connection(('127.0.0.1',5901),1);s.close()" 2>/dev/null; then
                    x11vnc_ok=1; break
                fi
                sleep 0.5; i=${'$'}((i+1))
            done
            if [ "${'$'}x11vnc_ok" != "1" ]; then
                echo "VNC_FAIL=x11vnc 未就绪：X 显示器 :${'$'}XVFB_DISP 或 x11vnc 启动失败"
                exit 1
            fi
            echo $READY_MARKER
            # 6081 而非 6080：PRoot 与宿主共享网络栈，宿主自身 noVNC 占用 6080，
            # 容器内绑 6080 必然 EADDRINUSE（实测），且宿主 6080 会干扰就绪探测。
            exec websockify localhost:6081 localhost:5901
        """.trimIndent()
    }
}
