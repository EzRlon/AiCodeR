package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.hasVisibleContent
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Search
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import kotlin.math.roundToInt

/** mini 悬浮窗卡片宽度（dp）。 */
private val MENU_CARD_WIDTH = 240.dp

/** mini 悬浮窗最大高度（dp）：条目多时卡片内滚动。 */
private val MENU_MAX_HEIGHT = 320.dp

/** 菜单条目行高（dp）。 */
private val MENU_ROW_HEIGHT = 38.dp

/** 锚点与卡片之间的间距（dp）。 */
private val MENU_ANCHOR_GAP = 8.dp

/** 卡片与容器边缘的安全边距（dp）。 */
private val MENU_EDGE_MARGIN = 8.dp

/** 入场动画时长（ms）与初始缩放：克制淡入 + 轻缩放。 */
private const val MENU_APPEAR_ANIM_MS = 150
private const val MENU_APPEAR_SCALE_START = 0.92f

/** mini 悬浮窗的一个操作项。 */
private data class MessageMenuEntry(
    val icon: ImageVector,
    val labelRes: Int,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/**
 * 消息长按 /「更多」的 mini 悬浮操作窗：紧贴锚点的紧凑小卡片（非全宽底部弹层）。
 *
 * 定位：优先展示在锚点上方（空间不足时放下方），水平方向 clamp 在容器内；窗口坐标锚点由
 * 调用方带出（长按触点或按钮 bounds），经 [containerOriginInWindow]（容器在窗口中的左上角）
 * 换算到容器局部坐标后布局。
 *
 * 内容 = 原「消息操作」能力集：复制 / 引用追问 / 解释这段 / 工作区查找 / 分享 / 选择文本
 * （后两者按内容可用性展示，解释与查找仅助手回复）；用户消息另带编辑；删除对任意消息可用。
 * 菜单只收集意图，具体动作（注入输入框 / 剪贴板 / 分享）由宿主 [AIChatPanel] 执行。
 */
@Composable
internal fun MessageContextMenuPopup(
    message: AgentUIMessage,
    anchorInWindow: Rect,
    containerOriginInWindow: Offset,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onExplain: () -> Unit,
    onSearchWorkspace: () -> Unit,
    onShare: () -> Unit,
    onSelectText: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val hasContent = message.content.hasVisibleContent()
    val entries = buildList {
        if (hasContent) {
            add(MessageMenuEntry(FeatherIcons.Copy, R.string.chat_action_copy) { onCopy() })
            add(MessageMenuEntry(FeatherIcons.MessageSquare, R.string.chat_action_quote) { onQuote() })
            // 解释 / 工作区查找：仅助手回复有意义
            if (message.role == MessageRole.ASSISTANT) {
                add(MessageMenuEntry(FeatherIcons.HelpCircle, R.string.chat_action_explain) { onExplain() })
                add(MessageMenuEntry(FeatherIcons.Search, R.string.chat_action_search_workspace) { onSearchWorkspace() })
            }
            add(MessageMenuEntry(FeatherIcons.Share2, R.string.chat_action_share) { onShare() })
            add(MessageMenuEntry(FeatherIcons.Type, R.string.chat_action_select_text) { onSelectText() })
        }
        onEditClick?.let { edit ->
            add(MessageMenuEntry(FeatherIcons.Edit2, R.string.chat_action_edit) { edit() })
        }
        onDeleteClick?.let { delete ->
            add(MessageMenuEntry(FeatherIcons.Trash2, R.string.chat_action_delete, destructive = true) { delete() })
        }
    }
    if (entries.isEmpty()) return

    val menuTitle = stringResource(R.string.chat_message_actions_title)

    // 入场动画：克制的淡入 + 轻缩放（一次性过渡，不循环）。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = MENU_APPEAR_ANIM_MS),
        label = "message-menu-appear"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val gapPx = with(density) { MENU_ANCHOR_GAP.toPx() }
        val marginPx = with(density) { MENU_EDGE_MARGIN.toPx() }
        val cardWidthPx = minOf(
            with(density) { MENU_CARD_WIDTH.toPx() },
            (containerWidthPx - marginPx * 2).coerceAtLeast(0f)
        )
        val maxCardHeight = minOf(
            MENU_MAX_HEIGHT,
            with(density) { (containerHeightPx - marginPx * 2).coerceAtLeast(0f).toDp() }
        )

        // 锚点转为容器局部坐标（容器即根 Box，均为窗口坐标，相减即得）。
        val anchorLeft = anchorInWindow.left - containerOriginInWindow.x
        val anchorTop = anchorInWindow.top - containerOriginInWindow.y
        val anchorBottom = anchorInWindow.bottom - containerOriginInWindow.y

        // 卡片高度首帧未知（按条目数先估）：入场动画期间用实测高度校正，用户无感。
        var cardHeightPx by remember { mutableStateOf(0) }
        val estimatedHeightPx = with(density) {
            (MENU_ROW_HEIGHT * entries.size + Spacing.xs * 2).toPx()
        }
        val effectiveHeightPx = if (cardHeightPx > 0) cardHeightPx.toFloat() else estimatedHeightPx

        // 垂直方向：优先锚点上方；上方放不下放下方；都放不下时贴边 clamp。
        val dropUp = anchorTop - gapPx - effectiveHeightPx >= marginPx
        val yPx = (if (dropUp) anchorTop - gapPx - effectiveHeightPx else anchorBottom + gapPx)
            .coerceIn(marginPx, (containerHeightPx - effectiveHeightPx - marginPx).coerceAtLeast(marginPx))
        // 水平方向：对齐锚点左缘，clamp 在容器内。
        val xPx = anchorLeft
            .coerceIn(marginPx, (containerWidthPx - cardWidthPx - marginPx).coerceAtLeast(marginPx))

        // scrim：点卡片外任一处关闭（半透明，不转移视觉焦点）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        Surface(
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                .width(with(density) { cardWidthPx.toDp() })
                .graphicsLayer {
                    alpha = appearProgress
                    val scale = MENU_APPEAR_SCALE_START + (1f - MENU_APPEAR_SCALE_START) * appearProgress
                    scaleX = scale
                    scaleY = scale
                    // 从贴近锚点的一侧生长（上弹贴底、下弹贴顶）。
                    transformOrigin = TransformOrigin(0.5f, if (dropUp) 1f else 0f)
                }
                .onSizeChanged { cardHeightPx = it.height }
                .semantics { paneTitle = menuTitle }
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = Spacing.xs)
                    .heightIn(max = maxCardHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                entries.forEach { entry ->
                    MessageMenuItem(
                        icon = entry.icon,
                        labelRes = entry.labelRes,
                        destructive = entry.destructive,
                        onClick = {
                            onDismiss()
                            entry.action()
                        }
                    )
                }
            }
        }
    }
}

/** mini 悬浮窗的单个操作条目：图标 + 标签，紧凑单行。 */
@Composable
private fun MessageMenuItem(
    icon: ImageVector,
    labelRes: Int,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md)
            .height(MENU_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
