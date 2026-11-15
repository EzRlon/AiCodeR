package com.aicode.feature.note.presentation.notebook

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.io.FileAccessProvider
import com.aicode.core.ui.floating.FloatingWindowGeometry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「随记」浮窗的状态：笔记列表、当前文稿、编辑种子内容与润色状态。
 *
 * 存储约定：`~/workspace/.aicode/notebook/` 下的 Markdown 文件（项目级、走 [FileAccessProvider]，
 * 本地与远程模式一致）。不放 `.aicode/notes/` —— 那是知识库与 AI 知识注入的目录，
 * 随记属于用户草稿，不应被自动喂进模型上下文。
 *
 * 编辑与写盘由复用的 Vditor 组件 + [com.aicode.feature.note.presentation.NoteEditorViewModel]
 * 负责（800ms 防抖）；本 VM 只保存「最新一次编辑内容」供润色/发送使用，避免每键入一次都触发重组。
 */
@HiltViewModel
class NotebookViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    companion object {
        private const val NOTEBOOK_DIR = "~/workspace/.aicode/notebook"
        private const val PREFS = "notebook_overlay"
        private const val KEY_LAST_PATH = "last_path"
        private const val KEY_X = "offset_x"
        private const val KEY_Y = "offset_y"
        private const val KEY_W = "width"
        private const val KEY_H = "height"
        const val DEFAULT_WIDTH_DP = 340f
        const val DEFAULT_HEIGHT_DP = 440f
    }

    data class NoteEntry(val path: String, val name: String, val modifiedAt: Long)

    data class UiState(
        val loading: Boolean = true,
        val notes: List<NoteEntry> = emptyList(),
        val currentPath: String? = null,
        /** 只有「加载/切换/润色」时更新；Vditor 只会在这个值变化时整页重载。 */
        val editorSeed: String = "",
        val polishing: Boolean = false,
        val canUndoPolish: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 最新编辑内容：由 Vditor 每次输入回传，不进 Compose 状态（避免逐键重组整窗）。 */
    @Volatile
    private var latestMarkdown: String = ""

    private var prePolishText: String? = null

    val geometry: FloatingWindowGeometry = FloatingWindowGeometry(
        xDp = prefs.getFloat(KEY_X, -1f).takeIf { it >= 0f } ?: 16f,
        yDp = prefs.getFloat(KEY_Y, -1f).takeIf { it >= 0f } ?: 96f,
        widthDp = prefs.getFloat(KEY_W, DEFAULT_WIDTH_DP),
        heightDp = prefs.getFloat(KEY_H, DEFAULT_HEIGHT_DP)
    )

    init {
        openInitialNote()
    }

    fun updateGeometry(geometry: FloatingWindowGeometry) {
        prefs.edit()
            .putFloat(KEY_X, geometry.xDp)
            .putFloat(KEY_Y, geometry.yDp)
            .putFloat(KEY_W, geometry.widthDp)
            .putFloat(KEY_H, geometry.heightDp)
            .apply()
    }

    fun onMarkdownChanged(markdown: String) {
        latestMarkdown = markdown
    }

    /** Vditor 防抖写盘成功（当前笔记仍是同一路径时才需要提示，保留给后续状态展示）。 */
    fun onSaved() = Unit

    fun selectNote(path: String) {
        if (path == _state.value.currentPath) return
        val previousPath = _state.value.currentPath
        val pending = latestMarkdown
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                // 切走前先把防抖窗口里的内容落盘，避免「刚写完就切换」丢字
                if (previousPath != null) {
                    runCatching { fileAccess.writeFile(previousPath, pending, overwrite = true) }
                }
                runCatching { fileAccess.readFile(path) }.getOrDefault("")
            }
            latestMarkdown = text
            prePolishText = null
            val notes = withContext(Dispatchers.IO) { listNotes() }
            _state.update {
                it.copy(
                    currentPath = path,
                    editorSeed = text,
                    notes = notes,
                    loading = false,
                    canUndoPolish = false
                )
            }
            prefs.edit().putString(KEY_LAST_PATH, path).apply()
        }
    }

    fun newNote() {
        val previousPath = _state.value.currentPath
        val pending = latestMarkdown
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                if (previousPath != null) {
                    runCatching { fileAccess.writeFile(previousPath, pending, overwrite = true) }
                }
                createNoteFile()
            }
            latestMarkdown = ""
            prePolishText = null
            val notes = withContext(Dispatchers.IO) { listNotes() }
            _state.update {
                it.copy(
                    currentPath = path,
                    editorSeed = "",
                    notes = notes,
                    loading = false,
                    canUndoPolish = false
                )
            }
            prefs.edit().putString(KEY_LAST_PATH, path).apply()
        }
    }

    /** 关闭浮窗前落盘；只写本地，不阻塞关闭动画。 */
    fun flush() {
        val path = _state.value.currentPath ?: return
        val text = latestMarkdown
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { fileAccess.writeFile(path, text, overwrite = true) }
        }
    }

    /**
     * 润色当前随记：复用输入框同一条润色链路（[provider] 由宿主注入，内部是
     * `AgentWorkflow.polishText`）。结果直接替换正文并立即落盘，可撤销一次。
     */
    fun polish(provider: suspend (String) -> String?, onResult: (Boolean) -> Unit) {
        val text = latestMarkdown.ifBlank { _state.value.editorSeed }
        if (text.isBlank() || _state.value.polishing) return
        _state.update { it.copy(polishing = true) }
        viewModelScope.launch {
            val polished = runCatching { provider(text) }.getOrNull()
            if (polished.isNullOrBlank() || polished == text) {
                _state.update { it.copy(polishing = false) }
                onResult(false)
                return@launch
            }
            prePolishText = text
            latestMarkdown = polished
            withContext(Dispatchers.IO) {
                _state.value.currentPath?.let { path ->
                    runCatching { fileAccess.writeFile(path, polished, overwrite = true) }
                }
            }
            _state.update { it.copy(polishing = false, editorSeed = polished, canUndoPolish = true) }
            onResult(true)
        }
    }

    fun undoPolish() {
        val previous = prePolishText ?: return
        prePolishText = null
        latestMarkdown = previous
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _state.value.currentPath?.let { path ->
                    runCatching { fileAccess.writeFile(path, previous, overwrite = true) }
                }
            }
            _state.update { it.copy(editorSeed = previous, canUndoPolish = false) }
        }
    }

    /** 当前编辑器内容（含尚未落盘的最新输入），供发送前判空。 */
    fun currentText(): String = latestMarkdown.ifBlank { _state.value.editorSeed }

    /** 一键发给 agent：返回 false 表示没有内容或被硬阈值拦截。 */
    fun send(dispatch: (String) -> Boolean): Boolean {
        val text = latestMarkdown.ifBlank { _state.value.editorSeed }
        if (text.isBlank()) return false
        return runCatching { dispatch(text) }.getOrDefault(false)
    }

    private fun openInitialNote() {
        viewModelScope.launch {
            val notes = withContext(Dispatchers.IO) { listNotes() }
            val remembered = prefs.getString(KEY_LAST_PATH, null)
            val target = notes.firstOrNull { it.path == remembered }?.path
                ?: notes.firstOrNull()?.path
                ?: withContext(Dispatchers.IO) { createNoteFile() }
            val text = withContext(Dispatchers.IO) {
                runCatching { fileAccess.readFile(target) }.getOrDefault("")
            }
            latestMarkdown = text
            val refreshed = if (notes.any { it.path == target }) notes else withContext(Dispatchers.IO) { listNotes() }
            _state.update {
                it.copy(
                    loading = false,
                    notes = refreshed,
                    currentPath = target,
                    editorSeed = text
                )
            }
            prefs.edit().putString(KEY_LAST_PATH, target).apply()
        }
    }

    private fun listNotes(): List<NoteEntry> = runCatching {
        if (!fileAccess.isDirectory(NOTEBOOK_DIR)) return emptyList()
        fileAccess.listFiles(NOTEBOOK_DIR)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .map { entry ->
                NoteEntry(
                    path = "$NOTEBOOK_DIR/${entry.name}",
                    name = entry.name.removeSuffix(".md"),
                    modifiedAt = entry.lastModified
                )
            }
            .sortedByDescending { it.modifiedAt }
    }.getOrDefault(emptyList())

    /** 新建一篇空白笔记；返回容器路径。同名已存在时追加序号。 */
    private fun createNoteFile(): String {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        var suffix = 0
        while (true) {
            val name = if (suffix == 0) "随手记-$stamp" else "随手记-$stamp-$suffix"
            val path = "$NOTEBOOK_DIR/$name.md"
            if (!fileAccess.exists(path)) {
                runCatching { fileAccess.writeFile(path, "", overwrite = false) }
                return path
            }
            suffix++
        }
    }
}
