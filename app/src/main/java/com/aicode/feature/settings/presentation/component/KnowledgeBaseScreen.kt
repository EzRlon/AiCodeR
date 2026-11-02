package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import java.io.File

/**
 * 知识库管理页：管理 `<projectRoot>/.aicode/notes/` 下的项目领域文档。
 *
 * 与世界书不同：知识库条目是完整文档（决策记录/接口约定/踩坑笔记），注入提示词的只是
 * 「文件名 + 首个 `# ` 标题」的索引，正文由 AI 按需读取。因此这里不设触发词与启停，
 * 只有文件级的新建、编辑、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    projectRoot: String,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var docs by remember(projectRoot) { mutableStateOf(listDocs(projectRoot)) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    val dir = remember(projectRoot) { File(projectRoot.trimEnd('/') + KNOWLEDGE_DIR) }

    if (creating || editing != null) {
        KnowledgeEditor(
            projectRoot = projectRoot,
            original = editing,
            onDone = {
                docs = listDocs(projectRoot)
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
                title = { Text(stringResource(R.string.kb_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.kb_new))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (docs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.kb_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.kb_empty_hint, dir.absolutePath),
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
            items(docs, key = { it }) { name ->
                KnowledgeRow(
                    name = name,
                    onClick = { editing = name },
                    onDelete = {
                        File(dir, "$name.md").delete()
                        docs = listDocs(projectRoot)
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun KnowledgeRow(
    name: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            FeatherIcons.FileText,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) {
            Icon(FeatherIcons.Trash2, contentDescription = stringResource(R.string.common_delete))
        }
    }
}

/**
 * 文档编辑页。文件名在新建时必填；既有文档只读展示文件名，正文整篇编辑。
 * 首个 `# ` 标题会作为索引标题注入提示词。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeEditor(
    projectRoot: String,
    original: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var name by rememberSaveable { mutableStateOf(original.orEmpty()) }
    var content by rememberSaveable {
        mutableStateOf(
            original?.let {
                runCatching {
                    File(projectRoot.trimEnd('/') + KNOWLEDGE_DIR, "$it.md").readText()
                }.getOrDefault("")
            }.orEmpty()
        )
    }

    val canSave = name.isNotBlank() && content.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (original == null) stringResource(R.string.kb_new)
                        else stringResource(R.string.kb_edit)
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
                            val dir = File(projectRoot.trimEnd('/') + KNOWLEDGE_DIR)
                            dir.mkdirs()
                            if (original != null && original != name.trim()) {
                                File(dir, "$original.md").delete()
                            }
                            File(dir, "${name.trim()}.md").writeText(content)
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
                label = stringResource(R.string.kb_name),
                singleLine = true,
                enabled = original == null,
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = content,
                onValueChange = { content = it },
                label = stringResource(R.string.kb_content),
                singleLine = false,
                minLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.kb_title_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 知识库目录（相对项目根），与 [com.aicode.feature.agent.domain.prompt.SystemPromptProvider] 的注入目录一致。 */
private const val KNOWLEDGE_DIR = "/.aicode/notes"

private fun listDocs(projectRoot: String): List<String> {
    if (projectRoot.isBlank()) return emptyList()
    val dir = File(projectRoot.trimEnd('/') + KNOWLEDGE_DIR)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
        ?.map { it.name.removeSuffix(".md") }
        ?.sorted()
        ?: emptyList()
}
