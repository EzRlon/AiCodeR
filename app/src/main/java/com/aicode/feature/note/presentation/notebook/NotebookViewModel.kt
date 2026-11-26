package com.aicode.feature.note.presentation.notebook

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.io.FileAccessProvider
import com.aicode.core.ui.floating.FloatingWindowGeometry
import com.aicode.core.util.FileLogger
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
        private const val TAG = "Notebook"
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

    /** 浮窗标题栏的保存状态；Idle 不展示，避免刚打开就闪一下「已保存」。 */
    enum class SaveState { Idle, Saving, Saved, Failed }

    data class UiState(
        val loading: Boolean = true,
        val notes: List<NoteEntry> = emptyList(),
        val currentPath: String? = null,
        /** 只有「加载/切换/润色」时更新；Vditor 只会在这个值变化时整页重载。 */
        val editorSeed: String = "",
        val polishing: Boolean = false,
        val canUndoPolish: Boolean = false,
        /** 首次加载失败（远端断开/目录不可写等）：不再无限转圈，页面给可读提示。 */
        val loadFailed: Boolean = false,
        /** 切换/新建失败时递增：浮窗据此弹一次性提示，但**不**用失败页顶掉当前可编辑笔记。 */
        val actionErrorTick: Int = 0,
        val saveState: SaveState = SaveState.Idle
    )

    private fun signalActionError() {
        _state.update { it.copy(actionErrorTick = it.actionErrorTick + 1) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 最新编辑内容：由 Vditor 每次输入回传，不进 Compose 状态（避免逐键重组整窗）。 */
    @Volatile
    private var latestMarkdown: String = ""

    /**
     * 是否发生过编辑。用来区分「还没输入」与「用户把内容清空了」：
     * 只看 `latestMarkdown.ifBlank` 会把清空误判成没输入，从而回退到旧种子并写回文件。
     */
    @Volatile
    private var hasEdits: Boolean = false

    private var prePolishText: String? = null

    /** 当前权威内容：编辑过以内存为准，否则以加载时的种子为准。 */
    private fun currentContent(): String = if (hasEdits) latestMarkdown else _state.value.editorSeed

    /**
     * 浮窗几何。必须是可变字段并在拖动/缩放提交时同步：只写 prefs 的话，关掉浮窗再打开
     * （重走 `remember(viewModel)`）会退回进程启动时的位置尺寸。
     */
    var geometry: FloatingWindowGeometry = FloatingWindowGeometry(
        xDp = prefs.getFloat(KEY_X, -1f).takeIf { it >= 0f } ?: 16f,
        yDp = prefs.getFloat(KEY_Y, -1f).takeIf { it >= 0f } ?: 96f,
        widthDp = prefs.getFloat(KEY_W, DEFAULT_WIDTH_DP),
        heightDp = prefs.getFloat(KEY_H, DEFAULT_HEIGHT_DP)
    )

    private var loaded = false

    /**
     * 第一次打开浮窗时才建目录/读笔记：Activity 启动时就做这些会给完全不用随记的用户
     * 增加一次无谓扫盘（功耗纪律：不做与当前操作无关的后台 IO）。
     */
    fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            _state.update { it.copy(loading = true, loadFailed = false) }
            openInitialNote()
        } else {
            // 重新打开浮窗：把编辑器种子同步成内存最新内容，避免 WebView 用旧种子重建
            reloadCurrent()
        }
    }

    /** 加载失败后的重试入口：失败时已把 [loaded] 复位，这里重新走首次加载。 */
    fun retryLoad() {
        loaded = false
        ensureLoaded()
    }

    /**
     * 浮窗重开 / 最小化恢复时，把编辑器种子同步成**内存里最新的内容**。
     *
     * 刻意不读盘：防抖写盘有 800ms 窗口，关掉或最小化后立刻重开时磁盘可能还是旧内容，
     * 读盘会把用户刚敲的字回退掉（并且下一次编辑会把旧内容写回去）。内存内容才是权威，
     * 顺带补一次落盘。App 重启后没有内存态，走 [openInitialNote] 的读盘分支。
     */
    fun reloadCurrent() {
        val path = _state.value.currentPath ?: return
        // 润色中也必须同步：否则润色失败/超时后，编辑器停在旧种子，下一次输入会把
        // 润色前敲的内容覆盖掉。润色完成时自身还有内容一致性校验兜底。
        // 没有新编辑时种子就是最新内容，不必重建页面，也不必再写一次盘
        if (!hasEdits) return
        val text = currentContent()
        hasEdits = false
        if (text != _state.value.editorSeed) {
            _state.update { it.copy(editorSeed = text) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { fileAccess.writeFile(path, text, overwrite = true) }
                .onFailure { FileLogger.w(TAG, "随记回写失败: $path", it) }
        }
    }

    fun updateGeometry(geometry: FloatingWindowGeometry) {
        this.geometry = geometry
        prefs.edit()
            .putFloat(KEY_X, geometry.xDp)
            .putFloat(KEY_Y, geometry.yDp)
            .putFloat(KEY_W, geometry.widthDp)
            .putFloat(KEY_H, geometry.heightDp)
            .apply()
    }

    /** 编辑器内容变化；[path] 用于丢弃切换笔记后旧 WebView 的迟到回调。 */
    fun onMarkdownChanged(path: String, markdown: String) {
        if (path != _state.value.currentPath) return
        latestMarkdown = markdown
        hasEdits = true
        // 用户已经继续编辑：润色撤销点随之失效。否则点「撤销润色」会把润色后新输入的内容
        // 一起回退掉（review P2：撤销可能连带回退新输入）。
        // 注意：润色完成会用 editorSeed 回填编辑器，回填回调的 markdown == editorSeed，不算编辑。
        val undoStale = prePolishText != null && markdown != _state.value.editorSeed
        if (undoStale) prePolishText = null
        // 同一段输入只切一次状态，避免逐键触发整窗重组
        if (_state.value.saveState != SaveState.Saving || undoStale) {
            _state.update { it.copy(saveState = SaveState.Saving, canUndoPolish = if (undoStale) false else it.canUndoPolish) }
        }
    }

    /** Vditor 防抖写盘结果；迟到的旧笔记结果不能覆盖当前笔记的标题栏状态。 */
    fun onSaveResult(path: String, ok: Boolean) {
        if (path != _state.value.currentPath) return
        _state.update { it.copy(saveState = if (ok) SaveState.Saved else SaveState.Failed) }
    }

    fun selectNote(path: String) {
        // 首次加载还没落地时切换会让两路协程互相覆盖 currentPath，直接忽略这次点击
        if (_state.value.loading) return
        if (path == _state.value.currentPath) return
        val previousPath = _state.value.currentPath
        val pending = currentContent()
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                // 切走前先把防抖窗口里的内容落盘，避免「刚写完就切换」丢字
                if (previousPath != null) {
                    runCatching { fileAccess.writeFile(previousPath, pending, overwrite = true) }
                        .onFailure { FileLogger.w(TAG, "切换前落盘失败: $previousPath", it) }
                }
                runCatching { fileAccess.readFile(path) }.getOrNull()
            }
            if (text == null) {
                // 远端读失败：留在当前笔记并提示一次，不能拿空串把编辑器清掉，
                // 也不能把整屏换成失败页——当前笔记仍然可编辑。
                signalActionError()
                return@launch
            }
            latestMarkdown = text
            hasEdits = false
            prePolishText = null
            val notes = withContext(Dispatchers.IO) { listNotes() }
            _state.update {
                it.copy(
                    currentPath = path,
                    editorSeed = text,
                    notes = notes,
                    loading = false,
                    loadFailed = false,
                    canUndoPolish = false,
                    saveState = SaveState.Idle
                )
            }
            prefs.edit().putString(KEY_LAST_PATH, path).apply()
        }
    }

    fun newNote() {
        if (_state.value.loading) return
        val previousPath = _state.value.currentPath
        val pending = currentContent()
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                if (previousPath != null) {
                    runCatching { fileAccess.writeFile(previousPath, pending, overwrite = true) }
                        .onFailure { FileLogger.w(TAG, "新建前落盘失败: $previousPath", it) }
                }
                createNoteFile()
            } ?: run {
                // 新建失败只提示，当前笔记保持原样
                signalActionError()
                return@launch
            }
            latestMarkdown = ""
            hasEdits = false
            prePolishText = null
            val notes = withContext(Dispatchers.IO) { listNotes() }
            _state.update {
                it.copy(
                    currentPath = path,
                    editorSeed = "",
                    notes = notes,
                    loading = false,
                    loadFailed = false,
                    canUndoPolish = false,
                    saveState = SaveState.Idle
                )
            }
            prefs.edit().putString(KEY_LAST_PATH, path).apply()
        }
    }

    /** 关闭浮窗前落盘；只写本地，不阻塞关闭动画。 */
    fun flush() {
        val path = _state.value.currentPath ?: return
        // 没有新编辑就不重复写盘（远程模式下每次写都是 SFTP 往返）
        if (!hasEdits) return
        val text = currentContent()
        hasEdits = false
        // 种子必须同步成已落盘内容：否则快速关开浮窗时 reloadCurrent 因 hasEdits==false
        // 短路，编辑器会用旧种子（新笔记即空白）重建，用户刚敲的内容看似全丢
        if (text != _state.value.editorSeed) {
            _state.update { it.copy(editorSeed = text) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { fileAccess.writeFile(path, text, overwrite = true) }
                .onFailure { FileLogger.w(TAG, "关闭前落盘失败: $path", it) }
        }
    }

    /**
     * 润色当前随记：复用输入框同一条润色链路（[provider] 由宿主注入，内部是
     * `AgentWorkflow.polishText`）。结果直接替换正文并立即落盘，可撤销一次。
     */
    /**
     * 随记 AI 动作（整理/总结/提取待办）：与润色同一条链路，只是 system 指令不同。
     * 结果直接替换正文并落盘，可撤销一次。
     */
    fun transform(
        instruction: String,
        provider: suspend (instruction: String, text: String) -> String?,
        onResult: (Boolean) -> Unit
    ) {
        val path = _state.value.currentPath ?: return
        val text = currentContent()
        if (text.isBlank()) { onResult(false); return }
        if (_state.value.polishing) return
        _state.update { it.copy(polishing = true) }
        viewModelScope.launch {
            val rewritten = runCatching { provider(instruction, text) }.getOrNull()
            // 慢请求期间可能切换笔记或继续输入：结果必须只落在发起时那一篇、且内容未变。
            if (_state.value.currentPath != path || currentContent() != text) {
                _state.update { it.copy(polishing = false) }
                onResult(false)
                return@launch
            }
            if (rewritten.isNullOrBlank() || rewritten == text) {
                _state.update { it.copy(polishing = false) }
                onResult(false)
                return@launch
            }
            prePolishText = text
            latestMarkdown = rewritten
            hasEdits = true
            val written = withContext(Dispatchers.IO) {
                runCatching { fileAccess.writeFile(path, rewritten, overwrite = true) }.isSuccess
            }
            _state.update {
                it.copy(
                    polishing = false,
                    editorSeed = rewritten,
                    canUndoPolish = true,
                    saveState = if (written) SaveState.Saved else SaveState.Failed
                )
            }
            onResult(written)
        }
    }

    fun polish(provider: suspend (String) -> String?, onResult: (Boolean) -> Unit) {
        val path = _state.value.currentPath ?: return
        val text = currentContent()
        if (text.isBlank()) {
            onResult(false)
            return
        }
        if (_state.value.polishing) return
        _state.update { it.copy(polishing = true) }
        viewModelScope.launch {
            val polished = runCatching { provider(text) }.getOrNull()
            // 润色是慢请求：期间用户可能切换/新建了笔记。结果必须回到「发起时那一篇」，
            // 否则会把旧笔记的润色文本写进新笔记（覆盖用户正在写的内容）。
            if (_state.value.currentPath != path) {
                _state.update { it.copy(polishing = false) }
                onResult(false)
                return@launch
            }
            if (polished.isNullOrBlank() || polished == text) {
                _state.update { it.copy(polishing = false) }
                onResult(false)
                return@launch
            }
            // 同一篇笔记里用户可能已经继续输入：此时润色结果基于旧文本，覆盖会吞掉新输入
            if (currentContent() != text) {
                _state.update { it.copy(polishing = false) }
                onResult(false)
                return@launch
            }
            prePolishText = text
            latestMarkdown = polished
            hasEdits = true
            val written = withContext(Dispatchers.IO) {
                runCatching { fileAccess.writeFile(path, polished, overwrite = true) }.isSuccess
            }
            _state.update {
                it.copy(
                    polishing = false,
                    editorSeed = polished,
                    canUndoPolish = true,
                    saveState = if (written) SaveState.Saved else SaveState.Failed
                )
            }
            // 写盘失败时标题栏已显示「保存失败」，这里不能再回报成功（否则 Toast 与状态矛盾）
            onResult(written)
        }
    }

    fun undoPolish() {
        val previous = prePolishText ?: return
        val path = _state.value.currentPath ?: return
        prePolishText = null
        latestMarkdown = previous
        hasEdits = true
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { fileAccess.writeFile(path, previous, overwrite = true) }.isSuccess
            }
            // 写盘期间可能已切走：只把种子应用回发起时那一篇，避免串稿
            if (_state.value.currentPath != path) return@launch
            _state.update {
                it.copy(
                    editorSeed = previous,
                    canUndoPolish = false,
                    saveState = if (written) SaveState.Saved else SaveState.Failed
                )
            }
        }
    }

    /** 当前编辑器内容（含尚未落盘的最新输入），供发送前判空。 */
    fun currentText(): String = currentContent()

    /** 一键发给 agent：返回 false 表示没有内容或被硬阈值拦截。 */
    fun send(dispatch: (String) -> Boolean): Boolean {
        if (_state.value.currentPath == null) return false
        val text = currentContent()
        if (text.isBlank()) return false
        return runCatching { dispatch(text) }.getOrDefault(false)
    }

    private fun openInitialNote() {
        viewModelScope.launch {
            // 远端 SFTP 断开、目录不可写都会抛异常：必须兜住并落到失败态，
            // 否则 loading 永远为 true，浮窗只转圈且没有任何提示。
            val opened = runCatching {
                val notes = withContext(Dispatchers.IO) { listNotes() }
                val remembered = prefs.getString(KEY_LAST_PATH, null)
                val target = notes.firstOrNull { it.path == remembered }?.path
                    ?: notes.firstOrNull()?.path
                    ?: withContext(Dispatchers.IO) { createNoteFile() ?: error("无法创建随记文件") }
                val text = withContext(Dispatchers.IO) {
                    runCatching { fileAccess.readFile(target) }.getOrDefault("")
                }
                val refreshed = if (notes.any { it.path == target }) {
                    notes
                } else {
                    withContext(Dispatchers.IO) { listNotes() }
                }
                Triple(target, refreshed, text)
            }
            opened.onSuccess { (target, notes, text) ->
                latestMarkdown = text
                hasEdits = false
                _state.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        notes = notes,
                        currentPath = target,
                        editorSeed = text,
                        saveState = SaveState.Idle
                    )
                }
                prefs.edit().putString(KEY_LAST_PATH, target).apply()
            }.onFailure { error ->
                FileLogger.w(TAG, "加载随记失败", error)
                // 复位 loaded：再次打开浮窗或点「重试」能重新加载，而不是永久卡在失败态
                loaded = false
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
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

    /** 新建一篇空白笔记；返回容器路径。同名已存在时追加序号；写盘失败返回 null。 */
    private fun createNoteFile(): String? {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        var suffix = 0
        while (suffix <= 50) {
            val name = if (suffix == 0) "随手记-$stamp" else "随手记-$stamp-$suffix"
            val path = "$NOTEBOOK_DIR/$name.md"
            if (!fileAccess.exists(path)) {
                // 写盘失败（目录只读/远端断连）不能假装成功：否则之后每次防抖保存都静默失败
                val written = runCatching { fileAccess.writeFile(path, "", overwrite = false) }
                    .onFailure { FileLogger.w(TAG, "创建随记失败: $path", it) }
                    .isSuccess
                return if (written) path else null
            }
            suffix++
        }
        return null
    }
}
