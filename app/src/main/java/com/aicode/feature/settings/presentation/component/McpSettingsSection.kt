package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.rememberListSelection
import com.aicode.core.ui.SelectionActionBar
import com.aicode.core.ui.SelectionActionButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import com.aicode.R

/**
 * MCP 二级页：与提供商/默认模型一致的 iOS 分组列表。
 * 白色分组卡片内每台 server 一行，两行布局（名称+状态 / 类型+摘要），支持左滑删除。
 *
 * 顶栏右上角「多选」进入批量模式（[selection] 由 SettingsScreen 持有）：可批量启用 / 停用 / 重连 / 删除。
 * 多选模式下全选只作用于**当前搜索筛选结果**，与列表里看到的条目一致。
 */
@Composable
internal fun McpSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    reloading: Boolean,
    multiLlmConfigured: Boolean,
    multiLlmEnv: SettingsViewModel.MultiLlmEnv,
    multiLlmInstalling: Boolean,
    multiLlmMessage: String?,
    selection: ListSelectionState,
    onInstallMultiLlm: () -> Unit,
    onAttachMultiLlm: () -> Unit,
    onReload: () -> Unit,
    onToggle: (String, Boolean, McpScope) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    onDelete: (String, McpScope) -> Unit,
    onBatchDelete: (List<Pair<String, McpScope>>) -> Unit,
    onBatchSetEnabled: (List<Pair<String, McpScope>>, Boolean) -> Unit,
    onBatchReconnect: (List<String>) -> Unit
) {
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val showMultiLlmCard = !multiLlmConfigured && multiLlmEnv != SettingsViewModel.MultiLlmEnv.UNSUPPORTED
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (showMultiLlmCard) {
                MultiLlmCard(multiLlmEnv, multiLlmInstalling, multiLlmMessage, onInstallMultiLlm, onAttachMultiLlm)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            FeatherIcons.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.mcp_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.mcp_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { entry ->
            entry.server.name.contains(searchQuery, ignoreCase = true) ||
                entry.server.url?.contains(searchQuery, ignoreCase = true) == true ||
                entry.server.command?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    // 多选 key 带作用域前缀：同名 server 可能同时存在于项目层与全局层。
    fun selectionKey(entry: McpServerEntry): String = "${entry.scope.name}:${entry.server.name}"
    val allKeys = remember(filteredEntries) { filteredEntries.map { selectionKey(it) } }
    val keyToEntry = remember(filteredEntries) { filteredEntries.associateBy { selectionKey(it) } }
    // 多选作用域跟搜索筛选：切换搜索词后，看不见的条目不该被批量操作误伤。
    val scaffold = rememberListSelection(allKeys, selection, filteredEntries.isEmpty()) { keyToEntry[it] }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isActive) {
            SelectionActionBar(
                selectedCount = selection.selectedCount,
                allSelected = scaffold.allSelected,
                onToggleSelectAll = { scaffold.toggleSelectAll() },
                onExit = { selection.exit() },
                actions = {
                    SelectionActionButton(
                        text = stringResource(R.string.common_enabled),
                        enabled = selection.selectedCount > 0,
                        onClick = {
                            onBatchSetEnabled(scaffold.selectedEntries.map { it.server.name to it.scope }, true)
                        }
                    )
                    SelectionActionButton(
                        text = stringResource(R.string.common_disabled),
                        enabled = selection.selectedCount > 0,
                        onClick = {
                            onBatchSetEnabled(scaffold.selectedEntries.map { it.server.name to it.scope }, false)
                        }
                    )
                    SelectionActionButton(
                        text = stringResource(R.string.mcp_batch_reconnect),
                        enabled = selection.selectedCount > 0,
                        onClick = { onBatchReconnect(scaffold.selectedEntries.map { it.server.name }) }
                    )
                    SelectionActionButton(
                        text = stringResource(R.string.common_delete),
                        enabled = selection.selectedCount > 0,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showBatchDeleteConfirm = true }
                    )
                }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (entries.size >= 3) {
                item(key = "search", contentType = "search") {
                    ModelSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.mcp_search_placeholder),
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
            }

            if (showMultiLlmCard) {
                item(key = "multillm-card", contentType = "card") {
                    MultiLlmCard(multiLlmEnv, multiLlmInstalling, multiLlmMessage, onInstallMultiLlm, onAttachMultiLlm)
                }
            }

            if (filteredEntries.isEmpty()) {
                item(key = "search-empty", contentType = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(
                                FeatherIcons.Search,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = stringResource(R.string.mcp_search_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredEntries,
                    key = { _, entry -> selectionKey(entry) },
                    contentType = { _, _ -> "mcp-row" }
                ) { index, entry ->
                    LazyGroupItem(index = index, lastIndex = filteredEntries.lastIndex) {
                        McpServerRow(
                            server = entry.server,
                            scope = entry.scope,
                            status = statuses.firstOrNull { it.name == entry.server.name },
                            onClick = { onEdit(entry) },
                            onDelete = { onDelete(entry.server.name, entry.scope) },
                            selectionMode = selection.isActive,
                            selected = selection.isSelected(selectionKey(entry)),
                            onToggleSelect = { selection.toggle(selectionKey(entry)) }
                        )
                    }
                }
            }
        }
    }

    if (showBatchDeleteConfirm) {
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.mcp_batch_delete_title),
            message = stringResource(R.string.mcp_batch_delete_confirm, scaffold.selectedEntries.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDelete(scaffold.selectedEntries.map { it.server.name to it.scope })
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}

/** 内置「模型顾问团」安装/接入卡片：列表里还没有 multi-llm 条目且本地容器模式时显示。 */
@Composable
private fun MultiLlmCard(
    env: SettingsViewModel.MultiLlmEnv,
    installing: Boolean,
    message: String?,
    onInstall: () -> Unit,
    onAttach: () -> Unit
) {
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.mcp_multillm_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.mcp_multillm_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            message?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { if (env == SettingsViewModel.MultiLlmEnv.READY) onAttach() else onInstall() },
                enabled = !installing,
                shape = RoundedCornerShape(10.dp)
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.mcp_multillm_installing))
                } else {
                    Text(
                        stringResource(
                            if (env == SettingsViewModel.MultiLlmEnv.READY) R.string.mcp_multillm_attach
                            else R.string.mcp_multillm_install
                        )
                    )
                }
            }
        }
    }
}

