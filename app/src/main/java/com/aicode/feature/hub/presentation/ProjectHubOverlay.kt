package com.aicode.feature.hub.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Home
import compose.icons.feathericons.Inbox
import compose.icons.feathericons.Package
import compose.icons.feathericons.Tool
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.ui.floating.FloatingWindowScaffold
import com.aicode.core.ui.floating.FloatingWindowState
import com.aicode.core.ui.settings.settingsPageBackground
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HUB_TAB_COUNT = 5
private const val CI_POLL_INTERVAL_MS = 15_000L

/**
 * 项目中心浮窗：与记忆图谱同款「悬浮底栏 + 分页卡片」布局（FloatingTabBar + HorizontalPager）。
 * 外框可拖拽/缩放/最小化（FloatingWindowScaffold）；数据全部手机直连 GitHub API。
 */
@Composable
fun ProjectHubOverlay(viewModel: ProjectHubViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val windowState = remember(viewModel) { FloatingWindowState(viewModel.geometry) }
    val pagerState = rememberPagerState(initialPage = 0) { HUB_TAB_COUNT }

    LaunchedEffect(Unit) {
        if (viewModel.state.value.repo.isNotBlank() && viewModel.state.value.info == null) {
            viewModel.refresh()
        }
    }
    // CI 页可见且存在进行中的 run 时才轮询（15s；离开页面即停，纯手机流量）
    LaunchedEffect(pagerState.currentPage, state.runs) {
        if (pagerState.currentPage == 2 && state.runs.any { it.running }) {
            delay(CI_POLL_INTERVAL_MS)
            viewModel.refreshRuns(silent = true)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        when (pagerState.currentPage) {
            2 -> if (!state.runsLoaded) viewModel.refreshRuns()
            3 -> if (!state.releasesLoaded) viewModel.refreshReleases()
            4 -> if (!state.opsLoaded) viewModel.refreshOps()
        }
    }

    FloatingWindowScaffold(
        title = "项目中心",
        state = windowState,
        onClose = onClose,
        onGeometryCommitted = viewModel::updateGeometry,
        headerActions = {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 88.dp)) {
                    when (page) {
                        0 -> OverviewPage(viewModel, state)
                        1 -> BuildPage(viewModel, state)
                        2 -> CiPage(viewModel, state)
                        3 -> ReleasesPage(viewModel, state)
                        else -> OpsPage(viewModel, state)
                    }
                }
            }
            if (state.message.isNotBlank()) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = Spacing.md, vertical = 2.dp)
                )
            }
            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Home, "总览"),
                    FloatingTabItem(FeatherIcons.Tool, "构建"),
                    FloatingTabItem(FeatherIcons.Activity, "CI"),
                    FloatingTabItem(FeatherIcons.Package, "产物"),
                    FloatingTabItem(FeatherIcons.Inbox, "事务")
                ),
                maskColor = settingsPageBackground(),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 与记忆图谱 SessionLaneCard 同款的卡片泳道。 */
@Composable
private fun HubCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) { content() }
    }
}

@Composable
private fun OverviewPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    var repoInput by remember(state.repo) { mutableStateOf(state.repo) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
        }
        state.info?.let { info ->
            item {
                HubCard {
                    Text(info.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (info.description.isNotBlank()) {
                        Text(
                            info.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        "★ ${info.stars} · Issues ${info.openIssues} · 默认分支 ${info.defaultBranch}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        if (state.commits.isNotEmpty()) {
            item {
                Text(
                    "最近提交",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            items(state.commits) { c ->
                HubCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm)
                        )
                        Text(
                            c.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (state.readmeSnippet.isNotBlank()) {
            item {
                HubCard {
                    Text("README", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.readmeSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md)) {
        HubCard {
            Text("远端构建", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "触发 GitHub Actions · android-release.yml（默认分支）。进度在 CI 页查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Box(
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (state.repo.isBlank() || state.loading) {
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
        HubCard(modifier = Modifier.padding(top = Spacing.sm)) {
            Text("本地构建", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "在容器内直接执行 gradle 打包（不占 CI 额度）。执行通道下一批接入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun CiPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(state.runs) { run ->
            HubCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        run.title.take(28),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when {
                            run.running -> "进行中"
                            run.conclusion == "success" -> "成功"
                            run.conclusion == "failure" -> "失败"
                            else -> run.conclusion ?: run.status
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            run.running -> MaterialTheme.colorScheme.tertiary
                            run.conclusion == "success" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                }
                Text(
                    "${run.branch} · ${formatTime(run.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 6.dp)) {
                    if (run.running) {
                        MiniAction("取消") { viewModel.cancelRun(run.id) }
                    } else if (run.conclusion == "failure") {
                        MiniAction("重跑失败任务") { viewModel.rerunFailed(run.id) }
                    }
                }
            }
        }
        if (state.runsLoaded && state.runs.isEmpty()) {
            item {
                Text(
                    "暂无 workflow 运行记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            MiniAction("刷新") { viewModel.refreshRuns() }
        }
    }
}

@Composable
private fun ReleasesPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(state.releases) { release ->
            HubCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        release.tag,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (release.prerelease) "预发布" else "正式",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (release.prerelease) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Text(
                    formatTime(release.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                release.assets.forEach { asset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { viewModel.downloadAsset(asset) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            asset.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${asset.sizeBytes / 1024 / 1024} MB ↓",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        if (state.releasesLoaded && state.releases.isEmpty()) {
            item {
                Text(
                    "暂无发行版",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            MiniAction("刷新") { viewModel.refreshReleases() }
        }
    }
    // 供未来「打开网页」入口复用（当前仅资产下载走 DownloadManager）
    @Suppress("UNUSED_EXPRESSION")
    context
}

@Composable
private fun OpsPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(state.ops) { item ->
            HubCard(modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.htmlUrl))) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.isPull) "PR" else "Issue",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.isPull) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "#${item.number} ${item.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm)
                    )
                    Text(
                        item.user,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.opsLoaded && state.ops.isEmpty()) {
            item {
                Text(
                    "没有打开的 Issue / PR",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            MiniAction("刷新") { viewModel.refreshOps() }
        }
    }
}

@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
