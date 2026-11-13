package com.aicode.feature.agent.presentation.component

import com.aicode.core.ui.markdown.MarkdownImageTransformer
import com.aicode.core.ui.markdown.LocalMarkdownImageTransformer
import com.aicode.core.ui.markdown.MarkdownRenderCache
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import com.aicode.feature.floatball.FloatBallCommandCenter
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AnchorMenuPopup
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.ImageViewerHost
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.readableContentMaxWidth
import com.aicode.core.ui.rememberImageViewerState
import com.aicode.core.ui.rememberViewerDecodeSpec
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.voice.VoskModelManager
import com.aicode.feature.agent.domain.workflow.BudgetApprovalChoice
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.feature.schedule.presentation.component.QuickSchedule
import com.aicode.feature.schedule.presentation.component.ScheduleExecutionSheet
import com.aicode.feature.schedule.presentation.component.ScheduledAiTaskSheet
import com.aicode.feature.schedule.presentation.component.ScheduledPlanSheet
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.HandoffPrompt
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.core.ui.onboarding.OnboardingStep
import com.aicode.core.ui.onboarding.onboardingTarget
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.data.repository.VoiceEngine
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * 流式尾巴的三种状态，用于 [when] 分支分发。
 *
 * 早先用 [androidx.compose.animation.Crossfade] 做淡入，但 Crossfade 按 targetState
 * 缓存 content 子组合——流式期间 targetState 一直不变，文本增长时不会重新调用 content，
 * 导致 [StreamingBubble] 收不到后续文本、停在首句。故改用枚举 + 直接 [when] 分发。
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, KEY_SWITCHED, NONE }

/** 悬浮层（横幅/面板/输入框）与最后一条消息的间距。 */
private val FLOATING_LAYER_GAP_DP = 8.dp

/** 内容底部驱动校准的容差（px）：最后内容底部超出安全区超过该值才向下校准，避免亚像素抖动。 */
private const val AUTO_SCROLL_TOLERANCE_PX = 2

/** AI 收工后继续逐帧校准的时长（ms）：md 异步解析仍可能改高度，不能一停就收手。 */
private const val CALIBRATE_TAIL_MS = 1_200L

/** 滚动到底部按钮直径（dp）。 */
private const val SCROLL_TO_BOTTOM_BTN_SIZE = 34

/** 连续新消息的入场错开间隔（ms）与总上限：一次插入很多卡片时不能让最后一张等好几秒。 */
private const val MESSAGE_ENTRY_STAGGER_MS = 90L
private const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** 轮内「结果置前」翻转时，相关 item 换位的 placement 动画时长（ms）。 */
private const val CHAT_ITEM_PLACEMENT_ANIM_MS = 150

/**
 * 系统语音识别「服务不可达/不可用」类错误码（F2/F7）：触发自动降级到离线引擎。
 * NO_MATCH（没说话声）/SPEECH_TIMEOUT/权限不足/识别器忙（RECOGNIZER_BUSY，重试即可）
 * 不在此列，按原文案提示。
 */
private val SYSTEM_VOICE_FAILURE_CODES = setOf(
    VoiceSession.ERROR_UNAVAILABLE,
    android.speech.SpeechRecognizer.ERROR_NETWORK,
    android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    android.speech.SpeechRecognizer.ERROR_SERVER,
)

/** 消息未就绪时延迟多久才显示加载提示（ms）：本地读库很快，立即显示反而闪。 */
private const val MESSAGES_LOADING_HINT_DELAY_MS = 220L

/**
 * 长消息拆块渲染：
 *
 * 超长助手正文（长文档复述）如果塞成单条 LazyColumn item，撑到几屏深时手势命中区
 * 会在列表可见区之外，深处表格的横滑/长按复制/点击全部失效（原版深处交互失效的根因）。
 * 限高+内滚的单窗口方案又会让窗口与列表世界之间出现「接不上、独立一块」的断接。
 *
 * 拆块的思路是：分裂成多条「有界高度」的列表 item，每条都是普通气泡（思考只在首块、
 * 操作行只在末块、相邻块零间距无缝衔接），列表单一滚动轴——世界上下完全接通、深处
 * 交互（表 8+ 横滑/复制/点击）因每条 item 高度有界而全部恢复。
 *
 * 若正文长度不超过阈值，不拆块，与普通消息完全一致。
 *
 * internal（而非 private）是为了让 [buildChatItems] 的用例能断言渲染项，见 ToolGroupExpansionTest。
 */
internal data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
    /**
     * 非空表示这是一条「连续工具调用」分组头 item。成员各自仍是独立 item（仅在该组展开时生成），
     * 因此无论展开与否，任何一条 item 的高度都有界——这是深处交互（表格横滑、长按复制）不失效的前提。
     */
    val toolGroup: List<AgentUIMessage>? = null,
    /** 分组当前是否展开（已含用户手动覆盖的结果）。 */
    val groupExpanded: Boolean = true,
    /** 非空 = 整轮折叠头 item（「执行中 / 已完成 Xs」）。 */
    val turnHeader: TurnHeaderState? = null,
    /** 轮头携带的过程项（思考 / 工具分组 / 中间正文）：整轮展开时随折叠头一起平滑展开。 */
    val turnProcess: List<ChatRenderItem> = emptyList(),
    /** 是否渲染消息自带的思考块；结果块把思考抽为过程项后传 false。 */
    val reasoningVisible: Boolean = true,
    /** 轮末「本轮产物」聚合 item 携带的附件（轮内助手/工具消息附件按路径去重）。 */
    val turnArtifacts: List<AgentAttachment> = emptyList(),
)

/** 工具调用分组头 item 的 contentType。 */
private const val TOOL_GROUP_CONTENT_TYPE = "tool-group"

/** 整轮折叠头 item 的 contentType。 */
private const val TURN_HEADER_CONTENT_TYPE = "turn-header"

/** 整轮展开时，被抽为独立过程项的「轮末助手思考」的 contentType。 */
private const val REASONING_CONTENT_TYPE = "reasoning"

/** 轮末「本轮产物」聚合 item 的 contentType。 */
private const val TURN_ARTIFACTS_CONTENT_TYPE = "turn-artifacts"

/**
 * 回滚冲突提示里最多列出几个文件路径。Toast 空间有限，超出部分折叠成「另有 N 个」，
 * 完整清单仍可从日志查看。
 */
private const val MAX_REWIND_CONFLICT_LINES = 3

/** 轮首用户消息 id → 整轮折叠的持久化 key（与 ViewModel.turnExpansionOverrides 同口径）。 */
internal fun turnKeyOf(firstMessageId: String): String = "turn:$firstMessageId"

/**
 * 被窗口截断、找不到轮首的那一截过程（见 [buildChatItems]）的折叠头 key。
 *
 * 取块尾消息 id 而非块首：窗口继续向前补加载时新消息插在块首，块尾不变、key 保持稳定。
 */
internal fun leadingHeadKey(lastMessage: AgentUIMessage): String = "turn:head:${lastMessage.id}"

/**
 * 整轮任务折叠头 item 的渲染状态。非空即表示这是一条「执行中 / 已完成」头行。
 *
 * [endMessageId] 是轮末助手消息 id，据此查本轮耗时（见 [computeTaskDurations]，口径一致）。
 */
internal data class TurnHeaderState(
    val key: String,
    val endMessageId: String?,
    /** 该轮是否仍在执行（末轮且 agent 忙）。 */
    val running: Boolean,
    /** 展开态（已含用户手动覆盖的结果）。 */
    val expanded: Boolean,
)

/**
 * 消息操作 mini 悬浮窗的打开状态。
 *
 * [anchor] 是锚点矩形（窗口坐标）：长按消息时为长按触点（零尺寸矩形），点「更多」时为按钮 bounds。
 */
private data class MessageMenuState(val message: AgentUIMessage, val anchor: Rect)

/** 文件卡片操作菜单的打开状态：目标附件 + 长按触点锚点（窗口坐标）。 */
private data class FileMenuState(val attachment: AgentAttachment, val anchor: Rect)

/**
 * 分组展开时成员行的左缩进：分组头与成员行行首元素原本左右完全对齐（同一格 16dp 图标 + 同一间距），
 * 看不出从属关系；给成员整行缩进一级，分组头是父、成员行是子，层级一眼可辨。
 * 固定取 16dp（不用 [Spacing.lg]：紧凑密度下它只有 14dp，缩进会随屏幕形态忽大忽小）。
 */
private val ToolGroupMemberPadding = PaddingValues(start = 16.dp)

/**
 * 该消息是否参与「连续工具调用」分组。
 *
 * 上下文压缩失败/摘要、后台通知这些 TOOL 消息各有专用渲染分支（见 [AgentMessageItem] 的早退），
 * 混进分组会被当成普通工具行，故一并排除；普通消息（用户/助手）天然打断分组。
 *
 * **带附件的工具（`sendFile` / `generateImage`）也不分组**：它们产出的文件行就是结果本身，
 * 折进「N 次工具调用」后随分组默认收起，等于把发来的文件藏起来；留作顶层 item 才常显。
 */
private fun AgentUIMessage.isGroupableTool(): Boolean =
    role == MessageRole.TOOL && !isCompactionFailure && !isContextSummary &&
        !isCompactionMarker && !isBackgroundNotification && !isVerificationReport && attachments.isEmpty()

/** 分组标识：取组内首条消息 id，保证一批工具调用在追加过程中 item key 稳定（不会重建导致视口跳动）。 */
private fun toolGroupKey(first: AgentUIMessage): String = "toolgroup:${first.id}"

/**
 * 该助手消息是否会渲染出「气泡下方的元信息行」——即能不能承接那排「复制 / 更多」按钮。
 *
 * 压缩标记、上下文摘要、压缩失败、后台通知条在 [AgentMessageItem] 里各有专用渲染分支并提前
 * return，不产生这一行；纯思考无正文的助手消息同理（没有正文可复制）。把「最新一条」的按钮
 * 挂到它们身上，整段会话就一个按钮都不剩。
 */
private fun AgentUIMessage.rendersActionRow(): Boolean =
    role == MessageRole.ASSISTANT &&
        !isCompactionMarker && !isContextSummary && !isCompactionFailure && !isBackgroundNotification &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

/**
 * 单条消息（非工具分组）在一次渲染中占据的 item：
 * 超长助手正文拆成多条有界 chunk，其余消息 1:1。
 */
private fun messageRenderItems(message: AgentUIMessage): List<ChatRenderItem> {
    val canSplit = message.role == MessageRole.ASSISTANT &&
        !message.isCompactionMarker &&
        !message.isContextSummary &&
        !message.isCompactionFailure &&
        !message.isBackgroundNotification &&
        message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
    if (!canSplit) {
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    val slices = splitLongContent(message.content)
    if (slices.size <= 1) {
        // 只拆出一块（如无空行的超长单段）：等同普通消息。
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    return slices.mapIndexed { idx, slice ->
        ChatRenderItem(
            message = message,
            key = "${message.id}#chunk$idx",
            contentType = "assistant-chunk",
            slice = slice,
            isChunkHeader = idx == 0,
            isChunkFooter = idx == slices.lastIndex,
        )
    }
}

/**
 * 消息列表 → LazyColumn item 列表。
 *
 * 三件事：**整轮任务折叠**、长文拆块、**连续工具调用分组**。
 *
 * 先按「用户消息 → 下一个用户消息之前」切成轮（见 [splitChatTurns]）：每轮依次产出「用户消息 →
 * 结果区（常显项与结果 + 产物聚合块）→ 过程区（轮头，携带过程项；仅当该轮有过程或正在运行）」。
 * 结果在前、过程在后是「结果置前」结构：轮完成后先看到结论、过程收在轮末；流式期间正文由流式
 * 尾巴渲染（不经过这里），完成后落库重排即翻转。过程项挂在轮头 item 内，整轮展开时随折叠头一起
 * 平滑展开；压缩摘要/后台通知/带附件工具等常显项不参与折叠。
 * 结果 = 轮内最后一条有可见正文的普通助手消息（其思考抽为过程项）。
 *
 * 轮首之前剩下的散消息同样按「一轮过程」处理：消息按条数截断加载，窗口边界常切在某一轮中间，
 * 这一段缺的是轮首用户消息而不是折叠头——原样常显会在历史最前面摊开一整轮过程。它们补一个不带
 * 耗时的折叠头（key 见 [leadingHeadKey]），默认收起。
 *
 * 轮头展开态：没手动记录时「运行中展开（看进展）、完成后自动收起进『构造过程』」，完成后会话里只剩
 * 用户消息与最终实施结果，过程（思考/工具/中间正文）折叠进轮头；手动点过的轮以上选择为准。
 *
 * 工具调用分组：手动选择（[groupOverrides]）优先；未点过时**跟随轮头展开态**——运行中的轮
 * 随轮头铺开（看得到进展），完成后随轮头一并收起；单张思考卡/工具卡同样只往运行中的轮自动展开。
 */
internal fun buildChatItems(
    messages: List<AgentUIMessage>,
    groupOverrides: Map<String, Boolean>,
    turnOverrides: Map<String, Boolean> = emptyMap(),
    activeTurnKey: String? = null,
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size)
    val (leading, turns) = splitChatTurns(messages)
    if (leading.isNotEmpty()) {
        val headKey = leadingHeadKey(leading.last())
        // 这一截通常结尾就是上一轮的最终回复（窗口从某一轮中间切开）：它照旧常显，
        // 只有它的思考与更早的过程步骤折进折叠头，与完整轮次的呈现一致。
        val resultId = leading.last().takeIf { it.isResultCandidate() }?.id
        val process = buildTurnProcessItems(leading, resultId, groupOverrides)
        if (process.isNotEmpty()) {
            items += ChatRenderItem(
                message = leading.last(),
                key = "$headKey#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = headKey,
                    endMessageId = null,
                    running = false,
                    expanded = turnOverrides[headKey] ?: false,
                ),
                turnProcess = process,
            )
        }
        items += buildTurnPersistentItems(leading, resultId)
    }
    for (turn in turns) {
        items += messageRenderItems(turn.userMessage)
        val endAssistant = turn.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.isPersistentInTurn()
        }
        val running = turn.key == activeTurnKey
        // 结果兑底：纯工具调用收尾（最终 AssistantText 无正文被 messagesState 过滤）时
        // isResultCandidate 为空，回退取轮内最后一条非 USER 行（最后一条 TOOL 结果/助手行）常显，
        // 保证任何一轮至少有一条可见 AI 痕迹（否则叠加折叠后「整轮只剩用户消息」，会被误读为历史丢失）
        val resultId = if (running) null
        else turn.messages.lastOrNull { it.isResultCandidate() }?.id
            ?: turn.messages.lastOrNull { it.role != MessageRole.USER }?.id
        // 展开态：手动覆盖优先；默认「运行中展开、完成后收起进『构造过程』」——运行中能看到思考与
        // 施工进展，收工后只留用户消息与最终实施结果，会话保持清爽。
        val expanded = turnOverrides[turn.key] ?: running
        // 过程项挂在轮头 item 里一起展开（保证展开动画是整体高度变化）；常显项与结果另作独立 item。
        // 分组展开默认值跟随轮头：运行中的轮「N 次工具调用」直接铺开，完成后一并收进构造过程。
        val process = buildTurnProcessItems(
            turn.messages,
            resultId,
            groupOverrides,
            expandGroups = expanded,
            // 运行中只铺开最新一组工具调用；历史组随新链出现自动收起（用户手动值优先）。
            latestGroupOnly = running
        )
        // 结果置前：常显项与结果先排（结论先看），过程区（轮头 + 过程项）整体排到轮末。
        items += buildTurnPersistentItems(turn.messages, resultId)
        // 本轮产物聚合块跟随结果区（提示型工具逐条展示之外，给交付物一个统一位置；双份展示是有意设计）
        val turnArtifacts = turn.messages
            .flatMap { it.attachments }
            .distinctBy { it.containerPath }
        if (turnArtifacts.isNotEmpty()) {
            items += ChatRenderItem(
                message = turn.userMessage,
                key = "${turn.key}#artifacts",
                contentType = TURN_ARTIFACTS_CONTENT_TYPE,
                turnArtifacts = turnArtifacts,
            )
        }
        if (process.isNotEmpty() || running) {
            items += ChatRenderItem(
                message = turn.userMessage,
                key = "${turn.key}#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = turn.key,
                    endMessageId = endAssistant?.id,
                    running = running,
                    expanded = expanded,
                ),
                turnProcess = process,
            )
        }
    }
    return items
}

