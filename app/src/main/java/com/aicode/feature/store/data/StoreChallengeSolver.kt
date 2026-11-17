package com.aicode.feature.store.data

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.aicode.core.util.ActivityTracker
import com.aicode.core.util.FileLogger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener

/**
 * Vercel 安全校验（Security Checkpoint）的 WebView 兜底通道。
 *
 * claudemarket.ai 等商店上游启用了 Vercel 人机校验：OkHttp 这类纯 HTTP 客户端只会拿到
 * `429 + text/html` 的挑战页，永远拿不到业务 JSON。挑战页本身由 JS 计算并刷新，只有真实
 * 浏览器内核能通过。
 *
 * 做法：把请求 URL 交给一个临时挂到当前窗口的 1dp 透明 WebView 加载；挑战通过后页面会
 * 自动刷新成接口 JSON，此时读出 `document.body.innerText` 直接当作响应体返回。WebView
 * 与 OkHttp 通过 [WebViewCookieJar] 共享 Cookie，后续请求通常就无需再次挑战。
 *
 * 只用于商店只读目录请求；失败（无 Activity、超时、非 JSON）一律返回 null，由调用方保留
 * 原有错误提示，不改变其它网络链路行为。
 */
@Singleton
class StoreChallengeSolver @Inject constructor() {

    private companion object {
        const val TAG = "StoreChallengeSolver"

        /** 挑战 + 自动刷新的总时限；Vercel 一般数秒内完成。 */
        const val TIMEOUT_MS = 25_000L

        /** 轮询页面正文的间隔。 */
        const val POLL_INTERVAL_MS = 700L

        /** 不含 "wv" 标记的常规移动 Chrome UA：WebView 默认 UA 更容易被当成机器人。 */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    /**
     * 用 WebView 加载 [url] 并返回挑战通过后的正文；[isJsonBody] 判断正文是否为业务 JSON。
     * 必须在主线程调用链上（内部会切到主线程）。
     */
    suspend fun fetchJsonViaWebView(url: String): String? = withContext(Dispatchers.Main) {
        val activity = ActivityTracker.current ?: run {
            FileLogger.w(TAG, "无前台 Activity，无法执行 WebView 安全校验")
            return@withContext null
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@withContext null

        val webView = WebView(activity)
        val container = FrameLayout(activity).apply {
            alpha = 0f
            addView(webView, ViewGroup.LayoutParams(1, 1))
        }
        root.addView(container, ViewGroup.LayoutParams(1, 1))

        try {
            suspendCancellableCoroutine { cont ->
                val main = Handler(Looper.getMainLooper())
                val finished = AtomicBoolean(false)

                fun finish(body: String?) {
                    if (!finished.compareAndSet(false, true)) return
                    main.removeCallbacksAndMessages(null)
                    if (cont.isActive) cont.resume(body)
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = BROWSER_UA
                }
                webView.setBackgroundColor(Color.TRANSPARENT)

                val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS

                fun probe() {
                    if (finished.get()) return
                    if (SystemClock.elapsedRealtime() > deadline) {
                        FileLogger.w(TAG, "WebView 安全校验超时: $url")
                        finish(null)
                        return
                    }
                    runCatching {
                        webView.evaluateJavascript(
                            "(function(){return document.body?document.body.innerText:''})()"
                        ) { raw ->
                            val body = decodeJsString(raw)
                            if (looksLikeJson(body)) {
                                FileLogger.i(TAG, "WebView 已通过安全校验: $url")
                                finish(body)
                            } else {
                                main.postDelayed({ probe() }, POLL_INTERVAL_MS)
                            }
                        }
                    }.onFailure {
                        FileLogger.w(TAG, "WebView 读取页面失败", it)
                        finish(null)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        probe()
                    }
                }

                cont.invokeOnCancellation { finish(null) }
                webView.loadUrl(url)
                main.postDelayed({ probe() }, POLL_INTERVAL_MS)
            }
        } finally {
            runCatching {
                (container.parent as? ViewGroup)?.removeView(container)
                container.removeAllViews()
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    /** `evaluateJavascript` 返回的是 JSON 编码的字符串字面量，解码成实际正文。 */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String ?: "" }.getOrDefault("")
    }

    /** 只认对象/数组形态的业务 JSON，排除挑战页的 HTML 文案。 */
    private fun looksLikeJson(body: String): Boolean {
        val text = body.trim()
        if (text.length < 2) return false
        val first = text.first()
        return (first == '{' || first == '[') && text.last() in charArrayOf('}', ']')
    }
}
