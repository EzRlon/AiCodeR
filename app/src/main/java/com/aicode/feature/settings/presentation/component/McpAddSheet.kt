package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe

/**
 * 「推荐」区条目：Context7 远程 HTTP 服务器（免安装、免登录，提供最新库/框架文档检索）。
 * 默认不启用——与在线商店「安装 ≠ 启用」语义一致；点击后走手动添加流程并预填本配置。
 */
internal val RecommendedContext7Config: McpServerConfig = McpServerConfig(
    name = "context7",
    url = "https://mcp.context7.com/mcp",
    enabled = false
)

/** REA 工具白名单：从 207 个工具中只放行核心只读分析集（防 prompt 预算爆炸），二期可按需扩充。 */
internal val ReaEnabledTools: Set<String> = setOf(
    "binary_session", "open_binary", "close_binary", "binary_overview", "inspect_artifact",
    "inspect_android_package", "inspect_android_class", "inspect_android_method",
    "search_android_classes", "trace_android_references", "analyze_javascript_application",
    "identify_flutter_build", "list_strings", "search_strings", "find_xrefs_to_name",
    "analyze_function", "procedure_pseudo_code", "procedure_callers", "batch_decompile",
    "list_unknowns", "export_evidence_bundle"
)

/**
 * 「推荐」区条目：REA（Reverse Engineer Anything）逆向分析。
 * 本地 stdio（npx），依赖容器「逆向分析」环境（jadx 引擎路径经 env 传入）；
 * 预填带工具白名单与 enabled=true（保存即用，配合「零门槛」定位）。
 */
internal val RecommendedReaConfig: McpServerConfig = McpServerConfig(
    name = "rea",
    command = "npx",
    args = listOf("-y", "rea-agents@6.5.0", "mcp"),
    env = mapOf("REA_JADX_MCP_JAR" to "/opt/rea/jadx-headless-mcp-0.7.1-all.jar"),
    enabled = true,
    enabledTools = ReaEnabledTools
)

/**
 * 「添加 MCP 服务器」底部弹层：顶部选作用域（全局 / 当前项目）；
 * 下方「推荐」区（Context7 一键预填）+ 两种添加方式——手动填写、粘贴标准 mcpServers JSON 批量导入。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun McpAddSheet(
    scope: McpScope,
    onScopeChange: (McpScope) -> Unit,
    onManual: () -> Unit,
    onImportJson: () -> Unit,
    onSelectRea: () -> Unit,
    onSelectContext7: () -> Unit,
    onDismiss: () -> Unit
) {
    val scopes = listOf(McpScope.GLOBAL, McpScope.PROJECT)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.mcp_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.mcp_scope_label),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SegmentedTabs(
                    selected = scopes.indexOf(scope).coerceAtLeast(0),
                    labels = listOf(
                        stringResource(R.string.mcp_scope_global),
                        stringResource(R.string.mcp_scope_project)
                    ),
                    onSelect = { onScopeChange(scopes[it]) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(R.string.mcp_recommended_section),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingsGroup {
                    SettingsRow(
                        icon = FeatherIcons.Cpu,
                        title = stringResource(R.string.mcp_recommended_rea_title),
                        subtitle = stringResource(R.string.mcp_recommended_rea_desc),
                        onClick = onSelectRea
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.Globe,
                        title = "Context7",
                        subtitle = stringResource(R.string.mcp_recommended_context7_desc),
                        onClick = onSelectContext7
                    )
                }
                Text(
                    text = stringResource(R.string.mcp_recommended_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Edit3,
                    title = stringResource(R.string.mcp_add_manual),
                    subtitle = stringResource(R.string.mcp_add_manual_desc),
                    onClick = onManual
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.mcp_add_from_json),
                    subtitle = stringResource(R.string.mcp_add_from_json_desc),
                    onClick = onImportJson
                )
            }
        }
    }
}
