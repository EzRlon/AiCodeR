package com.aicode.feature.settings.presentation.component

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.util.ExternalFileActions
import com.aicode.feature.plugin.domain.ConflictKind
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.ReleaseConflict
import com.aicode.feature.plugin.presentation.PluginViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import java.io.File

/**
 * 插件详情：状态、元信息、免责、权限、提供的能力、占用文件。
 *
 * 不提供「直接编辑清单」的入口——清单是插件与宿主的契约，改它等于改插件本身，
 * 那份活交给文件编辑器；这里只读 + 开关。
 */
@Composable
internal fun PluginDetailSection(
    plugin: InstalledPlugin,
    viewModel: PluginViewModel
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val manifest = plugin.manifest
    val busy = state.busyId == plugin.id

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            SettingsGroupHeader(text = manifest.name)
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.plugin_detail_status),
                    subtitle = if (plugin.enabled) {
                        stringResource(R.string.plugin_detail_enabled)
                    } else {
                        stringResource(R.string.plugin_detail_disabled)
                    },
                    showChevron = false,
                    trailing = {
                        AppSwitch(
                            checked = plugin.enabled,
                            onCheckedChange = { enabled ->
                                // 未同意免责时点开：由这里顺带记下同意并启用（用户已看到下方免责全文）
                                if (enabled && plugin.state.consentAt == null) {
                                    viewModel.acceptConsent(plugin.id, manifest.version)
                                } else {
                                    viewModel.setEnabled(plugin.id, enabled)
                                }
                            },
                            enabled = !busy
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.plugin_detail_version),
                    subtitle = manifest.version,
                    showChevron = false
                )
                manifest.author?.takeIf { it.isNotBlank() }?.let { author ->
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.plugin_detail_author),
                        subtitle = author,
                        showChevron = false
                    )
                }
                manifest.license?.takeIf { it.isNotBlank() }?.let { license ->
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.plugin_detail_license),
                        subtitle = license,
                        showChevron = false
                    )
                }
                SettingsDivider()
                // 远程 SSH 模式下插件在服务器上，没有本地目录可打开；只展示容器路径，不给点击。
                val localDir = plugin.localDir
                SettingsRow(
                    icon = FeatherIcons.Folder,
                    title = stringResource(R.string.plugin_detail_open_dir),
                    subtitle = localDir?.absolutePath ?: plugin.dirPath,
                    onClick = localDir?.let { dir -> { ExternalFileActions.viewInFileManager(context, dir) } }
                )
            }
        }

        manifest.disclaimer?.takeIf { it.isNotBlank() }?.let { disclaimer ->
            item {
                SettingsGroupHeader(text = stringResource(R.string.plugin_detail_disclaimer))
                SettingsGroup {
                    Text(
                        text = disclaimer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg)
                    )
                }
            }
        }

        item {
            SettingsGroupHeader(text = stringResource(R.string.plugin_detail_permissions))
            SettingsGroup {
                if (manifest.permissions.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.plugin_detail_permissions_none),
                        showChevron = false
                    )
                } else {
                    manifest.permissions.forEachIndexed { index, permission ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(title = permission, showChevron = false)
                    }
                }
            }
        }

        item {
            SettingsGroupHeader(text = stringResource(R.string.plugin_detail_capabilities))
            SettingsGroup {
                val rows = capabilityLines(manifest.capabilities)
                if (rows.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.plugin_detail_capabilities_none),
                        showChevron = false
                    )
                } else {
                    rows.forEachIndexed { index, text ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(title = text, showChevron = false)
                    }
                }
            }
        }

        if (state.conflicts.isNotEmpty()) {
            item {
                SettingsGroupHeader(
                    text = stringResource(R.string.plugin_conflicts_title, state.conflicts.size)
                )
                SettingsGroup {
                    state.conflicts.forEachIndexed { index, conflict ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            title = conflictText(conflict),
                            showChevron = false
                        )
                    }
                }
            }
        }

        item {
            val owned = plugin.state.ownedFiles
            SettingsGroupHeader(text = stringResource(R.string.plugin_detail_owned_files, owned.size))
            SettingsGroup {
                if (owned.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.plugin_detail_owned_files_none),
                        showChevron = false
                    )
                } else {
                    owned.forEachIndexed { index, path ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(title = path, showChevron = false)
                    }
                }
            }
        }

        // 卸载：装与卸对称。卸载会先摘掉已注入的能力（MCP/钩子/释放的文件），再删插件目录。
        item {
            var confirmUninstall by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.plugin_uninstall),
                        subtitle = stringResource(R.string.plugin_uninstall_desc),
                        showChevron = false,
                        trailing = {
                            Text(
                                text = stringResource(R.string.plugin_uninstall),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .clickable(enabled = !busy) { confirmUninstall = true }
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                            )
                        }
                    )
                }
                if (confirmUninstall) {
                    AlertDialog(
                        onDismissRequest = { confirmUninstall = false },
                        title = { Text(stringResource(R.string.plugin_uninstall_confirm_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.plugin_uninstall_confirm_text,
                                    manifest.name
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmUninstall = false
                                viewModel.uninstall(plugin.id)
                            }) {
                                Text(
                                    text = stringResource(R.string.plugin_uninstall),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmUninstall = false }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    )
                }
            }
        }

        // 占用统计要走本地目录递归；远程模式没有本地目录，跳过这一行（避免在组合线程上做 SFTP 列举）。
        plugin.localDir?.let { dir ->
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = Formatter.formatShortFileSize(context, dirSize(dir)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl)
                    )
                }
            }
        }
    }
}

