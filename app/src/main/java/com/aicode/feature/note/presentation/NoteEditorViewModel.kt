package com.aicode.feature.note.presentation

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewAssetLoader
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.Paths
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 笔记（WYSIWYG）页的后端：把 Markdown 原文注入 Vditor 模板，并负责把编辑结果写回文件。
 *
 * 资源加载沿用 Markdown 预览那套 [WebViewAssetLoader]：`/assets/` 提供离线打包的 Vditor，
 * `/local/` 从 [FileAccessProvider] 读取工作区文件（本地直读、远程 SFTP），让笔记里的相对路径图片能显示。
 */
@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    companion object {
        /** [WebViewAssetLoader] 默认虚拟 host。 */
        const val ASSET_HOST = "appassets.androidplatform.net"

        private const val ASSET_BASE = "https://$ASSET_HOST/assets/"
        private const val LOCAL_PREFIX = "/local/"
        private const val TAG = "NoteEditor"

        /** 工作区在容器内的根路径，与 WorkspacePathMapper.CONTAINER_ROOT 一致。 */
        private const val WS_ROOT = "~/workspace"

        private const val BG_DARK = "#1a1a1a"
        private const val BG_LIGHT = "#ffffff"

        /** 编辑停顿多久后才写盘，避免逐键写文件。 */
        private const val SAVE_DEBOUNCE_MS = 800L
    }

    private val template: String by lazy {
        context.assets.open("notes/note.html").bufferedReader().use { it.readText() }
    }

    @Volatile
    private var currentDark: Boolean = false

    private var saveJob: Job? = null

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(LOCAL_PREFIX, LocalAssetPathHandler())
        .build()

    /** 把 Markdown 原文注入模板生成笔记页 HTML；[dark] 决定 Vditor 明/暗主题。 */
    suspend fun buildHtml(text: String, dark: Boolean): String = withContext(Dispatchers.IO) {
        currentDark = dark
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        template
            .replace("__ASSET_BASE__", ASSET_BASE)
            .replace("__BG__", if (dark) BG_DARK else BG_LIGHT)
            .replace("__DARK__", if (dark) "true" else "false")
            .replace("__MD_B64__", b64)
    }

    /** 笔记页 baseUrl：以被编辑文件所在目录为基准，笔记里的相对图片路径解析到 `/local/<相对工作区根的目录>/`。 */
    fun baseUrl(path: String): String {
        val dir = relativeDirOf(path)
        val encoded = Uri.encode(dir, "/")
        return if (encoded.isEmpty()) {
            "https://$ASSET_HOST$LOCAL_PREFIX"
        } else {
            "https://$ASSET_HOST$LOCAL_PREFIX$encoded/"
        }
    }

    /** 防抖保存：合并连续编辑，停顿后写盘；成功回调 [onSaved]（在主线程）。 */
    fun scheduleSave(path: String, markdown: String, onSaved: () -> Unit) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            // 本地直读/远程 SFTP 都可能阻塞：写盘不能压在主线程上（随记浮窗会在输入时反复触发）
            val ok = withContext(Dispatchers.IO) {
                runCatching { fileAccess.writeFile(path, markdown, overwrite = true) }
                    .onFailure { FileLogger.w(TAG, "保存笔记失败: $path", it) }
                    .isSuccess
            }
            if (ok) onSaved()
        }
    }

    /** `/local/` 之后的相对路径还原为容器路径（收拢 `..`，避免跳出工作区）。 */
    internal fun containerPathOf(relativePath: String): String? {
        val decoded = runCatching { URLDecoder.decode(relativePath, "UTF-8") }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val normalized = runCatching { Paths.get("/$decoded").normalize().toString() }.getOrNull() ?: return null
        val trimmed = normalized.trimStart('/')
        if (trimmed.isEmpty()) return null
        return "$WS_ROOT/$trimmed"
    }

    private fun relativeDirOf(path: String): String {
        val parent = fileAccess.parentPath(path)?.trimEnd('/') ?: return ""
        return when {
            parent == WS_ROOT -> ""
            parent.startsWith("$WS_ROOT/") -> parent.removePrefix("$WS_ROOT/")
            else -> ""
        }
    }

    private inner class LocalAssetPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val containerPath = containerPathOf(path) ?: return notFound()
            val bytes = runCatching { fileAccess.readBytes(containerPath) }
                .onFailure { FileLogger.w(TAG, "读取笔记资源失败: $containerPath", it) }
                .getOrNull() ?: return notFound()
            return WebResourceResponse(mimeOf(containerPath), null, ByteArrayInputStream(bytes))
        }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}
