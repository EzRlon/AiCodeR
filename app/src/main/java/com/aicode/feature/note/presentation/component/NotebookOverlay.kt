package com.aicode.feature.note.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
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
import compose.icons.feathericons.Feather
import compose.icons.feathericons.List
import compose.icons.feathericons.Send

/**
 * 会话页「随记」浮窗：不打断会话可见性，随手记录 → 润色 → 一键交给 agent。
 *
 * 编辑器直接复用编辑器页同款 Vditor（[NoteEditorWebView]），写盘走同一套 800ms 防抖；
 * 本组件只负责浮窗外壳、笔记切换与两条动作（润色 / 发送）。
 *
 * [onPolish] 与 [onSend] 由宿主注入：跨 feature 的 AI 能力不在这里直接引用，
 * 避免新增 `feature/presentation → feature/presentation` 依赖。
 */
@Composable
internal fun NotebookOverlay(
    viewModel: NotebookViewModel,
    onClose: () -> Unit,
    onPolish: suspend (String) -> String?,
    onSend: (String) -> Boolean,
    noteEditorViewModel: NoteEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val windowState = remember(viewModel) { FloatingWindowState(viewModel.geometry) }
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
            Box {
                IconButton(onClick = { showNotes = true }, modifier = Modifier.size(32.dp)) {
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
                            text = {
                                Text(note.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        onClick = {
                            showNotes = false
                            viewModel.newNote()
                        }
                    )
                }
            }
            if (state.canUndoPolish) {
                TextButton(
                    onClick = { viewModel.undoPolish() },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.notebook_undo_polish),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(
                onClick = {
                    viewModel.polish(onPolish) { ok ->
                        Toast.makeText(context, if (ok) polishDone else polishFailed, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !state.polishing,
                modifier = Modifier.size(32.dp)
            ) {
                if (state.polishing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        // 与输入框润色按钮同一个「羽毛」图标，用户能认出是同一条能力
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
                        Toast.makeText(context, if (ok) sendDone else sendFailed, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
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
            else -> NoteEditorWebView(
                text = state.editorSeed,
                path = state.currentPath!!,
                onContentChanged = {},
                onSaved = viewModel::onSaved,
                onMarkdownChanged = viewModel::onMarkdownChanged,
                viewModel = noteEditorViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
