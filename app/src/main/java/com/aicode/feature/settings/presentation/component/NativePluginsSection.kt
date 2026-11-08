package com.aicode.feature.settings.presentation.component

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    LaunchedEffect(Unit) { viewModel.refresh() }

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

    if (state.plugins.isEmpty() && state.problems.isEmpty() && !state.loading) {
        PluginEmptyState()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
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
private fun PluginEmptyState() {
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
}
