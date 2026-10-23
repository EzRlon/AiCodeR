package com.aicode.feature.schedule.domain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定时任务完成通知：渠道复用 `agent_complete`，点击进 App。
 *
 * AI 型任务的成败都要报（用户要看执行摘要与「为什么没跑成」）；脚本型任务只在失败时报，
 * 成功静默——每天跑成功一次的脚本不该天天推一条通知。
 */
@Singleton
class ScheduledTaskNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** @param success false 时文案为「%1$s 失败」。 */
    fun notifyCompletion(task: ScheduledTask, success: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = context.getString(
            if (success) R.string.schedule_notify_done else R.string.schedule_notify_failed,
            task.name
        )
        val notification = NotificationCompat.Builder(context, "agent_complete")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.schedule_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID_BASE + task.id.hashCode(), notification) }
            .onFailure { FileLogger.w(TAG, "发送任务完成通知失败", it) }
    }

    private companion object {
        const val TAG = "ScheduledTaskNotifier"
        const val NOTIFICATION_ID_BASE = 3000
    }
}
