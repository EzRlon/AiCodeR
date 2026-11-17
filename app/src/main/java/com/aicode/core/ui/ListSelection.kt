package com.aicode.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.X

/**
 * 列表多选状态。key 由各页面自定义（`作用域:名称` 这类组合键），
 * 这样一套组件能服务所有「可批量操作的字符串键列表」，页面侧只需提供 key 映射。
 *
 * 约定：
 * - key 必须在列表内唯一，且数据刷新后保持稳定（不要用下标）。
 * - 数据刷新后调用 [retainOnly] 剔除已消失的选中项，避免批量操作打到已删除的条目。
 * - 退出多选（[exit]）会清空选中集合，返回键与顶栏开关共用它。
 */
class ListSelectionState {

    /** 是否处于多选模式。false 时列表行为与改造前完全一致（左滑删除、点击进详情）。 */
    var isActive by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<String>()

    val selectedCount: Int get() = _selected.size

    /** 当前选中 key 的快照：批量回调触发时取一次，避免边遍历边被改动。 */
    val selectedKeys: List<String> get() = _selected.toList()

    fun isSelected(key: String): Boolean = key in _selected

    fun toggle(key: String) {
        if (!_selected.remove(key)) _selected.add(key)
    }

    fun enter() {
        isActive = true
    }

    fun exit() {
        isActive = false
        _selected.clear()
    }

    fun clear() {
        _selected.clear()
    }

    /** 全选 / 取消全选：整体替换选中集合。 */
    fun replaceAll(keys: Collection<String>) {
        _selected.clear()
        _selected.addAll(keys)
    }

    /** 数据刷新后剔除失效 key（被删掉、被筛掉的条目）。 */
    fun retainOnly(keys: Set<String>) {
        _selected.retainAll(keys)
    }
}

/**
 * 顶部栏右上角的多选开关。列表为空时调用方不显示它；
 * [enabled] = false 时置灰不可点（如条目全部不可编辑，进多选也无项可勾）。
 */
@Composable
fun SelectionToggleAction(selection: ListSelectionState, enabled: Boolean = true) {
    IconButton(
        onClick = { if (selection.isActive) selection.exit() else selection.enter() },
        enabled = enabled
    ) {
        Icon(
            FeatherIcons.CheckSquare,
            contentDescription = stringResource(R.string.common_select_mode),
            tint = if (selection.isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.38f)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 列表顶部操作条：左侧「已选 N 项」，中间「全选 / 取消全选」，右侧页面自己的批量动作，最右退出。
 *
 * [actions] 里的按钮建议用 [SelectionActionButton] / [SelectionActionIconButton] 保持同一套尺寸与配色。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // 操作条只在多选模式出现：顺手把返回键接管成「退出多选」。
    BackHandler { onExit() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.common_selected_count, selectedCount),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onToggleSelectAll,
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text(
                text = stringResource(
                    if (allSelected) R.string.common_deselect_all else R.string.common_select_all
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        actions()
        IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.common_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 操作条上的紧凑动作按钮。窄屏要在一行里放下「全选 + 两三个动作 + 退出」，
 * 所以内边距压到 [Spacing.sm]、文字用 labelMedium、图标 16dp。
 */
@Composable
fun SelectionActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = tint),
        modifier = modifier.height(36.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * 列表多选的页面侧脚手架：把「key 列表 → 选中项 → 全选态 → 刷新后剔除失效项」这套样板
 * 收口成一处。MCP / 技能 / 子代理三个页面原先各写一份，改一处就要同步三处。
 *
 * @param keys 当前列表的稳定 key（顺序与列表一致，用于全选）
 * @param selection 页面持有的 [ListSelectionState]
 * @param isEmpty 当前列表是否为空（空则退出多选）
 * @param entryOf key → 业务条目，用于把选中 key 还原成可操作对象
 */
@Composable
fun <T> rememberListSelection(
    keys: List<String>,
    selection: ListSelectionState,
    isEmpty: Boolean,
    entryOf: (String) -> T?
): ListSelectionScaffold<T> {
    val keySet = remember(keys) { keys.toSet() }
    val selectedEntries = remember(selection.selectedKeys, keys) {
        selection.selectedKeys.mapNotNull(entryOf)
    }
    val allSelected = selection.selectedCount > 0 && selection.selectedCount == keys.size
    // 列表刷新后剔除已消失的选中项；全空了就退出多选。
    LaunchedEffect(keySet, selection.isActive) {
        selection.retainOnly(keySet)
        if (selection.isActive && isEmpty) selection.exit()
    }
    return ListSelectionScaffold(selection, keys, selectedEntries, allSelected)
}

/** [rememberListSelection] 的返回值：动作条与批量操作需要的那几项。 */
class ListSelectionScaffold<T>(
    private val selection: ListSelectionState,
    private val allKeys: List<String>,
    val selectedEntries: List<T>,
    val allSelected: Boolean
) {
    /** 全选/取消全选（全选覆盖全部 key，含折叠起来的）。 */
    fun toggleSelectAll() {
        if (allSelected) selection.clear() else selection.replaceAll(allKeys)
    }
}

/**
 * 批量删除二次确认弹窗。[inProgress] 为 true 时确认按钮转圈并锁定两个按钮，
 * 避免删除中途被关掉导致「看不见到底成没成」。
 */
@Composable
fun BatchDeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    inProgress: Boolean = false,
    progressText: String? = null
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                if (progressText != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !inProgress) {
                if (inProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