/** 一轮任务：轮首用户消息 + 其后的全部消息（助手 / 工具 / 压缩卡片等）。 */
private class ChatTurn(
    val key: String,
    val userMessage: AgentUIMessage,
    val messages: List<AgentUIMessage>,
)

/**
 * 按「用户消息」切成轮。返回 (轮首之前的散消息, 轮列表)。
 *
 * 后台通知虽可能是 USER 角色，但它是系统注入的提示条而非用户输入，不作为轮起点（归入当前轮内容）。
 */
private fun splitChatTurns(messages: List<AgentUIMessage>): Pair<List<AgentUIMessage>, List<ChatTurn>> {
    val leading = ArrayList<AgentUIMessage>()
    val turns = ArrayList<ChatTurn>()
    var user: AgentUIMessage? = null
    var body: MutableList<AgentUIMessage>? = null
    for (message in messages) {
        if (message.role == MessageRole.USER && !message.isBackgroundNotification && !message.isCompactionMarker) {
            user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
            user = message
            body = ArrayList()
        } else {
            if (body == null) leading += message else body.add(message)
        }
    }
    user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
    return leading to turns
}

/**
 * 会话消息 → 右侧时间线（对话鸟瞰）节点。
 *
 * 口径与 [buildChatItems] 一致：①用户提问取轮首用户消息 item（轮头 item 的 message 也是用户
 * 消息，靠 contentType 区分）；②每轮只取「结果」正文的第一块（长文拆块不产生多个节点）；
 * ③验收报告单独标出。itemIndex 即该 item 在 [chatItems] 中的下标，点击节点据此定位。
 */
internal fun buildTimelineNodes(
    messages: List<AgentUIMessage>,
    chatItems: List<ChatRenderItem>,
): List<TimelineNode> {
    val (_, turns) = splitChatTurns(messages)
    val resultIds = turns
        .mapNotNull { turn -> turn.messages.lastOrNull { it.isResultCandidate() }?.id }
        .toHashSet()
    val nodes = ArrayList<TimelineNode>()
    val seenResults = HashSet<String>()
    val seenReports = HashSet<String>()
    chatItems.forEachIndexed { index, item ->
        val message = item.message
        when {
            item.contentType == MessageRole.USER.name &&
                !message.isBackgroundNotification && !message.isCompactionMarker ->
                nodes += TimelineNode(index, TimelineNodeType.USER)
            // 思考项与结果正文同属一条消息：跳过，避免抢占「结果」节点的首块口径
            item.contentType == REASONING_CONTENT_TYPE -> Unit
            message.isVerificationReport -> {
                if (seenReports.add(message.id)) nodes += TimelineNode(index, TimelineNodeType.VERIFICATION)
            }
            message.id in resultIds && seenResults.add(message.id) ->
                nodes += TimelineNode(index, TimelineNodeType.ASSISTANT_RESULT)
        }
    }
    return nodes
}

/** 该消息在整轮折叠里是否「常显」——不参与过程折叠，无论轮展开与否都显示。 */
private fun AgentUIMessage.isPersistentInTurn(): Boolean =
    isCompactionMarker || isContextSummary || isCompactionFailure || isBackgroundNotification ||
        isVerificationReport || (role == MessageRole.TOOL && attachments.isNotEmpty())

/** 能否作为一轮的「结果」：最后一条有可见正文的普通助手消息。 */
private fun AgentUIMessage.isResultCandidate(): Boolean =
    role == MessageRole.ASSISTANT && !isPersistentInTurn() && content.hasVisibleContent()

/**
 * 过程项里「有可展开细节」的项：思考卡、带思考的助手消息、工具组、单条工具消息。
 *
 * 运行中的轮只自动展开当前思考链（见 [startsReasoningChain]），更早的保持收起——
 * 长任务不会把历史过程全摊开。整轮没有思考时，退化为只展开最后一条这类项。
 */
private fun ChatRenderItem.isAutoExpandableProcessItem(): Boolean =
    startsReasoningChain() || toolGroup != null || message.role == MessageRole.TOOL

/** 该过程项是否开启一条新的思考链（思考卡，或带思考内容的助手消息）。 */
private fun ChatRenderItem.startsReasoningChain(): Boolean =
    contentType == REASONING_CONTENT_TYPE ||
        (message.role == MessageRole.ASSISTANT && !message.reasoning.isNullOrEmpty())

/**
 * 轮内「过程」项（保持原序）：思考、连续工具调用分组、中间助手正文。挂在轮头 item 内一起展开。
 *
 * 结果消息的思考归过程（抽为一个 [REASONING_CONTENT_TYPE] 项）；压缩摘要/后台通知/带附件工具等
 * 常显项不进过程，由 [buildTurnPersistentItems] 单独产出。
 */
private fun buildTurnProcessItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
    groupOverrides: Map<String, Boolean>,
    expandGroups: Boolean = false,
    /**
     * 运行中的轮只自动展开「当前思考链」（最后一条带思考的助手消息及其后的工具组）。
     *
     * 若把所有工具组都铺开，长任务的历史调用会占满整屏；这条规则与思考卡一致：
     * 新思考链出现时，上一链的思考与工具自动收回。
     */
    latestGroupOnly: Boolean = false,
): List<ChatRenderItem> {
    // 当前链起点 = 最后一条带思考的助手消息所在下标；其后（含）的工具组才算「当前链」。
    // 整轮没有思考时 chainStart 为 -1，退化为只展开最后一个成组工具组。
    val chainStart: Int = if (expandGroups && latestGroupOnly) {
        messages.indexOfLast { it.role == MessageRole.ASSISTANT && !it.reasoning.isNullOrEmpty() }
    } else -1
    val latestGroupKey: String? = if (expandGroups && latestGroupOnly) {
        var last: String? = null
        var k = 0
        while (k < messages.size) {
            if (messages[k].isGroupableTool()) {
                var end = k
                while (end < messages.size && messages[end].isGroupableTool()) end++
                if (end - k >= 2) last = toolGroupKey(messages[k])
                k = end
            } else {
                k++
            }
        }
        last
    } else null

    val out = ArrayList<ChatRenderItem>(messages.size)
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (message.isGroupableTool()) {
            var j = i
            while (j < messages.size && messages[j].isGroupableTool()) j++
            val members = messages.subList(i, j).toList()
            // 只有一条工具时不折成「1 次工具调用」，直接当普通工具行显示。
            if (members.size == 1) {
                out += messageRenderItems(members.first())
            } else {
                val key = toolGroupKey(members.first())
                out += ChatRenderItem(
                    message = members.first(),
                    key = key,
                    contentType = TOOL_GROUP_CONTENT_TYPE,
                    toolGroup = members,
                    // 手动选择优先；未点过时跟随轮头展开态。运行中只铺开当前思考链里的组，
                    // 更早的组随新链出现自动收起；整轮无思考时只铺开最后一组。
                    groupExpanded = groupOverrides[key] ?: when {
                        !expandGroups -> false
                        !latestGroupOnly -> true
                        chainStart >= 0 -> i >= chainStart
                        else -> key == latestGroupKey
                    },
                )
            }
            i = j
            continue
        }
        when {
            message.isPersistentInTurn() -> Unit
            message.id == resultId -> {
                if (!message.reasoning.isNullOrEmpty()) {
                    out += ChatRenderItem(
                        message = message,
                        key = "${message.id}#reasoning",
                        contentType = REASONING_CONTENT_TYPE,
                    )
                }
            }
            else -> out += messageRenderItems(message)
        }
        i++
    }
    return out
}

/** 轮内「常显」项（保持原序）：结果正文（拆块，不重复渲染思考）与压缩卡片/通知/带附件工具。 */
private fun buildTurnPersistentItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>()
    for (message in messages) {
        if (message.id == resultId) {
            messageRenderItems(message).forEach { out += it.copy(reasoningVisible = false) }
        } else if (message.isPersistentInTurn()) {
            out += messageRenderItems(message)
        }
    }
    return out
}

/** 超过该长度（字符）的助手正文拆成多条有界 chunk。 */
private const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** 每条 chunk 的目标字符预算：正文按 markdown 块打包，单块超出预算（如巨大表格）时
 *  按行硬切兜底，保证任意 chunk 高度有界（≈0.5-0.7 屏）。 */
private const val CHUNK_BUDGET_CHARS = 1_200

/**
 * 长正文拆块：以行为单位识别三类 markdown 块——代码围栏（整段）、表格（连续 | 行，
 * 整表保持完整）、普通段落（以空行分隔），然后按字符预算贪心打包成 chunk。
 * 超预算的单块按行拆分为多个块，宁可打断表格也不让某条 item 无界长高。
 */
