package com.aicode.feature.settings.presentation.component

import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.settingsPageBackground
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import compose.icons.feathericons.Check
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.memory.MemoryScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 记忆新建/编辑页：自带顶栏（返回 + 右上角保存），与 [PromptEditorScreen] 一致，
 * 不能嵌进设置页的 Scaffold。
 *
 * 名称即身份（对应记忆文件名），既有记忆不允许改名，否则会另存成一条新记忆。
 * 作用域在新建时可选（项目 / 全局），编辑既有记忆时固定为原作用域。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryEditorScreen(
    isNew: Boolean,
    initialName: String,
    initialDescription: String,
    initialContent: String,
    initialScope: MemoryScope,
    hasWorkspace: Boolean,
    onSave: (name: String, description: String, content: String, scope: MemoryScope) -> Unit,
    onNavigateBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var content by rememberSaveable { mutableStateOf(initialContent) }
    var scopeIsProject by rememberSaveable { mutableStateOf(initialScope == MemoryScope.PROJECT) }

    val canSave = name.isNotBlank()

    BackHandler { onNavigateBack() }

    Scaffold(
        containerColor = settingsPageBackground(),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.memory_editor_new else R.string.memory_editor_edit
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                name.trim(),
                                description.trim(),
                                content,
                                if (scopeIsProject) MemoryScope.PROJECT else MemoryScope.GLOBAL
                            )
                        }
                    ) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = stringResource(R.string.common_save),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.memory_field_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.memory_field_name),
                        placeholder = stringResource(R.string.memory_field_name_placeholder),
                        singleLine = true,
                        readOnly = !isNew,
                        enabled = isNew
                    )
                    if (isNew) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Text(
                                text = stringResource(R.string.subagent_editor_scope),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = scopeIsProject,
                                enabled = hasWorkspace,
                                onClick = { scopeIsProject = true },
                                label = { Text(stringResource(R.string.subagent_scope_project)) }
                            )
                            FilterChip(
                                selected = !scopeIsProject,
                                onClick = { scopeIsProject = false },
                                label = { Text(stringResource(R.string.subagent_scope_global)) }
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.memory_scope_locked_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.memory_field_description))
            SettingsGroup {
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(R.string.memory_field_description_placeholder),
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.memory_field_content))
            SettingsGroup {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = stringResource(R.string.memory_field_content_placeholder),
                    singleLine = false,
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }
        }
    }
}
