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
private const val VNC_PAGE_URL = "https://appassets.androidplatform.net/assets/vnc/index.html"

private class VncWebViewHolder(
    context: Context,
    onConnectedChange: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    val webView: WebView = buildWebView(context, onConnectedChange)

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context, onConnectedChange: (Boolean) -> Unit): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(VncJsBridge(context, mainHandler, onConnectedChange), BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
            loadUrl(VNC_PAGE_URL)
        }
    }

    fun tryConnect() {
        mainHandler.post {
            if (!destroyed) webView.evaluateJavascript("window.vncTryConnect && vncTryConnect()", null)
        }
    }

    fun pasteFromAndroid() {
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

    @JavascriptInterface
    fun onVncStatus(status: String) {
        val connected = status == "connected"
        mainHandler.post { onConnectedChange(connected) }
    }

    @JavascriptInterface
    fun getAndroidClipboard(): String = try {
        clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    } catch (t: Throwable) {
        // 部分 ROM 在后台受限时读剪贴板会抛 SecurityException，属系统边界，降级为空串。
        FileLogger.e(TAG, "读取剪贴板失败", t)
        ""
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