/** 跳过原因按类型取双语文案；[ReleaseConflict.detail] 只带路径/名称。 */
@Composable
private fun conflictText(conflict: ReleaseConflict): String {
    val res = when (conflict.kind) {
        ConflictKind.TARGET_EXISTS -> R.string.plugin_conflict_target_exists
        ConflictKind.SOURCE_MISSING -> R.string.plugin_conflict_source_missing
        ConflictKind.WRITE_FAILED -> R.string.plugin_conflict_write_failed
        ConflictKind.MCP_NAME_INVALID -> R.string.plugin_conflict_mcp_name_invalid
        ConflictKind.MCP_EXISTS -> R.string.plugin_conflict_mcp_exists
        ConflictKind.MCP_WRITE_FAILED -> R.string.plugin_conflict_mcp_write_failed
        ConflictKind.HOOK_EVENT_UNSUPPORTED -> R.string.plugin_conflict_hook_event_unsupported
        ConflictKind.HOOK_EXISTS -> R.string.plugin_conflict_hook_exists
        ConflictKind.PROVISION_SKIPPED -> R.string.plugin_conflict_provision_skipped
        ConflictKind.PROVISION_FAILED -> R.string.plugin_conflict_provision_failed
        ConflictKind.PROVISION_NO_SCRIPT -> R.string.plugin_conflict_provision_no_script
    }
    return stringResource(res, conflict.detail)
}

/** 把能力声明翻成「人话」的一行行摘要；没有声明的项不出现。 */
@Composable
private fun capabilityLines(capabilities: com.aicode.feature.plugin.domain.PluginCapabilities): List<String> =
    buildList {
        if (capabilities.assets.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_assets, capabilities.assets.size))
        }
        capabilities.provision?.let {
            add(stringResource(R.string.plugin_capability_provision, it.scenario))
        }
        if (capabilities.mcp.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_mcp, capabilities.mcp.size))
        }
        if (capabilities.hooks.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_hooks, capabilities.hooks.size))
        }
        if (capabilities.skills.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_skills, capabilities.skills.size))
        }
        if (capabilities.agents.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_agents, capabilities.agents.size))
        }
        if (capabilities.teams.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_teams, capabilities.teams.size))
        }
        if (capabilities.scripts.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_scripts, capabilities.scripts.size))
        }
        if (capabilities.prompts.isNotEmpty()) {
            add(stringResource(R.string.plugin_capability_prompts, capabilities.prompts.size))
        }
    }

/** 插件目录占用（浅层递归；插件目录都很小，不必走后台扫描器）。 */
private fun dirSize(dir: File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
