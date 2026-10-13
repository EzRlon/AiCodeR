package com.aicode.feature.note.presentation.component

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.feature.note.presentation.NoteEditorViewModel

/**
 * 笔记（WYSIWYG）编辑器：用 WebView 承载离线打包的 Vditor，把 Markdown 当富文本就地编辑。
 *
 * 编辑内容经 [NoteJsBridge] 回传原生侧防抖写盘；[onContentChanged] 用于主界面标记「已改动」，
 * [onSaved] 在写盘成功后回调。资源（Vditor 本体、笔记内相对图片）由 [NoteEditorViewModel.assetLoader] 提供，不出网。
 */
@Composable
internal fun NoteEditorWebView(
    text: String,
    path: String,
    onContentChanged: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NoteEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) NOTE_BG_DARK else NOTE_BG_LIGHT

    val changedState = rememberUpdatedState(onContentChanged)
    val savedState = rememberUpdatedState(onSaved)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val webView = remember(path) {
        val themedContext = ContextThemeWrapper(context, noteThemeRes(dark))
        WebView(themedContext).apply {
            settings.javaScriptEnabled = true
            // 页面只经 assetLoader 取资源，不暴露本地文件系统
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setBackgroundColor(bgColor)
            addJavascriptInterface(
                NoteJsBridge { markdown ->
                    // JS 桥回调在非主线程，切回主线程再驱动 Compose 状态与防抖保存
                    mainHandler.post {
                        changedState.value.invoke()
                        viewModel.scheduleSave(path, markdown) { savedState.value.invoke() }
                    }
                },
                BRIDGE_NAME
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = viewModel.assetLoader.shouldInterceptRequest(request.url)
            }
        }
    }

    // WebView 的 isLightTheme 由上下文主题决定，深色切换时同步更新
    LaunchedEffect(dark) {
        (webView.context as? ContextThemeWrapper)?.setTheme(noteThemeRes(dark))
        webView.setBackgroundColor(bgColor)
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(text, path, dark) {
        val html = viewModel.buildHtml(text, dark)
        webView.loadDataWithBaseURL(viewModel.baseUrl(path), html, "text/html", "utf-8", null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** Vditor 与原生侧的桥：JS 调 `AiCodeNote.onChange(md)` 回传 Markdown。 */
private class NoteJsBridge(private val onChanged: (String) -> Unit) {
    @JavascriptInterface
    fun onChange(markdown: String) {
        onChanged(markdown)
    }

    @JavascriptInterface
    fun onReady() = Unit
}

private fun noteThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

private const val BRIDGE_NAME = "AiCodeNote"
private const val NOTE_BG_DARK = 0xFF1A1A1A.toInt()
private const val NOTE_BG_LIGHT = 0xFFFFFFFF.toInt()
