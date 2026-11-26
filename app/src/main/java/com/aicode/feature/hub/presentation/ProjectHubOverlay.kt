package com.aicode.feature.hub.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.floating.FloatingWindowScaffold
import com.aicode.core.ui.floating.FloatingWindowState

private val HUB_TABS = listOf("总览", "构建", "CI", "产物", "事务")

/**
 * 项目中心浮窗（第一批）：可拖拽/缩放/最小化的悬浮小程序。
 * 总览 = 仓库信息 + 最近提交 + README 摘要；构建 = 远端 workflow_dispatch 触发。
 * CI/产物/事务 为第二批接入位（保持结构，避免反复改布局）。
 */
@Composable
fun ProjectHubOverlay(viewModel: ProjectHubViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val windowState = remember(viewModel) { FloatingWindowState(viewModel.geometry) }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (viewModel.state.value.info == null) viewModel.refresh()
    }
    FloatingWindowScaffold(
        title = "项目中心",
        state = windowState,
        onClose = onClose,
        onGeometryCommitted = viewModel::updateGeometry
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 页签行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                HUB_TABS.forEachIndexed { index, label ->
                    val selected = tab == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                }
                            )
                            .clickable { tab = index }
                            .padding(horizontal = Spacing.sm, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            if (state.message.isNotBlank()) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = 2.dp)
                )
            }

            when (tab) {
                0 -> OverviewPage(viewModel)
                1 -> BuildPage(viewModel)
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${HUB_TABS[tab]} · 第二批接入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewPage(viewModel: ProjectHubViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var repoInput by remember(state.repo) { mutableStateOf(state.repo) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                singleLine = true,
                label = { Text("owner/repo", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .padding(start = Spacing.sm)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { viewModel.setRepo(repoInput) }
                    .padding(horizontal = Spacing.md, vertical = 10.dp)
            ) {
                Text("连接", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }

        state.info?.let { info ->
            Text(
                info.fullName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = Spacing.sm)
            )
            if (info.description.isNotBlank()) {
                Text(
                    info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "★ ${info.stars} · Issues ${info.openIssues} · 默认分支 ${info.defaultBranch}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (state.commits.isNotEmpty()) {
            Text(
                "最近提交",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs)
            )
            state.commits.forEach { c ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        c.sha,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        c.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.sm)
                    )
                    Text(
                        c.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.readmeSnippet.isNotBlank()) {
            Text(
                "README",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs)
            )
            Text(
                state.readmeSnippet,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BuildPage(viewModel: ProjectHubViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.md)) {
        Text(
            "构建方式",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = Spacing.md, vertical = 8.dp)
            ) {
                Text("远端 CI", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = Spacing.md, vertical = 8.dp)
            ) {
                Text("本地（第二批）", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "触发 GitHub Actions · android-release.yml（当前默认分支）。构建进度在 CI 页查看。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        Box(
            modifier = Modifier
                .padding(top = Spacing.md)
                .clip(RoundedCornerShape(Radius.sm))
                .background(
                    if (state.repo.isBlank()) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                .clickable(enabled = state.repo.isNotBlank() && !state.loading) { viewModel.dispatchBuild() }
                .padding(horizontal = Spacing.lg, vertical = 10.dp)
        ) {
            Text(
                if (state.loading) "触发中…" else "触发远端构建",
                color = if (state.repo.isBlank()) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
