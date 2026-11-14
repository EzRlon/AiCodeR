package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.core.ui.onboarding.OnboardingStep
import com.aicode.core.ui.onboarding.onboardingTarget
import com.aicode.feature.vnc.presentation.VncOverlayViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Edit
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Users
import compose.icons.feathericons.Terminal

/** 顶栏四周的自适应安全余量。 */
private data class HeaderInsets(val top: Dp, val left: Dp, val right: Dp)

/**
 * 顶栏安全余量（异形屏 / 灵动岛 / 横屏侧挖孔）。
 *
 * - 顶部：常规设备沿用「状态栏 45% + 4dp」的紧凑值（2026-11-11 用户要求整行上移）；
 *   居中挖孔或灵动岛会落在这段余量里，此时至少下移到开孔下沿再留 6dp，取两者较大值。
 * - 左右：竖屏通常为 0；横屏时刘海/挖孔可能移到侧边，按 displayCutout 的侧边安全
 *   inset 额外让位，避免两侧按钮被遮住（0 时完全不增加内距）。
 */
@Composable
private fun headerInsets(): HeaderInsets {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val cutoutLeft = WindowInsets.displayCutout.getLeft(density, layoutDirection)
    val cutoutRight = WindowInsets.displayCutout.getRight(density, layoutDirection)
    return with(density) {
        HeaderInsets(
            top = maxOf(statusBarTop * 0.45f + 4.dp.toPx(), cutoutTop + 6.dp.toPx()).toDp(),
            left = if (cutoutLeft > 0) (cutoutLeft + 4.dp.toPx()).toDp() else 0.dp,
            right = if (cutoutRight > 0) (cutoutRight + 4.dp.toPx()).toDp() else 0.dp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatHeader(
    inputTokens: Int,
    outputTokens: Int,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    /** 「新建会话」长按：弹新建类型选择层（新建/定时任务/定时 Plan）；null 时不响应长按。 */
    onNewChatLongClick: (() -> Unit)? = null,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    onNavigateToBrowser: () -> Unit = {},
    onNavigateToMemoryGraph: () -> Unit = {},
    onOpenTeams: (() -> Unit)? = null,
    /** 当前会话已召唤的专家团名；非 null 时按钮高亮，让用户一眼看到「已选」。 */
    activeTeamName: String? = null,
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    connectionState: com.aicode.feature.agent.domain.container.ConnectionState? = null,
    showMenuButton: Boolean = true,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    /** 当前上下文占用 token（最近一次请求的输入量）。 */
    contextTokens: Int = 0,
    /** 触发压缩的 token 线（模型输入预算 × 压缩阈值百分比）；0 表示未知，此时不显示该段。 */
    compactionLineTokens: Int = 0,
    /** 从自定义面板脚本里抠出来的真实余额文案（如 `$18.42`）；null 时回落到估算花费。 */
    balanceText: String? = null,
    /** 本会话估算花费（USD）；与 [balanceText] 二选一展示。 */
    estimatedCostUsd: Double? = null,
    /** 本会话缓存命中率（0..1）；null 或 0 时不显示。 */
    cacheHitRate: Float? = null,
    /** 多 Key 渠道当前取用的 Key 序号（序号, 总数）；单 Key 传 null。 */
    keyIndex: Pair<Int, Int>? = null,
    /** 长按状态条：展开底部的余额面板。 */
    onStatusStripLongPress: () -> Unit = {}
) {
    val insets = headerInsets()
    Surface(
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = insets.top, start = insets.left, end = insets.right)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 大屏常驻侧栏时隐掉汉堡键：侧栏已经摆在左边，再给个开关反而困惑。
                if (showMenuButton) {
                    Box(
                        modifier = Modifier
                            .size(HEADER_ICON_BUTTON_SIZE)
                            .onboardingTarget(OnboardingStep.OPEN_SIDEBAR)
                            .clickable(onClick = onOpenDrawer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            // 侧栏入口用常规汉堡菜单图标：这是「打开侧栏」的通用语义。
                            FeatherIcons.Menu,
                            contentDescription = stringResource(R.string.chat_open_sidebar),
                            modifier = Modifier.size(HEADER_ICON_SIZE),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                VncHeaderIconButton()
                // 顶栏按钮顺序（左→右）：专家团、记忆图谱、Git、终端、新建会话。
                // 「智能体」按钮已随引擎切换一期移除（引擎入口收编进模型选择弹窗；管理页本体保留）。
                onOpenTeams?.let { openTeams ->
                    WorkbenchIconButton(
                        icon = FeatherIcons.Users,
                        contentDescription = if (activeTeamName != null) {
                            stringResource(R.string.team_header_active, activeTeamName)
                        } else {
                            stringResource(R.string.team_picker_title)
                        },
                        // 已有专家团生效时按钮高亮：这是「当前选中了什么」最直接的一处反馈。
                        active = activeTeamName != null,
                        onClick = openTeams
                    )
                }
                WorkbenchIconButton(
                    icon = FeatherIcons.Share2,
                    contentDescription = stringResource(R.string.memory_graph_open),
                    active = false,
                    onClick = onNavigateToMemoryGraph
                )
                WorkbenchIconButton(
                    icon = FeatherIcons.GitBranch,
                    contentDescription = stringResource(R.string.chat_open_git),
                    active = gitActive,
                    onClick = onNavigateToGit
                )
                WorkbenchIconButton(
                    icon = FeatherIcons.Terminal,
                    contentDescription = stringResource(R.string.chat_open_terminal),
                    active = terminalActive,
                    onClick = onNavigateToTerminal
                )
                Box(
                    modifier = Modifier
                        .size(HEADER_ICON_BUTTON_SIZE)
                        .combinedClickable(
                            onClick = onNewChat,
                            onLongClick = onNewChatLongClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        // 新建会话用「撰写」图标：加号已被底栏「添加文件」占用，两者含义不同。
                        // 用 Edit 而非 Edit3：Edit3 笔画集中在中部，同尺寸下视觉重量只有邻键一半。
                        FeatherIcons.Edit,
                        contentDescription = stringResource(R.string.chat_new_session),
                        modifier = Modifier.size(HEADER_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 一个状态条同时接管了原来的「远程模式连接 + token 累计」一行的职责：
            // 远程时第一段是连接状态，其余段是上下文/余额/缓存。内容为空时整条不占高度。
            val labels = StatusStripLabels(
                context = stringResource(R.string.status_strip_context),
                balance = stringResource(R.string.status_strip_balance),
                estimate = stringResource(R.string.status_strip_estimate),
                cache = stringResource(R.string.status_strip_cache),
                sshConnecting = stringResource(R.string.chat_ssh_connecting),
                sshFailed = stringResource(R.string.chat_ssh_failed),
                sshDisconnected = stringResource(R.string.chat_ssh_disconnected)
            )
            val segments = buildStatusStripSegments(
                labels = labels,
                contextTokens = contextTokens,
                compactionLineTokens = compactionLineTokens,
                balanceText = balanceText,
                estimatedCostUsd = estimatedCostUsd,
                cacheHitRate = cacheHitRate,
                connectionState = connectionState,
                keyText = keyIndex?.let {
                    stringResource(R.string.status_strip_key, it.first, it.second)
                }
            )
            SessionStatusStrip(
                segments = segments,
                onLongPress = onStatusStripLongPress,
                contentDescription = stringResource(
                    R.string.status_strip_a11y,
                    statusStripSummary(segments),
                    inputTokens,
                    outputTokens
                )
            )
        }
    }
}

/** 状态条的一段：标签用弱色、数值用正常色，中间以 `·` 分隔。 */
internal data class StatusStripSegment(
    val label: String?,
    val value: String,
    /** 语义色覆盖（连接异常时用 error 色）；null = 跟随默认弱色文本。 */
    val tone: StripTone = StripTone.NORMAL
)

internal enum class StripTone { NORMAL, ALERT }

/**
 * 状态条的文案包。文案必须走双语 strings.xml（硬规则），而组装逻辑要保持纯函数可单测，
 * 于是把已解析的文案作为参数传进来——测试用固定标签，运行时用 [stringResource]。
 */
internal data class StatusStripLabels(
    val context: String,
    val balance: String,
    val estimate: String,
    val cache: String,
    val sshConnecting: String,
    val sshFailed: String,
    val sshDisconnected: String
)

/**
 * 组装状态条内容。纯函数，单测钉住「没有余额就显示估算」和各种缺数据的降级。
 *
 * 段落顺序：连接（仅远程异常）→ 上下文 → 余额/估算 → 缓存。屏幕不够宽时单行省略从尾部
 * 截断，正好先丢缓存、再丢金额，上下文永远留得住。
 */
internal fun buildStatusStripSegments(
    labels: StatusStripLabels,
    contextTokens: Int,
    compactionLineTokens: Int,
    balanceText: String?,
    estimatedCostUsd: Double?,
    cacheHitRate: Float?,
    connectionState: com.aicode.feature.agent.domain.container.ConnectionState? = null,
    /** 已格式化好的多 Key 片段（如 `Key 2/3`）；null 表示单 Key 或无信息。 */
    keyText: String? = null
): List<StatusStripSegment> {
    val segments = mutableListOf<StatusStripSegment>()

    // 连上的远程连接不占位：常态不值得一行字，异常才需要抢注意力。
    // 文案直接复用 chat_ssh_*（自带「SSH：」前缀），所以 label 留空。
    when (connectionState) {
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING ->
            segments += StatusStripSegment(null, labels.sshConnecting, StripTone.ALERT)
        com.aicode.feature.agent.domain.container.ConnectionState.FAILED ->
            segments += StatusStripSegment(null, labels.sshFailed, StripTone.ALERT)
        com.aicode.feature.agent.domain.container.ConnectionState.DISCONNECTED ->
            segments += StatusStripSegment(null, labels.sshDisconnected, StripTone.ALERT)
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED, null -> Unit
    }

    if (compactionLineTokens > 0) {
        val percent = (contextTokens.toFloat() / compactionLineTokens * 100f)
            .coerceIn(0f, 999f)
            .toInt()
        segments += StatusStripSegment(
            label = labels.context,
            value = "${formatStripTokens(contextTokens)}/${formatStripTokens(compactionLineTokens)} · $percent%"
        )
    }

    // 有钱看钱：真实余额优先，拿不到才回落到估算（用户 2026-11-11 定：C 方案）。
    if (!balanceText.isNullOrBlank()) {
        segments += StatusStripSegment(labels.balance, balanceText)
    } else if (estimatedCostUsd != null && estimatedCostUsd > 0) {
        segments += StatusStripSegment(labels.estimate, formatStripUsd(estimatedCostUsd))
    }

    if (!keyText.isNullOrBlank()) {
        segments += StatusStripSegment(null, keyText)
    }

    if (cacheHitRate != null && cacheHitRate > 0f) {
        segments += StatusStripSegment(
            label = labels.cache,
            value = "${(cacheHitRate.coerceIn(0f, 1f) * 100).toInt()}%"
        )
    }

    return segments
}

/** token 数按 k/M 缩写，去掉没意义的 `.0`（34k 而不是 34.0k）。 */
internal fun formatStripTokens(tokens: Int): String {
    val safe = tokens.coerceAtLeast(0)
    return when {
        safe >= 1_000_000 -> trimZero(safe / 1_000_000.0) + "M"
        safe >= 1_000 -> trimZero(safe / 1_000.0) + "k"
        else -> safe.toString()
    }
}

private fun trimZero(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(java.util.Locale.US, "%.1f", value)

/** 估算金额：不到 1 分钱时不显示 $0.00（会被读成「没花钱」），改用 `< $0.01`。 */
internal fun formatStripUsd(usd: Double): String =
    if (usd in 0.0..0.0049) "<\$0.01" else String.format(java.util.Locale.US, "\$%.2f", usd)

/** 状态条的朗读文本：先念各段，再补上会话累计 token（给读屏用户的完整信息）。 */
internal fun statusStripSummary(segments: List<StatusStripSegment>): String =
    segments.joinToString("，") { seg ->
        if (seg.label != null) "${seg.label} ${seg.value}" else seg.value
    }

/**
 * 顶栏底部的状态条，**24dp**。
 *
 * 高度刻意压这么低：它承载的是「瞥一眼」的信息（上下文、余额、缓存），不是操作区。
 * 没有可显示内容时整条不占高度（[segments] 为空直接返回），不给空数据留白。
 * 长按展开底部余额面板——这样余额面板可以默认收起，底栏整体更矮。
 */
@Composable
private fun SessionStatusStrip(
    segments: List<StatusStripSegment>,
    onLongPress: () -> Unit,
    contentDescription: String
) {
    if (segments.isEmpty()) return

    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val alert = MaterialTheme.colorScheme.error

    val annotated = buildAnnotatedString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                withStyle(SpanStyle(color = subtle.copy(alpha = 0.55f))) { append(" · ") }
            }
            val valueColor = if (segment.tone == StripTone.ALERT) alert else strong.copy(alpha = 0.85f)
            segment.label?.let {
                withStyle(SpanStyle(color = subtle.copy(alpha = 0.75f))) {
                    append(it)
                    append(" ")
                }
            }
            withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.Medium)) {
                append(segment.value)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 用户 2026-11-12 定：无论内容多少，状态条始终从屏幕中线向两侧展开，
            // 不再左对齐——左对齐会让短状态看起来像贴在侧边的标签。
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
        )
    }
}

/** 顶栏工作台入口按钮：大屏右栏开着对应内容时高亮，否则看不出点一下是开还是关。 */
@Composable
private fun WorkbenchIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    // 触控/按下卡片与底栏图标按钮统一为 36dp（底栏 IconButton 同尺寸、20dp 图标）：
    // 之前 40dp 热区 + 32dp 高亮底，比底栏大一圈，顶栏显得臃肿。
    Box(
        modifier = Modifier
            .size(HEADER_ICON_BUTTON_SIZE)
            .then(
                if (active) {
                    Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(HEADER_ICON_SIZE),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** 顶栏按钮尺寸：与底栏图标按钮（36dp / 20dp 图标）完全一致。 */
private val HEADER_ICON_BUTTON_SIZE = 36.dp
private val HEADER_ICON_SIZE = 20.dp

/** 顶栏 VNC 悬浮小窗入口：单击弹出，双击收起；小窗开着时高亮（同工作台按钮的 active 态）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VncHeaderIconButton() {
    val context = LocalContext.current
    val owner = remember(context) { context.findActivityOwner() }
    val viewModel: VncOverlayViewModel = hiltViewModel(
        viewModelStoreOwner = owner ?: checkNotNull(LocalViewModelStoreOwner.current)
    )
    val supported by viewModel.supported.collectAsStateWithLifecycle(initialValue = true)
    if (!supported) return
    val window by viewModel.window.collectAsStateWithLifecycle()
    val overlayOpen = window.visible && !window.minimized
    Box(
        modifier = Modifier
            .size(HEADER_ICON_BUTTON_SIZE)
            .then(
                if (overlayOpen) {
                    Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = viewModel::onHeaderSingleTap,
                onDoubleClick = viewModel::onHeaderDoubleTap,
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            FeatherIcons.Monitor,
            contentDescription = stringResource(R.string.vnc_toggle_window),
            modifier = Modifier.size(HEADER_ICON_SIZE),
            tint = if (overlayOpen) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** 顶栏按钮与浮窗渲染必须共用 Activity 级实例，否则点击只改页面级 VM，窗外永远不出现。 */
private tailrec fun Context.findActivityOwner(): ViewModelStoreOwner? = when (this) {
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.findActivityOwner()
    else -> null
}

@Composable
internal fun RemoteConnectingPlaceholder(
    state: com.aicode.feature.agent.domain.container.ConnectionState
) {
    val text = when (state) {
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING -> stringResource(R.string.chat_connecting_remote)
        com.aicode.feature.agent.domain.container.ConnectionState.FAILED -> stringResource(R.string.chat_remote_connect_failed)
        com.aicode.feature.agent.domain.container.ConnectionState.DISCONNECTED -> stringResource(R.string.chat_no_remote_connection)
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state == com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WelcomeState(bottomReserve: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        val targetOffset = minOf(-(maxHeight * 0.13f), -(bottomReserve / 2))
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (Build.VERSION.SDK_INT >= 30) snap() else tween(durationMillis = 220),
            label = "welcome-offset"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.offset(y = animatedOffset)
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}