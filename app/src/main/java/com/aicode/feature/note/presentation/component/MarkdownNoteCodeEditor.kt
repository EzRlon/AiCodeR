package com.aicode.feature.note.presentation.component

import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.R
import com.aicode.feature.editor.domain.TextMateSetup
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Code
import compose.icons.feathericons.Link2
import compose.icons.feathericons.MessageCircle
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.RotateCw
import compose.icons.feathericons.Terminal
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 随记浮窗的 Markdown 源码编辑器。
 *
 * 复用代码编辑器的 sora-editor + TextMate Markdown 语法，不再另造所见即所得编辑器：
 * 行号、自动换行、撤销栈、光标/选区都沿用现有实现；右侧纵向格式条只负责插入 Markdown
 * 记号，避免上一版 Vditor 在窄窗里折行、图标缺失和误插空表格的问题。
 */
@Stable
class MarkdownNoteController {
    // 用 Compose State 承载：factory 创建完成后，主题/语法 effect 才能重新执行
    internal var editor by mutableStateOf<CodeEditor?>(null)

    fun undo() {
        editor?.undo()
    }

    fun redo() {
        editor?.redo()
    }

    fun wrapSelection(prefix: String, suffix: String) {
        val ed = editor ?: return
        val text = ed.text
        // 必须在编辑前取行/列：sora 的 setSelection(line, column) 不是绝对字符下标，
        // 用绝对下标会在多行文档上抛 StringIndexOutOfBounds（运行时实测）。
        val line = ed.cursor.leftLine
        val column = ed.cursor.leftColumn
        val start = ed.cursor.left
        val end = ed.cursor.right
        if (start == end) {
            text.insert(line, column, prefix + suffix)
            ed.setSelection(line, column + prefix.length)
        } else {
            val selected = text.subSequence(start, end).toString()
            text.replace(start, end, prefix + selected + suffix)
            ed.setSelection(line, column + prefix.length)
        }
        ed.invalidate()
    }

    fun prefixLine(prefix: String) {
        val ed = editor ?: return
        val line = ed.cursor.leftLine
        // 同理：插入后把光标放到行首 prefix 之后，参数是（行，列）
        ed.text.insert(line, 0, prefix)
        ed.setSelection(line, prefix.length)
        ed.invalidate()
    }

    /** 代码块：选中文本包进 ```围栏；无选中时插入一对空围栏并把光标放进内部。 */
    fun insertCodeBlock() {
        val ed = editor ?: return
        val start = ed.cursor.left
        val end = ed.cursor.right
        if (start == end) {
            val line = ed.cursor.leftLine
            val column = ed.cursor.leftColumn
            val snippet = "```\n\n```"
            ed.text.insert(line, column, snippet)
            // 光标放到第一行空行内（行 +1，列 0）
            ed.setSelection(line + 1, 0)
        } else {
            val selected = ed.text.subSequence(start, end).toString()
            ed.text.replace(start, end, "```\n$selected\n```")
        }
        ed.invalidate()
    }

    /** 链接：选中文本当链接文字（[文字](url)），光标落 url 处；无选中插模板。 */
    fun insertLink() {
        val ed = editor ?: return
        val line = ed.cursor.leftLine
        val column = ed.cursor.leftColumn
        val start = ed.cursor.left
        val end = ed.cursor.right
        if (start == end) {
            ed.text.insert(line, column, "[](url)")
            ed.setSelection(line, column + 1)
        } else {
            val selected = ed.text.subSequence(start, end).toString()
            ed.text.replace(start, end, "[$selected](url)")
            // 光标落在 url 占位处：] 后 1 位
            ed.setSelection(line, column + selected.length + 3)
        }
        ed.invalidate()
    }
}

