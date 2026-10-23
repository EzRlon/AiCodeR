package com.aicode.feature.agent.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.aicode.core.ui.drawerWidth
import com.aicode.core.ui.pageEnter
import com.aicode.core.ui.pressScale
import com.aicode.core.ui.pageExit
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.feature.settings.presentation.component.ModelSearchField
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.ChatSearchHit
import com.aicode.feature.agent.presentation.ChatSearchState
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.agent.presentation.FileTreeRoot
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.ui.containerOsLogo
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Camera
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Crosshair
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Globe
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Search
import compose.icons.feathericons.Server
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import compose.icons.feathericons.Archive
import compose.icons.feathericons.File
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
import androidx.compose.ui.res.stringResource
import com.aicode.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** 抽屉标签项：图标 + 名称 + 功能说明（说明用于按住时的浮出提示）。 */
private data class DrawerTab(val icon: ImageVector, val label: String, val desc: String)

/** 索引标签宽度（同时决定浮出提示的横向偏移量）。验收反馈 F5：收窄一档，不占过大空间。 */
private val drawerTabWidth = 32.dp

/**
 * 标签列顶部下移量：与内容列表「第一条会话标题」水平对齐（验收反馈 2026-10-17）。
 * 估算（qa 校准）：搜索框 41 + 间距 8 + 分组头 44 + 行内标题中线 19.5 − 按钮半高 17（34dp 高按钮）≈ 95.5 → 96。
 */
private val drawerTabsTopPadding = 96.dp

/**
 * 产品册索引标签风竖排标签列：左直边贴面板右缘、右侧圆角的小标签，**整体位于面板外侧**（图1 定稿）。
 * **样式规则（勿破，2026-10-17 三次定稿）**：标签左直边贴面板右缘线、右半圆角朝外；
 * 垂直起点下移对齐会话条目（[drawerTabsTopPadding]）；以后新增的页签按钮只需加入 [tabs] 列表即可自动继承。
 * 按住时在右侧浮出「名称 + 功能说明」提示，松手即消失（渲染在 Popup 层，不被面板裁剪）。
 */
