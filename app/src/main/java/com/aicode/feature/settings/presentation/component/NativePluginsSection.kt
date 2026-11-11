package com.aicode.feature.settings.presentation.component

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.presentation.PluginViewModel

/**
 * 「原生插件」列表：扫 `~/.aicode/plugins` 下每个目录的 `plugin.json`。
 *
 * 与「引擎插件」是两个不同的东西：引擎插件是装进 Claude Code / Codex 等外部引擎的，
 * 这里的插件装进本 App 自己的技能 / MCP / 钩子体系。两者在设置里同属「插件」分组。
 */
@Composable
internal fun NativePluginsSection(
    viewModel: PluginViewModel,
    onOpenDetail: (id: String, name: String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 从文件安装：选一个插件包（zip）→ 后台读字节 → 交给 ViewModel 校验并落盘。
    // 读盘放 IO：包可能几十 MB，主线程读完会卡住整页。
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (payload == null || payload.isEmpty()) {
                Toast.makeText(context, R.string.plugin_install_bad_package, Toast.LENGTH_SHORT).show()
                return@launch
            }
            viewModel.installPackage(payload, displayName = queryDisplayName(context, uri))
        }
    }

    // 启用被拒/部分没装成这类一次性反馈：用 Toast，不占页面空间
    LaunchedEffect(state.messageRes, state.messageArgs) {
        val res = state.messageRes ?: return@LaunchedEffect
        val text = if (state.messageArgs.isEmpty()) {
            context.getString(res)
        } else {
            context.getString(res, *state.messageArgs.toTypedArray())
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    val onPickPackage = { installLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }

    if (state.plugins.isEmpty() && state.problems.isEmpty() && !state.loading) {
        PluginEmptyState(onInstall = onPickPackage)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        item(key = "install-from-file") {
            InstallFromFileRow(
                busy = state.busyId == PluginViewModel.INSTALL_BUSY_ID,
                onClick = onPickPackage
            )
        }

        items(state.plugins, key = { it.id }) { plugin ->
            PluginRow(
                plugin = plugin,
                busy = state.busyId == plugin.id,
                onOpen = { onOpenDetail(plugin.id, plugin.manifest.name) },
                onToggle = { enabled ->
                    if (enabled && plugin.state.consentAt == null) {
                        // 未同意免责：先去详情页读一遍，不在这里直接放行
                        onOpenDetail(plugin.id, plugin.manifest.name)
                    } else {
                        viewModel.setEnabled(plugin.id, enabled)
                    }
                }
            )
        }

        if (state.problems.isNotEmpty()) {
            item(key = "problems-header") {
                Text(
                    text = stringResource(R.string.plugin_problems_title, state.problems.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs)
                )
            }
            items(state.problems, key = { "problem-${it.dirName}" }) { problem ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.plugin_problem_dir, problem.dirName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    problem.errors.forEach { error ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "· $error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(end = Spacing.sm)
        ) {
            Text(
                text = plugin.manifest.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            val subtitle = plugin.manifest.description.ifBlank { plugin.manifest.version }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AppSwitch(
            checked = plugin.enabled,
            onCheckedChange = onToggle,
            enabled = !busy
        )
    }
}

@Composable
private fun PluginEmptyState(onInstall: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.xl)
                .clip(RoundedCornerShape(Radius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.native_plugins_empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.native_plugins_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            InstallFromFileRow(busy = false, onClick = onInstall)
        }
    }
}

/**
 * 「从文件安装」入口。
 *
 * 插件不再随安装包内置：用户拿到插件包（`.zip`）后从这里选文件安装。安装完默认**未启用**，
 * 仍需去详情页确认免责说明并打开开关。
 */
@Composable
private fun InstallFromFileRow(busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FeatherIcons.Plus,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (busy) R.string.plugin_installing else R.string.plugin_install_from_file
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.plugin_install_from_file_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 取 SAF 文档的显示名（没有就返回 null，交给清单里的 name）。 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()