@Composable
internal fun MarkdownNoteCodeEditor(
    text: String,
    path: String,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: MarkdownNoteController = remember { MarkdownNoteController() }
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = MaterialTheme.colorScheme.surface
    val changedState = rememberUpdatedState(onTextChanged)
    var textMateReady by remember { mutableStateOf(false) }
    var lineCount by remember(text) { mutableIntStateOf(text.lineCount()) }
    var cursorLine by remember { mutableIntStateOf(1) }

    // TextMate 语法包近 1 MB，解析放 IO；准备好之前先用纯文本，避免浮窗首帧卡住。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { TextMateSetup.ensureInitialized(context) }
        }
        textMateReady = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                factory = { ctx ->
                    CodeEditor(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isEditable = true
                        typefaceText = Typeface.MONOSPACE
                        setTextSize(14f)
                        // 浮窗宽窄变化时必须自动换行，不允许横向滚动
                        setWordwrap(true)
                        isCursorAnimationEnabled = false
                        verticalExtraSpaceFactor = 0.5f
                        isBlockLineEnabled = true
                        setLineNumberMarginLeft(spToPx(ctx, 2f))
                        setText(text)
                        controller.editor = this
                        val editorView = this
                        subscribeAlways(ContentChangeEvent::class.java) {
                            editorView.post {
                                val value = editorView.text.toString()
                                lineCount = value.lineCount()
                                changedState.value(value)
                            }
                        }
                        subscribeAlways(SelectionChangeEvent::class.java) {
                            cursorLine = cursor.leftLine + 1
                        }
                    }
                },
                onRelease = {
                    controller.editor = null
                    it.release()
                }
            )
            NoteFormatRail(controller = controller)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .background(surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.notebook_editor_position, cursorLine, lineCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.notebook_editor_markdown),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 主题/语法就绪后上色；切换深浅色不重建编辑器，保住撤销栈与未保存内容。
    val editor = controller.editor
    LaunchedEffect(textMateReady, dark, surface, editor) {
        editor ?: return@LaunchedEffect
        if (textMateReady) {
            runCatching {
                TextMateSetup.applyTheme(dark)
                editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                editor.setEditorLanguage(TextMateLanguage.create("text.html.markdown", false))
                applyEditorBackground(editor, surface, dark)
                editor.invalidate()
            }
        } else {
            applyEditorBackground(editor, surface, dark)
        }
    }

    // 切换笔记/润色后外部 seed 变化：只有内容确实不同才覆盖，避免打断用户输入。
    LaunchedEffect(text, editor) {
        editor ?: return@LaunchedEffect
        if (editor.text.toString() != text) {
            editor.setText(text)
            lineCount = text.lineCount()
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.editor = null }
    }
}

/** 右侧纵向格式条：带文字标签，窄窗可纵向滚动。
 *
 * 动作按随记的真实用法取舍（2026-11-16 用户反馈 2）：
 * - 待办/代码块/行内代码/链接/引用：AI 会话摘要、命令行与结论收集高频使用；
 * - 撤销/重做：浮窗手滑误删常见，保留兜底。
 * 旧「列表/加粗/斜体」从真实开发随记里几乎没有用途，已移除。
 */
@Composable
private fun NoteFormatRail(controller: MarkdownNoteController) {
    val actions = listOf(
        RailAction(R.string.notebook_format_check, FeatherIcons.CheckSquare) { controller.prefixLine("- [ ] ") },
        RailAction(R.string.notebook_format_code_block, FeatherIcons.Code) { controller.insertCodeBlock() },
        RailAction(R.string.notebook_format_inline_code, FeatherIcons.Terminal) { controller.wrapSelection("`", "`") },
        RailAction(R.string.notebook_format_link, FeatherIcons.Link2) { controller.insertLink() },
        RailAction(R.string.notebook_format_quote, FeatherIcons.MessageCircle) { controller.prefixLine("> ") },
        RailAction(R.string.notebook_format_undo, FeatherIcons.RotateCcw) { controller.undo() },
        RailAction(R.string.notebook_format_redo, FeatherIcons.RotateCw) { controller.redo() }
    )
    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        actions.forEach { action ->
            val label = stringResource(action.label)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(onClick = action.onClick)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = label,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

private data class RailAction(
    val label: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit
)

private fun applyEditorBackground(editor: CodeEditor, themeSurface: Color, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, lerp(base, themeSurface, 0.65f).toArgb())
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(
        EditorColorScheme.LINE_NUMBER_BACKGROUND,
        lerp(themeSurface, target, 0.08f).toArgb()
    )
}

private fun spToPx(context: android.content.Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

private fun String.lineCount(): Int = count { it == '\n' } + 1