internal fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val rawBlocks = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith(fence)) {
                    sb.append('\n').append(lines[j]); j++
                }
                if (j < lines.size) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            line.isBlank() -> i++
            trimmed.startsWith("|") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].trimStart().startsWith("|")) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            else -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() &&
                    !lines[j].trimStart().startsWith("```") &&
                    !lines[j].trimStart().startsWith("~~~") &&
                    !lines[j].trimStart().startsWith("|")
                ) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
        }
    }
    if (rawBlocks.isEmpty()) return listOf(text)

    // 超预算单块（如巨型表格/巨型段落）按行切成预算内的小块，兜底保证有界。
    // 代码块（``` / ~~~）作为不可分割的语法单元必须保持完整，绝不能按行拆碎，
    // 否则围栏闭合被破坏，后续片段会退化为普通正文、丢失高亮与复制，HTML 更会被预处理器误清洗。
    val blocks = ArrayList<String>()
    for (block in rawBlocks) {
        val isCodeBlock = block.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        if (block.length <= CHUNK_BUDGET_CHARS || isCodeBlock) {
            blocks.add(block)
        } else {
            val piece = StringBuilder()
            var weight = 0
            for (ln in block.lines()) {
                if (weight > 0 && weight + 1 + ln.length > CHUNK_BUDGET_CHARS) {
                    blocks.add(piece.toString()); piece.setLength(0); weight = 0
                }
                piece.append(ln).append('\n'); weight += ln.length + 1
            }
            if (piece.isNotBlank()) blocks.add(piece.toString())
        }
    }

    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    var weight = 0
    for (block in blocks) {
        val w = block.length + 2
        if (weight > 0 && weight + w > CHUNK_BUDGET_CHARS) {
            chunks.add(cur.toString()); cur.setLength(0); weight = 0
        }
        // 块间必须留空行：丢空行会让 markdown 语义粘连（段落 + 紧跟 --- 会被解析成 setext 标题，段落被夸成标题字号）。
        if (weight > 0) cur.append('\n')
        cur.append(block).append('\n'); weight += w
    }
    if (cur.isNotBlank()) chunks.add(cur.toString())
    return chunks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    viewModel: AIAgentViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    onNavigateToMemoryGraph: () -> Unit = {},
    /** 在内置编辑器打开工作区文件（文本类附件卡片用，反馈 9）。 */
    onOpenFile: (String) -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    onOpenDrawer: () -> Unit,
    showMenuButton: Boolean = true,
    /** 大屏右栏当前开的是终端 / Git 时，顶栏对应图标高亮。 */
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    currentFile: String? = null,
    selectedCode: String? = null,
    onboardingStep: OnboardingStep? = null,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onDismissModelSheetInOnboarding: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages
    val pendingScroll by viewModel.pendingScrollMessage.collectAsStateWithLifecycle()

    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // 工具卡片入场调度：只排本次浏览期间新追加到尾部的 TOOL 消息，逐个错开淡入。
    // 换会话时调度器重建，新会话的存量消息不入场。
    val entryScheduler = remember(currentSessionId) { MessageEntryScheduler() }
    val messageEntryDelays = remember(messages, entryScheduler) { entryScheduler.schedule(messages) }
    val currentSessionState by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val currentSession = currentSessionState
    val sessionInputTokens = currentSession?.totalInputTokens ?: 0
    val sessionOutputTokens = currentSession?.totalOutputTokens ?: 0
    val sessionLastInputTokens = currentSession?.lastInputTokens ?: 0
    val contextUsages by viewModel.contextUsages.collectAsStateWithLifecycle()
    val messagesReady = messagesState.loaded && messagesState.sessionId == currentSessionId
    val runningTool by viewModel.runningTool.collectAsStateWithLifecycle()
    val isCompacting by viewModel.isCompacting.collectAsStateWithLifecycle()
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val isPolishing by viewModel.isPolishing.collectAsStateWithLifecycle()
    val keySwitchState by viewModel.keySwitchState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val reasoningTiming by viewModel.reasoningTiming.collectAsStateWithLifecycle()
    val preparingTool by viewModel.preparingTool.collectAsStateWithLifecycle()
    // 等待模型或工具执行时的状态文案：
    // 1. 若当前有正在执行的工具（runningTool）或模型正在准备的工具（preparingTool，工具名先到参数还在流式），
    //    直接说清在做什么（「正在读取文件」、「正在编辑文件」等，见 toolRunningLabelRes）；
    // 2. 否则（如等待模型首字吐出、纯思考间隙）兜底显示「正在思考」。
    val activeToolName = runningTool.lastOrNull()?.toolName?.takeIf { it.isNotBlank() } ?: preparingTool
    val thinkingLabel = activeToolName?.let { stringResource(toolRunningLabelRes(it)) }
        ?: stringResource(R.string.chat_status_thinking)
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingPermissionSessionTitle by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    val currentTodoItems by viewModel.currentSessionTodoItems.collectAsStateWithLifecycle()
    val queuedRequests by viewModel.queuedRequests.collectAsStateWithLifecycle()
    val targetRewindMessageId by viewModel.targetRewindMessageId.collectAsStateWithLifecycle()
    val rewindConflicts by viewModel.rewindConflicts.collectAsStateWithLifecycle()
    val providers = (settingsViewModel?.providers?.collectAsStateWithLifecycle()?.value ?: emptyList()).filter { it.isEnabled }
    val modelMetadata = settingsViewModel?.modelMetadata?.collectAsStateWithLifecycle()?.value.orEmpty()
    val sessionEngine by viewModel.currentSessionEngine.collectAsStateWithLifecycle()
    val frameAwaitingApproval by viewModel.hasPendingApproval.collectAsStateWithLifecycle()
    // CLI 引擎会话的模型名（胶囊/模型页勾选用，而非默认 provider 模型）
    val sessionCliModel = if (sessionEngine != null) {
        currentSessionState?.model
    } else null
    val sessionCliProviderId = if (sessionEngine != null) currentSessionState?.providerId else null
    val sessionProviderModel by viewModel.currentSessionProviderModel.collectAsStateWithLifecycle()
    val defaultProviderId = settingsViewModel?.defaultModelProviderId?.collectAsStateWithLifecycle()?.value ?: ""
    val defaultModelName = settingsViewModel?.defaultModel?.collectAsStateWithLifecycle()?.value ?: ""
    // 未绑定会话回退：新会话默认模型（主页空会话中选择后记忆），未设置则为 null（UI 显示默认图标/顶栏模型名留空）。
    val defaultFallbackProvider = providers
        .find { it.id == defaultProviderId }
        ?.takeIf { it.hasUsableApiKey }
        ?.let { if (defaultModelName.isNotBlank() && defaultModelName in it.models) it.copy(selectedModel = defaultModelName) else it }
    val activeProvider = run {
        val (boundProviderId, boundModel) = sessionProviderModel
        if (!boundProviderId.isNullOrBlank()) {
            // 与 workflow.resolveProviderConfig 保持一致：绑定 provider 须启用且已填 apiKey，否则回退默认模型
            providers.find { it.id == boundProviderId }
                ?.takeIf { it.hasUsableApiKey }
                // 绑定模型已被移出列表时绑定失效，回退默认模型（与 workflow.resolveProviderConfig 一致）
                ?.takeIf { boundModel.isNullOrBlank() || boundModel in it.models }
                ?.let {
                    if (!boundModel.isNullOrBlank()) it.copy(selectedModel = boundModel) else it
                } ?: defaultFallbackProvider
        } else {
            defaultFallbackProvider
        }
    }
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val projectRoot = currentWorkspace?.path ?: ""
    val currentMode by viewModel.currentSessionMode.collectAsStateWithLifecycle()
    val currentGoal by viewModel.currentSessionGoal.collectAsStateWithLifecycle()
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()
    val fileMentionState by viewModel.fileMentionState.collectAsStateWithLifecycle()

    val inputText by viewModel.inputDraft.collectAsStateWithLifecycle()
    val draftIntentType by viewModel.draftIntentType.collectAsStateWithLifecycle()
    // 输入 "/" 打开命令菜单时重扫技能，反映磁盘上技能的增删改。
    LaunchedEffect(inputText) {
        if (inputText == "/") viewModel.refreshSlashCommands()
    }
    // 外部分享进来的文本：会话就绪后合并进输入框草稿（无会话时暂存在 ViewModel 里等待）。
    val pendingExternalText by viewModel.pendingExternalText.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalText, currentSessionId) {
        val text = pendingExternalText ?: return@LaunchedEffect
        if (currentSessionId.isNullOrBlank()) return@LaunchedEffect
        viewModel.updateInputDraft(if (inputText.isBlank()) text else "$inputText\n\n$text")
        viewModel.consumePendingExternalText()
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    // 外部注入的附件（截图标注汇总等）：会话就绪后读文件转成待发附件合并进列表。
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingExternalAttachments by viewModel.pendingExternalAttachments.collectAsStateWithLifecycle()
    LaunchedEffect(pendingExternalAttachments, currentSessionId) {
        if (pendingExternalAttachments.isEmpty() || currentSessionId.isNullOrBlank()) return@LaunchedEffect
        val consumed = viewModel.consumePendingExternalAttachments()
        val capacity = (MAX_PENDING_ATTACHMENTS - pendingAttachments.size).coerceAtLeast(0)
        val fetchable = consumed.take(capacity)
        val converted = fetchable
            .mapNotNull { externalAttachmentToPending(it, viewModel.fileAccess) }
        if (consumed.size > capacity) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_LONG).show()
        } else if (converted.size < fetchable.size) {
            Toast.makeText(context, context.getString(R.string.chat_attachment_import_failed), Toast.LENGTH_LONG).show()
        }
        if (converted.isNotEmpty()) {
            pendingAttachments = pendingAttachments + converted
        }
    }
    var uploadingCount by remember { mutableStateOf(0) }
    // 消息操作 mini 悬浮窗：长按消息与点「更多」共用一套打开状态（含锚点，见 MessageMenuState）。
    var messageMenuState by remember { mutableStateOf<MessageMenuState?>(null) }
    var fileMenuState by remember { mutableStateOf<FileMenuState?>(null) }
    // 附件长按回调经 static CompositionLocal 下发，值引用变化会强制重组合内容区，
    // 故用 remember 缓存 lambda（fileMenuState 的 delegate 本身跨重组稳定）。
    val attachmentLongPressHandler: (AgentAttachment, Rect) -> Unit = remember {
        { attachment, anchor -> fileMenuState = FileMenuState(attachment, anchor) }
    }
    // 根容器（Scaffold 内容区）在窗口中的位置：mini 悬浮窗按窗口坐标锚定，渲染前换算到容器局部。
    var panelRootInWindow by remember { mutableStateOf(Offset.Zero) }
    var textSelectMessageId by remember { mutableStateOf<String?>(null) }
    var editingMessage by remember { mutableStateOf<AgentUIMessage?>(null) }
    // 待确认删除的消息：删除会级联删掉其后整段对话，先弹确认再落库。
    var pendingDeleteMessage by remember { mutableStateOf<AgentUIMessage?>(null) }
    // 切换会话退出选字模式，避免跨会话残留
    LaunchedEffect(currentSessionId) {
        textSelectMessageId = null
        // 待确认删除的消息同理：不清理会在 A 会话弹着确认框、确认后删到 B 会话的同 id 消息。
        pendingDeleteMessage = null
    }
    var queuedEditing by remember { mutableStateOf<QueuedRequest?>(null) }
    val listState = rememberLazyListState()
    // 消息未就绪时的加载提示：本地读库通常几十毫秒，立刻显示反而闪一下，等一小会儿还没就绪才提示。
    var showMessagesLoading by remember(currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(currentSessionId, messagesReady) {
        if (messagesReady) {
            showMessagesLoading = false
            return@LaunchedEffect
        }
        delay(MESSAGES_LOADING_HINT_DELAY_MS)
        showMessagesLoading = true
    }
    // 贴底滚动留白：首帧测量前用兜底值（约输入框 + 间距），实测悬浮层高度后改为动态值，
    // 横幅/面板/输入框任何形态下最后一条消息都停在悬浮层上方不被遮挡。
    val inputBarBottomReserveDp = 156.dp
    var floatingLayerHeightPx by remember { mutableStateOf(0) }
    val inputBarReservePx = with(LocalDensity.current) {
        (if (floatingLayerHeightPx > 0) floatingLayerHeightPx + FLOATING_LAYER_GAP_DP.toPx()
        else inputBarBottomReserveDp.toPx()).toInt()
    }
    val markdownCache = remember { MarkdownRenderCache() }
    // 工具调用（分组头 / 单条工具行）的手动展开态：放在 ViewModel 里，切页、滚出视口回收后仍保留。
    // key = 分组 key（`toolgroup:<组内首条消息 id>`）或单条消息 id。
    val toolExpansionOverrides = viewModel.toolExpansionOverrides
    // 快照：读一次 map 让组合订阅到它的变化，同时给下面的 remember 一个可比较的 key。
    val toolGroupOverrideSnapshot = toolExpansionOverrides.toMap()
    // 整轮折叠的手动展开态，同款快照。
    val turnExpansionOverrides = viewModel.turnExpansionOverrides
    val turnOverrideSnapshot = turnExpansionOverrides.toMap()
    // 思考卡片的展开态，同款快照。
    val reasoningExpansionOverrides = viewModel.reasoningExpansionOverrides
    val reasoningOverrideSnapshot = reasoningExpansionOverrides.toMap()
    // 正在执行的工具 id 集合：只让集合内容参与 remember key，避免实时输出逐字刷新导致整表重建。
    val runningToolIds by remember { derivedStateOf { runningTool.mapTo(HashSet()) { it.messageId } } }
    val scope = rememberCoroutineScope()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // 回滚代码时若有文件因「快照后被改过」被跳过，必须告知用户——静默跳过等于把数据丢失
    // 换成了无声的失败。展示后立即清空状态，避免重组时重复弹。
    LaunchedEffect(rewindConflicts) {
        val skipped = rewindConflicts
        if (skipped.isEmpty()) return@LaunchedEffect
        val shown = skipped.take(MAX_REWIND_CONFLICT_LINES)
        val more = skipped.size - shown.size
        val listText = shown.joinToString("\n") + if (more > 0) "\n…另有 $more 个" else ""
        val message = if (skipped.size == 1) {
            context.getString(R.string.checkpoint_skipped_single, listText)
        } else {
            context.getString(R.string.checkpoint_skipped_multiple, skipped.size, listText)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.clearRewindConflicts()
    }

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    // 拆块 + 工具分组：超长助手消息展开成多条有界 item（单条滚动轴、外观连续），
    // 连续的工具调用折成一个「N 次工具调用」分组；chatItems 的顺序即 LazyColumn item 顺序。
    // 提到这里（而不是 LazyColumn 分支内）是因为 isFarFromBottom 的「布局是否对应当前消息」判定
    // 需要它：分组会让 item 数 ≠ 消息数 + 1，不能再拿消息数当期望值。
    // 末轮且 agent 忙 = 当前正在跑的那一轮：它的折叠头默认展开（显示「执行中」），收工后自动收起。
    val activeTurnKey = remember(messages, isBusy) {
        if (!isBusy) {
            null
        } else {
            messages.lastOrNull { it.role == MessageRole.USER && !it.isBackgroundNotification && !it.isCompactionMarker }
                ?.let { turnKeyOf(it.id) }
        }
    }
    val chatItems = remember(messages, toolGroupOverrideSnapshot, turnOverrideSnapshot, activeTurnKey) {
        buildChatItems(
            messages = messages,
            groupOverrides = toolGroupOverrideSnapshot,
            turnOverrides = turnOverrideSnapshot,
            activeTurnKey = activeTurnKey,
        )
    }
    // 右侧时间线节点（对话鸟瞰）：随 chatItems 重建，点击节点跳转定位。
    val timelineNodes = remember(messages, chatItems) {
        buildTimelineNodes(messages, chatItems)
    }
    // 最新一轮「结果区」首 item 下标：轮完成翻转后作为视角落点目标（见下方翻转落点 effect）。
    val lastResultItemIndex = remember(timelineNodes) {
        timelineNodes.lastOrNull { it.type == TimelineNodeType.ASSISTANT_RESULT }?.itemIndex
    }
    // 每轮任务的总耗时（用户发送 → 本轮 AI 收工）与 token 合计，都只挂在轮末助手气泡下方
    val taskDurations = remember(messages, isBusy) {
        computeTaskDurations(messages, lastTurnFinished = !isBusy)
    }
    val turnUsages = remember(messages, isBusy) {
        computeTurnUsage(messages, lastTurnFinished = !isBusy)
    }
    val reasoningDurations = viewModel.reasoningDurations
    // 方案 A：每轮正常收尾的最终回复（包括历史所有轮次的回复）均挂操作行（复制/更多），
    // 彻底解决依赖内存集合导致重启 App 后历史消息丢失操作按钮的 Bug。
    // 仅在最新一轮正在生成（isBusy == true）时暂不挂出，避免生成中途按钮乱跳。
    val actionableMessageIds = remember(messages, isBusy) {
        val (leading, turns) = splitChatTurns(messages)
        val ids = mutableSetOf<String>()
        leading.filter { it.rendersActionRow() }.forEach { ids += it.id }
        for (i in turns.indices) {
            val turn = turns[i]
            val isLastTurn = i == turns.lastIndex
            val turnFinished = !isLastTurn || !isBusy
            if (turnFinished) {
                val resultMsg = turn.messages.lastOrNull { it.isResultCandidate() && it.rendersActionRow() }
                    ?: turn.messages.lastOrNull { it.rendersActionRow() }
                if (resultMsg != null) {
                    ids += resultMsg.id
                }
            }
        }
        ids
    }
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = activeProvider?.let { modelMetadata[modelMetadataKey(it.id, activeModel)] }
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    val providerDashboards by (settingsViewModel?.providerDashboards?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) })
    val currentDashboardState = activeProvider?.let { providerDashboards[it.id] } ?: ProviderDashboardState.Idle

    // ── 顶栏状态条的数据 ──
    // 余额只能来自用户配的面板脚本（官方 API 大多没有余额接口）；拿不到就回落到本会话估算花费。
    // 注意这里是「尽力提取」，提取失败必须返回 null 而不是猜一个数字，否则会把 token 余量当余额显示。
    val dashboardBalanceText = remember(currentDashboardState) {
        (currentDashboardState as? ProviderDashboardState.Success)
            ?.let { com.aicode.feature.settings.domain.service.ProviderBalanceExtractor.extract(it.result.card) }
    }
    // 面板脚本拿不到余额时，直接问一次供应商接口（OpenRouter / DeepSeek / OpenAI 兼容中转站）。
    // 缓存与失败降级都在 VM 里，这里只负责在切换渠道时触发一次。
    val probedBalance by produceState<String?>(initialValue = null, activeProvider?.id) {
        value = activeProvider?.let { viewModel.probeProviderBalance(it.id) }
    }
    val effectiveBalanceText = dashboardBalanceText ?: probedBalance
    val sessionCostUsd by viewModel.currentSessionCostUsd.collectAsStateWithLifecycle()
    val sessionCachedInputTokens = currentSession?.totalCachedInputTokens ?: 0
    val sessionCacheHitRate = if (sessionInputTokens > 0) {
        sessionCachedInputTokens.toFloat() / sessionInputTokens
    } else {
        null
    }
    // 压缩线 = 模型输入预算 × 用户设定的压缩阈值百分比。分母刻意用压缩线而不是上下文窗口：
    // 用户真正关心的是「离被迫压缩还有多远」，额度环那边才用窗口。
    val compactionPercent = settingsViewModel?.compactionThresholdPercent
        ?.collectAsStateWithLifecycle()?.value ?: 90
    val compactionLineTokens = run {
        val budget = activeModelMetadata
            ?.let { com.aicode.feature.settings.domain.model.ModelContextPolicy.effectiveInputBudget(it) }
            ?: com.aicode.feature.settings.domain.model.ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
        (budget * compactionPercent / 100.0).toInt()
    }

    // 键盘弹出时收起面板，避免输入框被挤压
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottomPx > 0

    // 余额/待办面板展开状态：展开时叠加面板联动折叠，避免输入框被双重顶开
    var showTeamPicker by rememberSaveable { mutableStateOf(false) }
    var showNewSessionSheet by rememberSaveable { mutableStateOf(false) }
    var showScheduledTaskSheet by rememberSaveable { mutableStateOf(false) }
    var showScheduledPlanSheet by rememberSaveable { mutableStateOf(false) }
    var showScheduleExecutionSheet by rememberSaveable { mutableStateOf(false) }
    var dashboardExpanded by rememberSaveable { mutableStateOf(false) }
    var todoExpanded by rememberSaveable { mutableStateOf(false) }
    // ProviderDashboardBar 仅在有面板脚本的 provider 下渲染（见 ChatInputBar）。无该栏时
    // dashboardExpanded 可能残留 true：面板曾展开后随 provider 切换/脚本移除而卸载，LaunchedEffect
    // 上报链路中断无法复位，直接拿它做 forceCollapse 会把授权/询问/计划面板永久压成收起态。
    // 故叠加可见性，只在面板当前可见且展开时才折叠叠加面板。
    val dashboardBarVisible = activeProvider?.dashboardScriptPath?.isNotBlank() == true
    val dashboardCollapseActive = (dashboardExpanded && dashboardBarVisible) || (todoExpanded && currentTodoItems.isNotEmpty())

    // ── 底栏折叠（长任务时收成任务轨道）──
    // 状态按会话重置（remember 的 key 是会话 id）：切回来一律展开。不落盘是刻意的——
    // 「我上次收起了这个会话」跨天复现只会让人以为任务没在跑。
    // 事件接线在底栏调用点附近（planApproval/budgetApproval 在那一层才可见）。
    var bottomBarCollapse by rememberSaveable(currentSessionId, stateSaver = BottomBarCollapseState.Saver) {
        mutableStateOf(BottomBarCollapseState())
    }
    // 顶栏状态条长按 → 底栏余额面板展开一次。自增值做信号，重复长按也能重新触发。
    var dashboardExpandSignal by remember { mutableStateOf(0) }

    fun buildDashboardContext(
        lastInput: Int = 0,
        lastOutput: Int = 0,
        lastCached: Int = 0,
        refreshReason: String = ""
    ): DashboardContext {
        val curSessionId = currentSessionId.orEmpty()
        val agentStateStr = when (agentState) {
            is AgentUIState.Idle -> "idle"
            is AgentUIState.Loading -> "loading"
            is AgentUIState.Streaming -> "streaming"
            is AgentUIState.Result -> "result"
            is AgentUIState.Error -> "error"
        }
        // 未显式传最近 token（切会话/任务完成/手动刷新等场景）时，从最后一条含 token 的助手消息兜底：
        // 否则面板的「最近输入/输出/缓存」会显示 0。llm 事件触发的刷新仍用事件带的实时值（更及时）。
        val lastTokenMsg = messages.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && (it.inputTokens > 0 || it.outputTokens > 0)
        }
        val effLastInput = if (lastInput > 0) lastInput else (lastTokenMsg?.inputTokens ?: 0)
        val effLastOutput = if (lastOutput > 0) lastOutput else (lastTokenMsg?.outputTokens ?: 0)
        val effLastCached = if (lastCached > 0) lastCached else (lastTokenMsg?.cachedInputTokens ?: 0)
        return DashboardContext(
            model = activeModel,
            // 面板脚本在容器内执行，工作区路径必须是容器视角（~/workspace）；宿主真实路径在容器内不存在
            workspacePath = WorkspacePathMapper.CONTAINER_ROOT,
            workspaceName = if (projectRoot.isNotBlank()) File(projectRoot).name else "",
            sessionId = curSessionId,
            lastInputTokens = effLastInput,
            lastOutputTokens = effLastOutput,
            lastCachedTokens = effLastCached,
            totalInputTokens = currentSession?.totalInputTokens ?: 0,
            totalOutputTokens = currentSession?.totalOutputTokens ?: 0,
            modelContextTokens = activeModelMetadata?.contextTokens ?: 0,
            modelMaxInputTokens = activeModelMetadata?.inputTokens ?: 0,
            modelMaxOutputTokens = activeModelMetadata?.outputTokens ?: 0,
            modelInputCostUsdPerM = activeModelMetadata?.inputCostUsdPerM ?: 0.0,
            modelOutputCostUsdPerM = activeModelMetadata?.outputCostUsdPerM ?: 0.0,
            modelCacheReadCostUsdPerM = activeModelMetadata?.cacheReadCostUsdPerM ?: 0.0,
            modelSupportsTools = activeModelMetadata?.supportsTools ?: false,
            modelSupportsVision = activeModelMetadata?.supportsVision ?: false,
            modelSupportsReasoning = activeModelMetadata?.supportsReasoning ?: false,
            messageCount = messages.size,
            agentState = agentStateStr,
            sessionMode = currentMode.name.lowercase(),
            reasoningEffort = reasoningEffort.apiValue,
            refreshReason = refreshReason
        )
    }

    // 首次进入、切换提供商、切换脚本路径或切换会话时拉取一次面板。
    // 会话切换时等 currentSession / messages 都落到新会话（sessionReady）再刷新：
    // 否则 buildDashboardContext 读到的是切换瞬间的旧会话快照，面板会显示旧数据或空 token。
    val sessionReady = currentSession?.id == currentSessionId && messagesReady
    LaunchedEffect(activeProvider?.id, activeProvider?.dashboardScriptPath, currentSessionId, sessionReady) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val context = buildDashboardContext(refreshReason = "session")
        settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
    }

    // 每次单次 LLM 请求返回时，立即带上最新 Token 与上下文实时刷新面板
    // 注意：LaunchedEffect(Unit) 只启动一次，collect 闭包会捕获首次组合时的变量快照，
    // 必须用 rememberUpdatedState 取最新值，否则会话累计 Token/消息数等永远停留在旧值。
    val latestBuildDashboardContext by rememberUpdatedState(
        { lastInput: Int, lastOutput: Int, lastCached: Int, refreshReason: String ->
            buildDashboardContext(lastInput, lastOutput, lastCached, refreshReason)
        }
    )
    val latestActiveProvider by rememberUpdatedState(activeProvider)
    LaunchedEffect(Unit) {
        viewModel.llmCallEvents.collect { callEvent ->
            val provider = latestActiveProvider ?: return@collect
            if (provider.dashboardScriptPath.isBlank()) return@collect
            val context = latestBuildDashboardContext(
                callEvent.inputTokens,
                callEvent.outputTokens,
                callEvent.cachedTokens,
                "llm"
            )
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    // AI 一轮任务完成后刷新面板：让脚本拿到最终 agentState（result/idle/error），
    // 否则面板状态会一直停在「生成中」。busy→非busy 每轮只发生一次（Result 后再置 Idle 属
    // 非busy→非busy，不会重复触发），故这里触发的刷新恰好落在最终状态上。
    var lastAgentStateForPanel by remember { mutableStateOf<AgentUIState?>(null) }
    LaunchedEffect(agentState) {
        val prev = lastAgentStateForPanel
        lastAgentStateForPanel = agentState
        val wasBusy = prev is AgentUIState.Loading || prev is AgentUIState.Streaming
        val nowDone = agentState !is AgentUIState.Loading && agentState !is AgentUIState.Streaming
        if (wasBusy && nowDone) {
            val provider = latestActiveProvider ?: return@LaunchedEffect
            if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
            val context = latestBuildDashboardContext(0, 0, 0, "done")
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    LaunchedEffect(activeProvider?.type, activeModel) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (activeModel.isNotBlank()) {
            settingsViewModel?.resolveModelMetadata(provider.id, provider.type, listOf(activeModel))
        }
    }

    // 主页模型选择面板复用 ModelSelectionSheet，能力 Tag 依赖全量模型元数据；
    // 仅 resolve 当前激活模型会导致未选过的模型无 Tag（设置页进入时已全量加载，主页补齐）。
    // providers 异步加载，首次组合时为空，需等非空后再解析（providers 后续变化也会触发，成本低有缓存）。
    LaunchedEffect(providers) {
        if (providers.isNotEmpty()) {
            settingsViewModel?.loadAllModelMetadata()
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    fun handlePickedAttachments(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        if (projectRoot.isBlank()) {
            Toast.makeText(context, emptyWorkspaceMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAttachmentSlots(pendingAttachments.size)) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedAttachments(uris, pendingAttachments.size)
        scope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            uploadingCount = selected.size
            try {
                selected.forEach { uri ->
                    runCatching {
                        copyUriToWorkspace(context, uri, viewModel.fileAccess, includeImageData = images)
                    }.onSuccess { uploaded ->
                        pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                        successCount += 1
                    }.onFailure { error ->
                        failures += (error.message ?: uploadFallbackError(context))
                    }
                }
            } finally {
                uploadingCount = 0
            }
            // 结果提示：全失败展示首个错误；有文件被上限截断或上传失败时用 partial 文案；全成功用 success 文案。
            val skipped = uris.size - selected.size
            when {
                successCount == 0 && failures.isNotEmpty() ->
                    Toast.makeText(context, failures.first(), Toast.LENGTH_LONG).show()
                skipped > 0 || failures.isNotEmpty() ->
                    Toast.makeText(context, partialUploadMessage(context, successCount), Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(context, uploadSuccessMessage(context, successCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        handlePickedAttachments(uris, images = true)
    }

    // 拍照：输出到 cache 临时文件（FileProvider 授权 uri），拍完按图片附件处理。
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) {
            handlePickedAttachments(listOf(uri), images = true)
        }
    }
    fun takePhoto() {
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.chat_camera_file_failed), Toast.LENGTH_SHORT).show()
            return
        }
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    // 缓冲流式文本与思考过程：流式期间跟随 streamingText / streamingReasoning；流式刚结束而数据库尚未完成派发期间（messages 末尾仍是 USER/TOOL 消息），
    // 持续保留本轮完整文本与思考，撑住底部气泡，彻底杜绝「字快打完了突然整段蒸发消失（空白闪回）」或「思考块突然消失又冒出」；
    // 一旦 messages 列表末尾正式接纳了本轮助手消息，立即清空让位。
    var retainedStreamingText by remember { mutableStateOf<String?>(null) }
    var retainedStreamingReasoning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSessionId) {
        retainedStreamingText = null
        retainedStreamingReasoning = null
    }

    val lastMsg = messages.lastOrNull()
    // 本轮助手消息是否已经在消息列表中正式就位渲染：
    // 只有末尾消息是 ASSISTANT 且正文与思考前缀吻合，才代表本轮输出已落库进 messages 列表
    val isAssistantSettled = lastMsg?.role == MessageRole.ASSISTANT && run {
        val currentText = streamingText ?: retainedStreamingText
        val currentReasoning = streamingReasoning ?: retainedStreamingReasoning
        val textSettled = if (currentText.isNullOrBlank()) {
            true
        } else {
            val prefix = currentText.trimStart().take(20)
            prefix.isEmpty() || lastMsg.content.trimStart().startsWith(prefix)
        }
        val reasoningSettled = if (currentReasoning.isNullOrBlank()) {
            true
        } else {
            val prefix = currentReasoning.trimStart().take(20)
            prefix.isEmpty() || (lastMsg.reasoning?.trimStart()?.startsWith(prefix) == true)
        }
        textSettled && reasoningSettled
    }

    LaunchedEffect(streamingText, isAssistantSettled) {
        val st = streamingText
        if (st != null && st.hasVisibleContent()) {
            retainedStreamingText = st
        } else if (isAssistantSettled) {
            retainedStreamingText = null
        }
    }

    LaunchedEffect(streamingReasoning, isAssistantSettled) {
        val sr = streamingReasoning
        if (sr != null && sr.hasVisibleContent()) {
            retainedStreamingReasoning = sr
        } else if (isAssistantSettled) {
            retainedStreamingReasoning = null
        }
    }

    // retained 快照只在「流式已结束、落库消息尚未出现」的短窗口里兜底，撑住底部气泡不闪空；
    // 末尾消息一旦出现或更替（有新消息落库），或整轮结束，兜底使命即结束。
    // 不能只按 lastMsg.role 判定：多步工具回合里末尾在 TOOL→TOOL 之间来回，role 不变则本 effect
    // 不重启；而助手消息落库又常与紧随其后的工具消息被合并派发，UI 观察不到「助手消息成为末尾」
    // 那一帧，isAssistantSettled 的前缀校验恒不成立——两条清除路径同时失效，旧思考/正文就会一直
    // 挂在尾巴上、新内容反而出现在它上方。改按末尾消息 id 判定，任何新消息落库都会触发清空。
    LaunchedEffect(isBusy, lastMsg?.id) {
        retainedStreamingText = null
        retainedStreamingReasoning = null
    }

    val displayStreamingText = if (isAssistantSettled) null else (streamingText ?: retainedStreamingText)
    val showStreaming = displayStreamingText?.hasVisibleContent() == true

    val displayStreamingReasoning = if (isAssistantSettled) null else (streamingReasoning ?: retainedStreamingReasoning)
    val showReasoning = displayStreamingReasoning?.hasVisibleContent() == true

    // 打字机渲染进度：持有在 LazyColumn 之外，尾巴 item 滚出视口被 dispose 后进度不丢。
    val typewriter = rememberTypewriterStreamingText(
        text = displayStreamingText ?: "",
        active = streamingText != null,
        sessionKey = currentSessionId
    )
    val typewriterRenderText = typewriter.text
    // 思考过程同样走打字机：与回复文本共用同一速率自适应逻辑。
    // 正文开始输出（streamingText 非空）即视为思考结束：思考打字机立即收尾，
    // 避免思考还没打完、正文已开始导致两者叠着慢慢打。
    val typewriterReasoningText = rememberTypewriterStreamingText(
        text = displayStreamingReasoning ?: "",
        active = streamingReasoning != null && streamingText == null,
        sessionKey = currentSessionId
    ).text

    // 自动滚动跟随
    // 两个状态都必须 saveable：窄窗打开编辑器 / 终端 / Git / 设置都是全屏路由，聊天页整棵组合
    // 被 dispose。用普通 remember 的话返回时 positionedSession 归 null（当成换了会话重新贴底）、
    // followBottom 归 true（校准循环把恢复出的位置拉回底部），浏览历史的位置就丢了。
    var positionedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var userScrollGesture by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var nestedFling by remember { mutableStateOf(false) }
    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrollGesture = true
                    followBottom = false
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                nestedFling = true
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                nestedFling = false
                return Velocity.Zero
            }
        }
    }

    // key 必须带上 inputBarReservePx：闭包捕获的是创建时的值，用无 key 的 remember 会让
    // 判定永远停在首帧的兜底留白（156dp）上，面板展开把悬浮层顶高后仍按旧安全区算。
    val isAtBottom by remember(inputBarReservePx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // 列表还没测量（首帧、或消息未就绪期间 LazyColumn 未挂载）时 layoutInfo 为空、
            // canScrollForward 恒 false。此时不能判「在底部」——从别的页面返回的首帧会因此
            // 恢复跟随，把 listState 刚恢复出的滚动位置拉回底部。
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastIndex = layout.totalItemsCount - 1
            // 到底 = 最后内容底部停在安全区（悬浮层上沿）附近，而非视口底：
            // 判定更严格，避免用户拖走一小段后仍被判「在底部」而恢复跟随、立即被拉回。
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= safeBottom + AUTO_SCROLL_TOLERANCE_PX
        }
    }

    LaunchedEffect(pointerDown, userScrollGesture, nestedFling, listState.isScrollInProgress) {
        if (!pointerDown && userScrollGesture && !nestedFling && !listState.isScrollInProgress) {
            delay(150)
            userScrollGesture = false
            followBottom = isAtBottom
        }
    }

    // 回底按钮的显示门槛：只用「不在底部」会让流式增长的那一两帧（校准循环还没把视口拉回）
    // 也算离底，按钮跟着闪。要求离底超过半个视口，用户真的翻上去看历史时才出现。
    // messagesReady / messages.isEmpty() 与 totalItemsCount 是同一枚硬币的两面：layoutInfo 是
    // 「最后一次布局 pass」的产物，LazyColumn 卸载（空会话 WelcomeState、加载占位）后不会自动
    // 清空——旧会话翻历史后切到空会话，残留布局会让按钮悬在新会话上。totalItemsCount 再拦截
    // 「新列表尚未按当前消息重测」（layout 开始前 layoutInfo 仍是旧会话的）那一帧。
    val isFarFromBottom by remember(inputBarReservePx, messagesReady, messages.size, chatItems.size) {
        derivedStateOf {
            if (!messagesReady || messages.isEmpty()) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (layout.totalItemsCount != chatItems.size + 1) return@derivedStateOf false
            if (lastVisible.index < layout.totalItemsCount - 1) return@derivedStateOf true
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            (lastVisible.offset + lastVisible.size) - safeBottom > layout.viewportEndOffset / 2
        }
    }

    // 用户开始拖拽：停止跟随。松手时若已到底则恢复跟随。
    // 额外：流式输出时内容持续增长，用户可能松手后又被「顶」离底部——
    // 用 snapshotFlow { isAtBottom } 持续监测，只要滑到底部就恢复跟随，
    // 满足「流式中滚到底部自动继续跟随」。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 松手后延迟判定是否恢复跟随：等惯性滚动稳定，
                    // 避免「松手在底部但惯性上滑」被立即拉回。
                    scope.launch {
                        delay(150)
                        if (!userScrollGesture) followBottom = isAtBottom
                    }
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom && !userScrollGesture }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }

    // 贴底定位（发送消息、切换会话）：直接滚到锚点——scrollToItem 的目标 offset 使
    // 最后一项底部恰好停在悬浮层上方（contentPadding 预留 reserve，滚到该位置即列表
    // 可滚的最底部，无需依赖动画与逐步对齐）。
    val snapToBottom: suspend () -> Unit = {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            // 滚到列表可滚最底部：scrollToItem 的 offset 会被 clamp 到 maxScroll，
            // 最后一项底部恰好停在 contentPadding 底部（= 悬浮层上沿预留），无需手算项高度。
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    // 锚点式常驻校准循环：锚点 = 最后内容底部恰好停在悬浮层（输入框）上沿。
    // scrollToItem(最后一项, Int.MAX_VALUE) 会被 LazyColumn clamp 到可滚的最底部
    // （contentPadding 底部预留 reserve 保证），即最后一项底部停在悬浮层上沿，
    // 数学上任何时刻都成立——消息足够时，最后一条消息永不落入输入框之下，
    // 且不依赖“最后可见项 == 最后一项”的高度假设（高度跳变时也不会算错目标）。
    // 每帧检查最后可见项：最后内容被增长推下（底部超安全区）或有内容被推出视口下方
    // （最后可见项不是最后一项，即跟丢）时，滚回锚点；md 异步解析的高度跳变也会在
    // 下一帧被检测到，不存在信号与渲染错位。
    // 只向下校准：内容变矮（流式结束、折叠）时保持当前位置，避免「往回滚」与拉锯。
    // reserve 经 State 传递：下面这个 lambda 只创建一次，直接捕获局部 Int 会一直用首帧的兜底值。
    val reservePxState = rememberUpdatedState(inputBarReservePx)
    val busyState = rememberUpdatedState(isBusy)
    val calibrateToAnchor: suspend () -> Unit = remember(listState) {
        {
            // 无向下滚动空间（内容不满屏或已滚到锚点）：最后内容必然在安全区上方，无需校准。
            if (followBottom && !userScrollGesture && listState.canScrollForward) {
                val layout = listState.layoutInfo
                val lastIndex = layout.totalItemsCount - 1
                if (lastIndex >= 0) {
                    val lastVisible = layout.visibleItemsInfo.lastOrNull()
                    val safeBottom = layout.viewportEndOffset - reservePxState.value
                    // 最后一项被推出视口下方（跟丢）或最后内容底部越过安全区：滚回锚点。
                    // 用户在别处浏览时 followBottom 已为 false，不会走到这里。
                    val lost = lastVisible == null || lastVisible.index < lastIndex
                    val pushedDown = lastVisible != null &&
                        lastVisible.offset + lastVisible.size > safeBottom + AUTO_SCROLL_TOLERANCE_PX
                    if (lost || pushedDown) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                }
            }
        }
    }

    // 只在「跟随中且内容可能还在动」时逐帧校准。原来是无条件 while(true)，followBottom
    // 为 false 也只 continue、帧回调照旧注册，等于让主线程全程每帧醒一次（空闲也在耗电）。
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { followBottom && (busyState.value || listState.isScrollInProgress) }
            .collectLatest { active ->
                if (active) {
                    while (true) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                } else {
                    // 收工那一刻内容未必已稳定（md 异步解析往往落在后面），再兜一小段再收手。
                    // 用 delay 而非逐帧 withFrameNanos：这段只是等异步解析落地，不需要每帧回调，
                    // 1200ms 逐帧等于任务结束后再醒 72 次主线程。
                    delay(CALIBRATE_TAIL_MS)
                    calibrateToAnchor()
                }
            }
    }

    // 内容变化信号旁路：文本/思考/消息条数变化时立即校准一次，不等下一帧——
    // 与常驻校准循环互为补充，覆盖「无动画帧」的间隙，杜绝跟丢窗口。
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow {
            Triple(streamingText?.length, streamingReasoning?.length, messages.size)
        }.collect { calibrateToAnchor() }
    }

    // ── 翻转视角落点：轮完成后「结果置前」重排，若用户视口仍在底部附近，平滑滚到该轮
    // 「结果区」顶部——「先看结论」正合产品意图；用户在别处回看历史则不打扰。落点会关闭
    // 贴底跟随（视口不再贴底），用户滚回底部时由既有监测自动恢复跟随。──
    var flipLandingArmed by remember { mutableStateOf(false) }
    // 武装时刻的旧结果消息 id：收工后只有出现「新结果」才算本轮翻转，避免旧值误触发。
    var flipLandingBaseResultId by remember { mutableStateOf<String?>(null) }
    val lastResultMessageId = remember(lastResultItemIndex, chatItems) {
        lastResultItemIndex?.let { chatItems.getOrNull(it)?.message?.id }
    }
    LaunchedEffect(currentSessionId) {
        flipLandingArmed = false
        flipLandingBaseResultId = null
    }
    LaunchedEffect(currentSessionId, isBusy, messagesReady) {
        // 运行中且「当前会话消息已就绪」才武装：messagesReady 防切会话过渡帧用旧会话数据武装
        // （就绪前 messages 仍属上一个会话，base 取错会误滚动）；就绪后本 effect 重跑，再按当时 isBusy 判定。
        if (isBusy && messagesReady) {
            flipLandingArmed = true
            flipLandingBaseResultId = lastResultMessageId
        }
    }
    LaunchedEffect(isBusy, lastResultMessageId, chatItems) {
        if (isBusy || !flipLandingArmed) return@LaunchedEffect
        // 结果 item 还没出现（落库稍晚于状态翻转）：保持武装，等它出现后的下一轮触发。
        val target = lastResultItemIndex ?: return@LaunchedEffect
        val targetId = lastResultMessageId ?: return@LaunchedEffect
        if (targetId == flipLandingBaseResultId) return@LaunchedEffect
        flipLandingArmed = false
        // 视口靠近列表底部才落点：贴底跟随时（短 item 一屏多条的贴底场景首可见项可能距末尾 >3 项），
        // 或首可见 item 距末尾 ≤3 个；翻了历史则不打扰。
        val nearBottom = followBottom || listState.firstVisibleItemIndex >= chatItems.size - 4
        if (!nearBottom) return@LaunchedEffect
        followBottom = false
        // 等 placement 动画（150ms）跑完再滚：滑行途中叠加滚动会放大交叉叠印（F6）
        delay(CHAT_ITEM_PLACEMENT_ANIM_MS + 20L)
        withFrameNanos { }
        listState.animateScrollToItem(target)
    }

    // ── 语音输入（应用内识别）：长按发送键为「按住说话」（松手后识别文本进输入框草稿，不直接发送）；
    // 点击麦克风为点击聆听（再点一下结束，同样进草稿）。──
    // 引擎来自设置（系统 / 离线）：切换时重建会话，下方 DisposableEffect 负责释放旧实例。
    val voiceEngine by viewModel.voiceEngine.collectAsStateWithLifecycle()
    val voiceController: VoiceSession = remember(voiceEngine) {
        VoiceSession.create(context.applicationContext, voiceEngine)
    }
    // F2/F7：离线模型状态（引导卡片进度/就绪收起用）。
    val voiceModelState by viewModel.voiceModelState.collectAsStateWithLifecycle()
    // 系统识别不可用且模型未就绪时显示的引导卡片；就绪后自动收起（引擎由 VM 自动切换）。
    var showVoiceSetupCard by remember { mutableStateOf(false) }
    LaunchedEffect(voiceModelState) {
        if (voiceModelState is VoskModelManager.ModelState.Ready) showVoiceSetupCard = false
    }
    // 冷启动探测 + WiFi 静默下载（本进程至多一次；计费网络不静默下载）。
    LaunchedEffect(Unit) {
        viewModel.refreshSystemSttAvailability()
        viewModel.maybeAutoDownloadVoiceModel()
    }
    var voiceProcessing by remember { mutableStateOf(false) }
    // 微信式「松开发送」：长按原地松手时置 true，识别结果到达后直接发送而非进草稿。
    // 不用 by 委托：需要在 sendRef 间接调用与 onVoiceHoldFinish 两处读写
    val pendingAutoSend = remember { mutableStateOf(false) }
    // 供 onVoiceResult（定义在前）间接调用 sendMessage：自动发送复用完整发送链路（队列/清草稿/贴底）
    val sendRef = remember { mutableStateOf<(() -> Unit)?>(null) }
    // 点击聆听进行中（从开始持续到结果/错误到达；结果分流靠它区分点击与长按两种来源）
    var voiceTapActive by remember { mutableStateOf(false) }
    // 主动取消标记：系统识别器在 cancel() 后可能补一次 ERROR_CLIENT，据此静默吞掉
    var voiceCancelled by remember { mutableStateOf(false) }
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, context.getString(R.string.chat_voice_permission_granted_tap), Toast.LENGTH_SHORT).show()
        }
    }
    fun launchVoiceInput() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            voicePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        if (voiceTapActive) {
            // 再点一下结束：结果处理中重复点击忽略，等 onResult/onError 复位
            if (!voiceProcessing) {
                voiceProcessing = true
                voiceController.finish()
            }
        } else if (!voiceProcessing) {
            // 先立标记再启动：无识别服务时 start() 会同步回调 onError，标记随复位不残留
            voiceTapActive = true
            voiceCancelled = false
            voiceController.start()
        }
    }
    val onVoiceResult = rememberUpdatedState<(String) -> Unit>({ text ->
        voiceProcessing = false
        voiceTapActive = false
        if (voiceCancelled) {
            // 主动取消后迟到的结果（系统 stopListening 已发出的、离线已排队的结果）一律丢弃
            voiceCancelled = false
            return@rememberUpdatedState
        }
        val t = text.trim()
        if (t.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.chat_voice_no_speech), Toast.LENGTH_SHORT).show()
        } else if (pendingAutoSend.value) {
            // 微信式「松开发送」：识别结果直接发出，不再进草稿确认阶段。
            // sendMessage 定义在本块之后，经 sendRef 间接调用避免局部变量前向引用
            pendingAutoSend.value = false
            viewModel.updateInputDraft(t)
            sendRef.value?.invoke()
        } else {
            // 识别文本一率先进输入框草稿、不直接发送：可编辑、删除（撤销）或直接发送——
            // 等价于「未发出语音」的可撤销确认阶段（AI 只接收文字，没有语音消息载体）。
            viewModel.updateInputDraft(if (inputText.isBlank()) t else "$inputText $t")
        }
    })
    val onVoiceError = rememberUpdatedState<(Int) -> Unit>({ code ->
        voiceProcessing = false
        voiceTapActive = false
        if (voiceCancelled) {
            // 用户主动结束的本次识别，不再提示失败
            voiceCancelled = false
        } else if (voiceEngine == VoiceEngine.SYSTEM && code in SYSTEM_VOICE_FAILURE_CODES) {
            // F2/F7：系统引擎的网络/服务类失败 → 探测并自动降级：模型就绪直接切离线（提示重试），
            // 未就绪弹引导卡片下载；其余错误维持原 Toast 文案。
            if (viewModel.onSystemVoiceFailure()) {
                showVoiceSetupCard = true
            } else {
                Toast.makeText(context, context.getString(R.string.voice_switched_toast), Toast.LENGTH_LONG).show()
            }
        } else {
            val msgRes = when (code) {
                VoiceSession.ERROR_UNAVAILABLE -> R.string.chat_voice_unavailable
                VoiceSession.ERROR_MODEL_MISSING -> R.string.chat_voice_model_missing
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.chat_voice_no_speech
                else -> R.string.chat_voice_failed
            }
            Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
        }
    })
    DisposableEffect(voiceController) {
        voiceController.onResult = { text -> onVoiceResult.value(text) }
        voiceController.onError = { code -> onVoiceError.value(code) }
        onDispose { voiceController.destroy() }
    }

    val sendMessage: () -> Unit = sendMessage@{
        val text = inputText.trim()
        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val attachments = pendingAttachments
            val modelSupportsVision = activeModelMetadata?.supportsVision == true
            val promptAttachments = if (modelSupportsVision) attachments.filterNot { it.isImage } else attachments
            val intentType = draftIntentType
            val modelRequest = appendAttachmentsToRequest(context, text, promptAttachments) +
                MessageIntentCatalog.modelNote(context, intentType)
            val images = if (modelSupportsVision) attachments.toAgentImages() else emptyList()
            // 统一走队列：AI 忙时入队（等本轮结束后自动发送下一条），空闲时直接发送。
            // 斜杠命令在 ViewModel 内（agent workflow 之前）分流执行，无需在此区分。
            val queuedBefore = viewModel.queuedRequests.value.size
            val accepted = viewModel.enqueueAgentRequest(
                request = text,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = images,
                inputAttachments = attachments.toAgentAttachments(),
                intentType = intentType
            )
            // 暂停会话会拒绝发送：保留草稿与附件，避免用户输入被清空丢失。
            // 拒绝必须有可见反馈（用户反馈：终止后发送无任何反应像失灵）。
            if (!accepted) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.chat_send_rejected_paused),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@sendMessage
            }
            // 入队必须有可见反馈：队列面板会被 IME/审批浮层强制折叠，
            // 用户看不到「排队中」就会以为消息被吞了（外部引擎跑长任务时尤其明显）。
            if (viewModel.queuedRequests.value.size > queuedBefore) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.chat_queued_toast),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            viewModel.clearInputDraft()
            pendingAttachments = emptyList()
            followBottom = true
            scope.launch {
                kotlinx.coroutines.delay(0)
                snapToBottom()
            }
        }
    }
    sendRef.value = sendMessage

    // 切换会话 / 打开搜索命中：优先定位到目标消息，否则贴底并恢复跟随。
    LaunchedEffect(currentSessionId, messagesReady, pendingScroll, chatItems) {
        if (!messagesReady) return@LaunchedEffect
        val pending = pendingScroll
        if (pending != null && pending.first == currentSessionId) {
            val index = chatItems.indexOfFirst { it.message.id == pending.second }
            if (index >= 0) {
                // 等一帧让 LazyColumn 按新会话完成重组，再瞬移到目标消息。
                withFrameNanos { }
                followBottom = false
                listState.scrollToItem(index)
                positionedSession = currentSessionId
                viewModel.consumePendingScroll()
            } else if (!messagesState.hasMore) {
                // 全部消息已加载仍找不到（消息可能已被删除）：放弃，避免卡在待定位态。
                viewModel.consumePendingScroll()
            }
            return@LaunchedEffect
        }
        if (positionedSession != currentSessionId) {
            // 等一帧让 LazyColumn 按新会话完成重组，再滚到锚点（maxScroll）。
            withFrameNanos { }
            snapToBottom()
            positionedSession = currentSessionId
            followBottom = true
        }
    }

    // 展开/收起一条 item（工具卡片、工具分组头）之后**不做任何主动滚动**：就地展开、就地收起，
    // 视口一动不动；只暂停贴底跟随（用户滚回底部时由 isAtBottom 监测自动恢复）。
    //
    // 从前这里会按「让 item 底部露出安全区」重定位视口，还带一条「item 完全不可见就 scrollToItem」
    // 的兜底；卡片比一屏高时前者算出的目标被夹到 0，后者干脆把 item 顶到视口顶——两条路径都会让
    // 被点的那一行整条跳到顶部（概率性出现，取决于点的那一刻布局稳定到哪一帧）。这个跳动比
    // 「展开后底部被输入框挡一点」难接受得多，整段重定位逻辑去掉。
    val onToolItemToggled: () -> Unit = { followBottom = false }

    // 顶部快到头时加载更多历史。绝不能把 firstVisibleItemIndex 作为组合期读取的 effect key：
    // fling 中 index 每跨一个 item 就变一次，会让整个 AIChatPanel 大函数随之重组、连带全部可见
    // item 重组，是快速滚动掉帧（进而 fling 补偿跳变、抽搐）的主要放大器。改为在协程内用
    // snapshotFlow 观察 index，组合体完全不读它。
    val latestMessagesState = rememberUpdatedState(messagesState)
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                val ms = latestMessagesState.value
                if (index <= 3 && ms.hasMore && !ms.isLoadingMore) {
                    viewModel.loadMoreMessages()
                }
            }
    }

    val executionMode = settingsViewModel?.executionMode?.collectAsStateWithLifecycle()?.value
    val connectionState = settingsViewModel?.connectionState?.collectAsStateWithLifecycle()?.value
    val isRemote = executionMode == com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH

    val markdownImageTransformer = remember(viewModel.fileAccess) {
        MarkdownImageTransformer(viewModel.fileAccess)
    }
    val imageViewerState = rememberImageViewerState()
    val viewerDecodeSpec = rememberViewerDecodeSpec()
    val chatImageLoad = remember(viewModel.fileAccess, viewerDecodeSpec) {
        chatImageLoader(viewModel.fileAccess, viewerDecodeSpec.maxEdge, viewerDecodeSpec.maxPixels)
    }

    // 文件卡片点击用系统 app 打开：重取/拷贝需要在 IO 线程跑（远程模式要下载），故由这里注入协程。
    // 文本类附件（.md/.txt/代码等）改用内置编辑器打开（用户反馈 9：系统无 markdown 处理应用，必失败）；
    // 编辑器打开失败（路径不在工作区等）时回退系统 app。
    val attachmentOpener = remember(viewModel.fileAccess, context) {
        AttachmentOpener { attachment ->
            scope.launch {
                if (isTextLikeAttachment(attachment) && attachment.containerPath.isNotBlank()) {
                    val opened = runCatching {
                        onOpenFile(attachment.containerPath)
                        true
                    }.getOrDefault(false)
                    if (opened) return@launch
                }
                openSentAttachment(context, attachment, viewModel.fileAccess)
            }
        }
    }

    // 渲染一条 item。轮头 / 工具分组的展开内容与折叠头在同一 Composable 内用 AnimatedVisibility
    // 整体展开（而非逐个列表项各自 placement 动画）：高度平滑撑开、内容跟着一起走，手感是一个整体。
    // [autoExpandCards]：仅运行中的轮自动铺开单张思考卡/工具卡（看得到进展）；完成后随「构造过程」收起，
    // 手动展开轮头时卡片仍默认收起（二级细节点开看），用户手动开关过的卡以手动值为准。
    @Composable
    fun RenderChatItem(item: ChatRenderItem, autoExpandCards: Boolean = false) {
        val header = item.turnHeader
        val group = item.toolGroup
        when {
            header != null -> {
                // 外层 item 容器是 Box（叠放）：轮头与过程区必须包进 Column 纵向排列，
                // 否则「已完成 Xs」会和第一张过程卡同一 y 叠印。
                Column(modifier = Modifier.fillMaxWidth()) {
                    TurnCollapseHeader(
                        running = header.running,
                        durationMs = header.endMessageId?.let { taskDurations[it] },
                        expanded = header.expanded,
                        onToggle = {
                            viewModel.setTurnExpanded(header.key, !header.expanded)
                            onToolItemToggled()
                        }
                    )
                    AnimatedVisibility(
                        visible = header.expanded,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 运行中只自动铺开「当前思考链」：最后一条带思考的助手消息及其之后的
                            // 工具调用默认展开；更早的思考链/工具调用随新链出现自动收起，长任务不会
                            // 把历史过程全摊开铺满整屏。整轮没有思考时退化为「只展开最后一条过程项」。
                            // 用户手动开关过的项仍以手动值为准（override 快照优先级高于这里的默认值）。
                            val process = item.turnProcess
                            val lastChainStart = process.indexOfLast { it.startsReasoningChain() }
                            val autoExpandFrom = if (lastChainStart >= 0) {
                                lastChainStart
                            } else {
                                process.indexOfLast { it.isAutoExpandableProcessItem() }
                            }
                            process.forEachIndexed { idx, child ->
                                RenderChatItem(
                                    child,
                                    autoExpandCards = header.running && autoExpandFrom >= 0 && idx >= autoExpandFrom
                                )
                            }
                        }
                    }
                }
            }
            item.contentType == TURN_ARTIFACTS_CONTENT_TYPE -> {
                TurnArtifactsBlock(artifacts = item.turnArtifacts)
            }
            item.contentType == REASONING_CONTENT_TYPE -> {
                // 助手的思考抽为过程项：展开态交给宿主持久化，划出视口/切页返回仍保持。
                ReasoningBubble(
                    text = item.message.reasoning.orEmpty(),
                    cache = markdownCache,
                    durationMs = reasoningDurations[item.message.id],
                    expandedOverride = reasoningOverrideSnapshot[item.message.id] ?: autoExpandCards,
                    onExpandedChange = { viewModel.setReasoningExpanded(item.message.id, it) }
                )
            }
            group != null -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ToolCallGroupHeader(
                        count = group.size,
                        running = group.any { it.id in runningToolIds || it.isToolRunning(null) },
                        expanded = item.groupExpanded,
                        onToggle = {
                            viewModel.setToolExpanded(item.key, !item.groupExpanded)
                            onToolItemToggled()
                        }
                    )
                    AnimatedVisibility(
                        visible = item.groupExpanded,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        Column {
                            group.forEach { member ->
                                val live = runningTool.firstOrNull { it.messageId == member.id }?.text
                                AgentMessageItem(
                                    message = member,
                                    showActions = member.id in actionableMessageIds,
                                    liveOutput = live,
                                    markdownCache = markdownCache,
                                    onRewindClick = { viewModel.openRewindMenu(it) },
                                    onMoreClick = { msg, anchor -> messageMenuState = MessageMenuState(msg, anchor) },
                                    toolExpandedOverride = toolGroupOverrideSnapshot[member.id] ?: autoExpandCards,
                                    onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(member.id, isExpanded) },
                                    onToolToggle = onToolItemToggled,
                                    // 分组展开时成员行内缩一级，与分组头区分层级
                                    contentPadding = ToolGroupMemberPadding,
                                    entryDelayMs = messageEntryDelays[member.id]
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                val message = item.message
                val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                AgentMessageItem(
                    message = message,
                    showActions = message.id in actionableMessageIds,
                    liveOutput = live,
                    markdownCache = markdownCache,
                    contentSlice = item.slice,
                    isChunkHeader = item.isChunkHeader,
                    isChunkFooter = item.isChunkFooter,
                    reasoningVisible = item.reasoningVisible,
                    onRewindClick = { viewModel.openRewindMenu(it) },
                    onMoreClick = { msg, anchor -> messageMenuState = MessageMenuState(msg, anchor) },
                    toolExpandedOverride = toolGroupOverrideSnapshot[message.id] ?: autoExpandCards,
                    onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(message.id, isExpanded) },
                    onToolToggle = onToolItemToggled,
                    reasoningDurationMs = reasoningDurations[message.id],
                    reasoningExpandedOverride = reasoningOverrideSnapshot[message.id] ?: autoExpandCards,
                    onReasoningExpandedChange = { isExpanded -> viewModel.setReasoningExpanded(message.id, isExpanded) },
                    taskDurationMs = taskDurations[message.id],
                    turnUsage = turnUsages[message.id],
                    entryDelayMs = messageEntryDelays[message.id],
                    onMessageLongPress = { msg, anchor -> messageMenuState = MessageMenuState(msg, anchor) },
                    selectTextMode = textSelectMessageId == message.id,
                    onSelectTextDone = { textSelectMessageId = null }
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalMarkdownImageTransformer provides markdownImageTransformer,
        LocalImageViewer provides imageViewerState,
        LocalAttachmentOpener provides attachmentOpener,
        LocalAttachmentLongPress provides attachmentLongPressHandler
    ) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatHeader(
                inputTokens = sessionInputTokens,
                outputTokens = sessionOutputTokens,
                onOpenDrawer = {
                    keyboardController?.hide()
                    onOpenDrawer()
                },
                onNewChat = { viewModel.newSession() },
                onNewChatLongClick = { showNewSessionSheet = true },
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit,
                onNavigateToBrowser = onNavigateToBrowser,
                onNavigateToMemoryGraph = onNavigateToMemoryGraph,
                onOpenTeams = { showTeamPicker = true },
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                connectionState = connectionState?.takeIf { isRemote },
                showMenuButton = showMenuButton,
                terminalActive = terminalActive,
                gitActive = gitActive,
                browserActive = browserActive,
                contextTokens = sessionLastInputTokens,
                compactionLineTokens = compactionLineTokens,
                balanceText = effectiveBalanceText,
                estimatedCostUsd = sessionCostUsd,
                cacheHitRate = sessionCacheHitRate,
                keyIndex = keySwitchState?.let { it.newIndex to it.total },
                onStatusStripLongPress = {
                    // 面板在底栏里，收起态下先展开底栏，再让面板自己展开。
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.USER_EXPANDED)
                    dashboardExpandSignal++
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { panelRootInWindow = it.boundsInWindow().topLeft }
        ) {
        // 大屏正文列限宽居中：铺满整个平板宽度会让一行文字过长、气泡横跨全屏，读起来很累。
        // 窄窗下 readableContentMaxWidth() 返回 Dp.Unspecified，widthIn 不产生任何约束。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = readableContentMaxWidth())
                .fillMaxSize()
        ) {
            // 内容层：消息列表延伸到屏幕底部，输入框悬浮其上，滚动时卡片可滑入输入框后面
            Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (!messagesReady) {
                    // 远程模式连接未就绪时显示连接状态占位，避免空白或旧工作区记录闪烁
                    if (isRemote && connectionState != null && connectionState != com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED) {
                        RemoteConnectingPlaceholder(state = connectionState)
                    } else if (showMessagesLoading) {
                        // 本地模式读库偏慢时的占位：以前这里什么都不画，切会话会先闪一下空白。
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (messages.isEmpty()) {
                    WelcomeState(
                        bottomReserve = with(LocalDensity.current) { inputBarReservePx.toDp() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(userScrollConnection)
                            .pointerInput(listState) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    pointerDown = true
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        pointerDown = false
                                    }
                                }
                            },
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            top = Spacing.md,
                            bottom = with(LocalDensity.current) { inputBarReservePx.toDp() }
                        )
                    ) {
                        itemsIndexed(chatItems, key = { _, item -> item.key }, contentType = { _, item -> item.contentType }) { _, item ->
                            // 轮完成「结果置前」翻转时，轮头与结果区换位：placement 动画平滑过渡。
                            // fadeIn/fadeOut 全关：fadeOut 的 ghost 会在旧位置悬浮绘制、与新就位
                            // item 叠印（验收反馈 F6 的真凶，切回会话恰逢收工翻转时最明显），
                            // 宁可消失时瞬时硬切也不叠印。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = tween(
                                            durationMillis = CHAT_ITEM_PLACEMENT_ANIM_MS,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                            ) {
                                RenderChatItem(item)
                            }
                        }
                        val reasoning = displayStreamingReasoning
                        val showReasoning = reasoning?.hasVisibleContent() == true
                        // 忙碌状态指示器：在没有正文/思考流式输出、未在压缩、未被权限弹窗或询问挂起时展示。
                        // 工具正在执行或模型正在准备工具时，文案会由 thinkingLabel 动态呈现为对应场景（如「正在读取文件」）；
                        // 既无流式又无具体工具时兜底显示「正在思考」。
                        val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && pendingPermission == null && pendingQuestion == null
                        val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val showKeySwitched = keySwitchState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val tailKind = when {
                            showStreaming -> TailKind.STREAMING
                            isCompacting -> TailKind.COMPACTING
                            showKeySwitched -> TailKind.KEY_SWITCHED
                            showRetrying -> TailKind.RETRYING
                            showThinking -> TailKind.THINKING
                            else -> TailKind.NONE
                        }
                        // 尾巴 item：思考气泡与状态尾巴合并进同一个永久挂载的 item，二者都不按状态增删。
                        // 思考开始/结束或流式开始/结束若让 totalItemsCount 突增突减，LazyColumn 会把
                        // firstVisibleItemIndex 向下 clamp → 视口上跳（旧症状2根因）。item 数量恒为 1，
                        // anchor 不会被 clamp：showReasoning 时渲染思考气泡（内部自带折叠），否则为空；
                        // tailKind 为 NONE 时尾巴为空 Box（0 高度）。流结束落库后跟随 effect 会把新消息贴底。
                        item(key = "__active__", contentType = "tail") {
                            Column {
                                if (showReasoning) {
                                    // 流式实时：默认收起，折叠行跟着正在写的那一行滚动；点开看全文
                                    val reasoningLive = reasoningTiming != null && reasoningTiming?.second == null &&
                                        !showStreaming && preparingTool == null
                                    ReasoningBubble(
                                        text = typewriterReasoningText,
                                        cache = markdownCache,
                                        showTimer = reasoningLive,
                                        durationMs = reasoningTiming?.let { (start, end) -> end?.minus(start) },
                                        timerStartMs = reasoningTiming?.first,
                                        preRendered = true,
                                        sessionKey = currentSessionId,
                                        live = reasoningLive,
                                    )
                                }
                                when (tailKind) {
                                    TailKind.THINKING -> ThinkingBubble(label = thinkingLabel)
                                    TailKind.STREAMING -> StreamingBubble(text = typewriterRenderText, cache = markdownCache)
                                    TailKind.COMPACTING -> CompactionProgressBubble()
                                    TailKind.RETRYING -> {
                                        val rs = retryState
                                        if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries, rs.error) else Box(Modifier)
                                    }
                                    TailKind.KEY_SWITCHED -> {
                                        val ks = keySwitchState
                                        if (ks != null) KeySwitchedBubble(ks.newIndex, ks.total) else Box(Modifier)
                                    }
                                    TailKind.NONE -> Box(Modifier)
                                }
                            }
                        }
                    }
                }
                // 右侧时间线（对话鸟瞰）：覆在消息列表容器右缘，不占布局宽度；点击节点平滑
                // 跳到对应消息（跳转视为主动离开底部，关闭贴底跟随）。
                if (timelineNodes.isNotEmpty()) {
                    TimelineRail(
                        nodes = timelineNodes,
                        listState = listState,
                        onNodeClick = { node ->
                            followBottom = false
                            scope.launch { listState.animateScrollToItem(node.itemIndex) }
                        },
                        // 向上收到底栏悬浮层之上：底栏 Surface 覆盖整行全宽，其占位 pointerInput
                        // 会吞掉落在卡片范围内的触摸；不留白时轨道下段既被遮挡又点不到。
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(bottom = with(LocalDensity.current) { inputBarReservePx.toDp() })
                    )
                }
            }
            } // 内容层结束

            // 悬浮层：错误气泡 / 面板 / 输入框（蒙版在 ChatInputBar 内部，跟随键盘上移）
            val floatingPanelAlpha by animateFloatAsState(
                targetValue = if (listState.isScrollInProgress) 0.4f else 1f,
                animationSpec = tween(200),
                label = "floating-panel-alpha"
            )
            // 动画结束后摘掉 graphicsLayer：它会把整层画进离屏图层，主题切换时内部文字不重绘
            // （标题先不可见、触摸局部重绘后才出现）。用布尔驱动而非在组合期读 alpha，
            // 避免动画每帧重组本作用域；动画值只在 graphicsLayer lambda 里读，走绘制期。
            // 只套在需要弱化的横幅/面板上：ChatInputBar 自己已有一层 contentAlpha，
            // 再套一层会双重淡化（0.4×0.4≈0.16）并把必须不透明的渐变蒙版一起淡掉。
            var scrollFadeActive by remember { mutableStateOf(false) }
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) {
                    scrollFadeActive = true
                } else {
                    delay(240)
                    scrollFadeActive = false
                }
            }
            val floatingFadeModifier = if (scrollFadeActive) {
                Modifier.graphicsLayer { alpha = floatingPanelAlpha }
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 底栏折叠/展开、面板伸缩都改由这里统一补高度动画：ChatInputBar 折叠时是
                    // 早返回（整段不组合），内容高度会在一帧内跳变，没有这层就会「啪」地抽走半屏。
                    .animateContentSize(
                        animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f)
                    )
                    // 实测悬浮层实际高度作为滚动留白，横幅/面板/输入框任何形态都不遮挡最后一条
                    .onGloballyPositioned { if (it.size.height > 0) floatingLayerHeightPx = it.size.height }
            ) {
            Box(modifier = Modifier.fillMaxWidth().then(floatingFadeModifier)) {
                StatusBanner(state = agentState)
            }

            // 退场动画期间 uploadingCount 已归零，直接读会淡出一个「正在上传 0 个文件」，
            // 与 StatusBanner 同样用非空记忆值兜住退场。
            val lastUploadingCount = rememberLastNonNull(uploadingCount.takeIf { it > 0 })
            AnimatedVisibility(
                visible = uploadingCount > 0,
                modifier = floatingFadeModifier,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastUploadingCount?.let { UploadingBanner(count = it) }
            }

            val questionForPanel = rememberLastNonNull(pendingQuestion)
            AnimatedVisibility(
                visible = pendingQuestion != null,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(floatingFadeModifier),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                questionForPanel?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val planApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
            val planForPanel = rememberLastNonNull(planApproval)
            AnimatedVisibility(
                visible = planApproval != null,
                modifier = floatingFadeModifier,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                planForPanel?.let { state ->
                    PlanApprovalPanel(
                        state = state,
                        onApprove = { viewModel.approvePlanAndBuild() },
                        onRefine = { viewModel.refinePlan() },
                        onSchedule = { showScheduleExecutionSheet = true },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val budgetApproval by viewModel.pendingBudgetApproval.collectAsStateWithLifecycle()
            val budgetForPanel = rememberLastNonNull(budgetApproval)
            AnimatedVisibility(
                visible = budgetApproval != null,
                modifier = floatingFadeModifier,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                budgetForPanel?.let { state ->
                    BudgetApprovalPanel(
                        state = state,
                        onContinue = { viewModel.resolveBudgetApproval(state.id, BudgetApprovalChoice.CONTINUE) },
                        onStop = { viewModel.resolveBudgetApproval(state.id, BudgetApprovalChoice.STOP) },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val handoffPrompt by viewModel.handoffPrompt.collectAsStateWithLifecycle()
            // 只显示当前会话的交接提示：避免 A 会话的暂停/提醒误显示在 B 会话上。
            val currentHandoff = handoffPrompt?.takeIf { it.sessionId == currentSessionId }
            val handoffForPanel = rememberLastNonNull(currentHandoff)
            AnimatedVisibility(
                visible = currentHandoff != null,
                modifier = floatingFadeModifier,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                handoffForPanel?.let { prompt ->
                    HandoffPromptPanel(
                        percent = prompt.percent,
                        generating = prompt.generating,
                        paused = prompt.paused,
                        preparingCopy = prompt.preparingCopy,
                        onConfirm = { viewModel.confirmHandoff() },
                        onCopy = { viewModel.copyHandoffContent() },
                        onContinue = { viewModel.continueAfterPause() },
                        onDismiss = { viewModel.dismissHandoff() }
                    )
                }
            }

            val teamName by viewModel.teamName.collectAsStateWithLifecycle()
            if (showTeamPicker) {
                // 每次打开实时扫描专家团目录，用户改动定义文件后立即可见
                val teams = remember(showTeamPicker) { viewModel.listTeams() }
                val teamTranslations by produceState(emptyMap<String, StoreTranslation>(), showTeamPicker) {
                    value = viewModel.teamTranslations()
                }
                TeamPickerSheet(
                    teams = teams,
                    translations = teamTranslations,
                    currentTeam = teamName,
                    onSelect = { name ->
                        viewModel.setSessionTeam(name)
                        showTeamPicker = false
                    },
                    onDismiss = { showTeamPicker = false }
                )
            }

            if (showNewSessionSheet) {
                NewSessionTypeSheet(
                    onDismiss = { showNewSessionSheet = false },
                    onNewSession = { viewModel.newSession() },
                    onScheduledTask = { showScheduledTaskSheet = true },
                    onScheduledPlan = { showScheduledPlanSheet = true }
                )
            }

            if (showScheduledTaskSheet) {
                ScheduledAiTaskSheet(
                    initialContent = inputText,
                    initialMode = currentMode,
                    onDismiss = { showScheduledTaskSheet = false },
                    onCreate = { content, mode, categories, quick ->
                        viewModel.createScheduledAiTask(
                            name = "",
                            prompt = content,
                            runMode = mode,
                            categories = categories,
                            schedule = quick.toTaskSchedule()
                        )
                        toastScheduledCreated(context, quick)
                    }
                )
            }

            if (showScheduledPlanSheet) {
                ScheduledPlanSheet(
                    initialGoal = inputText,
                    onDismiss = { showScheduledPlanSheet = false },
                    onPrepare = { goal -> viewModel.startPlanDraftingSession(goal) }
                )
            }

            if (showScheduleExecutionSheet) {
                ScheduleExecutionSheet(
                    onDismiss = { showScheduleExecutionSheet = false },
                    onConfirm = { categories, quick ->
                        val created = viewModel.scheduleCurrentPlan(
                            name = "",
                            planText = planApproval?.reason.orEmpty(),
                            categories = categories,
                            schedule = quick.toTaskSchedule()
                        )
                        if (created != null) toastScheduledCreated(context, quick)
                    }
                )
            }

            // 悬浮球（FB2）：模型选择已下沉至 FloatBallSessionHub 自足提供（不依赖页面存活），此处无需注册

            // ── 底栏折叠事件接线 ──
            // 放在这里而不是状态声明处，是因为计划/预算审批状态在本层作用域才可见。

            // 阻塞型事件（授权/提问/计划审批/预算审批）：不展开用户就没法继续，必须打断手动折叠。
            val collapseBlocking = pendingPermission != null || pendingQuestion != null ||
                planApproval != null || budgetApproval != null
            LaunchedEffect(collapseBlocking) {
                if (collapseBlocking) {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.BLOCKING)
                }
            }

            // 需介入：目标受阻/预算耗尽，或回合出错。
            val goalNeedsHelp = currentGoal?.status == GoalStatus.BLOCKED ||
                currentGoal?.status == GoalStatus.EXHAUSTED
            val turnErrored = agentState is AgentUIState.Error
            LaunchedEffect(goalNeedsHelp, turnErrored) {
                if (goalNeedsHelp || turnErrored) {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.NEED_INTERVENTION)
                }
            }

            // 用户发了新消息 = 意图变了：展开并清掉本轮的「手动收起」粘性。
            val lastUserMessageId = messages.lastOrNull {
                it.role == MessageRole.USER && !it.isBackgroundNotification
            }?.id
            LaunchedEffect(lastUserMessageId) {
                if (lastUserMessageId != null) {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.USER_SENT)
                }
            }

            // 任务全部完成且空闲：通知级展开（用户本轮手动收起过就不打扰，见 BottomBarCollapse）。
            val allTodosDone = currentTodoItems.isNotEmpty() &&
                currentTodoItems.all { it.status == TodoStatus.COMPLETED }
            LaunchedEffect(allTodosDone, isBusy) {
                if (allTodosDone && !isBusy) {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.COMPLETED)
                }
            }

            // 交付物：AI 发来带附件的消息（send_file）也算「需要你看一眼」，与完成同级。
            val lastDeliveryId = messages.lastOrNull {
                it.role == MessageRole.ASSISTANT && it.attachments.isNotEmpty()
            }?.id
            LaunchedEffect(lastDeliveryId) {
                if (lastDeliveryId != null) {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.DELIVERY)
                }
            }

            // 验收报告 = 用户说的「需要测试」：AI 已自测完，轮到人看结果，必须展开。
            val lastVerificationId = messages.lastOrNull { it.isVerificationReport }?.id
            LaunchedEffect(lastVerificationId) {
                if (lastVerificationId != null) {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.NEED_INTERVENTION)
                }
            }

            val showsTaskRail = bottomBarCollapse.showsRail(isBusy)

            // 轨道「卡住告警」的活动信号：任一项变化就说明任务还在动。
            // 用 remember 包一层让列表身份稳定，否则每次重组都是新对象、计时被无限重置。
            val railActivityKey = remember(
                messages.size,
                streamingText?.length,
                runningTool,
                currentTodoItems,
                isCompacting
            ) {
                listOf(
                    messages.size,
                    streamingText?.length ?: 0,
                    runningTool,
                    currentTodoItems.map { it.status },
                    isCompacting
                )
            }

            ChatInputBar(
                value = inputText,
                onValueChange = viewModel::updateInputDraft,
                onSend = sendMessage,
                onVoiceHoldStart = {
                    voiceProcessing = false
                    voiceCancelled = false
                    // 长按接管：清掉未完成的点击聆听标记，识别结果统一按长按路径直接发送
                    voiceTapActive = false
                    voiceController.start()
                },
                onVoiceHoldFinish = { transcribe ->
                    // transcribe=false（原地松手）→ 识别后自动发送；true（滑到转文字）→ 填输入框可编辑
                    pendingAutoSend.value = !transcribe
                    voiceProcessing = true
                    voiceController.finish()
                },
                onVoiceHoldCancel = {
                    voiceProcessing = false
                    voiceCancelled = true
                    voiceController.cancel()
                },
                voiceProcessing = voiceProcessing,
                voiceTapActive = voiceTapActive,
                onVoiceTapCancel = {
                    voiceProcessing = false
                    voiceTapActive = false
                    voiceCancelled = true
                    voiceController.cancel()
                },
                enterToSend = settingsViewModel?.enterToSend?.collectAsStateWithLifecycle()?.value ?: false,
                onStop = { viewModel.stopAgent() },
                isBusy = isBusy,
                workspaceViewModel = workspaceViewModel,
                hasRunningSessions = { viewModel.hasRunningSessionsInCurrentWorkspace() },
                onSwitchWorkspaceConfirmed = { viewModel.stopAllAndCloseTerminal() },
                activeProvider = activeProvider,
                providers = providers,
                modelMetadata = modelMetadata,
                onSelectModel = { p, m ->
                    viewModel.setSessionProviderModel(p, m)
                },
                sessionEngine = sessionEngine,
                onSetSessionEngine = { engine -> viewModel.setSessionEngine(engine) },
                sessionCliModel = sessionCliModel,
                sessionCliProviderId = sessionCliProviderId,
                awaitingApproval = frameAwaitingApproval,
                onSelectCliModel = { p, m -> viewModel.setSessionCliModel(p, m) },
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                currentEngineMode = currentSessionState?.engineMode,
                onSelectEngineMode = { viewModel.setSessionEngineMode(it) },
                onModePillLongClick = {
                    when (currentMode) {
                        AgentMode.PLAN -> showScheduledPlanSheet = true
                        else -> showScheduledTaskSheet = true
                    }
                },
                intentType = draftIntentType,
                onIntentTypeChange = viewModel::updateDraftIntentType,
                currentGoal = currentGoal,
                onSetGoalPaused = { viewModel.setGoalPaused(it) },
                onClearGoal = { viewModel.clearGoal() },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) },
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = ::removePendingAttachment,
                canUploadFiles = canUploadFiles,
                canUploadImages = canUploadImages,
                onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                onUploadImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhoto = ::takePhoto,
                onVoiceInput = { launchVoiceInput() },
                slashCommands = slashCommands,
                fileMentionState = fileMentionState,
                onFileMentionOpen = viewModel::requestFileMention,
                onFileMentionDismiss = viewModel::dismissFileMention,
                matchFileMentions = viewModel::matchFileMentions,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                onMoveQueued = { from, to -> viewModel.moveQueuedRequest(from, to) },
                onEditQueued = { queuedEditing = it },
                onInterjectQueued = { viewModel.interjectQueuedRequest(it) },
                onPassiveForwardQueued = { viewModel.passiveForwardQueuedRequest(it) },
                dashboardState = currentDashboardState,
                todoItems = currentTodoItems,
                sessionId = currentSessionId.orEmpty(),
                onTodoExpandedChange = { todoExpanded = it },
                voiceSetupCard = if (showVoiceSetupCard) {
                    {
                        VoiceFallbackCard(
                            state = voiceModelState,
                            failureReason = viewModel.voiceModelFailureReason,
                            onDownload = { viewModel.startVoiceModelDownload() },
                            onDismiss = { showVoiceSetupCard = false },
                        )
                    }
                } else null,
                forceCollapseDashboard = pendingPermission != null || pendingQuestion != null || planApproval != null || budgetApproval != null || imeVisible,
                onDashboardExpandedChange = { dashboardExpanded = it },
                onRefreshDashboard = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "manual")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                onRefreshDashboardByButton = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "button")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                tokenEstimated = contextUsages[currentSessionId]?.estimated ?: (sessionLastInputTokens <= 0 && currentSessionId != null),
                tokenProgress = run {
                    // CLI 引擎会话没有 modelMetadata，旧逻辑回退 0 导致额度环恒不显示（用户反馈环消失）；
                    // 无元数据时按默认窗口估算，让环始终有分母。
                    val contextLimit = activeModelMetadata
                        ?.let { com.aicode.feature.settings.domain.model.ModelContextPolicy.effectiveInputBudget(it) }
                        ?: com.aicode.feature.settings.domain.model.ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
                    val usage = contextUsages[currentSessionId]?.takeIf { sessionLastInputTokens > 0 || isBusy }
                    (usage?.inputTokens ?: sessionLastInputTokens).toFloat() / contextLimit
                },
                isScrolling = listState.isScrollInProgress,
                forceOpenModelSheet = onboardingStep == OnboardingStep.SIMULATE_CHOOSE_MODEL,
                onModelSheetDismiss = onDismissModelSheetInOnboarding,
                onSelectModelInOnboarding = onSelectModelInOnboarding,
                onRequestCollapse = {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.USER_COLLAPSED)
                },
                dashboardExpandSignal = dashboardExpandSignal,
                collapsed = showsTaskRail,
                activityKey = railActivityKey,
                isPolishing = isPolishing,
                onPolish = { viewModel.polishInput() },
                onUndoPolish = { viewModel.restoreDraftBeforePolish() },
                onToggleCollapsed = {
                    bottomBarCollapse = bottomBarCollapse.reduce(BottomBarCollapseEvent.USER_EXPANDED)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onboardingTarget(OnboardingStep.SEND_MESSAGE)
            )
            } // 悬浮层结束

            // 悬浮授权弹窗：独立于底栏排版流，悬浮于输入框上方，避免撑大底栏高度导致消息列表被顶上去。
            // 用户上下滑动消息列表时跟随透明弱化，不遮挡阅读视线。
            val permissionForPanel = rememberLastNonNull(pendingPermission)
            var permissionPanelHeightPx by remember { mutableStateOf(0) }
            AnimatedVisibility(
                visible = pendingPermission != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(LocalDensity.current) { floatingLayerHeightPx.toDp() } + Spacing.xs)
                    .onGloballyPositioned { permissionPanelHeightPx = if (pendingPermission != null) it.size.height else 0 },
                enter = fadeIn(tween(220)) +
                        slideInVertically(tween(220)) { it / 3 } +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
                exit = fadeOut(tween(160)) +
                       slideOutVertically(tween(160)) { it / 3 } +
                       scaleOut(targetScale = 0.96f, animationSpec = tween(160))
            ) {
                permissionForPanel?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) },
                        sessionTitle = pendingPermissionSessionTitle,
                        forceCollapse = dashboardCollapseActive,
                        isScrolling = listState.isScrollInProgress
                    )
                }
            }

            // 滚动到底部按钮：悬浮在输入框右上角上方（避让底栏及可能悬浮的授权弹窗），离底超过半屏时
            // 显示，不看滚动方向——往上翻历史后停住恰恰是最需要一键回底的时刻；滚动时跟随输入框淡出。
            val permissionOffsetPx = with(LocalDensity.current) {
                if (pendingPermission != null) permissionPanelHeightPx + Spacing.xs.toPx() else 0f
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.lg)
                    .padding(bottom = with(LocalDensity.current) { (floatingLayerHeightPx + permissionOffsetPx + FLOATING_LAYER_GAP_DP.toPx()).toDp() })
                    // 仅在滚动中挂层做淡出；静止时摘层，避免按钮图标缓存旧主题色。
                    .then(
                        if (listState.isScrollInProgress) Modifier.graphicsLayer { alpha = 0.4f } else Modifier
                    ),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ScrollToBottomButton(
                    onClick = {
                        followBottom = true
                        scope.launch { snapToBottom() }
                    }
                )
            }

            targetRewindMessageId?.let { targetId ->
                val targetMsg = messages.find { it.id == targetId }
                RewindOptionsBottomSheet(
                    promptSnippet = targetMsg?.content ?: "",
                    onOptionSelected = { option ->
                        viewModel.executeRewindOption(targetId, option) { text, attachments ->
                            viewModel.updateInputDraft(text)
                            pendingAttachments = attachments.map { it.toPendingAttachment() }
                        }
                    },
                    onDismissRequest = { viewModel.dismissRewindMenu() }
                )
            }

            editingMessage?.let { message ->
                EditMessageDialog(
                    initialText = message.content,
                    onDismiss = { editingMessage = null },
                    onConfirm = { newContent ->
                        viewModel.updateMessageContent(message.id, newContent)
                        editingMessage = null
                    }
                )
            }

            pendingDeleteMessage?.let { message ->
                BatchDeleteConfirmDialog(
                    title = stringResource(R.string.chat_message_delete_confirm_title),
                    message = stringResource(
                        if (message.role == MessageRole.USER) {
                            R.string.chat_message_delete_confirm_user
                        } else {
                            R.string.chat_message_delete_confirm_other
                        }
                    ),
                    onConfirm = {
                        viewModel.deleteMessage(message.id)
                        pendingDeleteMessage = null
                    },
                    onDismiss = { pendingDeleteMessage = null }
                )
            }

            queuedEditing?.let { req ->
                EditMessageDialog(
                    initialText = req.request,
                    onDismiss = { queuedEditing = null },
                    onConfirm = { newContent ->
                        viewModel.updateQueuedRequest(req.id, newContent)
                        queuedEditing = null
                    }
                )
            }
        }
        // 消息操作 mini 悬浮窗：挂在根 Box（限宽正文列之外）——全屏 scrim 与窗口坐标锚定不受
        // 正文列限宽影响；锚点由长按触点 /「更多」按钮位置带出（见 MessageMenuState）。
        messageMenuState?.let { state ->
            val message = state.message
            val menuClipboard = LocalClipboard.current
            val menuCopyScope = rememberCoroutineScope()

            // 整条消息转引用块（超长截断），供「引用追问 / 解释 / 工作区查找」预填输入框。
            fun quoteBlock(content: String, maxChars: Int): String {
                val trimmed = content.trim()
                val clipped = if (trimmed.length > maxChars) trimmed.take(maxChars) + " …" else trimmed
                return clipped.lines().joinToString("\n") { "> $it" }
            }

            MessageContextMenuPopup(
                message = message,
                anchorInWindow = state.anchor,
                containerOriginInWindow = panelRootInWindow,
                onDismiss = { messageMenuState = null },
                onCopy = {
                    menuCopyScope.launch {
                        menuClipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content)))
                    }
                },
                onQuote = {
                    viewModel.queueExternalText(quoteBlock(message.content, 500))
                },
                onExplain = {
                    viewModel.queueExternalText(
                        context.getString(R.string.chat_explain_prefix) + "\n\n" + quoteBlock(message.content, 1500)
                    )
                },
                onSearchWorkspace = {
                    viewModel.queueExternalText(
                        context.getString(R.string.chat_search_workspace_prefix) + "\n\n" + quoteBlock(message.content, 800)
                    )
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message.content)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.chat_action_share)))
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.chat_share_failed), Toast.LENGTH_SHORT).show()
                    }
                },
                onSelectText = { textSelectMessageId = message.id },
                onEditClick = if (message.role == MessageRole.USER) {
                    { editingMessage = message }
                } else null,
                // 删除不限角色：对齐原「更多」菜单的能力集（助手回复也能删）。
                // 用户消息的删除会级联删掉其后整段对话，必须先二次确认。
                onDeleteClick = {
                    messageMenuState = null
                    pendingDeleteMessage = message
                }
            )
        }
        // 文件卡片操作菜单（长按附件弹出；条目构建见 attachmentActionEntries）。
        fileMenuState?.let { state ->
            AnchorMenuPopup(
                title = state.attachment.fileName.ifBlank { context.getString(R.string.common_file) },
                entries = attachmentActionEntries(context, state.attachment, viewModel.fileAccess, scope),
                anchorInWindow = state.anchor,
                containerOriginInWindow = panelRootInWindow,
                onDismiss = { fileMenuState = null }
            )
        }
        }
    }

        // Dialog 是独立 window、不占父布局尺寸，挂在 Scaffold 之后即可覆盖整屏 ——
        // 平板双栏下不会只盖住聊天列，也不会被 MainActivity 画在最上层的全局背景水印压住。
        ImageViewerHost(state = imageViewerState, load = chatImageLoad)
    }
}

