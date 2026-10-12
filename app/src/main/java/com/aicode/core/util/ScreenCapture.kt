package com.aicode.core.util

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas

/** 界面截屏：把 Activity 当前窗口内容绘制到软件位图，不需要任何系统权限。 */
object ScreenCapture {

    /** 截取 Activity 当前窗口（含状态栏区域的容器视图）。失败（视图未布局等）返回 null。 */
    fun captureActivity(activity: Activity): Bitmap? = runCatching {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap
    }.onFailure {
        FileLogger.w("ScreenCapture", "窗口截屏失败", it)
    }.getOrNull()

    /** 位图编码为 PNG 字节。 */
    fun toPngBytes(bitmap: Bitmap): ByteArray? = runCatching {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }.getOrNull()
}
