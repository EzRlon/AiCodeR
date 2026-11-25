package com.aicoder.app.mcp

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * 子程序崩溃主动上报（L2 契约）：包装默认 UncaughtExceptionHandler，
 * 进程死亡前向母程序（同签名）发一条显式广播，并落一份本地 last-crash.json 供排查。
 *
 * 链式调用原 handler——不吞异常、不改变原有崩溃退出行为。
 * 安装点：宿主 Application.onCreate（覆盖启动期崩溃）与 SubprogramMcpService.onStartCommand
 * （兜底：宿主没建 Application 子类时，调试通道拉起即生效）。幂等，可重复调用。
 *
 * 宿主清单需声明（同签名自动授予，第三方 App 持有不了）：
 *   <uses-permission android:name="com.aicoder.permission.SUBPROGRAM_DIAGNOSTIC" />
 */
object SubprogramCrashReporter {

    const val MOTHER_PACKAGE = "com.aicoder"
    const val MOTHER_RECEIVER = "com.aicoder.feature.settings.data.SubprogramCrashReceiver"
    const val ACTION = "com.aicoder.action.SUBPROGRAM_CRASH"
    const val MOTHER_DIAGNOSTIC_PERMISSION = "com.aicoder.permission.SUBPROGRAM_DIAGNOSTIC"

    private const val EXTRA_PACKAGE = "packageName"
    private const val EXTRA_TIME = "crashTime"
    private const val EXTRA_TYPE = "crashType"
    private const val EXTRA_MESSAGE = "crashMessage"
    private const val EXTRA_STACK = "stackTrace"
    private const val MAX_STACK = 4000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { report(appContext, thread, throwable) }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    private fun report(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val type = throwable.javaClass.name
        val message = throwable.message.orEmpty().take(500)
        val stack = throwable.stackTraceToString().take(MAX_STACK)

        runCatching {
            File(context.filesDir, "last-crash.json").writeText(
                JSONObject()
                    .put("time", now)
                    .put("thread", thread.name)
                    .put("type", type)
                    .put("message", message)
                    .put("stack", stack)
                    .toString()
            )
        }

        runCatching {
            context.sendBroadcast(
                Intent(ACTION)
                    .setClassName(MOTHER_PACKAGE, MOTHER_RECEIVER)
                    .putExtra(EXTRA_PACKAGE, context.packageName)
                    .putExtra(EXTRA_TIME, now)
                    .putExtra(EXTRA_TYPE, type)
                    .putExtra(EXTRA_MESSAGE, message)
                    .putExtra(EXTRA_STACK, stack)
            )
        }
    }
}
