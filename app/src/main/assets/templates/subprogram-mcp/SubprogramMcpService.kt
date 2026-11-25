package com.aicoder.app.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** 前台服务承载 MCP server（规避后台冻结；通知常驻说明「子程序调试通道」）。 */
class SubprogramMcpService : Service() {

    private var server: SubprogramMcpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (port <= 0 || token.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        AppContextHolder.init(applicationContext)
        // 兕底：宿主没建 Application 子类时，服务拉起也把崩溃上报装上（幂等）
        SubprogramCrashReporter.install(applicationContext)
        runCatching {
            server = SubprogramMcpServer(port, token, SubprogramMcpServer.Info(
                packageName = packageName,
                versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
                displayName = applicationInfo.loadLabel(packageManager).toString()
            )).also { it.start(NanoHTTPD_TIMEOUT, true) }
        }.onFailure { stopSelf() }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "子程序调试通道", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("子程序调试通道运行中")
            .setContentText("母程序可通过 MCP 本地通道调试本应用")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "subprogram_mcp"
        private const val NOTIFICATION_ID = 0x5A10
        private const val EXTRA_PORT = "port"
        private const val EXTRA_TOKEN = "token"
        private const val NanoHTTPD_TIMEOUT = 5000

        fun start(context: Context, port: Int, token: String) {
            val intent = Intent(context, SubprogramMcpService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TOKEN, token)
            context.startForegroundService(intent)
        }
    }
}
