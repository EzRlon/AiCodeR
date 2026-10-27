package com.aicode.feature.vnc.presentation

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.aicode.core.util.FileLogger
import com.aicode.feature.vnc.domain.VncServiceState
import java.io.ByteArrayInputStream

/**
 * 小窗内的 VNC 画面：WebView 加载 assets/vnc（noVNC），经 WebViewAssetLoader 的
 * https 虚拟域以支持 ES module，再连本机回环 ws://127.0.0.1:6080（容器内 websockify）。
 * https 页面连明文 ws 属 mixed content，需 MIXED_CONTENT_ALWAYS_ALLOW（目标仅 127.0.0.1）。
 * 页面与 App 之间用 VncBridge 同步连接状态与剪贴板。
 */
@Composable
internal fun VncWebViewPanel(
    serviceState: VncServiceState,
    pasteRequests: Int,
    reconnectRequests: Int,
    onConnectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val holder = remember(appContext) {
        VncWebViewHolder(appContext, onConnectedChange)
    }
    LaunchedEffect(serviceState) {
        if (serviceState is VncServiceState.Ready) holder.tryConnect()
    }
    LaunchedEffect(pasteRequests) {
        if (pasteRequests > 0) holder.pasteFromAndroid()
    }
    LaunchedEffect(reconnectRequests) {
        if (reconnectRequests > 0) holder.tryConnect()
    }
    AndroidView(
        factory = { holder.webView },
        modifier = modifier,
        onRelease = { holder.destroy() },
    )
}

private const val BRIDGE_NAME = "VncBridge"

/** 资产虚拟域：只有这个 host 的请求放行，其余一律阻断。 */
private const val ASSET_HOST = "appassets.androidplatform.net"

private const val VNC_PAGE_URL = "https://appassets.androidplatform.net/assets/vnc/index.html"

private class VncWebViewHolder(
    context: Context,
    onConnectedChange: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private val jsBridge = VncJsBridge(context, mainHandler, onConnectedChange)
    val webView: WebView = buildWebView(context)

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 保留 ALWAYS_ALLOW：https 虚拟域页面连 ws://127.0.0.1:6080 属 mixed content，
            // 降为 COMPATIBILITY_MODE 会直接断掉 VNC 连接（不是配置偏好问题）。
            // 改由下方「只允许加载自身资产」封住滥用面：页面永远只能是我们自己的 vnc 资产，
            // 外部内容既加载不到、也导航不过去，mixed content 策略因此无外部子资源可利用。
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(jsBridge, BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    // 非本 App 资产一律拒绝：返回空响应而不是 null（null 会走网络加载）。
                    if (url.host != ASSET_HOST) return blockedResponse()
                    return assetLoader.shouldInterceptRequest(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 禁止页面导航到任何其它地址（包括被注入内容试图跳转）。
                    return request.url.host != ASSET_HOST
                }
            }
            loadUrl(VNC_PAGE_URL)
        }
    }

    /** 拒绝外部资源时返回的空响应（空 body、无类型）。 */
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun tryConnect() {
        mainHandler.post {
            if (!destroyed) webView.evaluateJavascript("window.vncTryConnect && vncTryConnect()", null)
        }
    }

    fun pasteFromAndroid() {
        // 先放行一次剪贴板读取，再让页面发起粘贴（顺序不能反）。
        jsBridge.armClipboardReadOnce()
        mainHandler.post {
            if (!destroyed) webView.evaluateJavascript("window.vncPasteFromAndroid && vncPasteFromAndroid()", null)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        webView.loadUrl("about:blank")
        webView.stopLoading()
        webView.destroy()
    }
}

/** 页面 ↔ App 的桥：连接状态回传 + 剪贴板互通。 */
private class VncJsBridge(
    context: Context,
    private val mainHandler: Handler,
    private val onConnectedChange: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val clipboard =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * 读剪贴板的一次性放行令牌：只由用户点「粘贴」的 App 侧入口置位，
     * 页面脚本自行调 getAndroidClipboard 拿不到值。防止被注入/被劫持的页面静默读取用户复制的密码。
     */
    @Volatile
    private var clipboardReadArmed = false

    /** 用户显式要求「从 Android 粘贴到 VNC」时调用：只放行紧接着的一次读取。 */
    fun armClipboardReadOnce() {
        clipboardReadArmed = true
    }

    @JavascriptInterface
    fun onVncStatus(status: String) {
        val connected = status == "connected"
        mainHandler.post { onConnectedChange(connected) }
    }

    @JavascriptInterface
    fun getAndroidClipboard(): String {
        if (!clipboardReadArmed) {
            FileLogger.w(TAG, "页面未获授权读取剪贴板，已拒绝")
            return ""
        }
        clipboardReadArmed = false
        return try {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        } catch (t: Throwable) {
            // 部分 ROM 在后台受限时读剪贴板会抛 SecurityException，属系统边界，降级为空串。
            FileLogger.e(TAG, "读取剪贴板失败", t)
            ""
        }
    }

    @JavascriptInterface
    fun pushToAndroid(text: String) {
        mainHandler.post {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("vnc", text))
            } catch (t: Throwable) {
                FileLogger.e(TAG, "写入剪贴板失败", t)
            }
        }
    }

    companion object {
        private const val TAG = "VncBridge"
    }
}
