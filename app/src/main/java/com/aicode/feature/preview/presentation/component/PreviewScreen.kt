package com.aicode.feature.preview.presentation.component

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.appTextFieldColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.X

/**
 * Web 预览：把容器里跑的 HTTP 服务（dev server、静态站点等）直接在内嵌 WebView 里打开。
 * 手机与容器共享网络命名空间（与 VNC 同一机制），页面直接连 `http://127.0.0.1:端口`，
 * 前端热更新（HMR）由页面自身完成，无需 App 干预。
 */
@Composable
internal fun PreviewScreen(
    embedded: Boolean,
    onNavigateBack: () -> Unit
) {
    var portInput by rememberSaveable { mutableStateOf(COMMON_PORTS.first().toString()) }
    var activePort by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 深色适配：WebView 底色与原生控件主题跟随 App 主题（网页自身配色不干预）。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = MaterialTheme.colorScheme.background
    val bgArgb = bgColor.toArgb()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .imePadding()
    ) {
        // ── 顶栏：地址输入 + 端口快选 + 刷新/关闭 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!embedded) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            FeatherIcons.X,
                            contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                AppTextField(
                    value = portInput,
                    onValueChange = { input -> portInput = input.filter { it.isDigit() }.take(5) },
                    placeholder = stringResource(R.string.preview_port_hint),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = appTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        val p = portInput.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@Button
                        failed = false
                        loading = true
                        activePort = p.toString()
                        webView?.loadUrl("http://127.0.0.1:$p")
                    }
                ) {
                    Text(stringResource(R.string.preview_open))
                }
                if (activePort != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    IconButton(onClick = {
                        failed = false
                        loading = true
                        webView?.reload()
                    }) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.preview_reload),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                COMMON_PORTS.forEach { p ->
                    Surface(
                        shape = RoundedCornerShape(Radius.pill),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        onClick = { portInput = p.toString() }
                    ) {
                        Text(
                            text = ":$p",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // ── 页面区 ──
        Box(modifier = Modifier.weight(1f).background(bgColor)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ContextThemeWrapper(ctx, webViewThemeRes(dark))).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(bgArgb)
                        settings.apply {
                            @SuppressLint("SetJavaScriptEnabled")
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    failed = true
                                }
                            }
                        }
                        webView = this
                    }
                },
                update = { },
                modifier = Modifier.fillMaxSize()
            )
            if (activePort == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.preview_idle_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xl)
                    )
                }
            } else {
                if (loading && !failed) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
                if (failed) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.preview_failed, activePort.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        TextButton(onClick = {
                            failed = false
                            loading = true
                            webView?.reload()
                        }) {
                            Text(stringResource(R.string.preview_retry))
                        }
                    }
                }
            }
        }
    }

    // 主题切换时同步 WebView 原生配色（滚动条等）与底色。
    LaunchedEffect(dark, webView) {
        webView?.let { view ->
            (view.context as? ContextThemeWrapper)?.setTheme(webViewThemeRes(dark))
            view.setBackgroundColor(bgArgb)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }
}

/** WebView 原生控件的深浅配色主题（与 Markdown 预览同款资源）。 */
private fun webViewThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

/** 常见 dev server 端口快选。 */
private val COMMON_PORTS = listOf(3000, 5173, 8080)
