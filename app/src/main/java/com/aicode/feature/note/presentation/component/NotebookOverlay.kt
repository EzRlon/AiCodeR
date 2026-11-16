package com.aicode.feature.note.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.ui.floating.FloatingWindowScaffold
import com.aicode.core.ui.floating.FloatingWindowState
import com.aicode.feature.note.presentation.NoteEditorViewModel
import com.aicode.feature.note.presentation.notebook.NotebookViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Feather
import compose.icons.feathericons.List
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Send

/**
 * 会话页「随记」浮窗：不打断会话可见性，随手记录 → 润色 → 一键交给 agent。
 *
 * 本轮按用户反馈改成代码编辑器形态：复用 sora-editor + TextMate Markdown，
 * 行号、自动换行、撤销栈都沿用编辑器页；右侧是纵向格式条（待办/列表/加粗/斜体/撤销/重做），
 * 底部不再放大卡片，润色与发送都收进标题栏，只保留图标。
 *
 * [onPolish] 与 [onSend] 由宿主注入：跨 feature 的 AI 能力不在这里直接引用。
 */
@Composable
internal fun NotebookOverlay(
    viewModel: NotebookViewModel,
    onClose: () -> Unit,
    onPolish: suspend (String) -> String?,
    /** 随记右侧动作（整理/总结/提取待办）：(指令, 文本) → 改写结果。 */
    onTransform: suspend (String, String) -> String? = { _, _ -> null },
    onSend: (String) -> Boolean,
    noteEditorViewModel: NoteEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val windowState = remember(viewModel) { FloatingWindowState(viewModel.geometry) }
    // 首次打开才加载笔记（不在 App 启动时做无关磁盘 IO）；再次打开时同步内存最新内容
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    // 切换/新建失败只弹一次性提示，不用失败页顶掉当前仍可编辑的笔记
    val loadFailedMsg = stringResource(R.string.notebook_load_failed)
    LaunchedEffect(state.actionErrorTick) {
        if (state.actionErrorTick > 0) {
            Toast.makeText(context, loadFailedMsg, Toast.LENGTH_SHORT).show()
        }
    }
    // 最小化会销毁浮窗内容：从「最小化」恢复时把种子同步为内存最新内容。
    var wasMinimized by remember { mutableStateOf(windowState.minimized) }
    LaunchedEffect(windowState.minimized) {
        if (wasMinimized && !windowState.minimized && viewModel.state.value.currentPath != null) {
            viewModel.reloadCurrent()
        }
        wasMinimized = windowState.minimized
    }
    var showNotes by remember { mutableStateOf(false) }

    val polishDone = stringResource(R.string.chat_polish_done)
    val polishFailed = stringResource(R.string.chat_polish_failed)
    val sendDone = stringResource(R.string.notebook_send_done)
    val sendFailed = stringResource(R.string.notebook_send_failed)
    val sendEmpty = stringResource(R.string.notebook_send_empty)

    FloatingWindowScaffold(
        title = state.currentPath
            ?.substringAfterLast('/')
            ?.removeSuffix(".md")
            ?: stringResource(R.string.notebook_title),
        state = windowState,
        onClose = {
            viewModel.flush()
            onClose()
        },
        onGeometryCommitted = viewModel::updateGeometry,
        headerActions = {
            SaveStateLabel(state.saveState)
            Box {
                IconButton(onClick = { showNotes = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        FeatherIcons.List,
                        contentDescription = stringResource(R.string.notebook_list),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = showNotes, onDismissRequest = { showNotes = false }) {
                    state.notes.forEach { note ->
                        DropdownMenuItem(
                            text = { Text(note.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = if (note.path == state.currentPath) {
                                {
                                    Icon(
                                        FeatherIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                showNotes = false
                                viewModel.selectNote(note.path)
                            }
                        )
                    }
                    if (state.notes.isNotEmpty()) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notebook_new)) },
                        leadingIcon = {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        onClick = {
                            showNotes = false
                            viewModel.newNote()
                        }
                    )
                }
            }
            if (state.canUndoPolish) {
                IconButton(
                    onClick = { viewModel.undoPolish() },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        FeatherIcons.RotateCcw,
                        contentDescription = stringResource(R.string.notebook_undo_polish),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = {
                    viewModel.polish(onPolish) { ok ->
                        Toast.makeText(
                            context,
                            if (ok) polishDone else polishFailed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                enabled = !state.polishing,
                modifier = Modifier.size(30.dp)
            ) {
                if (state.polishing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        FeatherIcons.Feather,
                        contentDescription = stringResource(R.string.notebook_polish),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = {
                    if (viewModel.currentText().isBlank()) {
                        Toast.makeText(context, sendEmpty, Toast.LENGTH_SHORT).show()
                    } else {
                        val ok = viewModel.send(onSend)
                        Toast.makeText(
                            context,
                            if (ok) sendDone else sendFailed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    FeatherIcons.Send,
                    contentDescription = stringResource(R.string.notebook_send),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        when {
            state.loadFailed -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.notebook_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.retryLoad() }) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            state.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            state.currentPath == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.notebook_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                val path = state.currentPath!!
                key(path) {
                    MarkdownNoteCodeEditor(
                        text = state.editorSeed,
                        path = path,
                        onTextChanged = { value ->
                            viewModel.onMarkdownChanged(path, value)
                            noteEditorViewModel.scheduleSave(
                                path = path,
                                markdown = value,
                                onSaved = { viewModel.onSaveResult(path, true) },
                                onFailed = { viewModel.onSaveResult(path, false) }
                            )
                        },
                        onAiAction = { action ->
                            viewModel.transform(action.instruction, onTransform) { ok ->
                                if (!ok) Toast.makeText(
                                    context,
                                    context.getString(R.string.notebook_ai_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        canUndoAi = state.canUndoPolish,
                        onUndoAi = { viewModel.undoPolish() },
                        transforming = state.polishing,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** 标题栏右侧的自动保存状态；Idle 时完全不占位。 */
@Composable
private fun SaveStateLabel(saveState: NotebookViewModel.SaveState) {
    if (saveState == NotebookViewModel.SaveState.Idle) return
    val failed = saveState == NotebookViewModel.SaveState.Failed
    val textRes = when (saveState) {
        NotebookViewModel.SaveState.Saving -> R.string.notebook_saving
        NotebookViewModel.SaveState.Failed -> R.string.notebook_save_failed
        else -> R.string.notebook_saved
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier.padding(end = 2.dp)
    )
}
