package com.aicode.feature.settings.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 子程序崩溃主动上报的母程序接收端（L2 契约）。
 *
 * 清单上 android:permission 要求发送方持有 signature 级
 * com.aicoder.permission.SUBPROGRAM_DIAGNOSTIC——只有同签名子程序能到达这里，
 * 第三方 App 伪造崩溃广播会被系统直接拒绝。
 */
class SubprogramCrashReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SubprogramCrashContract.ACTION) return
        val pkg = intent.getStringExtra(SubprogramCrashContract.EXTRA_PACKAGE).orEmpty()
        if (pkg.isBlank()) return
        SubprogramCrashStore.record(
            context = context,
            packageName = pkg,
            crashType = intent.getStringExtra(SubprogramCrashContract.EXTRA_TYPE).orEmpty(),
            crashMessage = intent.getStringExtra(SubprogramCrashContract.EXTRA_MESSAGE).orEmpty(),
            atMs = intent.getLongExtra(SubprogramCrashContract.EXTRA_TIME, System.currentTimeMillis()),
        )
    }
}

/** 母程序/模板两侧共用的广播契约常量（模板内持同名副本，见 subprogram-mcp/SubprogramCrashReporter.kt）。 */
object SubprogramCrashContract {
    const val ACTION = "com.aicoder.action.SUBPROGRAM_CRASH"
    const val EXTRA_PACKAGE = "packageName"
    const val EXTRA_TIME = "crashTime"
    const val EXTRA_TYPE = "crashType"
    const val EXTRA_MESSAGE = "crashMessage"
    const val EXTRA_STACK = "stackTrace"
}