/**
 * 单个 MCP server 行：分组内白底行，图标 + 名称/状态 + 类型/摘要 + 右箭头，左滑删除。
 */
@Composable
internal fun McpServerRow(
    server: McpServerConfig,
    scope: McpScope,
    status: McpServerStatus?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val isConnected = server.enabled && status?.state == McpServerStatus.State.CONNECTED
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val statusText = when {
        !server.enabled -> stringResource(R.string.mcp_disabled)
        status == null -> stringResource(R.string.mcp_not_connected)
        else -> when (status.state) {
            McpServerStatus.State.CONNECTED -> stringResource(R.string.mcp_connected)
            McpServerStatus.State.CONNECTING -> stringResource(R.string.mcp_connecting)
            McpServerStatus.State.NEEDS_AUTH -> stringResource(R.string.mcp_needs_auth)
            McpServerStatus.State.FAILED -> stringResource(R.string.mcp_connection_failed)
            McpServerStatus.State.DISABLED -> stringResource(R.string.mcp_disabled)
        }
    }

    val statusColor = when {
        !server.enabled || status == null || status.state == McpServerStatus.State.DISABLED ->
            MaterialTheme.colorScheme.outline
        status.state == McpServerStatus.State.CONNECTED ->
            MaterialTheme.colorScheme.tertiary
        status.state == McpServerStatus.State.CONNECTING ->
            MaterialTheme.colorScheme.primary
        status.state == McpServerStatus.State.NEEDS_AUTH ->
            MaterialTheme.colorScheme.primary
        else ->
            MaterialTheme.colorScheme.error
    }

    val statusBgColor = statusColor.copy(alpha = 0.12f)

    val typeText = if (server.isStdio) stringResource(R.string.mcp_type_stdio) else "HTTP"
    // HTTP 未连接时不再展示完整地址：URL 很长，在列表里只能看到一截断的头，占位而无信息量。
    val infoText = when {
        isConnected -> stringResource(R.string.mcp_tools_count, status?.toolCount ?: 0)
        server.isStdio -> server.command.orEmpty().ifEmpty { "stdio" }
        else -> null
    }

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧容器图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    imageVector = FeatherIcons.Box,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称 / 类型 + 摘要
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McpPill(
                        text = if (scope == McpScope.PROJECT) stringResource(R.string.mcp_scope_project) else stringResource(R.string.mcp_scope_global),
                        textColor = if (scope == McpScope.PROJECT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = if (scope == McpScope.PROJECT) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    McpPill(
                        text = typeText,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    if (infoText != null) {
                        McpPill(
                            text = infoText,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 状态 pill 与右箭头垂直居中，与整行中心对齐
            McpPill(
                text = statusText,
                textColor = statusColor,
                backgroundColor = statusBgColor
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.mcp_details),
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 紧凑 pill 标签：胶囊背景 + 小字，用于状态/类型/摘要。 */
@Composable
internal fun McpPill(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}