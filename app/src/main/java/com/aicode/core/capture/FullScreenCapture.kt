package com.aicode.core.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.aicode.core.util.FileLogger

/**
 * 整屏一帧捕获（MediaProjection）：供「标注中心」定格画面使用。
 *
 * 与窗口级截图不同，它能拍到 Dialog/Popup 等独立窗口层的内容——用户先点开弹窗再标注，
 * 定格图里就真的包含弹窗。仅在需要时抓一帧即释放虚拟显示器与投影，不做常驻录屏。
 */
object FullScreenCapture {

    private const val TAG = "FullScreenCapture"

    /** 构造屏幕捕获授权 Intent（配合 Activity 的 startActivityForResult 使用）。 */
    fun createPermissionIntent(context: Context): Intent? =
        runCatching {
            context.getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()
        }.onFailure {
            FileLogger.w(TAG, "构造屏幕捕获授权 Intent 失败", it)
        }.getOrNull()

    /**
     * 抓取一帧全屏（含所有窗口层），结果回调到主线程；失败回调 null。
     * [resultCode]/[data] 来自 [createPermissionIntent] 的授权结果。
     */
    fun captureOnce(context: Context, resultCode: Int, data: Intent, onResult: (Bitmap?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val thread = HandlerThread("aicode-fullcap").apply { start() }
        val handler = Handler(thread.looper)
        handler.post {
            val result = runCatching { captureBlocking(context, resultCode, data, handler) }
                .onFailure { FileLogger.w(TAG, "全屏捕获失败", it) }
                .getOrNull()
            thread.quitSafely()
            main.post { onResult(result) }
        }
    }

    private fun captureBlocking(context: Context, resultCode: Int, data: Intent, handler: Handler): Bitmap? {
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return null
        val projection = manager.getMediaProjection(resultCode, data) ?: return null
        try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            try {
                val display = projection.createVirtualDisplay(
                    "aicode-annotate-capture",
                    width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )
                try {
                    val deadline = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < deadline) {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            try {
                                return imageToBitmap(image, width, height)
                            } finally {
                                image.close()
                            }
                        }
                        Thread.sleep(40)
                    }
                    FileLogger.w(TAG, "全屏捕获超时：5s 内未取到帧")
                    return null
                } finally {
                    display?.release()
                }
            } finally {
                reader.close()
            }
        } finally {
            runCatching { projection.stop() }
        }
    }

    /** ImageReader 的 RGBA 帧转 Bitmap：行步长可能大于内容宽度，需要按行距裁剪。 */
    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }
}
