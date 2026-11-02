package com.aicode.feature.vnc.presentation

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
    onKeyboardRequest: () -> Unit = {},
    onPageReady: (VncInputChannel) -> Unit = {},
    mouseButton: Int = 0,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val holder = remember(appContext) {
        VncWebViewHolder(appContext, onConnectedChange, onKeyboardRequest)
    }
    // 页面就绪后把输入通道交给外层回填；这里不主动调用，避免在组合期发 evaluateJavascript。
    LaunchedEffect(Unit) {
        onPageReady(VncInputChannel(holder))
    }
    // 鼠标按键模式变化同步到页面（标题栏按钮切换后触点按下按新按键上报）。
    LaunchedEffect(mouseButton) {
        if (mouseButton != 0) holder.setMouseButton(mouseButton)
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

private const val PANEL_TAG = "VncWebViewPanel"
private const val VNC_PAGE_URL = "https://appassets.androidplatform.net/assets/vnc/index.html"

/**
 * 页面就绪后交给外层的输入通道：字符、单个 keysym、鼠标按键模式都从这里走。
 * 外层（VncOverlayWindow）用它回填 [VncOverlayViewModel] 的回调。
 */
internal class VncInputChannel(private val holder: VncWebViewHolder) {
    fun sendKeys(text: String) = holder.sendKeys(text)
    fun sendKeysym(code: Int, down: Boolean) = holder.sendKeysym(code, down)
    fun setMouseButton(button: Int) = holder.setMouseButton(button)
}

internal class VncWebViewHolder(
    context: Context,
    onConnectedChange: (Boolean) -> Unit,
    onKeyboardRequest: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private val jsBridge = VncJsBridge(context, mainHandler, onConnectedChange, onKeyboardRequest)
    val webView: WebView = buildWebView(context)

    /** 把字符发给页面，由页面转成按键事件发往远端。 */
    fun sendKeys(text: String) {
        if (destroyed) return
        val escaped = org.json.JSONObject.quote(text)
        webView.evaluateJavascript("window.vncSendText && window.vncSendText($escaped)", null)
    }

    /** 发送单个 X keysym 的按下/抬起（删除、回车、方向键、修饰键、Ctrl 组合等特殊键）。 */
    fun sendKeysym(code: Int, down: Boolean) {
        if (destroyed) return
        webView.evaluateJavascript("window.vncSendKeysym && window.vncSendKeysym($code,$down)", null)
    }

    /** 切换鼠标按键模式（0=左键 2=右键），触点按下时按此按键上报。 */
    fun setMouseButton(button: Int) {
        if (destroyed) return
        webView.evaluateJavascript("window.vncSetMouseButton && window.vncSetMouseButton($button)", null)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
            // 触摸模式下自行可获焦：页面里那个用于唤起输入法的隐藏 textarea 需要 WebView 先有焦点。
            isFocusable = true
            isFocusableInTouchMode = true
            jsBridge.webView = this
            // 按下时先把焦点抢到 WebView，否则页面里的隐藏输入框拿不到真焦点（软键盘不弹）。
            setOnTouchListener { v, _ ->
                if (!v.hasFocus()) v.requestFocusFromTouch()
                false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    FileLogger.i(PANEL_TAG, "console[${msg.messageLevel()}]: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    // 非本 App 资产一律拒绝：返回空响应而不是 null（null 会走网络加载）。
                    if (url.host != ASSET_HOST) {
                        FileLogger.w(PANEL_TAG, "拦截非资产请求: $url")
                        return blockedResponse()
                    }
                    val resp = assetLoader.shouldInterceptRequest(url)
                    if (resp == null) FileLogger.w(PANEL_TAG, "资产未命中: $url")
                    return resp
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 禁止页面导航到任何其它地址（包括被注入内容试图跳转）。
                    return request.url.host != ASSET_HOST
                }

                override fun onPageFinished(view: WebView, url: String) {
                    FileLogger.i(PANEL_TAG, "页面加载完成: $url")
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    FileLogger.e(PANEL_TAG, "资源加载失败: ${request.url} code=${error.errorCode} ${error.description}")
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    FileLogger.w(PANEL_TAG, "HTTP 错误: ${request.url} status=${errorResponse.statusCode}")
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
    private val onKeyboardRequest: () -> Unit = {},
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

    /** 由持有方在 WebView 创建后回填；输入法相关回调需要它。 */
    @Volatile
    var webView: WebView? = null

    /** 页面点画面并聚焦隐藏输入框后回调：Android 侧必须主动取焦并请求输入法，否则 WebView 不弹软键盘。 */
    @JavascriptInterface
    fun onVncWantKeyboard() {
        FileLogger.i(TAG, "页面请求输入法")
        // 改由原生输入框承接：WebView 内部隐藏 input 拿不到真正的输入连接
        // （showSoftInput 返回 true 但键盘不弹），所以交给 Compose 侧的真实输入框。
        mainHandler.post { onKeyboardRequest() }
    }

    @JavascriptInterface
    fun onVncDiag(msg: String) {
        FileLogger.i(TAG, "画面自检: $msg")
    }

    @JavascriptInterface
    fun onVncStatus(status: String) {
        FileLogger.i(TAG, "页面连接状态: $status")
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