/**
 * 记住最后一个非空值，供 [AnimatedVisibility] 的退出动画继续渲染旧内容。
 *
 * 用普通对象而不是 [androidx.compose.runtime.MutableState] 持有：这里只需要跨重组留住上一个值，
 * 不需要它自己触发重组（源值变化本身就会重组读取点），进快照系统反而会多引发一次无效重组。
 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNullHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private class LastNonNullHolder<T : Any>(var value: T? = null)

/**
 * 工具卡片入场动画的调度器：决定哪些消息该播入场、以及各自错开多久。
 *
 * 判据是「本调度器存续期间新追加到尾部」，而不是「timestamp 距今 N 秒内」——
 * 后者在切页返回时会把仍在时间窗内的那批消息再判成新消息，动画重播一遍。
 * 首次调用时列表里已有的消息一律记为存量；向上翻页加载进来的历史比已见最大时间戳更旧，
 * 同样不入场。会话切换时整个调度器重建，新会话的存量消息也不入场。
 */
private class MessageEntryScheduler {
    private val seen = mutableSetOf<String>()
    private val delays = mutableMapOf<String, Long>()
    private var initialized = false
    private var maxSeenTimestamp = Long.MIN_VALUE

    /** 返回「消息 id → 入场延迟（ms）」；不在表内的消息直接显示。 */
    fun schedule(messages: List<AgentUIMessage>): Map<String, Long> {
        if (!initialized) {
            initialized = true
            messages.forEach { seen += it.id }
            maxSeenTimestamp = messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            return emptyMap()
        }
        var consecutive = 0
        for (message in messages) {
            if (message.id in seen) {
                consecutive = 0
                continue
            }
            seen += message.id
            val appendedAtTail = message.timestamp >= maxSeenTimestamp
            maxSeenTimestamp = maxOf(maxSeenTimestamp, message.timestamp)
            if (appendedAtTail && message.role == MessageRole.TOOL) {
                delays[message.id] = (consecutive * MESSAGE_ENTRY_STAGGER_MS)
                    .coerceAtMost(MESSAGE_ENTRY_MAX_STAGGER_MS)
                consecutive++
            } else {
                consecutive = 0
            }
        }
        return delays.toMap()
    }
}

/** 滚动到底部按钮：圆形，悬浮在输入框右上角，不在底部时显示。 */
@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(SCROLL_TO_BOTTOM_BTN_SIZE.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = FeatherIcons.ArrowDown,
                contentDescription = stringResource(R.string.common_scroll_to_bottom),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 定时任务创建成功提示（待排期用专属文案）。 */
private fun toastScheduledCreated(context: android.content.Context, quick: QuickSchedule) {
    val msg = if (quick == QuickSchedule.PENDING) {
        context.getString(R.string.schedule_created_pending_toast)
    } else {
        context.getString(R.string.schedule_created_toast, context.getString(quick.labelRes))
    }
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

