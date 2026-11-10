package com.aicode.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aicode.MainActivity
import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.floatball.FloatBallOverlay
import com.aicode.feature.floatball.FloatBallStateStore
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** 用户在设置页开启的常驻保活：为 true 时即便没有后台会话也保持前台通知。 */
    private var persistent = false

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var generalSettings: GeneralSettingsRepository

    @Inject
    lateinit var floatBallStateStore: FloatBallStateStore

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    /** 悬浮球 mini 会话窗口（FB1）的状态镜像：面板消息流/标题从这里只读消费。 */
    @Inject
    lateinit var sessionHub: com.aicode.feature.floatball.FloatBallSessionHub

    @Inject
    lateinit var remoteSshConnection: RemoteSshConnection

    /** 悬浮球开关（内存态；onCreate 从持久化恢复）。悬浮球也由本服务承载——全应用只保留一条常驻通知。 */
    private var floatBallOn = false

    private var overlay: FloatBallOverlay? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 前台保活期间持有的 Wi-Fi 锁：避免切后台/息屏后 Wi-Fi 进入省电使长连接被回收。 */
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var wifiLocksInitialized = false

    /** 远程 SSH 已连接期间需 Wi-Fi 锁防息屏断连；仅常驻开关/悬浮球不构成持锁理由。 */
    private var remoteConnected = false

    /** 本次服务实例的存活起点（onCreate 记下，onDestroy 结算）：保活时长指标（B4）。 */
    private var createdAtMs = 0L

    /** 停止原因标记：onTaskRemoved 置位，onDestroy 结算时区分「用户主动划掉」与「系统回收」。 */
    private var stoppedByTaskRemoval = false

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        createdAtMs = System.currentTimeMillis()
        FileLogger.i(TAG, "Service created")
        // 进程被系统回收后 START_STICKY 重建：persistent 是内存态会丢失。
        // 从持久化开关恢复常驻前台，否则服务空转后 stopSelf，保活形同虚设。
        serviceScope.launch {
            persistent = keepaliveSettings.isEnabled()
            floatBallOn = runCatching { generalSettings.floatBallEnabledFlow.first() }
                .getOrDefault(false)
            if (persistent) {
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
            if (floatBallOn) {
                attachFloatBall()
                FileLogger.i(TAG, "Restored float ball after restart")
            }
            // 远程连接中不允许因「无会话无开关」退出：L2 的锁保护对象就是这个长连接。
            if (!persistent && !floatBallOn &&
                remoteSshConnection.connectionState.value != ConnectionState.CONNECTED
            ) {
                stopSelf()
            }
        }
        // L2：随远程连接状态实时收敛 Wi-Fi 锁（连接中持锁、断开放锁）。
        serviceScope.launch {
            remoteSshConnection.connectionState.collect { state ->
                remoteConnected = state == ConnectionState.CONNECTED
                updateWifiLocks()
            }
        }
    }

    override fun onDestroy() {
        // 保活时长指标（B4）：结算本次实例存活时长。短于阈值即「保活被系统提前回收」的直接证据
        // （用户主动划掉任务也会走这里，但那种情况 stoppedByTaskRemoval 已置位可区分）。
        FileLogger.i(TAG, "Service destroyed")
        runCatching {
            val startedAt = createdAtMs
            if (startedAt > 0) {
                val aliveMs = System.currentTimeMillis() - startedAt
                FileLogger.i(TAG, "保活指标: 结果=${if (stoppedByTaskRemoval) "用户划掉" else "系统回收或正常停止"} 存活ms=$aliveMs")
            }
        }
        removeOverlay()
        serviceScope.cancel()
        releaseWifiLocks()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 app = 明确不要后台运行：停掉前台服务并取消 WorkManager 周期兜底，
        // 否则进程被杀后服务会被 START_STICKY 或 KeepaliveWorker 重新拉起并弹通知。
        // 开关本身保持不变，下次打开 app 时由 MainActivity / AIEditorApp 自动恢复。
        stoppedByTaskRemoval = true
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                ensureForeground()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (sessionCount == 0 && !persistent && !floatBallOn && !remoteConnected) {
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0 && !floatBallOn && !remoteConnected) {
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
            ACTION_SYNC_FLOAT_BALL -> {
                floatBallOn = intent.getBooleanExtra(EXTRA_FLOAT_BALL_ENABLED, false)
                if (floatBallOn) {
                    attachFloatBall()
                } else {
                    removeOverlay()
                    if (!persistent && sessionCount == 0 && !remoteConnected) {
                        stopSelf(startId)
                    } else {
                        ensureForeground()
                    }
                }
                FileLogger.i(TAG, "Float ball synced: enabled=$floatBallOn")
            }
        }
        // L2：会话计数/开关变化后按持锁条件收敛 Wi-Fi 锁。
        updateWifiLocks()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 进入前台并刷新通知；文案随「常驻保活 / 后台会话」组合变化。
     *
     * startForeground 在 Android 12+ 从后台启动前台服务时可能抛
     * ForegroundServiceStartNotAllowedException，此处捕获仅记录——常驻通知本次未能展示，
     * 不应让进程崩溃（设置页的开关仍是开启态，下次前台时由 [com.aicode.MainActivity] 恢复）。
     */
    private fun ensureForeground() {
        // 文案按真实原因组合：只开悬浮球时不再误写「保活已开启」；保活与球并存时用后缀同时表达。
        val ballSuffix = if (floatBallOn) getString(R.string.notification_float_ball_suffix) else ""
        val text = when {
            sessionCount > 0 && persistent ->
                getString(R.string.notification_keepalive_persistent_sessions, sessionCount) + ballSuffix
            sessionCount > 0 ->
                getString(R.string.notification_keepalive_running_sessions, sessionCount) + ballSuffix
            floatBallOn && !persistent -> getString(R.string.notification_float_ball_only)
            remoteConnected -> getString(R.string.notification_remote_active) + ballSuffix
            else -> getString(R.string.notification_keepalive_enabled) + ballSuffix
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        // L3：API 34+ 用 specialUse 前台类型（Manifest 同步声明），低版本不带类型。
        val fgsType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType) }
            .onSuccess { updateWifiLocks() }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /** 挂载悬浮球（幂等）：无权限时静默跳过（开关仍保留，下次前台恢复时重试）。 */
    private fun attachFloatBall() {
        if (overlay != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            FileLogger.w(TAG, "无悬浮窗权限，不显示悬浮球")
            return
        }
        ensureForeground()
        overlay = FloatBallOverlay(this, floatBallStateStore, themeSettings, sessionHub) {
            // 用户点「关闭悬浮球」：关开关、撤窗口，服务按其余职责决定去留
            removeOverlay()
            floatBallOn = false
            serviceScope.launch {
                runCatching { generalSettings.setFloatBallEnabled(false) }
                if (!persistent && sessionCount == 0 && !remoteConnected) stopSelf()
            }
        }
        // 赋值后再挂载（attach 幂等）：挂载失败时回调能拿到已赋值的 overlay 完成清理。
        overlay?.attach()
    }

    private fun removeOverlay() {
        overlay?.detach()
        overlay = null
    }

    /** L2 持锁条件：后台 AI 会话进行中或远程 SSH 已连接；常驻开关/悬浮球本身不持锁。 */
    private fun updateWifiLocks() {
        if (sessionCount > 0 || remoteConnected) acquireWifiLocks() else releaseWifiLocks()
    }

    /**
     * 获取 Wi-Fi 锁，尽量保持 Wi-Fi 不休眠。延后到首次进入前台时惰性创建：只有真正保活期间
     * 才需要，避免无谓占用。
     *
     * 只持 HIGH_PERF 一把：本服务的场景是息屏后仍要保持连接（后台 SSH/AI 会话），
     * 而 LOW_LATENCY 是面向前台亮屏交互的低延迟语义，息屏下不一定回退到同样的保活强度。
     * 同时持两把只会多占一份系统资源与耗电。
     */
    private fun acquireWifiLocks() {
        runCatching {
            if (!wifiLocksInitialized) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                @Suppress("DEPRECATION")
                wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiCode:Keepalive")
                wifiLocks.forEach { it.setReferenceCounted(false) }
                wifiLocksInitialized = true
            }
            wifiLocks.forEach { lock -> if (!lock.isHeld) lock.acquire() }
        }.onFailure { FileLogger.w(TAG, "acquireWifiLock failed", it) }
    }

    private fun releaseWifiLocks() {
        wifiLocks.forEach { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        const val ACTION_START_SESSION = "com.aicode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.aicode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aicode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aicode.action.DISABLE_PERSISTENT"
        const val ACTION_SYNC_FLOAT_BALL = "com.aicode.action.SYNC_FLOAT_BALL"
        private const val EXTRA_FLOAT_BALL_ENABLED = "float_ball_enabled"

        /** 同步悬浮球开关（幂等）：与保活共用本服务与同一条常驻通知。 */
        fun syncFloatBall(context: Context, enabled: Boolean) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_SYNC_FLOAT_BALL
                putExtra(EXTRA_FLOAT_BALL_ENABLED, enabled)
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "syncFloatBall startService failed", it) }
        }

        /** 开启常驻保活（幂等）。 */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure {
                    // 后台冷启动时系统拒绝 startService（Android 8+ 后台执行限制），属预期场景，
                    // 保活会在 App 回到前台或 WorkManager 兜底时恢复，不必刷全栈吓人日志。
                    if (it.message?.contains("app is in background") == true) {
                        FileLogger.i(TAG, "enablePersistent skipped: app in background, keepalive deferred")
                    } else {
                        FileLogger.e(TAG, "enablePersistent startService failed", it)
                    }
                }
        }

        /** 关闭常驻保活（幂等）。仅在确曾开启过时调用，避免为关闭而凭空拉起 Service。 */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }

        /** 登记一个后台会话（不改变用户常驻开关）。由调用方按状态迁移保证不重复计数。 */
        fun startSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "startSession startService failed", it) }
        }

        /** 结束一个后台会话。 */
        fun stopSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "stopSession startService failed", it) }
        }

        /** 判断保活服务当前是否在运行。WorkManager 兜底拉起前先探测，避免反复 startService。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = TerminalKeepaliveService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }
    }
}
