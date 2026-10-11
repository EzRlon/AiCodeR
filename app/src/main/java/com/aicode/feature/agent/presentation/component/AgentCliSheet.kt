package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.domain.cliagent.CliAgent
import com.aicode.feature.agent.presentation.AgentCliViewModel
import com.aicode.feature.settings.presentation.component.McpPill
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Code
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.ExternalLink

/**
 * AI 智能体（终端 CLI）管理弹层：展示 Claude Code / Codex / OpenCode 的安装状态，
 * 提供一键安装（后台终端标签跑 npm，进度可见）与一键启动（带起终端进入交互会话）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AgentCliSheet(
    onDismiss: () -> Unit,
    onLaunched: () -> Unit,
    viewModel: AgentCliViewModel = hiltViewModel()
) {
    val states by viewModel.states.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val sheetContentHeight = screenHeight * 0.85f

    // VM 作用域在聊天页（复开不重建）：每次打开都重检一次安装状态，免得用户在终端手动装好后面板仍显示未安装。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    error?.let { message ->
        androidx.compose.runtime.LaunchedEffect(message) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.agent_cli_action_failed, message),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            viewModel.consumeError()
        }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
                .height(sheetContentHeight)
        ) {
            Text(
                text = stringResource(R.string.agent_cli_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xs)
            )
            Text(
                text = stringResource(R.string.agent_cli_sheet_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)
            ) {
                items(states, key = { it.agent.id }) { state ->
                    AgentCliRow(
                        state = state,
                        onInstall = { viewModel.install(state.agent) },
                        onLaunch = { viewModel.launch(state.agent) { onLaunched() } }
                    )
                }
                item(key = "__hint__") {
                    Text(
                        text = stringResource(R.string.agent_cli_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.md)
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentCliRow(
    state: AgentCliViewModel.AgentCliState,
    onInstall: () -> Unit,
    onLaunch: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = agentIcon(state.agent.id),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.agent.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    when {
                        state.checking -> McpPill(
                            text = stringResource(R.string.agent_cli_checking),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        state.installing -> McpPill(
                            text = stringResource(R.string.agent_cli_installing),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                        state.version != null -> McpPill(
                            text = stringResource(R.string.agent_cli_installed, state.version),
                            textColor = MaterialTheme.semanticColors.success,
                            backgroundColor = MaterialTheme.semanticColors.successContainer
                        )
                        else -> McpPill(
                            text = stringResource(R.string.agent_cli_not_installed),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                if (state.version != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = agentLoginGuide(state.agent.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            when {
                state.installing -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                state.version != null -> {
                    TextButton(onClick = onInstall) {
                        Text(stringResource(R.string.agent_cli_reinstall))
                    }
                    Button(onClick = onLaunch) {
                        Text(stringResource(R.string.agent_cli_launch))
                    }
                }
                else -> {
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(state.agent.websiteUrl) } }
                    ) {
                        Icon(
                            FeatherIcons.ExternalLink,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.agent_cli_install))
                    }
                }
            }
        }
    }
}

private fun agentIcon(agentId: String): ImageVector = when (agentId) {
    "claude" -> FeatherIcons.Box
    "codex" -> FeatherIcons.Cpu
    else -> FeatherIcons.Code
}

@Composable
private fun agentLoginGuide(agentId: String): String = when (agentId) {
    "claude" -> stringResource(R.string.agent_cli_login_claude)
    "codex" -> stringResource(R.string.agent_cli_login_codex)
    else -> stringResource(R.string.agent_cli_login_opencode)
}