@Composable
private fun DrawerVerticalTabs(
    selected: Int,
    tabs: List<DrawerTab>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 沉浸配色（2026-10-17 验收反馈）：未选中与左侧栏底部卡片（SettingsGroup 的 cardSurface）同色，融进面板；
    // 选中用主题高亮色「亮起」，点击前后一眼可辨。
    val unselectedColor = MaterialTheme.semanticColors.cardSurface
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    // 提示统一浮在按钮右侧（用户规则：含以后新增的按钮）；宽度自适应到屏幕右缘，窄屏自动折行。
    val tooltipOffsetPx = with(LocalDensity.current) { (drawerTabWidth + 10.dp).roundToPx() }
    val tooltipMaxWidth = (LocalConfiguration.current.screenWidthDp - drawerWidth().value - drawerTabWidth.value - 10)
        .coerceIn(64f, 220f).dp
    // 形状定稿（图1）：左直边贴面板右缘、圆角只在右侧。
    val tabShape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)

    Column(
        modifier = modifier
            .width(drawerTabWidth)
            .padding(top = drawerTabsTopPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selected == index
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            Box {
                Surface(
                    modifier = Modifier
                        .pressScale(interactionSource, pressedScale = 0.92f, label = "drawer-tab-press")
                        .width(drawerTabWidth)
                        .height(34.dp)
                        .selectable(
                            selected = isSelected,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab
                        ) { onSelect(index) },
                    shape = tabShape,
                    color = if (isSelected) selectedColor else unselectedColor,
                    shadowElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(16.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (pressed) {
                    Popup(
                        alignment = Alignment.CenterStart,
                        offset = IntOffset(tooltipOffsetPx, 0)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = tooltipMaxWidth)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = tab.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 侧边栏内容：右缘「产品册索引标签」切换「会话」/「文件」（按住浮出功能提示），底部「设置」入口卡片。
 * Tab0 为根会话列表，带子代理的会话行可就地展开；Tab1 为当前工作区的文件树。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String> = emptySet(),
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onDeleteSessions: ((Set<String>) -> Unit)? = null,
    onRename: (ChatSession, String) -> Unit,
    onTogglePin: (ChatSession) -> Unit,
    onExport: (ChatSession) -> Unit,
    subSessionsByParent: Map<String, List<ChatSession>> = emptyMap(),
    browseState: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String? = null,
    fileOpPaths: Set<String> = emptySet(),
    clipboard: BrowseClipboard? = null,
    pasteConflict: Pair<String, String>? = null,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefreshBrowse: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onPasteOverwrite: () -> Unit,
    onCancelPasteOverwrite: () -> Unit,
    onClearClipboard: () -> Unit,
    onNavigateToBrowser: (() -> Unit)? = null,
    /** 标注中心入口：活标注/截屏标注/账本统一界面；null 则隐藏该入口。 */
    onNavigateToAnnotations: (() -> Unit)? = null,
    /** 交付物列表（左栏第三页）：当前工作区的会话附件（AI 交付的文件与安装包），时间倒序。 */
    deliverables: List<AIAgentViewModel.DeliverableItem> = emptyList(),
    deliverablesScanning: Boolean = false,
    onRefreshDeliverables: () -> Unit = {},
    /** 交付物操作菜单所需的文件访问（保存到手机/MT/分享等）；null 时行点击仅提示。 */
    deliverableFileAccess: com.aicode.feature.workspace.domain.FileAccessProvider? = null,
    onNavigateToRea: (() -> Unit)? = null,
    /** Web 预览入口：打开端口预览页；null 则隐藏该入口。 */
    onNavigateToPreview: (() -> Unit)? = null,
    /** 远程管理入口：与 Web 预览同行弹出选择；null 则隐藏该选项。 */
    onNavigateToRemoteManager: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit,
    /** 定位请求（容器路径）：非空时切到「文件」页签；展开由外部先行完成，收到后调 [onRevealConsumed] 消费。 */
    revealRequest: String? = null,
    onRevealConsumed: (() -> Unit)? = null,
    searchQuery: String,
    searchState: ChatSearchState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit,
    modifier: Modifier = Modifier
) {
    // tab 与展开状态进 saveable：大屏下侧栏收起后整棵子树会离开组合（见 MainActivity 的
    // SaveableStateProvider），用 remember 存会让每次回到聊天页都重置回「会话」页。
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showPreviewEntrySheet by remember { mutableStateOf(false) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedSessionIds = remember { mutableStateListOf<String>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    val listState = rememberLazyListState()

    // 外部定位请求（存储空间「在文件树中查看」）：切到「文件」页签后随即消费。
    LaunchedEffect(revealRequest) {
        if (revealRequest != null) {
            selectedTab = 1
            onRevealConsumed?.invoke()
        }
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedSessionIds.clear()
    }

    // 点击会话/重开侧边栏保持原滚动位置；仅当同一会话的最后回复时间变化（发消息/收到回复）时滚回顶部。
    var lastTouched by remember { mutableStateOf<Pair<String?, Long?>?>(null) }
    val currentUpdatedAt = sessions.firstOrNull { it.id == currentSessionId }?.updatedAt
    LaunchedEffect(currentSessionId, currentUpdatedAt) {
        val cur = currentSessionId to currentUpdatedAt
        val prev = lastTouched
        lastTouched = cur
        if (prev != null && prev.first == cur.first && prev.second != cur.second) {
            listState.scrollToItem(0)
        }
    }

    val imeInset = rememberImeBottomInset()
    val focusManager = LocalFocusManager.current
    // 根 Box 比面板多出「标签骑线悬出」的半宽：面板只画到 drawerWidth()，
    // 右侧多出的一条让「会话 / 文件」标签骑在面板右缘线上（中心对齐边线，图2 定稿）。
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(drawerWidth())
                .fillMaxHeight()
                .background(settingsPageBackground())
                // 沉浸：背景铺满全高（含状态栏后面），内容只避开系统栏；底部手势区也留白。
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = Spacing.lg, top = Spacing.sm)
                .padding(bottom = imeInset + Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                // 内容区右侧留出标签左半宽：标签骑在面板右缘线上后，左半压在面板内这条边距上，
                // 不遮列表文字（2026-10-17 二次验收：按钮改为骑线形态，中心对齐面板右缘线）。
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(end = Spacing.lg)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {

            // 聊天记录搜索框放在 Tab 栏正下方，仅「会话」Tab 可见。
                    if (selectedTab == 0 && isSelectionMode) {
                        Surface(
                            color = MaterialTheme.semanticColors.cardSurface,
                            shape = RoundedCornerShape(Radius.mdLarge),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        isSelectionMode = false
                                        selectedSessionIds.clear()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.X,
                                        contentDescription = stringResource(R.string.common_cancel),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    text = stringResource(R.string.chat_sessions_selected_count, selectedSessionIds.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                val allSessionIds: List<String> = remember(sessions, subSessionsByParent) {
                                    val ids = mutableListOf<String>()
                                    sessions.forEach { s ->
                                        ids.add(s.id)
                                        subSessionsByParent[s.id]?.forEach { sub -> ids.add(sub.id) }
                                    }
                                    ids
                                }
                                val allSelected = allSessionIds.isNotEmpty() && selectedSessionIds.size == allSessionIds.size
                                TextButton(onClick = {
                                    if (allSelected) {
                                        selectedSessionIds.clear()
                                    } else {
                                        selectedSessionIds.clear()
                                        selectedSessionIds.addAll(allSessionIds)
                                    }
                                }) {
                                    Text(stringResource(if (allSelected) R.string.provider_models_deselect_all else R.string.provider_models_select_all))
                                }
                                IconButton(
                                    onClick = { showBatchDeleteConfirm = true },
                                    enabled = selectedSessionIds.isNotEmpty(),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.Trash2,
                                        contentDescription = stringResource(R.string.chat_batch_delete_title),
                                        modifier = Modifier.size(18.dp),
                                        tint = if (selectedSessionIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    } else if (selectedTab == 0) {
                        ChatSearchField(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            onClear = onClearSearch
                        )
                    }

                    AnimatedContent(
                        targetState = selectedTab,
                        modifier = Modifier
                            .weight(1f)
                            // 按压列表/文件树即视为「注意力离开搜索」：清焦点使搜索框自动收起（不消耗事件）。
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        if (awaitPointerEvent().type == PointerEventType.Press) {
                                            focusManager.clearFocus()
                                        }
                                    }
                                }
                            },
                        transitionSpec = {
                            // 与全局页面切换动效同语言：切到「文件」从右来、切回「会话」从左来。
                            // sizeTransform 必须给 null，否则容器裁剪会留下拖影。
                            val forward = targetState > initialState
                            ContentTransform(pageEnter(forward), pageExit(forward), sizeTransform = null)
                        },
                        label = "drawer-tab-content"
                    ) { tab ->
                        when (tab) {
                            0 -> SessionListTab(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                agentStates = agentStates,
                                awaitingPermissionSessionIds = awaitingPermissionSessionIds,
                                subSessionsByParent = subSessionsByParent,
                                listState = listState,
                                searchQuery = searchQuery,
                                searchState = searchState,
                                selectionMode = isSelectionMode,
                                selectedSessionIds = selectedSessionIds,
                                onToggleSelectSession = { s ->
                                    if (s.id in selectedSessionIds) {
                                        selectedSessionIds.remove(s.id)
                                    } else {
                                        selectedSessionIds.add(s.id)
                                    }
                                },
                                onSelect = onSelect,
                                onLongClick = { menuSession = it },
                                onOpenSearchHit = onOpenSearchHit
                            )
                            1 -> FileBrowserTab(
                                state = browseState,
                                expandedPaths = expandedPaths,
                                expandingPath = expandingPath,
                                fileOpPaths = fileOpPaths,
                                clipboard = clipboard,
                                onToggleExpand = onToggleExpand,
                                onOpenFile = onOpenFile,
                                onRefresh = onRefreshBrowse,
                                onCreateFile = onCreateFile,
                                onCreateFolder = onCreateFolder,
                                onRenameEntry = onRenameEntry,
                                onDeleteEntry = onDeleteEntry,
                                onCopyEntry = onCopyEntry,
                                onCutEntry = onCutEntry,
                                onPasteEntry = onPasteEntry,
                                onClearClipboard = onClearClipboard
                            )
                            2 -> DeliverablesTab(
                                deliverables = deliverables,
                                scanning = deliverablesScanning,
                                onRefresh = onRefreshDeliverables,
                                fileAccess = deliverableFileAccess
                            )
                        }
                    }
                }
            }

            // 底部卡片与内容列表右缘对齐（同一右边距；功能图标仅随布局移位、不翻转）。
            SettingsGroup(modifier = Modifier.padding(end = Spacing.lg)) {
                if (onNavigateToBrowser != null) {
                    SettingsRow(
                        icon = FeatherIcons.Globe,
                        title = stringResource(R.string.browser_title),
                        onClick = onNavigateToBrowser,
                        compact = true
                    )
                    SettingsDivider()
                }
                if (onNavigateToRea != null) {
                    SettingsRow(
                        icon = FeatherIcons.Cpu,
                        title = stringResource(R.string.rea_entry_title),
                        onClick = onNavigateToRea,
                        compact = true
                    )
                    SettingsDivider()
                }
                if (onNavigateToPreview != null) {
                    SettingsRow(
                        icon = FeatherIcons.Monitor,
                        title = stringResource(R.string.preview_entry_title),
                        onClick = { showPreviewEntrySheet = true },
                        compact = true
                    )
                    SettingsDivider()
                }
                if (onNavigateToAnnotations != null) {
                    SettingsRow(
                        icon = FeatherIcons.Crosshair,
                        title = stringResource(R.string.annotation_hub_entry),
                        onClick = onNavigateToAnnotations,
                        compact = true
                    )
                    SettingsDivider()
                }
                SettingsRow(
                    icon = FeatherIcons.Settings,
                    title = stringResource(R.string.chat_settings),
                    onClick = onNavigateToSettings,
                    compact = true,
                    modifier = Modifier.onboardingTarget(OnboardingStep.ENTER_SETTINGS)
                )
            }
        }

        // 「会话 / 文件」标签列：左直边贴面板右缘、整体位于面板外侧（图1 定稿）。
        // 显式按「面板宽 + 标签宽」定宽（而非跟随容器宽度），保证按钮左缘永远对齐面板右缘。
        Box(
            modifier = Modifier
                .width(drawerWidth() + drawerTabWidth)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars)
                // 与面板内容同步下移：顶部沉浸留白（搜索图标与状态栏之间）
                .padding(top = Spacing.sm)
        ) {
            DrawerVerticalTabs(
                selected = selectedTab,
                tabs = listOf(
                    DrawerTab(
                        FeatherIcons.MessageSquare,
                        stringResource(R.string.subagent_tab_sessions),
                        stringResource(R.string.drawer_tab_sessions_desc)
                    ),
                    DrawerTab(
                        FeatherIcons.Folder,
                        stringResource(R.string.drawer_tab_files),
                        stringResource(R.string.drawer_tab_files_desc)
                    ),
                    DrawerTab(
                        FeatherIcons.Archive,
                        stringResource(R.string.drawer_tab_deliverables),
                        stringResource(R.string.drawer_tab_deliverables_desc)
                    )
                ),
                onSelect = { selectedTab = it },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }

    pasteConflict?.let { (_, targetPath) ->
        AlertDialog(
            onDismissRequest = onCancelPasteOverwrite,
            title = { Text(stringResource(R.string.file_browser_paste_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.file_browser_paste_conflict_message,
                        targetPath.substringAfterLast('/')
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onPasteOverwrite) { Text(stringResource(R.string.common_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelPasteOverwrite) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showPreviewEntrySheet) {
        val entrySheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showPreviewEntrySheet = false },
            sheetState = entrySheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .padding(bottom = Spacing.xl)
            ) {
                SheetActionRow(
                    icon = FeatherIcons.Monitor,
                    label = stringResource(R.string.preview_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        showPreviewEntrySheet = false
                        onNavigateToPreview?.invoke()
                    }
                )
                if (onNavigateToRemoteManager != null) {
                    SheetActionRow(
                        icon = FeatherIcons.Server,
                        label = stringResource(R.string.remote_mgr_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            showPreviewEntrySheet = false
                            onNavigateToRemoteManager?.invoke()
                        }
                    )
                }
            }
        }
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onTogglePin = {
                menuSession = null
                onTogglePin(session)
            },
            onRename = {
                menuSession = null
                pendingRename = session
            },
            onExport = {
                menuSession = null
                onExport(session)
            },
            onBatchSelect = {
                menuSession = null
                isSelectionMode = true
                selectedSessionIds.clear()
                selectedSessionIds.add(session.id)
            },
            onDelete = {
                menuSession = null
                pendingDelete = session
            },
            onDismiss = { menuSession = null }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_batch_delete_title)) },
            text = {
                Text(stringResource(R.string.chat_batch_delete_confirm, selectedSessionIds.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteConfirm = false
                    val toDelete = selectedSessionIds.toSet()
                    selectedSessionIds.clear()
                    isSelectionMode = false
                    if (onDeleteSessions != null) {
                        onDeleteSessions(toDelete)
                    } else {
                        toDelete.forEach { sid: String ->
                            sessions.firstOrNull { it.id == sid }?.let { onDelete(it) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                AppTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = stringResource(R.string.chat_session_name),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Tab0：会话列表。搜索词非空时列表区切换为跨会话搜索结果。
 */
@Composable
private fun SessionListTab(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    searchQuery: String,
    searchState: ChatSearchState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit
) {
    if (searchQuery.isBlank()) {
        SessionListContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            listState = listState,
            selectionMode = selectionMode,
            selectedSessionIds = selectedSessionIds,
            onToggleSelectSession = onToggleSelectSession,
            onSelect = onSelect,
            onLongClick = onLongClick
        )
    } else {
        ChatSearchResults(state = searchState, onOpenHit = onOpenSearchHit)
    }
}

/** Tab0 的会话列表本体（按最后回复时间分组）。带子代理的会话行尾有展开箭头，展开后在其下方缩进列出子代理。 */
@Composable
private fun SessionListContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.chat_no_sessions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }
        return
    }
    var expandedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    val groups = remember(sessions) {
        val now = System.currentTimeMillis()
        val pinned = sessions.filter { it.isPinned }
        val unpinned = sessions.filterNot { it.isPinned }
        buildList {
            if (pinned.isNotEmpty()) add(SessionGroup("pinned", pinned))
            addAll(buildSessionGroups(unpinned, now))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // 每个分组占一个 item、组内 forEach 全量渲染会让 LazyColumn 失去惰性：
        // 「最近 30 天」这种分组有上百个会话时，一个 item 就要组合上百行。分组头与会话各自成 item。
        groups.forEach { group ->
            item(key = "group-${group.groupKey}", contentType = "group-header") {
                SettingsGroupHeader(
                    text = sessionGroupLabel(group.groupKey, group.sessions.first())
                )
            }
            itemsIndexed(
                items = group.sessions,
                key = { _, session -> session.id },
                contentType = { _, _ -> "session" }
            ) { index, session ->
                Column {
                    if (index > 0) {
                        if (group.groupKey == "pinned") Spacer(Modifier.height(Spacing.sm)) else SettingsDivider()
                    }
                    val state = agentStates[session.id]
                    val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
                    val subSessions = subSessionsByParent[session.id].orEmpty()
                    val expanded = session.id in expandedIds
                    ChatSessionRow(
                        session = session,
                        selected = if (selectionMode) false else session.id == currentSessionId,
                        isExecuting = isExecuting,
                        awaitingPermission = session.id in awaitingPermissionSessionIds,
                        pinned = session.isPinned,
                        selectionMode = selectionMode,
                        checked = session.id in selectedSessionIds,
                        onCheckedChange = { onToggleSelectSession(session) },
                        onClick = {
                            if (selectionMode) {
                                onToggleSelectSession(session)
                            } else {
                                onSelect(session)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) {
                                onToggleSelectSession(session)
                            } else {
                                onLongClick(session)
                            }
                        },
                        trailing = if (subSessions.isEmpty()) null else {
                            {
                                SubAgentExpandToggle(
                                    expanded = expanded,
                                    count = subSessions.size,
                                    onToggle = {
                                        expandedIds = if (expanded) {
                                            expandedIds - session.id
                                        } else {
                                            expandedIds + session.id
                                        }
                                    }
                                )
                            }
                        }
                    )
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut()
                    ) {
                        Column {
                            subSessions.forEach { sub ->
                                val subState = agentStates[sub.id]
                                Row(modifier = Modifier.padding(start = Spacing.lg)) {
                                    ChatSessionRow(
                                        session = sub,
                                        selected = if (selectionMode) false else sub.id == currentSessionId,
                                        isExecuting = subState is AgentUIState.Loading ||
                                            subState is AgentUIState.Streaming,
                                        awaitingPermission = sub.id in awaitingPermissionSessionIds,
                                        pinned = false,
                                        selectionMode = selectionMode,
                                        checked = sub.id in selectedSessionIds,
                                        onCheckedChange = { onToggleSelectSession(sub) },
                                        onClick = {
                                            if (selectionMode) {
                                                onToggleSelectSession(sub)
                                            } else {
                                                onSelect(sub)
                                            }
                                        },
                                        onLongClick = {
                                            if (selectionMode) {
                                                onToggleSelectSession(sub)
                                            } else {
                                                onLongClick(sub)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab0 搜索入口：默认只显示一个搜索图标按钮；点击展开为输入框并自动聚焦，
 * 焦点离开（点列表、切页或收键盘）时自动收起回图标。
 * 搜索词非空时图标保持高亮，表示列表仍按关键词过滤。
 */
@Composable
private fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // 忽略展开瞬间的初始「未聚焦」回调，避免搜索框刚出现就立刻收起。
    var receivedFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val hasQuery = query.isNotEmpty()

    Crossfade(
        targetState = expanded,
        animationSpec = tween(durationMillis = 160),
        label = "drawer-search-fold"
    ) { open ->
        if (open) {
            ModelSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.chat_search_hint),
                modifier = Modifier.fillMaxWidth(),
                focusRequester = focusRequester,
                onFocusChanged = { state ->
                    if (state.isFocused) receivedFocus = true
                    else if (receivedFocus) expanded = false
                },
                trailing = if (query.isEmpty()) null else {
                    {
                        IconButton(
                            onClick = {
                                onClear()
                                focusRequester.requestFocus()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.X,
                                contentDescription = stringResource(R.string.chat_search_clear),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Surface(
                onClick = {
                    receivedFocus = false
                    expanded = true
                },
                shape = CircleShape,
                color = if (hasQuery) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.semanticColors.capsuleSurface,
                modifier = Modifier.size(41.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = FeatherIcons.Search,
                        contentDescription = stringResource(R.string.chat_search_hint),
                        modifier = Modifier.size(18.dp),
                        tint = if (hasQuery) MaterialTheme.colorScheme.primary
                        else MaterialTheme.semanticColors.subtleText
                    )
                }
            }
        }
    }
}

/** 搜索结果区：加载中 / 无结果 / 命中列表三态。 */
@Composable
private fun ChatSearchResults(
    state: ChatSearchState,
    onOpenHit: (ChatSearchHit) -> Unit
) {
    when {
        state.hits.isEmpty() && state.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.hits.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.chat_search_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.hits, key = { it.messageId }) { hit ->
                    ChatSearchResultRow(
                        hit = hit,
                        query = state.query,
                        onClick = { onOpenHit(hit) }
                    )
                    SettingsDivider()
                }
            }
        }
    }
}

/** 单条搜索结果行：会话标题 + 命中片段（关键词高亮），点击跳转并定位。 */
@Composable
private fun ChatSearchResultRow(
    hit: ChatSearchHit,
    query: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        Text(
            text = hit.sessionTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = highlightSnippet(
                snippet = hit.snippet,
                query = query,
                highlight = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 把片段中所有命中词标为高亮样式。 */
private fun highlightSnippet(snippet: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        if (query.isBlank()) return@buildAnnotatedString
        var start = 0
        while (true) {
            val index = snippet.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            addStyle(highlight, index, index + query.length)
            start = index + query.length
        }
    }

/** 会话行尾的子代理展开开关：显示数量与箭头，自己消费点击，不触发整行选中。 */
@Composable
private fun SubAgentExpandToggle(
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            contentDescription = stringResource(
                if (expanded) R.string.drawer_collapse_subagents else R.string.drawer_expand_subagents
            ),
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tab1：当前工作区的文件树。从工作区根就地展开，缩进表示层级；
 * 每行共享同一横向滚动，路径过深时左右滑动查看完整名称，不再截断成省略号。
 * 新建文件/文件夹通过长按目录（含工作区根）行的菜单发起。
 */
@Composable
private fun FileBrowserTab(
    state: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String?,
    fileOpPaths: Set<String>,
    clipboard: BrowseClipboard?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onClearClipboard: () -> Unit
) {
    var creating by remember { mutableStateOf<CreateTarget?>(null) }
    var menuNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingRename by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingDelete by remember { mutableStateOf<FileTreeNode?>(null) }
    val hScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is FileBrowseState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            is FileBrowseState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.detail ?: stringResource(R.string.file_browser_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }

            is FileBrowseState.Success -> {
                val listState = rememberLazyListState()
                val textMeasurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.bodyMedium
                val density = LocalDensity.current
                // 预算最宽一行的内容宽度（固定开销 + 缩进 + 文本），供所有行取统一宽度，
                // 以实现整棵树统一横向平移（而非每行各自滚动）。
                // 逐个 measure 在大目录下会在组合期同步跑上千次文本测量；先按「缩进 + 字符宽度权重」
                // 挑出最宽的那一行，只对它做一次真实测量。估算不精确，但这里只用来给横向滚动留够宽度。
                val maxContentPx = remember(state.nodes, textStyle) {
                    with(density) {
                        val widest = state.nodes.maxByOrNull { node ->
                            node.depth * 4 + node.displayName.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }
                        } ?: return@with 0
                        val textW = textMeasurer.measure(widest.displayName, textStyle).size.width
                        (FILE_TREE_ROW_OVERHEAD.toPx() + (Spacing.lg * widest.depth).toPx() + textW).toInt()
                    }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rowWidthPx = maxOf(constraints.maxWidth, maxContentPx)
                    val rowWidth = with(density) { rowWidthPx.toDp() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(hScroll)
                    ) {
                        items(state.nodes, key = { it.path }) { node ->
                            FileTreeRow(
                                node = node,
                                rowWidth = rowWidth,
                                expanding = node.entry.isDirectory && node.path == expandingPath,
                                busy = node.path in fileOpPaths,
                                onClick = {
                                    if (node.entry.isDirectory) onToggleExpand(node.path)
                                    else onOpenFile(node.path)
                                },
                                onLongClick = { menuNode = node },
                                onRefreshRoot = if (node.root != null) onRefresh else null,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    fadeOutSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                    placementSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                                )
                            )
                        }
                    }
                }
            }
        }

        // 右上角操作排：剪贴板指示器（有背景、有边框，与无背景的刷新图标区分）。
        // 刷新按钮已下放到两个根行右侧（项目空间 / 内置容器各自独立）。
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = Spacing.lg + Spacing.xs + 16.dp)
                .padding(end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            clipboard?.let { clip ->
                ClipboardIndicator(
                    clipboard = clip,
                    onClear = onClearClipboard,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
            }
            if (fileOpPaths.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = Spacing.xs).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    creating?.let { target ->
        FileNameInputDialog(
            title = stringResource(
                if (target.kind == CreateKind.FILE) R.string.file_browser_new_file
                else R.string.file_browser_new_folder
            ),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onConfirm = { name ->
                if (target.kind == CreateKind.FILE) onCreateFile(target.parent, name)
                else onCreateFolder(target.parent, name)
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    menuNode?.let { node ->
        FileTreeActionSheet(
            node = node,
            clipboard = clipboard,
            onNewFile = {
                menuNode = null
                if (node.root == null && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FILE)
            },
            onNewFolder = {
                menuNode = null
                if (node.root == null && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FOLDER)
            },
            onRename = {
                menuNode = null
                pendingRename = node
            },
            onDelete = {
                menuNode = null
                pendingDelete = node
            },
            onCopy = {
                menuNode = null
                onCopyEntry(node.path, node.entry.name)
            },
            onCut = {
                menuNode = null
                onCutEntry(node.path, node.entry.name)
            },
            onPasteHere = {
                val target = node.path
                menuNode = null
                onPasteEntry(target) { /* 结果由调用方决定是否提示，这里保持静默 */ }
            },
            onDismiss = { menuNode = null }
        )
    }

    pendingRename?.let { node ->
        FileNameInputDialog(
            title = stringResource(R.string.common_rename),
            confirmLabel = stringResource(R.string.common_rename),
            initialName = node.entry.name,
            onConfirm = { name ->
                onRenameEntry(node.path, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        if (node.entry.isDirectory) {
                            R.string.file_browser_delete_folder_confirm
                        } else {
                            R.string.file_browser_delete_file_confirm
                        },
                        node.entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(node.path)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 新建对象类型，决定确认后调创建文件还是创建文件夹。 */
private enum class CreateKind { FILE, FOLDER }

/** 新建目标：在哪个目录（[parent]）下新建，以及新建文件还是文件夹（[kind]）。 */
private data class CreateTarget(val parent: String, val kind: CreateKind)

/** 新建 / 重命名共用的名称输入弹窗：名称非法或与原名相同时禁用确认。 */
@Composable
private fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = stringResource(R.string.file_browser_name_label),
                modifier = Modifier.fillMaxWidth(),
                colors = dialogTextFieldColors()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 剪切板指示器：右上角刷新按钮旁的主题色图标，显示复制/剪切状态，点击清空。 */
@Composable
private fun ClipboardIndicator(
    clipboard: BrowseClipboard,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClear,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (clipboard.isCut) Icons.Filled.ContentCut else FeatherIcons.Copy,
            contentDescription = stringResource(
                if (clipboard.isCut) R.string.file_browser_clipboard_cut
                else R.string.file_browser_clipboard_copy
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** 文件树节点长按弹出的功能菜单：目录（含工作区根）可新建，非根节点可复制/剪切/重命名/删除，
 * 剪切板非空时目录可「粘贴到此处」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPasteHere: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            // 剪切板非空且当前节点是目录 / 根：优先提供「粘贴到此处」。
            if (clipboard != null && node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.Clipboard,
                    label = stringResource(R.string.file_browser_paste_here),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPasteHere
                )
            }
            if (node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.FilePlus,
                    label = stringResource(R.string.file_browser_new_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFile
                )
                SheetActionRow(
                    icon = FeatherIcons.FolderPlus,
                    label = stringResource(R.string.file_browser_new_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFolder
                )
            }
            if (node.root == null) {
                SheetActionRow(
                    icon = FeatherIcons.Copy,
                    label = stringResource(R.string.common_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCopy
                )
                SheetActionRow(
                    icon = Icons.Filled.ContentCut,
                    label = stringResource(R.string.common_cut),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCut
                )
                SheetActionRow(
                    icon = FeatherIcons.Edit2,
                    label = stringResource(R.string.common_rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRename
                )
                SheetActionRow(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

/** 文件树的一行：按 [FileTreeNode.depth] 缩进，目录带展开箭头。
 * 所有行取统一的 [rowWidth]（与外层 horizontalScroll 配合），横向滑动时整树一起平移，
 * 名称不截断、不换行。着色优先级：读取失败 > .gitignore 命中（橙）> dotfile（弱化）> 常规。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileTreeNode,
    rowWidth: Dp,
    expanding: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRefreshRoot: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 展开加载态加最小延迟：本地模式列目录极快，避免每次展开都闪一下转圈。
    var showExpanding by remember { mutableStateOf(false) }
    LaunchedEffect(expanding) {
        if (expanding) {
            delay(180)
            showExpanding = true
        } else {
            showExpanding = false
        }
    }
    val isDotfile = node.root == null && node.entry.name.startsWith(".")
    val decorationColor: Color? = when {
        node.hasError -> MaterialTheme.colorScheme.error
        node.ignored -> FileTreeIgnoredColor
        isDotfile -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> null
    }
    Row(
        modifier = modifier
            .width(rowWidth)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.xs + 2.dp)
            .padding(start = Spacing.sm, end = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(Spacing.lg * node.depth))
        if (node.entry.isDirectory) {
            if (showExpanding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                ExpandableChevronIcon(
                    expanded = node.isExpanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    contentDescription = stringResource(
                        if (node.isExpanded) R.string.file_browser_collapse else R.string.file_browser_expand
                    ),
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(Spacing.xs))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FileTreeIcon(node = node, decorationColor = decorationColor)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = decorationColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
        if (onRefreshRoot != null) {
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onRefreshRoot,
                modifier = Modifier
                    .size(32.dp)
                    .offset(x = 8.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.file_browser_refresh),
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 文件树行的类型图标：目录用文件夹，文件按扩展名挑选单色线性图标。容器根用「容器与镜像」列表同款图标。
 * [decorationColor] 非空时统一染色（.gitignore/dotfile 弱化）。 */
@Composable
private fun FileTreeIcon(node: FileTreeNode, decorationColor: Color?) {
    val containerRoot = node.root as? FileTreeRoot.Container
    if (containerRoot != null) {
        val tint = decorationColor ?: MaterialTheme.colorScheme.primary
        val logo = containerOsLogo(containerRoot.osId)
        if (logo != null) {
            Icon(
                painter = logo,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
        } else {
            Icon(
                imageVector = if (containerRoot.remote) FeatherIcons.Server else FeatherIcons.HardDrive,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
        }
        return
    }
    val vector = if (node.entry.isDirectory) {
        FeatherIcons.Folder
    } else {
        fileTypeIconFor(node.entry.name)
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = decorationColor
            ?: if (node.entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** .gitignore 命中条目的弱化色（橙，类 VSCode）。 */
private val FileTreeIgnoredColor = Color(0xFFCC8844)

/** 文件树一行的显示名：根节点用根标签（工作区根 `workspace` / 当前容器名），其余用条目名。 */
private val FileTreeNode.displayName: String get() = root?.label ?: entry.name

/** 文件树一行除缩进与文本外的固定宽度开销（start 8 + 箭头 16 + 4 + 图标 20 + 8 + end 12），供预算整树统一行宽。 */
private val FILE_TREE_ROW_OVERHEAD = 68.dp


/**
 * 会话行长按弹出的功能菜单：置顶 / 重命名 / 导出 / 删除。底部 sheet 样式参照 git 分支的 RefActionSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ChatSession,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBatchSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = Icons.Outlined.PushPin,
                label = stringResource(if (session.isPinned) R.string.chat_unpin_session else R.string.chat_pin_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onTogglePin()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Download,
                label = stringResource(R.string.chat_export_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onExport()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.CheckSquare,
                label = stringResource(R.string.chat_batch_select),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onBatchSelect()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

/** 侧边栏会话分组：同一时间组（今天 / 昨天 / 7天内 / 30天内 / 月份）内的会话，按最后回复时间降序。 */
internal data class SessionGroup(
    val groupKey: String,
    val sessions: List<ChatSession>
)

/**
 * 按最后回复时间（updatedAt）降序的会话列表分组：今天 / 昨天 / 7天内 / 30天内 / 更早按月。
 */
internal fun buildSessionGroups(sessions: List<ChatSession>, now: Long): List<SessionGroup> {
    val groups = mutableListOf<SessionGroup>()
    for (session in sessions) {
        val groupKey = sessionGroupKey(session.updatedAt, now)
        val lastIndex = groups.lastIndex
        if (lastIndex >= 0 && groups[lastIndex].groupKey == groupKey) {
            groups[lastIndex] = groups[lastIndex].copy(sessions = groups[lastIndex].sessions + session)
        } else {
            groups += SessionGroup(groupKey, listOf(session))
        }
    }
    return groups
}

/** 返回会话所属分组 key；月份分组为 ISO 年月（如 2026-05），其余为固定字面量。 */
internal fun sessionGroupKey(updatedAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days <= 7L -> "7d"
        days <= 30L -> "30d"
        else -> YearMonth.from(day).toString()
    }
}

@Composable
private fun sessionGroupLabel(groupKey: String, anchorSession: ChatSession): String = when (groupKey) {
    "pinned" -> stringResource(R.string.session_group_pinned)
    "today" -> stringResource(R.string.session_group_today)
    "yesterday" -> stringResource(R.string.session_group_yesterday)
    "7d" -> stringResource(R.string.session_group_last_7_days)
    "30d" -> stringResource(R.string.session_group_last_30_days)
    else -> SimpleDateFormat(
        stringResource(R.string.session_group_month_format),
        Locale.getDefault()
    ).format(Date(anchorSession.updatedAt))
}

/** 交付物页：AI 发进会话的附件，按后缀分组、组内时间倒序；行点击弹出操作菜单。 */
@Composable
private fun DeliverablesTab(
    deliverables: List<AIAgentViewModel.DeliverableItem>,
    scanning: Boolean,
    onRefresh: () -> Unit,
    fileAccess: com.aicode.feature.workspace.domain.FileAccessProvider?
) {
    LaunchedEffect(Unit) { onRefresh() }
    if (deliverables.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (scanning) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.deliverables_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    val groups = remember(deliverables) {
        deliverables.groupBy { deliverableGroupKey(it.attachment.fileName) }
            .toList()
            .sortedBy { (key, _) -> deliverableGroupOrder(key) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xl)
    ) {
        groups.forEach { (key, list) ->
            item(key = "deliv-$key") {
                SettingsGroupHeader(text = deliverableGroupTitle(key))
            }
            items(list, key = { it.attachment.containerPath }) { item ->
                DeliverableRow(item = item, fileAccess = fileAccess)
            }
        }
    }
}

/** 交付物分组键：按文件名后缀归类。 */
private fun deliverableGroupKey(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk", "apks", "xapk", "aab" -> "apk"
        "zip", "7z", "rar", "tar", "gz", "tgz" -> "archive"
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> "image"
        "md", "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "json", "csv", "html" -> "doc"
        else -> "other"
    }
}

private fun deliverableGroupOrder(key: String): Int = when (key) {
    "apk" -> 0
    "archive" -> 1
    "image" -> 2
    "doc" -> 3
    else -> 4
}

@Composable
private fun deliverableGroupTitle(key: String): String = stringResource(
    when (key) {
        "apk" -> R.string.deliverables_group_apk
        "archive" -> R.string.deliverables_group_archive
        "image" -> R.string.deliverables_group_image
        "doc" -> R.string.deliverables_group_doc
        else -> R.string.deliverables_group_other
    }
)

@Composable
private fun DeliverableRow(
    item: AIAgentViewModel.DeliverableItem,
    fileAccess: com.aicode.feature.workspace.domain.FileAccessProvider?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    // 动作作用域挂在行上（菜单内容随退场动画销毁，不能在那里 rememberCoroutineScope）。
    val actionScope = rememberCoroutineScope()
    val key = deliverableGroupKey(item.attachment.fileName)
    val icon = when (key) {
        "apk" -> FeatherIcons.Download
        "archive" -> FeatherIcons.Archive
        "image" -> FeatherIcons.Image
        "doc" -> FeatherIcons.FileText
        else -> FeatherIcons.File
    }
    val timeText = remember(item.timestamp) {
        if (item.timestamp <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(item.timestamp))
    }
    val sizeText = remember(item.attachment.sizeBytes) {
        if (item.attachment.sizeBytes <= 0) "" else
            android.text.format.Formatter.formatShortFileSize(context, item.attachment.sizeBytes)
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    item.attachment.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    FeatherIcons.MoreHorizontal,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val meta = listOfNotNull(
                timeText.takeIf { it.isNotBlank() },
                sizeText.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp + Spacing.sm, top = 1.dp)
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (fileAccess != null) {
                attachmentActionEntries(context, item.attachment, fileAccess, actionScope).forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.labelRes)) },
                        leadingIcon = {
                            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        onClick = {
                            menuOpen = false
                            entry.action()
                        }
                    )
                }
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.deliverables_unavailable)) },
                    onClick = { menuOpen = false }
                )
            }
        }
    }
}
