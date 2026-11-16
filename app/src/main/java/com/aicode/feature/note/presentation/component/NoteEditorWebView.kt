package com.aicode.feature.note.presentation.component

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.note.presentation.NoteEditorViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Code
import compose.icons.feathericons.Link2
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.RotateCw
import compose.icons.feathericons.Terminal

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
    /**
     * 每次编辑回调（所属文件路径 + 最新 Markdown）：随记浮窗要在「润色/发送」时拿到
     * 未被保存防抖拖住的内容，并用 path 丢弃切换笔记后旧 WebView 的迟到回调。
     */
    onMarkdownChanged: (String, String) -> Unit = { _, _ -> },
    /**
     * 重载页面时取哪份文本。默认用 [text]；随记浮窗传「内存最新内容」，
     * 避免深浅色切换触发整页重载时用滞后的种子覆盖刚输入的内容。
     */
    latestTextProvider: () -> String = { text },
    viewModel: NoteEditorViewModel = hiltViewModel(),
    /**
     * 浮窗模式：隐藏 Vditor 自带工具栏（窄窗口会折行且图标易缺失），
     * 改用原生带文字的「待办 / 列表 / 加粗 / 斜体 / 撤销 / 重做」格式条。
     */
    compactToolbar: Boolean = false,
    /** 空文档时的提示文案；只在 [compactToolbar] 场景传入。 */
    placeholder: String = "",
    /** 防抖写盘失败回调（只在浮窗里用于显示「保存失败」）。 */
    onSaveFailed: () -> Unit = {},
    /**
     * 带路径的写盘结果：浮窗切到别的笔记后，迟到的 A 笔记保存结果不能改 B 的标题栏状态，
     * 由调用方按 path 比对当前笔记。
     */
    onSaveResult: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) NOTE_BG_DARK else NOTE_BG_LIGHT

    val changedState = rememberUpdatedState(onContentChanged)
    val savedState = rememberUpdatedState(onSaved)
    val failedState = rememberUpdatedState(onSaveFailed)
    val saveResultState = rememberUpdatedState(onSaveResult)
    val markdownState = rememberUpdatedState(onMarkdownChanged)
    val latestTextState = rememberUpdatedState(latestTextProvider)
    val placeholderState = rememberUpdatedState(placeholder)
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
                        markdownState.value.invoke(path, markdown)
                        viewModel.scheduleSave(
                            path = path,
                            markdown = markdown,
                            onSaved = {
                                savedState.value.invoke()
                                saveResultState.value.invoke(path, true)
                            },
                            onFailed = {
                                failedState.value.invoke()
                                saveResultState.value.invoke(path, false)
                            }
                        )
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

    LaunchedEffect(text, path, dark, compactToolbar, placeholder) {
        val html = viewModel.buildHtml(
            text = latestTextState.value(),
            dark = dark,
            compact = compactToolbar,
            placeholder = placeholderState.value
        )
        webView.loadDataWithBaseURL(viewModel.baseUrl(path), html, "text/html", "utf-8", null)
    }

    if (compactToolbar) {
        Column(modifier = modifier) {
            NoteFormatBar { action ->
                // 点原生按钮可能让 WebView 失焦；先要回焦点，再由页面把光标放回编辑器，
                // 避免格式操作后键盘收起、光标消失。
                webView.requestFocus()
                webView.evaluateJavascript("window.__noteFormat('$action')", null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Box(modifier = Modifier.weight(1f)) {
                // factory 只在组合槽首次创建时执行一次：换笔记时 webView 已按 path 重建，
                // 若不加 key 会继续挂着旧的（已 destroy）实例，表现为切换后白屏/不可编辑。
                key(path) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    } else {
        key(path) {
            AndroidView(factory = { webView }, modifier = modifier)
        }
    }
}

/**
 * 浮窗原生格式条。
 *
 * 每个动作都带文字标签，避免 Vditor 纯图标工具栏在窄浮窗里「看不懂」；只放不需要
 * 二级弹层的常用操作，表格 / 分割线这类容易误插的项不进入浮窗。
 */
@Composable
private fun NoteFormatBar(onAction: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NOTE_FORMAT_ACTIONS.forEach { action ->
            FormatBarItem(action = action, onClick = { onAction(action.id) })
        }
    }
}

@Composable
private fun FormatBarItem(action: NoteFormatAction, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(action.label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private data class NoteFormatAction(
    val id: String,
    val icon: ImageVector,
    @StringRes val label: Int
)

private val NOTE_FORMAT_ACTIONS = listOf(
    NoteFormatAction("check", FeatherIcons.CheckSquare, R.string.notebook_format_check),
    NoteFormatAction("codeblock", FeatherIcons.Code, R.string.notebook_format_code_block),
    NoteFormatAction("inlinecode", FeatherIcons.Terminal, R.string.notebook_format_inline_code),
    NoteFormatAction("link", FeatherIcons.Link2, R.string.notebook_format_link),
    NoteFormatAction("undo", FeatherIcons.RotateCcw, R.string.notebook_format_undo),
    NoteFormatAction("redo", FeatherIcons.RotateCw, R.string.notebook_format_redo)
)

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
