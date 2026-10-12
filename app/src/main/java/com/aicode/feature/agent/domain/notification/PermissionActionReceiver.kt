package com.aicode.feature.agent.domain.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 通知栏授权按钮的落点：批准（本次放行）/ 拒绝，动作透传给 [ToolPermissionManager.resolve]。
 *
 * 显式广播（Intent 指定组件）不受隐式广播限制；manifest 静态注册（exported=false），
 * 进程不在时系统会拉起本 App 进程执行 onReceive（Hilt 通过 [PermissionReceiverEntryPoint] 取依赖）。
 * 进程被杀后挂起请求已丢失，resolve 静默忽略——用户打开 App 重新处理即可。
 */
class PermissionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val manager = EntryPointAccessors
            .fromApplication(context.applicationContext, PermissionReceiverEntryPoint::class.java)
            .toolPermissionManager()
        val resolved = when (intent.action) {
            ACTION_APPROVE -> manager.resolve(requestId, PermissionChoice.ONCE)
            ACTION_REJECT -> manager.resolve(requestId, PermissionChoice.REJECT)
            else -> return
        }
        FileLogger.i(TAG, "通知栏授权动作: action=${intent.action} request=$requestId resolved=$resolved")
        // 只在本次真正命中挂起请求时撤下通知：避免旧通知点击（resolve 未命中）误撤新请求的同 ID 通知
        if (resolved) {
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        }
    }

    companion object {
        private const val TAG = "PermissionActionReceiver"
        const val ACTION_APPROVE = "com.aicode.agent.permission.APPROVE"
        const val ACTION_REJECT = "com.aicode.agent.permission.REJECT"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val NOTIFICATION_ID = 102
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PermissionReceiverEntryPoint {
    fun toolPermissionManager(): ToolPermissionManager
}
