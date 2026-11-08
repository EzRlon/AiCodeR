package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.core.ui.AppSwitch
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.prompt.LoreEntry
import com.aicode.feature.agent.domain.prompt.LorebookStore
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import java.io.File

/**
 * 世界书管理页：列出 `<projectRoot>/.aicode/lorebook/` 下的条目，支持新建、编辑、启停、删除。
 *
 * 条目以文件为唯一存储，编辑即改写对应 `.md`。名称即文件名，既有条目不允许改名
 * （与记忆一致，改名会变成另一条）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookScreen(
    projectRoot: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var entries by remember(projectRoot) { mutableStateOf(LorebookStore.load(projectRoot)) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val dir = remember(projectRoot) { File(projectRoot.trimEnd('/') + LorebookStore.DIR) }

    if (creating || editing != null) {
        LoreEditor(
            projectRoot = projectRoot,
            original = editing?.let { name -> entries.firstOrNull { it.name == name } },
            onDone = {
                entries = LorebookStore.load(projectRoot)
                creating = false
                editing = null
            },
            onBack = {
                creating = false
                editing = null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lorebook_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.lorebook_new))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.lorebook_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.lorebook_empty_hint, dir.absolutePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            items(entries, key = { it.name }) { entry ->
                LoreRow(
                    entry = entry,
                    onClick = { editing = entry.name },
                    onToggle = { enabled ->
                        LorebookStore.save(projectRoot, entry.copy(enabled = enabled))
                        entries = LorebookStore.load(projectRoot)
                    },
                    onDelete = {
                        LorebookStore.delete(projectRoot, entry.name)
                        entries = LorebookStore.load(projectRoot)
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LoreRow(
    entry: LoreEntry,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.xs),
        ) {
            TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
            }
            val trigger = if (entry.keywords.isEmpty()) {
                stringResource(R.string.lorebook_always_on)
            } else {
                entry.keywords.joinToString("、")
            }
            Text(
                text = trigger,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSwitch(checked = entry.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(FeatherIcons.Trash2, contentDescription = stringResource(R.string.common_delete))
        }
    }
}

/**
 * 条目编辑页。名称在新建时必填且决定文件名；既有条目只读展示名称。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoreEditor(
    projectRoot: String,
    original: LoreEntry?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var keywords by rememberSaveable { mutableStateOf(original?.keywords?.joinToString(", ").orEmpty()) }
    var content by rememberSaveable { mutableStateOf(original?.content.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf((original?.priority ?: 0).toString()) }
    var enabled by rememberSaveable { mutableStateOf(original?.enabled ?: true) }

    val canSave = name.isNotBlank() && content.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (original == null) stringResource(R.string.lorebook_new)
                        else stringResource(R.string.lorebook_edit)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val entry = LoreEntry(
                                name = name.trim(),
                                content = content.trim(),
                                keywords = keywords.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                                enabled = enabled,
                                priority = priority.toIntOrNull() ?: 0,
                            )
                            LorebookStore.save(projectRoot, entry, renameFrom = original?.name)
                            onDone()
                        },
                        enabled = canSave,
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.lorebook_name),
                singleLine = true,
                enabled = original == null,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = stringResource(R.string.lorebook_keywords),
                supportingText = { Text(stringResource(R.string.lorebook_keywords_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = priority,
                onValueChange = { priority = it.filter { c -> c.isDigit() || c == '-' } },
                label = stringResource(R.string.lorebook_priority),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.lorebook_enabled), style = MaterialTheme.typography.bodyMedium)
                AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }
            AppTextField(
                value = content,
                onValueChange = { content = it },
                label = stringResource(R.string.lorebook_content),
                singleLine = false,
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
