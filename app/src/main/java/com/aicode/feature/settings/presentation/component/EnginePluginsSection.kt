package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.store.data.PluginStoreItem
import com.aicode.feature.store.presentation.StoreViewModel

/**
 * 「引擎插件」管理页：按引擎列出已安装的插件，可逐个卸载。
 *
 * 已安装判断与卸载动作都直接复用商店那一套（`StoreViewModel.loadPlugins` / `uninstallPlugin`）——
 * 两处各自判断「装没装」是这类页面最容易出的错，这里刻意不重造判断逻辑。
 */
@Composable
internal fun EnginePluginsSection(viewModel: StoreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingUninstall by remember { mutableStateOf<Pair<PluginStoreItem, EngineTarget>?>(null) }

    LaunchedEffect(Unit) { viewModel.loadPlugins() }

    val installedByEngine = remember(state.plugins, state.installedPluginEngines) {
        EngineTarget.entries.associateWith { engine ->
            state.plugins.filter { item ->
                engine in item.engines && state.installedPluginEngines[item.id].orEmpty().contains(engine)
            }
        }
    }

    if (installedByEngine.values.all { it.isEmpty() }) {
        EmptyState()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        EngineTarget.entries.forEach { engine ->
            val installed = installedByEngine[engine].orEmpty()
            if (installed.isEmpty()) return@forEach

            item(key = "header-${engine.name}") {
                Text(
                    text = engine.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs)
                )
            }
            items(installed, key = { "${engine.name}-${it.id}" }) { item ->
                InstalledPluginRow(
                    item = item,
                    busy = state.installingId == item.id,
                    onUninstall = { pendingUninstall = item to engine }
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.engine_plugins_more_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xl)
            )
        }
    }

    pendingUninstall?.let { (item, engine) ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.engine_plugins_uninstall_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.engine_plugins_uninstall_message,
                        engine.label,
                        item.name
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.uninstallPlugin(item, engine)
                        pendingUninstall = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.engine_plugins_uninstall),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun InstalledPluginRow(
    item: PluginStoreItem,
    busy: Boolean,
    onUninstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.version.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onUninstall, enabled = !busy) {
            Text(
                text = stringResource(R.string.engine_plugins_uninstall),
                color = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun EmptyState() {
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
                text = stringResource(R.string.engine_plugins_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.engine_plugins_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
