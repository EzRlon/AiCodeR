package com.aicode.feature.settings.presentation.component

import com.aicode.core.ui.settings.SettingsGroup
import com.aicode.core.ui.settings.SettingsGroupHeader
import com.aicode.core.ui.settings.SettingsRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Command
import compose.icons.feathericons.Package

/**
 * 「插件」分组首页：把两类插件分开摆，避免用户在同一张列表里混淆。
 *
 * - **原生插件**：装进本 App 自己的能力（技能 / MCP / 钩子），卸载即从本 App 摘除。
 * - **引擎插件**：装进 Claude Code / Codex / OpenCode 等外部引擎，由引擎自己的 CLI 管理。
 */
@Composable
internal fun PluginsOverviewSection(
    onOpenNative: () -> Unit,
    onOpenEngine: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            SettingsGroupHeader(text = stringResource(R.string.native_plugins))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = stringResource(R.string.native_plugins),
                    subtitle = stringResource(R.string.native_plugins_subtitle),
                    onClick = onOpenNative
                )
            }
        }
        item {
            SettingsGroupHeader(text = stringResource(R.string.settings_engine_plugins))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Command,
                    title = stringResource(R.string.settings_engine_plugins),
                    subtitle = stringResource(R.string.engine_plugins_empty_desc),
                    onClick = onOpenEngine
                )
            }
        }
    }
}
