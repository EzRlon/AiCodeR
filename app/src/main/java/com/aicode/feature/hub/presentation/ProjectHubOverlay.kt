package com.aicode.feature.hub.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Home
import compose.icons.feathericons.Inbox
import compose.icons.feathericons.Package
import compose.icons.feathericons.Tool
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.floating.FloatingWindowScaffold
import com.aicode.core.ui.floating.FloatingWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HUB_TAB_COUNT = 5
private const val CI_POLL_INTERVAL_MS = 15_000L

private data class HubTab(val icon: ImageVector, val label: String)

private val HUB_TABS = listOf(
    HubTab(FeatherIcons.Home, "总览"),
    HubTab(FeatherIcons.Tool, "构建"),
    HubTab(FeatherIcons.Activity, "CI"),
    HubTab(FeatherIcons.Package, "产物"),
    HubTab(FeatherIcons.Inbox, "事务")
)

/**
 * 项目中心浮窗：仓库自动识别（当前工作区 .git/config），下拉刷新与记忆图谱同交互；
 * 底栏为窗口宽度自适应的紧凑版（任意缩放都完整显示五项）。
 */
@Composable
fun ProjectHubOverlay(viewModel: ProjectHubViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val windowState = rememberWindowState(viewModel)
    val pagerState = rememberPagerState(initialPage = 0) { HUB_TAB_COUNT }

    // 浮窗打开：自动识别当前工作区仓库并加载各页（幂等，已加载则跳过）
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    // CI 页可见且存在进行中 run 时才轮询（15s；离开即停，纯手机流量）
    LaunchedEffect(pagerState.currentPage, state.runs) {
        if (pagerState.currentPage == 2 && state.runs.any { it.running }) {
            delay(CI_POLL_INTERVAL_MS)
            viewModel.refreshRuns(silent = true)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        when (pagerState.currentPage) {
            2 -> if (!state.runsLoaded || state.runs.isEmpty()) viewModel.refreshRuns()
            3 -> if (!state.releasesLoaded || state.releases.isEmpty()) viewModel.refreshReleases()
            4 -> if (!state.opsLoaded || state.ops.isEmpty()) viewModel.refreshOps()
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
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 64.dp)) {
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = Spacing.md, vertical = 2.dp)
                )
            }
            HubCompactTabBar(
                pagerState = pagerState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun rememberWindowState(viewModel: ProjectHubViewModel): FloatingWindowState =
    androidx.compose.runtime.remember(viewModel) { FloatingWindowState(viewModel.geometry) }

/** 窗口宽度自适应的紧凑底栏：5 项恒完整显示，窄窗缩图标/文字，宽窗放大。 */
@Composable
private fun HubCompactTabBar(
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val itemWidth = (maxWidth / HUB_TAB_COUNT).coerceIn(48.dp, 76.dp)
        val iconSize = if (maxWidth < 300.dp) 18.dp else 20.dp
        val fontSize = if (maxWidth < 300.dp) 9.sp else 10.sp
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .padding(horizontal = Spacing.xs, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HUB_TABS.forEachIndexed { index, tab ->
                val selected = pagerState.currentPage == index
                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(iconSize),
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        tab.label,
                        fontSize = fontSize,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HubPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun HubSecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun HubCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) { content() }
    }
}

/** 未识别到仓库时的空态引导卡：任何页面都不应「纯白」。 */
@Composable
private fun NotConnectedCard() {
    HubCard {
        Text("未识别到仓库", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "项目中心会自动读取当前工作区的 GitHub 远端；若刚克隆/切换了工作区，下拉刷新重试。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun HubPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

@Composable
private fun OverviewPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    HubPullRefresh(state.quietRefreshing, viewModel::pullRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            state.info?.let { info ->
                item {
                    HubCard {
                        Text(info.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (info.description.isNotBlank()) {
                            Text(
                                info.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (info.defaultBranch != "-") {
                            Text(
                                "默认分支 ${info.defaultBranch}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
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
            if (state.detected && state.info == null) {
                item {
                    HubCard {
                        Text("未识别到仓库", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "当前工作区没有可用的 GitHub 远端；克隆或关联 GitHub 仓库后回到此页下拉刷新。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    HubPullRefresh(state.quietRefreshing, viewModel::pullRefresh) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md)) {
            HubCard {
                Text("构建方式", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    ModeChip(
                        label = "远端 CI",
                        selected = state.buildMode == ProjectHubViewModel.BuildMode.REMOTE
                    ) { viewModel.setBuildMode(ProjectHubViewModel.BuildMode.REMOTE, false) }
                    ModeChip(
                        label = "本地",
                        selected = state.buildMode == ProjectHubViewModel.BuildMode.LOCAL
                    ) { viewModel.setBuildMode(ProjectHubViewModel.BuildMode.LOCAL, false) }
                }
                Row(
                    modifier = Modifier.padding(top = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "设为默认构建方式（下次打开默认选中）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    HubSecondaryButton(if (state.buildModeIsDefault) "已是默认" else "设为默认") {
                        viewModel.setBuildMode(state.buildMode, true)
                    }
                }
                Box(modifier = Modifier.padding(top = Spacing.md)) {
                    HubPrimaryButton(
                        label = when {
                            state.loading -> "处理中…"
                            state.buildMode == ProjectHubViewModel.BuildMode.REMOTE -> "触发远端构建"
                            else -> "开始本地构建"
                        },
                        enabled = state.info != null && !state.loading
                    ) { viewModel.startBuild() }
                }
                Text(
                    when (state.buildMode) {
                        ProjectHubViewModel.BuildMode.REMOTE ->
                            "触发 GitHub Actions · android-release.yml（默认分支）。进度在 CI 页查看。"
                        ProjectHubViewModel.BuildMode.LOCAL ->
                            "在容器内直接 gradle 打包。执行器下一批接入，现在请用远端 CI。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CiPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    HubPullRefresh(state.quietRefreshing, viewModel::pullRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state.info == null) {
                item { NotConnectedCard() }
            }
            items(state.runs) { run ->
                HubCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            run.title.take(28),
                            style = MaterialTheme.typography.labelLarge,
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
                    if (run.running || run.conclusion == "failure") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            if (run.running) {
                                HubSecondaryButton("取消") { viewModel.cancelRun(run.id) }
                            } else {
                                HubSecondaryButton("重跑失败任务") { viewModel.rerunFailed(run.id) }
                            }
                        }
                    }
                }
            }
            if (state.runsLoaded && state.runs.isEmpty()) {
                item {
                    HubCard(modifier = Modifier.clickable { viewModel.refreshRuns() }) {
                        Text("暂无运行记录", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "可能网络抖动或仓库尚无 CI 记录。点此重试，或下拉刷新。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleasesPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    HubPullRefresh(state.quietRefreshing, viewModel::pullRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state.info == null) {
                item { NotConnectedCard() }
            }
            val official = state.releases.filter { !it.prerelease }
            val pre = state.releases.filter { it.prerelease }
            if (official.isNotEmpty()) {
                item {
                    Text(
                        "正式发行",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(official) { release -> ReleaseCard(viewModel, release) }
            }
            if (pre.isNotEmpty()) {
                item {
                    Text(
                        "预发行（RC）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
                items(pre) { release -> ReleaseCard(viewModel, release) }
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
        }
    }
}

@Composable
private fun ReleaseCard(viewModel: ProjectHubViewModel, release: ProjectHubViewModel.ReleaseItem) {
    HubCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                release.tag,
                style = MaterialTheme.typography.labelLarge,
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

@Composable
private fun OpsPage(viewModel: ProjectHubViewModel, state: ProjectHubViewModel.UiState) {
    val context = LocalContext.current
    HubPullRefresh(state.quietRefreshing, viewModel::pullRefresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state.info == null) {
                item { NotConnectedCard() }
            }
            items(state.ops) { item ->
                HubCard(modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.htmlUrl))) }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (item.isPull) "PR" else "Issue",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.isPull) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "#${item.number} ${item.title}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm)
                        )
                        Text(
                            if (item.isOpen) item.user else "${item.user} · 已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state.opsLoaded && state.ops.isEmpty()) {
                item {
                    HubCard(modifier = Modifier.clickable { viewModel.refreshOps() }) {
                        Text("暂无 Issue / PR", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "点此重试，或下拉刷新。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
