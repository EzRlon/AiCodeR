package com.aicode.feature.agent.domain.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权请求的通知栏通道：用户离开 App 时 AI 发起工具授权，从系统通知栏直接批准/拒绝，
 * 不必切回 App。通知由 [PermissionActionReceiver] 承接按钮动作，最终落到 [ToolPermissionManager.resolve]。
 *
 * 语义约定：
 * - 仅当请求产生时 App 不在前台才发通知（前台场景 App 内弹窗已经可见，避免双份打扰）；
 * - 请求被解决（App 内弹窗、通知按钮、会话停止）后统一撤下通知；
 * - 进程被杀后挂起请求随之丢失，遗留通知的按钮点击无效（resolve 静默忽略）且通知被撤下，
 *   用户回 App 重新发起即可——不做更重的持久化恢复。
 */
@Singleton
class PermissionNotificationCenter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val toolPermissionManager: ToolPermissionManager
) {
    private companion object {
        const val TAG = "PermissionNotificationCenter"
        const val CHANNEL_ID = "agent_permission"
        const val NOTIFICATION_ID = 102
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            toolPermissionManager.pendingRequest.collect { request ->
                if (request == null) {
                    cancelNotification()
                    return@collect
                }
                val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                if (!appInForeground) {
                    notifyRequest(request)
                }
            }
        }
        // 前台产生、用户随后离开 App 的待授权请求：到后台时补发一条通知，保持「离开 App 可直接批准」
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                toolPermissionManager.pendingRequest.value?.let { notifyRequest(it) }
            }
        })
        FileLogger.i(TAG, "工具授权通知通道已启动")
    }

    private fun notifyRequest(request: PendingToolPermission) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            FileLogger.w(TAG, "通知权限不可用，跳过授权通知: ${request.toolName}")
            return
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val approveIntent = actionIntent(request.id, PermissionActionReceiver.ACTION_APPROVE)
        val rejectIntent = actionIntent(request.id, PermissionActionReceiver.ACTION_REJECT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.permission_notification_title))
            .setContentText(request.title)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (request.summary.isBlank()) request.title else "${request.title}\n${request.summary}"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.permission_notification_reject), rejectIntent)
            .addAction(0, context.getString(R.string.permission_notification_approve), approveIntent)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { FileLogger.w(TAG, "发送授权通知失败", it) }
    }

    private fun actionIntent(requestId: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            Intent(context, PermissionActionReceiver::class.java).apply {
                this.action = action
                putExtra(PermissionActionReceiver.EXTRA_REQUEST_ID, requestId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun cancelNotification() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
