package com.aicode.feature.floatball

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 跨应用悬浮球服务：前台服务持有 WindowManager 悬浮窗（小球 + 迷你面板）。
 * 开关持久化，进程重建后 START_STICKY 恢复；用户从最近任务划掉 App 时随任务停止。
 */
@AndroidEntryPoint
class FloatBallService : Service() {

    @Inject
    lateinit var generalSettings: GeneralSettingsRepository

    @Inject
    lateinit var stateStore: FloatBallStateStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: FloatBallOverlay? = null

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "FloatBallService created")
        // START_STICKY 重建后从持久化开关恢复：开关关着就立即自停。
        serviceScope.launch {
            if (generalSettings.floatBallEnabledFlow.first()) {
                startOverlayIfPermitted()
            } else {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW) startOverlayIfPermitted()
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 划掉 App 即停止悬浮球（与保活服务一致的口径），下次打开 App 由启动逻辑恢复。
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOverlayIfPermitted() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // 无权限时直接停掉服务，避免成为常驻僵尸；授权后再次开开关即可正常启动。
            FileLogger.w(TAG, "无悬浮窗权限，不显示悬浮球")
            stopForegroundCompat()
            stopSelf()
            return
        }
        if (overlay == null) {
            ensureForeground()
            overlay = FloatBallOverlay(this, stateStore) {
                removeOverlay()
                stopSelf()
            }
        }
    }

    private fun removeOverlay() {
        overlay?.detach()
        overlay = null
        stopForegroundCompat()
    }

    private fun ensureForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.float_ball_notification_title))
            .setContentText(getString(R.string.float_ball_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "FloatBallService"
        private const val CHANNEL_ID = "float_ball"
        private const val NOTIFICATION_ID = 2301
        private const val ACTION_SHOW = "com.aicode.floatball.SHOW"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.float_ball_notification_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        /** 按开关启停：开关开着且已授权 → 启动；否则停止。 */
        fun sync(context: Context, enabled: Boolean) {
            ensureChannel(context)
            val intent = Intent(context, FloatBallService::class.java)
            if (enabled) {
                // 带 ACTION_SHOW：服务已存活（如授权后再次开开关）时也能重新挂载悬浮球。
                intent.action = ACTION_SHOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
