package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Code
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.PieChart
import compose.icons.feathericons.Server
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Zap

/**
 * 开发环境一键搭建（场景目录）：用户选「想开发什么」，确认后新建会话并自动发入
 * 搭建任务，由 AI 在当前执行环境（本地容器 apk / 远程发行版包管理器）安装工具链并验证。
 *
 * 目录是静态数据：新增场景只需加一条 [DevEnvScenario] 与对应字符串资源，无需改逻辑。
 */
internal data class DevEnvScenario(
    val id: String,
    val icon: ImageVector,
    val nameRes: Int,
    val descRes: Int,
    /** 发给 AI 的完整搭建任务提示词。 */
    val promptRes: Int
)

/** 场景目录。顺序即展示顺序，按开发形态分组（group 是组标题资源）。 */
private data class DevEnvGroup(val titleRes: Int, val scenarios: List<DevEnvScenario>)

private val devEnvGroups: List<DevEnvGroup> = listOf(
    DevEnvGroup(
        R.string.dev_env_group_web,
        listOf(
            DevEnvScenario(
                id = "static-web",
                icon = FeatherIcons.Globe,
                nameRes = R.string.dev_env_static_web,
                descRes = R.string.dev_env_static_web_desc,
                promptRes = R.string.dev_env_static_web_prompt
            ),
            DevEnvScenario(
                id = "frontend",
                icon = FeatherIcons.Code,
                nameRes = R.string.dev_env_frontend,
                descRes = R.string.dev_env_frontend_desc,
                promptRes = R.string.dev_env_frontend_prompt
            ),
            DevEnvScenario(
                id = "android",
                icon = FeatherIcons.Smartphone,
                nameRes = R.string.dev_env_android,
                descRes = R.string.dev_env_android_desc,
                promptRes = R.string.dev_env_android_prompt
            )
        )
    ),
    DevEnvGroup(
        R.string.dev_env_group_backend,
        listOf(
            DevEnvScenario(
                id = "python",
                icon = FeatherIcons.Terminal,
                nameRes = R.string.dev_env_python,
                descRes = R.string.dev_env_python_desc,
                promptRes = R.string.dev_env_python_prompt
            ),
            DevEnvScenario(
                id = "node",
                icon = FeatherIcons.Server,
                nameRes = R.string.dev_env_node,
                descRes = R.string.dev_env_node_desc,
                promptRes = R.string.dev_env_node_prompt
            ),
            DevEnvScenario(
                id = "java",
                icon = FeatherIcons.Box,
                nameRes = R.string.dev_env_java,
                descRes = R.string.dev_env_java_desc,
                promptRes = R.string.dev_env_java_prompt
            ),
            DevEnvScenario(
                id = "go",
                icon = FeatherIcons.Zap,
                nameRes = R.string.dev_env_go,
                descRes = R.string.dev_env_go_desc,
                promptRes = R.string.dev_env_go_prompt
            ),
            DevEnvScenario(
                id = "rust",
                icon = FeatherIcons.Cpu,
                nameRes = R.string.dev_env_rust,
                descRes = R.string.dev_env_rust_desc,
                promptRes = R.string.dev_env_rust_prompt
            ),
            DevEnvScenario(
                id = "cpp",
                icon = FeatherIcons.HardDrive,
                nameRes = R.string.dev_env_cpp,
                descRes = R.string.dev_env_cpp_desc,
                promptRes = R.string.dev_env_cpp_prompt
            )
        )
    ),
    DevEnvGroup(
        R.string.dev_env_group_data,
        listOf(
            DevEnvScenario(
                id = "datasci",
                icon = FeatherIcons.PieChart,
                nameRes = R.string.dev_env_datasci,
                descRes = R.string.dev_env_datasci_desc,
                promptRes = R.string.dev_env_datasci_prompt
            )
        )
    )
)

@Composable
internal fun DevEnvironmentsSection(
    onLaunch: (title: String, prompt: String) -> Unit
) {
    var pendingScenario by remember { mutableStateOf<DevEnvScenario?>(null) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "intro") {
                Surface(
                    shape = RoundedCornerShape(Radius.mdLarge),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.Tool,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.dev_env_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            devEnvGroups.forEach { group ->
                item(key = "group-${group.titleRes}") {
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
                items(group.scenarios, key = { it.id }) { scenario ->
                    DevEnvCard(scenario = scenario, onClick = { pendingScenario = scenario })
                }
            }
        }
    }

    pendingScenario?.let { scenario ->
        val scenarioTitle = stringResource(scenario.nameRes)
        val scenarioPrompt = stringResource(scenario.promptRes)
        AlertDialog(
            onDismissRequest = { pendingScenario = null },
            title = { Text(stringResource(scenario.nameRes)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.dev_env_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(scenario.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingScenario = null
                    onLaunch(scenarioTitle, scenarioPrompt)
                }) {
                    Text(stringResource(R.string.dev_env_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingScenario = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun DevEnvCard(scenario: DevEnvScenario, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Icon(
                    scenario.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(scenario.nameRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(scenario.descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
