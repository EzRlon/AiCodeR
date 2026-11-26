package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.core.ui.onboarding.OnboardingStep
import com.aicode.core.ui.onboarding.onboardingTarget
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandKind
import com.aicode.feature.agent.domain.engine.AgentEngines
import com.aicode.feature.agent.domain.engine.EngineModeCatalog
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.presentation.SessionGoalUi
import com.aicode.feature.agent.presentation.TeamBannerUi
import com.aicode.feature.agent.presentation.TeamMemberStatus
import com.aicode.feature.agent.presentation.TeamMemberRuntimeUi
import com.aicode.feature.agent.presentation.TeamRuntimeUi
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aicode.feature.agent.domain.mention.FileMentionState
import com.aicode.feature.agent.domain.mention.fileMentionQuery
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import com.aicode.feature.workspace.presentation.component.WorkspaceIconButton
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Feather
import compose.icons.feathericons.Pause
import compose.icons.feathericons.Play
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Target
import compose.icons.feathericons.Users
import compose.icons.feathericons.X
import compose.icons.feathericons.Maximize
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Minimize
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Square
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Tag
import compose.icons.feathericons.Tool
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 输入框区域蒙版高度：盖住圆角容器，滚动内容滑入时被渐变遮罩；随键盘（IME）上移。 */
private val INPUT_BAR_MASK_HEIGHT = 110.dp

/** 输入框高度上下限：超过上限后文本在框内滚动，右上角露出「展开」按钮。与 TextField 的 heightIn 一致。 */
private val INPUT_FIELD_MIN_HEIGHT = 40.dp
private val INPUT_FIELD_MAX_HEIGHT = 140.dp

/**
 * 空闲紧凑态下的输入框地板高度。
 *
 * 空闲时把卡片整体再压 20dp 左右（用户 2026-11-11 提）：无内容、非忙、无附件时输入框只剩
 * 一行占位文本，40dp 的高度显得空旷。36dp 已是视觉与命中率的折中——再低手指就容易点空，
 * 项目此前的 40dp 本身也已低于 Material 建议的 48dp 触摸目标，继续压只会更难用。
 */
private val INPUT_FIELD_MIN_HEIGHT_COMPACT = 36.dp

/** 控制行最小高度：空闲态同步压到 36dp，忙态/有内容时恢复 40dp，避免打字时控件「变脸」。 */
private val CONTROL_ROW_MIN_HEIGHT = 40.dp
private val CONTROL_ROW_MIN_HEIGHT_COMPACT = 36.dp

/** 折叠态底部渐变蒙版高度：只盖住 44dp 轨道 + 一点余量，比展开态短，收起才真正省下高度。 */
private val COLLAPSED_BAR_MASK_HEIGHT = 56.dp

/** 模式说明条自动收起延时：切模式/进入非构建会话自动展开后，展示多久自动收起（用户不应长久固定显示）。 */
private const val MODE_INFO_AUTO_COLLAPSE_MS = 6_000L

/**
 * M3 无标签 TextField 的默认 contentPadding（上下左右各 16dp）。
 * 文本是否超过输入框高度要先用相同约束实测一遍，反推文本可视区域得靠这个值。
 */
private val INPUT_FIELD_CONTENT_PADDING = 16.dp

/** 「展开为全屏输入」热区：右侧尾槽里，空闲时不进布局。 */
private val INPUT_FIELD_EXPAND_SLOT = 28.dp

/**
 * 右侧尾槽总宽（录音入口已统一为长按发送键，槽内只剩展开键）。
 * 宽度与「是否显示展开键」无关，输入框可占字数因此恒定，
 * 不会出现按钮一出现文字区就跳窄一截的问题。
 */
private val INPUT_FIELD_TRAILING_WIDTH = INPUT_FIELD_EXPAND_SLOT

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    /** 回车键是否直接发送：开启后 IME 回车键变为「发送」，关闭则回车换行（默认）。 */
    enterToSend: Boolean = false,
    onStop: () -> Unit,
    isBusy: Boolean,
    /** 长按发送键开始应用内语音识别（微信式「按住说话」）。 */
    onVoiceHoldStart: () -> Unit = {},
    /** 松手：结束收音并等待识别结果；原地松手=识别后直接发送，滑到「转文字」=结果填回输入框。 */
    onVoiceHoldFinish: (transcribe: Boolean) -> Unit = {},
    /** 上滑取消：放弃本次语音。 */
    onVoiceHoldCancel: () -> Unit = {},
    /** 识别处理中（浮层显示「正在识别…」）。 */
    voiceProcessing: Boolean = false,
    /** 识别中间结果：长按期间实时驱动浮层文字气泡。 */
    voicePartialText: String = "",
    /** 长按语音浮层显隐上报：外层据此对内容层加实时高斯模糊。 */
    onVoiceHoldActiveChange: (Boolean) -> Unit = {},
    workspaceViewModel: WorkspaceViewModel?,
    hasRunningSessions: () -> Boolean,
    onSwitchWorkspaceConfirmed: () -> Unit = {},
    activeProvider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    /** 当前会话引擎（引擎切换）；null = 本体内置。 */
    sessionEngine: String? = null,
    /** 会话引擎切换（对当前会话生效，下一回合起用新引擎）；返回 false = 回合进行中拒绝。 */
    onSetSessionEngine: ((String?) -> Boolean)? = null,
    /** CLI 引擎会话的模型名（取自会话 model，供图标与勾选显示）。 */
    sessionCliModel: String? = null,
    /** CLI 引擎会话选定的 App 供应商（供应商模型用）。 */
    sessionCliProviderId: String? = null,
    /** 是否有等待用户裁决的审批（授权/提问/计划）：外框红色快脉冲。 */
    awaitingApproval: Boolean = false,
    onSelectCliModel: (providerId: String?, model: String) -> Unit = { _, _ -> },
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    /** 当前引擎原生工作模式 id（见 EngineModeCatalog）；null = 跟随引擎默认档。 */
    currentEngineMode: String? = null,
    /** 切换引擎原生工作模式。 */
    onSelectEngineMode: (String) -> Unit = {},
    /** 长按模式图标：按当前模式弹「定时任务 / 定时 Plan」创建面板；null 时不响应。 */
    onModePillLongClick: (() -> Unit)? = null,
    /** 本条消息类型键（控制行类型图标按钮）；null=未分类。 */
    intentType: String? = null,
    onIntentTypeChange: (String?) -> Unit = {},
    currentGoal: SessionGoalUi? = null,
    /** 当前生效的专家团（含成员显示名）；null 时不显示横幅。 */
    activeTeamRuntime: TeamRuntimeUi? = null,
    /** 点横幅「更换」：打开选择面板。 */
    onOpenTeamPicker: () -> Unit = {},
    /** 点横幅「移除」：清除本会话的专家团绑定。 */
    onClearTeam: () -> Unit = {},
    onSetGoalPaused: (Boolean) -> Unit = {},
    onClearGoal: () -> Unit = {},
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    pendingAttachments: List<PendingUploadAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    canUploadFiles: Boolean,
    canUploadImages: Boolean,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    slashCommands: List<SlashCommand> = emptyList(),
    fileMentionState: FileMentionState = FileMentionState.Idle,
    onFileMentionOpen: () -> Unit = {},
    onFileMentionDismiss: () -> Unit = {},
    matchFileMentions: (String) -> List<String> = { emptyList() },
    queuedRequests: List<QueuedRequest> = emptyList(),
    onRemoveQueued: (String) -> Unit = {},
    onMoveQueued: (Int, Int) -> Unit = { _, _ -> },
    onEditQueued: (QueuedRequest) -> Unit = {},
    onInterjectQueued: (String) -> Unit = {},
    onPassiveForwardQueued: (String) -> Unit = {},
    tokenProgress: Float = 0f,
    tokenEstimated: Boolean = false,
    dashboardState: ProviderDashboardState = ProviderDashboardState.Idle,
    onRefreshDashboard: () -> Unit = {},
    onRefreshDashboardByButton: () -> Unit = {},
    onDashboardExpandedChange: (Boolean) -> Unit = {},
    todoItems: List<TodoItem> = emptyList(),
    sessionId: String = "",
    onTodoExpandedChange: (Boolean) -> Unit = {},
    forceCollapseDashboard: Boolean = false,
    /** 消息列表正在滚动时内容区淡出到 40%，停止滚动恢复；用于长列表阅读时降低底部干扰（同 git 页 tab 栏）。 */
    isScrolling: Boolean = false,
    forceOpenModelSheet: Boolean = false,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onModelSheetDismiss: (() -> Unit)? = null,
    /** 语音离线模型引导卡片（F2/F7）：系统识别不可用且模型未就绪时由宿主提供内容，渲染在待办面板之后。 */
    voiceSetupCard: (@Composable () -> Unit)? = null,
    /**
     * 忙时在卡片顶边正中渲染「收起把手」并回调此函数；null 时不给折叠入口
     * （例如宿主不在聊天页，或折叠态本就不适用）。
     */
    onRequestCollapse: (() -> Unit)? = null,
    /** 顶栏状态条长按：自增信号，要求把余额面板展开一次。 */
    dashboardExpandSignal: Int = 0,
    /** 折叠态：只渲染 [CollapsedBottomBar]（任务轨道），整条输入卡与上方面板都不组合。 */
    collapsed: Boolean = false,
    /** 在轨道上点任意位置（或上滑）展开。 */
    onToggleCollapsed: () -> Unit = {},
    /**
     * 任务活动信号：变化一次就重置轨道上的「无输出」计时。
     * 宿主用「新消息 / 流式文本长度 / 当前工具 / 待办状态」拼一个稳定的键，不要把每帧都变的
     * 实时值直接塞进来（那会让轨道高频重组）。
     */
    activityKey: Any? = null,
    /** 润色请求进行中（按钮转圈并禁止重复点击）。 */
    isPolishing: Boolean = false,
    /** 点润色：把草稿改写成更专业的表述。 */
    onPolish: () -> Unit = {},
    /** 长按润色键：撤销最近一次润色，恢复原文。 */
    onUndoPolish: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasContent = value.isNotBlank() || pendingAttachments.isNotEmpty()
    val canSend = hasContent
    // 空闲紧凑态：无输入、非忙、无附件时才压高度。打字或任务进行中立刻恢复原尺寸——
    // 否则用户一敲字输入框就「长高」，等于在输入路径上放了一次布局跳动。
    val compactIdle = !isBusy && !hasContent
    val voiceContext = LocalContext.current
    // 业务规则：底栏已有多条待发送消息（≥2）时，发送键的「停止」不再强制中断。
    // 用户连点发送排队时，按钮会在发完后切到停止态；若此时放行，会误停并把多条排队消息一起丢掉。
    // 队列只有 0/1 条时仍按原行为停止；多条时给出提示，让用户先处理队列。
    val stopUnlessQueued: () -> Unit = {
        if (queuedRequests.size >= 2) {
            android.widget.Toast.makeText(
                voiceContext,
                R.string.chat_stop_blocked_by_queue,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            onStop()
        }
    }

    // 折叠态走早返回：整条输入卡（含 BasicTextField、待办/用量面板、所有底部弹层）都不组合，
    // 高度真正降到轨道那 44dp。不用 AnimatedVisibility 包住整个函数体——那需要给 700 行
    // 内容整体缩进，而这层切换本身没有需要保留的组合状态（输入文本由宿主持有）。
    // 收起/展开的高度动画由宿主侧的 animateContentSize 负责。
    if (collapsed) {
        CollapsedBottomBar(
            items = todoItems,
            queuedCount = queuedRequests.size,
            activityKey = activityKey,
            onExpand = onToggleCollapsed,
            // 折叠态的停止是显式控件，不属于「发送键连点」手势，保持原行为。
            onStop = onStop,
            modifier = modifier
        )
        return
    }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showFullScreenInput by remember { mutableStateOf(false) }
    var showIntentPicker by remember { mutableStateOf(false) }
    // TextField 的 String 重载在整串替换时会保留旧 selection 下标（点快捷命令补全时光标会停在中间），
    // 这里自持 TextFieldValue：外部改值时把光标移到末尾。
    var inputFieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (inputFieldValue.text != value) {
            inputFieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }
    val showSlashMenu = !isBusy && slashCommands.isNotEmpty() &&
        value.startsWith("/") && !value.contains("\n")
    val filteredCommands = if (showSlashMenu) {
        if (value == "/") slashCommands
        else slashCommands.filter { "/${it.name}".startsWith(value) }
    } else emptyList()

    // `@` 文件引用：`/` 菜单优先，忙碌时不弹；查询词从文本末尾提取。
    val mentionQuery = if (!isBusy && !showSlashMenu) fileMentionQuery(value) else null

    // ── 语音「按住说话」：长按发送键录音，松手识别并发送；上滑取消。──
    var voiceHoldActive by remember { mutableStateOf(false) }
    // 长按语音的手势分支，实时驱动浮层分支提示（微信式）；传对象引用给 SendButton 写、浮层读
    val voiceDragTarget = remember { mutableStateOf(VoiceDragTarget.SEND) }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            android.widget.Toast.makeText(
                voiceContext,
                voiceContext.getString(R.string.chat_voice_permission_granted),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    val mentionFiles = remember(mentionQuery, fileMentionState) {
        if (mentionQuery != null && fileMentionState is FileMentionState.Ready) {
            matchFileMentions(mentionQuery)
        } else {
            emptyList()
        }
    }
    LaunchedEffect(mentionQuery) {
        if (mentionQuery != null) onFileMentionOpen() else onFileMentionDismiss()
    }
    // 选中文件：把 `@查询词` 替换为 `@相对路径 `，光标移到末尾。
    val applyMention: (String) -> Unit = { path ->
        val at = value.lastIndexOf('@')
        if (at >= 0) {
            val newText = value.substring(0, at) + "@" + path + " "
            inputFieldValue = TextFieldValue(newText, TextRange(newText.length))
            onValueChange(newText)
        }
    }

    // 文本是否已经多行（卡片向上延伸）。按 TextField 实际可占宽度实测文本高度：
    // 宽度要用「输入框宽 - 左内距 - 右侧尾槽」，以前只减左右各 16dp，把尾槽那 38dp
    // 也算进可用宽度，导致换行判定偏晚、可占字数对不上（用户 2026-11-12 反馈）。
    // 走 derivedStateOf：只在「是否多行」结果变化时触发重组，不必每次键入都重绘整条底栏。
    val textMeasurer = rememberTextMeasurer()
    val inputDensity = LocalDensity.current
    var inputFieldWidthPx by remember { mutableStateOf(0) }
    val inputTextStyle = MaterialTheme.typography.bodyLarge
    val inputGrew by remember(value, inputFieldWidthPx, inputDensity, inputTextStyle) {
        derivedStateOf {
            if (inputFieldWidthPx <= 0 || value.isEmpty()) return@derivedStateOf false
            val startPx = with(inputDensity) { INPUT_FIELD_CONTENT_PADDING.toPx() }
            val endPx = with(inputDensity) { INPUT_FIELD_TRAILING_WIDTH.toPx() }
            val contentWidthPx = inputFieldWidthPx - startPx.toInt() - endPx.toInt()
            if (contentWidthPx <= 0) return@derivedStateOf false
            val textHeightPx = textMeasurer.measure(
                text = AnnotatedString(value),
                style = inputTextStyle,
                constraints = Constraints(maxWidth = contentWidthPx)
            ).size.height
            // bodyLarge 行高 24sp（见 AppTypography）；超过一行半即视为已换行。
            textHeightPx > with(inputDensity) { 24.sp.toPx() } * 1.5f
        }
    }

    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        val imeInset = rememberImeBottomInset()
        // 滚动弱化：内容区（slash 菜单/排队面板/数据面板/输入框本体）整体淡出到 40%，蒙版渐变保持不透明。
        val contentAlpha by animateFloatAsState(
            targetValue = if (isScrolling) 0.4f else 1f,
            animationSpec = tween(200),
            label = "inputbar-content-alpha"
        )
        // 渐变终点固定在蒙版可视高度内：若跟随整个 Box（含 imeInset 被键盘拉长的部分），
        // 键盘弹起时可见区域只占渐变前段，alpha 被摊薄到几乎透明——看起来像没有蒙版。
        val maskGradientEndY = with(LocalDensity.current) { INPUT_BAR_MASK_HEIGHT.toPx() }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val dashboardBodyMaxHeight = (maxHeight - imeInset - Spacing.md).coerceAtLeast(0.dp) * 0.4f
            // 半透明渐变蒙版：盖住输入框区域 + 导航栏（手势小白条）区域，滚动内容滑入时被遮罩
            // （能看见但看不清）；高度含 IME inset 随键盘上移，渐变在 INPUT_BAR_MASK_HEIGHT 内完成，
            // 之下为纯色。注意 IME padding 不能加在外层 Box 上——align(BottomCenter) 的子项
            // 对齐在 padding 内部，会漏掉导航栏那条区域。
            // 蒙版不能参与 Box 的尺寸测量：它固定 110dp 时会把「空闲更紧凑」省下的高度
            // 原样补回来（Box 高 = max(内容, 蒙版)），用户看到的是一点没矮的卡片。
            // 用 matchParentSize 让它只跟随内容高度、不影响测量，且声明在内容之前所以画在底层。
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(INPUT_BAR_MASK_HEIGHT + imeInset)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    // 蒙版是「页面背景」向滚动内容的过渡，必须取 background 而不是
                                    // surface：深色/非默认主题下二者不同色，用 surface 会在底栏下方
                                    // 露出一条与页面不一致的色板。
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
                                ),
                                startY = 0f,
                                endY = maskGradientEndY
                            )
                        )
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 只在弱化进行中挂层：常驻 graphicsLayer 离屏缓存不随主题切换刷新，
                    // 整条卡片（含上方面板）会停在旧配色直到触摸（同 AIChatPanel 横幅修复）。
                    .then(if (contentAlpha < 1f) Modifier.graphicsLayer { alpha = contentAlpha } else Modifier)
                    // 顶部预留进度环的外扩空间：本 Column 经 graphicsLayer 图层渲染，
                    // 绘制在图层 bounds 外会被裁掉，卡片上方无兄弟节点时环顶边会缺失。
                    // 空闲态压到 2dp，忙态恢复 6dp（环只在忙态出现）。
                    .padding(top = if (compactIdle) 2.dp else 6.dp)
                    .padding(horizontal = Spacing.lg)
                    // 底部间隙：导航栏/手势条由 imeInset 兜住，这里只是视觉留白，
                    // 空闲态从 8dp 收到 4dp。
                    .padding(bottom = if (compactIdle) Spacing.xs else Spacing.sm)
                    .padding(bottom = imeInset)
            ) {
            if (filteredCommands.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    ) {
                        filteredCommands.forEach { command ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .clickable {
                                        onValueChange(
                                            if (command.acceptsArgs) "/${command.name} " else "/${command.name}"
                                        )
                                    }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "/${command.name}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (command.kind == SlashCommandKind.SKILL) {
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        stringResource(R.string.settings_skills),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (mentionQuery != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    ) {
                        val mentionHint = when (fileMentionState) {
                            FileMentionState.Idle, FileMentionState.Loading ->
                                stringResource(R.string.chat_mention_loading)
                            FileMentionState.Unavailable ->
                                stringResource(R.string.chat_mention_unavailable)
                            is FileMentionState.Error ->
                                stringResource(R.string.chat_mention_error)
                            is FileMentionState.Ready -> null
                        }
                        when {
                            mentionHint != null -> Text(
                                mentionHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            )
                            mentionFiles.isEmpty() -> Text(
                                stringResource(R.string.chat_mention_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            )
                            else -> mentionFiles.forEach { path ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.sm))
                                        .clickable { applyMention(path) }
                                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (voiceHoldActive || voiceProcessing) {
                VoiceHoldStage(
                    processing = voiceProcessing,
                    dragTarget = voiceDragTarget.value,
                    partialText = voicePartialText
                )
            }

            if (queuedRequests.isNotEmpty()) {
                QueuedRequestPanel(
                    queuedRequests = queuedRequests,
                    sessionId = sessionId,
                    forceCollapse = forceCollapseDashboard,
                    onRemoveQueued = onRemoveQueued,
                    onMoveQueued = onMoveQueued,
                    onEditQueued = onEditQueued,
                    onInterjectQueued = onInterjectQueued,
                    onPassiveForwardQueued = onPassiveForwardQueued
                )
            }

            if (todoItems.isNotEmpty()) {
                TodoDashboardBar(
                    items = todoItems,
                    sessionId = sessionId,
                    forceCollapse = forceCollapseDashboard,
                    animateInProgress = isBusy,
                    onExpandedChange = onTodoExpandedChange
                )
            }

            voiceSetupCard?.invoke()

            if (activeProvider != null && activeProvider.dashboardScriptPath.isNotBlank()) {
                ProviderDashboardBar(
                    provider = activeProvider,
                    state = dashboardState,
                    onRefresh = onRefreshDashboard,
                    onRefreshByButton = onRefreshDashboardByButton,
                    onExpandedChange = onDashboardExpandedChange,
                    maxExpandedBodyHeight = dashboardBodyMaxHeight,
                    forceCollapse = forceCollapseDashboard,
                    expandSignal = dashboardExpandSignal
                )
            }

            currentGoal?.let { goal ->
                GoalBannerRow(
                    goal = goal,
                    onSetPaused = onSetGoalPaused,
                    onClear = onClearGoal
                )
            }

            // 已召唤专家团横幅：人员状态表（谁在干活/干到哪），点击向上展开、空闲自动翻滚。
            activeTeamRuntime?.let { runtime ->
                TeamBannerRow(
                    runtime = runtime,
                    onChange = onOpenTeamPicker,
                    onClear = onClearTeam
                )
            }

            // 任务危险程度动态外框（用户 2026-10-24 定稿）：不做任务进度（待办已有展示位），
            // 外框只表达危险程度——等待审批=红快脉冲（最高危）；执行中=跟随发送按钮忙色慢流动。
            // 颜色与发送按钮状态语义同步；描边极细（约 0.55px）；空闲不渲染。
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        // 卡片底色必须取页面背景色（不是 surface）：深色主题里 surface 比
                        // background 明显更亮（如 #191B1F vs #101114），用 surface 会让底栏
                        // 在深色下浮出一块异色底板，与页面分成两种颜色（用户反复反馈）。
                        .background(MaterialTheme.colorScheme.background)
                        // 空闲态上下内距收到 2dp：卡片看起来更薄，又不至于让输入框贴住边。
                        .padding(
                            horizontal = Spacing.sm,
                            vertical = if (compactIdle) 2.dp else Spacing.xs
                        )
                ) {
                    // 收起把手：忙时才出现，贴在卡片顶边正中。点击把整条底栏收成 44dp 任务轨道
                    // （用户 2026-11-11 提；"拉链头"即 bottom sheet 的 grabber）。
                    // 只在忙时给入口——空闲状态没有任务可看，多一条把手反而是噪音。
                    if (isBusy && onRequestCollapse != null) {
                        CollapseGrabber(onCollapse = onRequestCollapse)
                    }

                    PendingAttachmentPreviewList(
                        attachments = pendingAttachments,
                        onRemoveAttachment = onRemoveAttachment
                    )

                    // 模式切换不再弹「已切换」Toast（验收反馈：底栏模式说明条已承载模式提示，弹窗多余）。

                    // 模式说明条（验收反馈）：底栏向上拓展展示当前模式规则。
                    // 默认：切到非默认档位自动展开；默认档位默认收起，再次点击已点亮的模式图标开关本说明。
                    val engineModeEntries = EngineModeCatalog.entriesFor(sessionEngine)
                    val activeModeId = EngineModeCatalog.normalize(sessionEngine, currentEngineMode)
                    val defaultModeId = EngineModeCatalog.defaultModeId(sessionEngine)
                    val activeSemantic = EngineModeCatalog.semantic(sessionEngine, activeModeId)
                    var modeInfoVisible by rememberSaveable(sessionId) { mutableStateOf(activeModeId != defaultModeId) }
                    var lastModeId by remember(sessionId) { mutableStateOf(activeModeId) }
                    // 切到任一档位都把它的说明条展开（含默认档）；首次进入不算切换，保持默认收起。
                    LaunchedEffect(activeModeId) {
                        if (activeModeId != lastModeId) {
                            lastModeId = activeModeId
                            modeInfoVisible = true
                        }
                    }
                    // 自动展开的说明条展示数秒后自动收起（验收反馈：不应长久固定占用底栏高度）。
                    // 键随档位/会话重启：切档重开计时；用户中途手动点开（无档位变化）不受影响。
                    LaunchedEffect(activeModeId, sessionId) {
                        delay(MODE_INFO_AUTO_COLLAPSE_MS)
                        modeInfoVisible = false
                    }
                    var modeMenuVisible by remember { mutableStateOf(false) }
                    AnimatedVisibility(
                        visible = modeInfoVisible,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(160)),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xs)
                                .padding(bottom = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = modeIcon(activeSemantic),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        engineModeBannerRes(sessionEngine, activeModeId)
                                            ?: modeBannerRes(activeSemantic)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // 外部引擎的模式由引擎原生能力实现，行为细节与内置引擎不同，必须如实告知。
                                engineModeNoteRes(sessionEngine, activeSemantic)?.let { noteRes ->
                                    Text(
                                        text = stringResource(noteRes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        if (activeSemantic == AgentMode.PLAN) R.string.chat_mode_longpress_hint_plan
                                        else R.string.chat_mode_longpress_hint
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (activeModeId == defaultModeId) modeInfoVisible = false
                                    else onSelectEngineMode(defaultModeId)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    FeatherIcons.X,
                                    contentDescription = stringResource(
                                        if (activeModeId == defaultModeId) R.string.common_collapse
                                        else R.string.agent_mode_back_to_build
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        // BasicTextField 而非 TextField：M3 TextField 内部强制 56dp 地板，
                        // 空闲高度压不下去；这里自己控制地板 44dp，行为（键盘/光标/多行滚动）不变。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(
                                    min = if (compactIdle) INPUT_FIELD_MIN_HEIGHT_COMPACT else INPUT_FIELD_MIN_HEIGHT,
                                    max = INPUT_FIELD_MAX_HEIGHT
                                )
                                .onSizeChanged { inputFieldWidthPx = it.width },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = inputFieldValue,
                                onValueChange = { new ->
                                    inputFieldValue = new
                                    onValueChange(new.text)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 右侧留出整个尾槽（展开键 + 麦克风），不叠加 16dp 内容内边距：
                                    // 之前文字末尾到麦克风空出一大块（用户反馈）。
                                    .padding(
                                        start = INPUT_FIELD_CONTENT_PADDING,
                                        end = INPUT_FIELD_TRAILING_WIDTH
                                    ),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = if (enterToSend) {
                                    KeyboardOptions(imeAction = ImeAction.Send)
                                } else {
                                    KeyboardOptions(imeAction = ImeAction.Default)
                                },
                                keyboardActions = if (enterToSend) {
                                    KeyboardActions(onSend = { onSend() })
                                } else {
                                    KeyboardActions.Default
                                },
                                decorationBox = { inner ->
                                    if (inputFieldValue.text.isEmpty()) {
                                        Text(
                                            stringResource(if (isBusy) R.string.chat_queue_hint else R.string.chat_input_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    inner()
                                }
                            )
                            // 右侧尾槽：全屏键在卡片因文本变高时出现；空闲/未换行时不进布局、不占宽
                            //（录音入口已统一为长按发送键，无独立麦克风）。
                            if (inputGrew) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 2.dp)
                                        .size(INPUT_FIELD_EXPAND_SLOT)
                                        .clip(CircleShape)
                                        .clickable { showFullScreenInput = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        FeatherIcons.Maximize,
                                        contentDescription = stringResource(R.string.chat_input_expand),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 控制行高度保底：上层 Column 总高溢出时防测量挤压（发送按钮变形，用户反馈）。
                            // 空闲态同步压到 36dp 并去掉纵向内距——这是「整条底栏再矮一点」的主要来源。
                            .heightIn(
                                min = if (compactIdle) CONTROL_ROW_MIN_HEIGHT_COMPACT else CONTROL_ROW_MIN_HEIGHT
                            )
                            .padding(
                                horizontal = Spacing.xs,
                                vertical = if (compactIdle) 0.dp else 2.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 控制行用 FlowRow：屏幕窄时自动换行，保证每个按钮都完整可见（用户反馈：
                        // 小屏设备上思考强度按钮直接不见了）。不再用横向滑动——滑不到的按钮等于不存在。
                        // 三键均匀分布：左对齐会把空隙全堆到标签键与发送键之间，视觉上标签键偏左。
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            maxItemsInEachRow = Int.MAX_VALUE
                        ) {
                            // 模式区：构建 / 计划 / 自动合并成一个按钮（用户反馈：三个独立按钮占位太多）。
                            // 点按弹模式菜单，选中即切换并自动展开说明条；再点当前模式只开关说明条；
                            // 长按进「定时任务 / 定时 Plan」面板。
                            Box {
                                Icon(
                                    imageVector = modeIcon(activeSemantic),
                                    contentDescription = stringResource(engineModeLabelRes(sessionEngine, activeModeId)),
                                    tint = when (activeSemantic) {
                                        AgentMode.BUILD -> MaterialTheme.semanticColors.success
                                        AgentMode.PLAN -> MaterialTheme.colorScheme.primary
                                        AgentMode.AUTO -> MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .combinedClickable(
                                            onClick = { modeMenuVisible = true },
                                            onLongClick = onModePillLongClick
                                        )
                                        .padding(8.dp)
                                )
                                DropdownMenu(
                                    expanded = modeMenuVisible,
                                    onDismissRequest = { modeMenuVisible = false }
                                ) {
                                    engineModeEntries.forEach { entry ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(engineModeLabelRes(sessionEngine, entry.id))) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = modeIcon(entry.semantic),
                                                    contentDescription = null,
                                                    tint = if (entry.id == activeModeId) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            trailingIcon = if (entry.id == activeModeId) {
                                                {
                                                    Icon(
                                                        FeatherIcons.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else null,
                                            onClick = {
                                                modeMenuVisible = false
                                                if (entry.id == activeModeId) modeInfoVisible = !modeInfoVisible
                                                else onSelectEngineMode(entry.id)
                                            }
                                        )
                                    }
                                }
                            }

                            ModelIconButton(
                                provider = activeProvider,
                                providers = providers,
                                modelMetadata = modelMetadata,
                                onSelectModel = onSelectModel,
                                engine = sessionEngine,
                                cliModel = sessionCliModel,
                                cliProviderId = sessionCliProviderId,
                                onSelectCliModel = onSelectCliModel,
                                forceOpenSheet = forceOpenModelSheet,
                                onSelectSuccessInOnboarding = onSelectModelInOnboarding,
                                onSheetDismiss = onModelSheetDismiss,
                                onSetEngine = onSetSessionEngine,
                                modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_MODEL_PICKER)
                            )


                            // 元数据未命中时（如中转站改了模型名）退回全部档位由用户自选：
                            // 这类模型可能强制开启推理，隐藏按钮会导致无法调档。
                            val availableEfforts = remember(activeProvider, modelMetadata) {
                                activeProvider?.let { provider ->
                                    modelMetadata[modelMetadataKey(provider.id, provider.effectiveModel)]?.reasoningEffortOptions
                                        ?.let { ReasoningEffort.fromValues(it) }
                                }.orEmpty().ifEmpty { ReasoningEffort.entries }
                            }
                            // 思考强度：从控制行收纳进意图菜单（为发送按钮居中腾位）
                            Box {
                                IntentIconButton(
                                    intentType = intentType,
                                    onClick = { showIntentPicker = true }
                                )
                                DropdownMenu(
                                    expanded = showIntentPicker,
                                    onDismissRequest = { showIntentPicker = false },
                                    modifier = Modifier.heightIn(max = 320.dp),
                                    scrollState = rememberScrollState(),
                                ) {
                                    // 思考强度：**只靠「选中即实心」表达状态**——等级前不放图标、后面不打勾
                                    // （用户 2026-11-11 反馈：图标+对勾让一行塞不下，视觉也乱）。
                                    // 档位数量随模型变化（3~7 个），FlowRow 自动换行，窄屏也不会被压扁。
                                    MenuSectionLabel(
                                        text = stringResource(R.string.chat_reasoning_effort),
                                        icon = FeatherIcons.Zap
                                    )
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        availableEfforts.forEach { candidate ->
                                            OptionChip(
                                                label = stringResource(candidate.labelRes()),
                                                selected = candidate == reasoningEffort,
                                                enabled = !isBusy,
                                                onClick = { onReasoningEffortChange(candidate) }
                                            )
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )
                                    // 反馈标签：与思考强度同一套胶囊语言——无图标、无对勾，选中实心。
                                    // 分「编程 / 通用」两类平铺，不再折叠：折叠会把选项藏起来，用户反馈
                                    // 希望一眼看全（选中后控制行上的标签按钮图标仍然会变，那部分保留）。
                                    MenuSectionLabel(text = stringResource(R.string.intent_picker_title))
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, top = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OptionChip(
                                            label = stringResource(R.string.intent_none),
                                            selected = intentType == null,
                                            enabled = true,
                                            onClick = {
                                                showIntentPicker = false
                                                onIntentTypeChange(null)
                                            }
                                        )
                                    }
                                    MenuGroupCaption(text = stringResource(R.string.intent_picker_coding))
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        MessageIntentCatalog.coding.forEach { item ->
                                            OptionChip(
                                                label = stringResource(item.labelRes),
                                                selected = item.key == intentType,
                                                enabled = true,
                                                onClick = {
                                                    showIntentPicker = false
                                                    onIntentTypeChange(item.key)
                                                }
                                            )
                                        }
                                    }
                                    MenuGroupCaption(text = stringResource(R.string.intent_picker_general))
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        MessageIntentCatalog.general.forEach { item ->
                                            OptionChip(
                                                label = stringResource(item.labelRes),
                                                selected = item.key == intentType,
                                                enabled = true,
                                                onClick = {
                                                    showIntentPicker = false
                                                    onIntentTypeChange(item.key)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 工作中也可用：附件/语音输入随消息排队发送（AIChatPanel 统一队列链路）。
                        SendButton(
                            canSend = canSend,
                            hasContent = hasContent,
                            isBusy = isBusy,
                            tokenProgress = tokenProgress,
                            tokenEstimated = tokenEstimated,
                            onSend = onSend,
                            onStop = stopUnlessQueued,
                            onVoiceHoldStart = {
                                if (voiceContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    voiceHoldActive = true
                                    onVoiceHoldStart()
                                } else {
                                    recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onVoiceHoldFinish = { transcribe ->
                                if (voiceHoldActive) {
                                    voiceHoldActive = false
                                    onVoiceHoldFinish(transcribe)
                                }
                            },
                            onVoiceHoldCancel = {
                                if (voiceHoldActive) {
                                    voiceHoldActive = false
                                    onVoiceHoldCancel()
                                }
                            },
                            dragTarget = voiceDragTarget
                        )
                        // 两侧等宽（左：模式/模型/标签；右：润色/工作区/附件），发送键靠两个
                        // weight(1f) 落在正中。工具入口已按用户要求移除（原来只是占位的空弹层）。
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 润色放在工具组最前：它作用于「正在写的这条草稿」，与旁边的工具/附件
                            // 同属「对输入做点什么」，比塞进工具弹层里少一次点击。
                            PolishIconButton(
                                enabled = value.isNotBlank(),
                                running = isPolishing,
                                onPolish = onPolish,
                                onUndo = onUndoPolish
                            )
                            if (workspaceViewModel != null) {
                                WorkspaceIconButton(
                                    viewModel = workspaceViewModel,
                                    hasRunningSessions = hasRunningSessions,
                                    onSwitchConfirmed = onSwitchWorkspaceConfirmed,
                                    modifier = Modifier.size(36.dp),
                                    iconSize = 20.dp
                                )
                            }
                            UploadIconButton(
                                enabled = true,
                                icon = FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.chat_add_attachment),
                                onClick = { showAttachmentSheet = true }
                            )
                        }
                    }
                }

            }
        }
        }
    }

    if (showAttachmentSheet) {
        AttachmentSheet(
            canUploadFiles = canUploadFiles,
            canUploadImages = canUploadImages,
            onUploadFile = {
                showAttachmentSheet = false
                onUploadFile()
            },
            onUploadImage = {
                showAttachmentSheet = false
                onUploadImage()
            },
            onTakePhoto = {
                showAttachmentSheet = false
                onTakePhoto()
            },
            onDismiss = { showAttachmentSheet = false }
        )
    }

    if (showFullScreenInput) {
        FullScreenInputDialog(
            value = value,
            onValueChange = onValueChange,
            placeholder = stringResource(if (isBusy) R.string.chat_queue_hint else R.string.chat_input_placeholder),
            onDismiss = { showFullScreenInput = false }
        )
    }
}

/** 把手下滑多少距离算「收起」手势。 */
private val GRABBER_SWIPE_THRESHOLD = 24.dp

/**
 * 卡片顶边正中的收起把手（bottom sheet 式 grabber）。
 *
 * 只占 16dp 高、触摸热区铺满整行宽度：视觉上是一条细横杠，实际点击面积远大于它——
 * 4dp 高的可点区域在手机上几乎点不中。整行可点也让「点卡片上方正中」这一直觉成立。
 *
 * 除了点击，还支持**向下轻扫**收起（与 bottom sheet 的交互预期一致）。两者共存：
 * 按下后移动超过触摸斜率就交给拖拽、不触发点击；原地抬手才走 clickable。
 */
@Composable
private fun CollapseGrabber(onCollapse: () -> Unit) {
    val description = stringResource(R.string.bottom_bar_collapse)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clickable(onClick = onCollapse)
            .pointerInput(Unit) {
                var totalDy = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDy = 0f },
                    onDragEnd = {
                        if (totalDy > GRABBER_SWIPE_THRESHOLD.toPx()) onCollapse()
                    },
                    onDragCancel = { totalDy = 0f },
                    onVerticalDrag = { change, dy ->
                        totalDy += dy
                        change.consume()
                    }
                )
            }
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        )
    }
}

/**
 * 折叠态底栏：只保留 [TaskRail] 与同款底部渐变蒙版。
 *
 * 与展开态共用同一套横向内边距和 IME 避让，收起/展开时卡片左右边缘不会错位。
 * 蒙版高度独立于展开态（56dp 而非 110dp），否则「收起后底栏仍是 110dp」等于没省。
 */
@Composable
internal fun CollapsedBottomBar(
    items: List<TodoItem>,
    queuedCount: Int,
    activityKey: Any?,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imeInset = rememberImeBottomInset()
    val maskGradientEndY = with(LocalDensity.current) { COLLAPSED_BAR_MASK_HEIGHT.toPx() }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // matchParentSize：蒙版不参与测量，底栏高度由轨道本身决定（同展开态的注释）。
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(COLLAPSED_BAR_MASK_HEIGHT + imeInset)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                // 同上：折叠态蒙版同样跟随页面背景，避免深色主题下出现异色底板。
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
                            ),
                            startY = 0f,
                            endY = maskGradientEndY
                        )
                    )
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xs)
                .padding(bottom = imeInset)
        ) {
            TaskRail(
                items = items,
                queuedCount = queuedCount,
                activityKey = activityKey,
                onExpand = onExpand,
                onStop = onStop
            )
        }
    }
}

/**
 * 全屏输入框：底部输入框被高度上限截断、框内滚动时，点右上角「展开」进入这里，
 * 用整屏编辑同一份草稿（内容实时回写，收起后输入框里就是最新文本）。
 */
@Composable
private fun FullScreenInputDialog(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 不设则 dialog window 是 WRAP_CONTENT，内容铺不满屏
            usePlatformDefaultWidth = false,
            // 不设则内容被系统栏 inset 挤进安全区，底色在状态栏处断开、露出后面的聊天界面
            decorFitsSystemWindows = false
        )
    ) {
        // dialog 有自己的 window：系统栏图标色要单独按当前主题设置，否则浅色主题下状态栏图标压在浅底上看不见。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBackground
                    isAppearanceLightNavigationBars = lightBackground
                }
            }
        }

        val imeInset = rememberImeBottomInset()
        val focusRequester = remember { FocusRequester() }
        // 打开即聚焦，键盘不停，可以接着往下写。
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = imeInset)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.chat_input_fullscreen_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            FeatherIcons.Minimize,
                            contentDescription = stringResource(R.string.common_collapse_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

/** 消息类型图标按钮（控制行「思考强度」右侧）：点击弹出锚定菜单；已选时图标高亮、右上角带角标点。 */
@Composable
private fun IntentIconButton(
    intentType: String?,
    onClick: () -> Unit
) {
    val intent = MessageIntentCatalog.find(intentType)
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp)
    ) {
        Box {
            Icon(
                imageVector = intent?.icon ?: FeatherIcons.Tag,
                contentDescription = stringResource(
                    R.string.intent_chip_label,
                    stringResource(intent?.labelRes ?: R.string.intent_none)
                ),
                tint = if (intent != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            if (intent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/**
 * 菜单里的一个选项胶囊：**只有文字**，选中即实心主色。
 *
 * 刻意不做「前图标 + 后对勾」：等级与标签各有 10 来个选项，加上图标和对勾后一行放不下、
 * 换行后参差不齐；而「选中态本身就是最强的高亮」，不需要额外符号重复表达（用户 2026-11-11 反馈）。
 */
@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        !enabled && !selected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(50)
                    )
                }
            )
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            color = contentColor
        )
    }
}

/** 菜单里的一级分区标题（可带一个语义图标，只做标记，不参与点击）。 */
@Composable
private fun MenuSectionLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 二级分组小标题（如「编程方向 / 通用方向」），只做归类，不折叠。 */
@Composable
private fun MenuGroupCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
    )
}

/** 模式图标与说明层共用的名称/说明/图标。 */
private fun modeLabelRes(mode: AgentMode): Int = when (mode) {
    AgentMode.BUILD -> R.string.agent_mode_build
    AgentMode.PLAN -> R.string.agent_mode_plan
    AgentMode.AUTO -> R.string.agent_mode_auto
}

private fun modeBannerRes(mode: AgentMode): Int = when (mode) {
    AgentMode.BUILD -> R.string.agent_mode_build_desc
    AgentMode.PLAN -> R.string.agent_mode_plan_banner
    AgentMode.AUTO -> R.string.agent_mode_auto_banner
}

/** 外部引擎的模式由引擎原生能力实现，行为细节与内置引擎不同，必须如实告知。 */
private fun engineModeNoteRes(engine: String?, mode: AgentMode): Int? = when (engine) {
    AgentEngines.CODEX -> when (mode) {
        AgentMode.BUILD -> R.string.engine_mode_note_codex_build
        AgentMode.PLAN -> R.string.engine_mode_note_codex_plan
        else -> null
    }
    AgentEngines.CLAUDE_CODE, AgentEngines.OPENCODE, AgentEngines.DEEPSEEK -> null
    else -> null
}

/** 引擎原生档位显示名。内置引擎直接用语义档位的名字。 */
private fun engineModeLabelRes(engine: String?, modeId: String): Int = when (engine) {
    AgentEngines.CLAUDE_CODE -> when (modeId) {
        "manual" -> R.string.engine_mode_claude_manual
        "acceptEdits" -> R.string.engine_mode_claude_accept_edits
        "plan" -> R.string.engine_mode_claude_plan
        "auto" -> R.string.engine_mode_claude_auto
        "dontAsk" -> R.string.engine_mode_claude_dont_ask
        else -> R.string.engine_mode_claude_bypass
    }
    AgentEngines.CODEX -> when (modeId) {
        "read-only" -> R.string.engine_mode_codex_read_only
        "workspace-write" -> R.string.engine_mode_codex_workspace_write
        else -> R.string.engine_mode_codex_full_access
    }
    AgentEngines.OPENCODE -> when (modeId) {
        "build" -> R.string.engine_mode_opencode_build
        "build+auto" -> R.string.engine_mode_opencode_build_auto
        "plan" -> R.string.engine_mode_opencode_plan
        else -> R.string.engine_mode_opencode_plan_auto
    }
    AgentEngines.DEEPSEEK -> when (modeId) {
        "read-only" -> R.string.engine_mode_deepseek_read_only
        "workspace-write" -> R.string.engine_mode_deepseek_workspace_write
        else -> R.string.engine_mode_deepseek_full_access
    }
    else -> modeLabelRes(EngineModeCatalog.semantic(null, modeId))
}

/** 引擎原生档位的说明（底欄向上延伸那条）；null = 不展示说明。 */
private fun engineModeBannerRes(engine: String?, modeId: String): Int? = when (engine) {
    AgentEngines.CLAUDE_CODE -> when (modeId) {
        "manual" -> R.string.engine_mode_desc_claude_manual
        "acceptEdits" -> R.string.engine_mode_desc_claude_accept_edits
        "plan" -> R.string.engine_mode_desc_claude_plan
        "auto" -> R.string.engine_mode_desc_claude_auto
        "dontAsk" -> R.string.engine_mode_desc_claude_dont_ask
        else -> R.string.engine_mode_desc_claude_bypass
    }
    AgentEngines.CODEX -> when (modeId) {
        "read-only" -> R.string.engine_mode_desc_codex_read_only
        "workspace-write" -> R.string.engine_mode_desc_codex_workspace_write
        else -> R.string.engine_mode_desc_codex_full_access
    }
    AgentEngines.OPENCODE -> when (modeId) {
        "build" -> R.string.engine_mode_desc_opencode_build
        "build+auto" -> R.string.engine_mode_desc_opencode_build_auto
        "plan" -> R.string.engine_mode_desc_opencode_plan
        else -> R.string.engine_mode_desc_opencode_plan_auto
    }
    AgentEngines.DEEPSEEK -> when (modeId) {
        "read-only" -> R.string.engine_mode_desc_deepseek_read_only
        "workspace-write" -> R.string.engine_mode_desc_deepseek_workspace_write
        else -> R.string.engine_mode_desc_deepseek_full_access
    }
    else -> modeBannerRes(EngineModeCatalog.semantic(null, modeId))
}

private fun modeIcon(mode: AgentMode): ImageVector = when (mode) {
    AgentMode.BUILD -> FeatherIcons.Tool
    AgentMode.PLAN -> FeatherIcons.Clipboard
    AgentMode.AUTO -> FeatherIcons.Zap
}

@Composable
internal fun UploadIconButton(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 润色按钮：把输入框里的自然语言改写成清晰、专业、逻辑通顺的表述。
 *
 * - 没有可润色的内容时置灰不可点（空输入没有任何意义）；
 * - 请求进行中换成转圈，避免连点产生多次计费调用；
 * - **长按撤销**：润色会直接覆盖草稿，必须给一次无成本的反悔机会，否则用户手写的原文就没了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PolishIconButton(
    enabled: Boolean,
    running: Boolean,
    onPolish: () -> Unit,
    onUndo: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .combinedClickable(
                enabled = enabled && !running,
                onClick = onPolish,
                onLongClick = onUndo
            ),
        contentAlignment = Alignment.Center
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                FeatherIcons.Feather,
                contentDescription = stringResource(
                    if (enabled) R.string.chat_polish else R.string.chat_polish_undo
                ),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun SendButton(
    canSend: Boolean,
    hasContent: Boolean,
    isBusy: Boolean,
    tokenProgress: Float,
    tokenEstimated: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceHoldStart: () -> Unit = {},
    /** 松手：结束收音并等待识别结果；原地松手=识别后直接发送，滑到「转文字」=结果填回输入框。 */
    onVoiceHoldFinish: (transcribe: Boolean) -> Unit = {},
    /** 实时手势分支，驱动语音浮层的分支高亮。 */
    dragTarget: androidx.compose.runtime.MutableState<VoiceDragTarget>? = null,
    onVoiceHoldCancel: () -> Unit = {}
) {
    // AI 工作中：输入框为空显示停止按钮；有内容显示橙色发送（消息排队发送）；空闲时常规蓝色发送。
    // 四态色均需与底色对比明显：idle 用 primary（用户反馈：surfaceVariant 灰与输入栏融为一体，
    // 深色下尤甚，像“只有一个色且未适配深色”）。
    val showStop = isBusy && !hasContent
    val haptic = LocalHapticFeedback.current
    val clickable = showStop || canSend
    val buttonColor = when {
        showStop -> MaterialTheme.colorScheme.error
        isBusy -> Brand.Orange
        canSend -> MaterialTheme.semanticColors.success
        else -> MaterialTheme.colorScheme.primary
    }
    // 前景箭头恒白（用户反馈：浅色模式下蓝底/亮绿底箭头都应是白色）；四态底色均足够深，白箭头对比都成立。
    // 历史：曾按底色亮度自适应，后按用户拍板改恒白，2026-10-22 再次反馈补齐空闲蓝底态。
    val iconTint = Color.White
    val arcColor = buttonColor.copy(alpha = 0.85f)
    val clampedProgress = tokenProgress.coerceIn(0f, 1f)
    // pointerInput(Unit) 手势协程只在首次组合启动一次，直接捕获 clickable/onSend 等会拿到旧值
    // （停止后输入文字再点发送无反应、退出会话重建节点才恢复），必须经 rememberUpdatedState 读取最新值。
    val currentClickable by rememberUpdatedState(clickable)
    val currentShowStop by rememberUpdatedState(showStop)
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentOnVoiceHoldStart by rememberUpdatedState(onVoiceHoldStart)
    val currentOnVoiceHoldFinish by rememberUpdatedState(onVoiceHoldFinish)
    val currentOnVoiceHoldCancel by rememberUpdatedState(onVoiceHoldCancel)
    Box(
        modifier = Modifier
            .padding(Spacing.xs)
            .size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        // 额度环必须常驻：0% 时也要画出底圈，否则新会话/无用量时整圈消失（用户反馈环不见了）。
        val usageLabel = stringResource(
            if (tokenEstimated) R.string.chat_context_usage_estimated else R.string.chat_context_usage,
            (clampedProgress * 100).toInt()
        )
        Canvas(
            modifier = Modifier
                .size(44.dp)
                // 上下文用量只靠这圈弧表达，给读屏补上百分比。
                .semantics { contentDescription = usageLabel }
        ) {
            val stroke = 3.dp.toPx()
            val arcSize = size.minDimension - stroke
            val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f)
            val arcRect = androidx.compose.ui.geometry.Size(arcSize, arcSize)
            drawArc(
                color = arcColor.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcRect,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (clampedProgress > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clampedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcRect,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .then(
                    if (currentShowStop) {
                        Modifier.clickable(enabled = currentClickable, onClick = currentOnStop)
                    } else {
                        // 短按 = 发送；长按 = 应用内语音「按住说话」（松手识别发送、上滑取消）。
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var longPressed = false
                                // 长按阈值内抬手视为短按；超时（PointerEventTimeout）视为长按成立。
                                try {
                                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                        waitForUpOrCancellation()
                                    }
                                } catch (e: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                                    longPressed = true
                                }
                                if (!longPressed) {
                                    if (currentClickable) currentOnSend()
                                    return@awaitEachGesture
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnVoiceHoldStart()
                                var target = VoiceDragTarget.SEND
                                var released = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (!change.pressed) {
                                        released = true
                                        break
                                    }
                                    val dy = change.position.y - down.position.y
                                    val dx = change.position.x - down.position.x
                                    // 方向主判据：左右滑动直接决定分支，不要求先向下移出底边（发送键贴底，下方没有可滑空间）。
                                    // 左滑取消，右滑/上滑转文字（与扇形按钮：左取消、右转文字一致），回中发送；
                                    // 实时写入 dragTarget 供语音浮层高亮当前分支。
                                    target = when {
                                        dx > VOICE_BRANCH_DX_DP.dp.toPx() -> VoiceDragTarget.TRANSCRIBE
                                        dx < -VOICE_BRANCH_DX_DP.dp.toPx() -> VoiceDragTarget.CANCEL
                                        dy < -VOICE_CANCEL_SLIDE_DP.dp.toPx() -> VoiceDragTarget.TRANSCRIBE
                                        else -> VoiceDragTarget.SEND
                                    }
                                    dragTarget?.value = target
                                    change.consume()
                                }
                                if (released && target != VoiceDragTarget.CANCEL) {
                                    // 与微信一致：松手执行（发送/转文字）再振一下，与长按开始的振动区分阶段。
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnVoiceHoldFinish(target == VoiceDragTarget.TRANSCRIBE)
                                } else {
                                    currentOnVoiceHoldCancel()
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showStop) {
                Icon(
                    FeatherIcons.Square,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 长按语音：手指向上滑动超过该距离视为取消（松手不发送）。 */
private const val VOICE_CANCEL_SLIDE_DP = 60f

/** 专家团横幅展开面板的最大高度：信息不完整时可滚，但不撑屏（用户明确要求「别展开太大」）。 */
private const val TEAM_PANEL_MAX_HEIGHT_DP = 180

/** 专家团横幅自动翻滚的步进间隔：空闲时缓慢向上转，有新活动重置、用户触摸即停。 */
private const val TEAM_AUTO_SCROLL_INTERVAL_MS = 3_000L

/** 分支区内水平偏移超过该距离才判定为对应分支，避免误触。 */
private const val VOICE_BRANCH_DX_DP = 50f

/** 底部扇形舞台高度与弧带线宽（dp）。 */
private const val VOICE_STAGE_SECTOR_HEIGHT_DP = 150
private const val VOICE_STAGE_SECTOR_STROKE_DP = 64

/** 长按语音的手势分支：SEND=原地松手发送；CANCEL=取消丢弃；TRANSCRIBE=识别结果填回输入框不发送。 */
internal enum class VoiceDragTarget { SEND, CANCEL, TRANSCRIBE }

/**
 * 长按语音全屏舞台（微信式）：背后内容层已由外层加实时高斯模糊，这里叠半透明遮罩、
 * 屏幕中央绿色呼吸光球、实时识别文字气泡，以及底部两个扇形按钮（左取消/右转文字）。
 * 高亮随 [dragTarget] 手势分支切换；识别中间结果实时显示在 [partialText] 气泡里。
 *
 * 用 Popup 而非普通子项：浮层必须盖住整屏（含顶栏），不受输入栏父容器裁剪。
 */
@Composable
internal fun VoiceHoldStage(
    processing: Boolean,
    dragTarget: VoiceDragTarget,
    partialText: String
) {
    Popup(
        alignment = Alignment.TopStart,
        properties = PopupProperties(focusable = false, clippingEnabled = false)
    ) {
        val orbPulse = rememberInfiniteTransition(label = "voice-orb")
        val orbScale by orbPulse.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = androidx.compose.animation.core.EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "voice-orb-scale"
        )
        val orbAlpha by orbPulse.animateFloat(
            initialValue = 0.75f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(650),
                repeatMode = RepeatMode.Reverse
            ),
            label = "voice-orb-alpha"
        )
        val cancelLabel = stringResource(R.string.common_cancel)
        val transcribeLabel = stringResource(R.string.chat_voice_transcribe_label)

        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩：内容层已被 blur，再压一层暗色让前景更突出
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )

            // 中央：实时文字气泡 + 呼吸光球 + 状态提示
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = partialText.isNotBlank(),
                    enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = partialText,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                        )
                    }
                }

                Box(contentAlignment = Alignment.Center) {
                    // 光晕
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .graphicsLayer {
                                scaleX = orbScale * 1.15f
                                scaleY = orbScale * 1.15f
                                alpha = 0.25f * orbAlpha
                            }
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                    )
                    // 光球本体
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .graphicsLayer {
                                scaleX = orbScale
                                scaleY = orbScale
                                alpha = orbAlpha
                            }
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                    )
                }

                Text(
                    text = stringResource(
                        when {
                            processing -> R.string.chat_voice_processing
                            dragTarget == VoiceDragTarget.CANCEL -> R.string.chat_voice_release_cancel
                            dragTarget == VoiceDragTarget.TRANSCRIBE -> R.string.chat_voice_release_transcribe
                            else -> R.string.chat_voice_release_send
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            // 底部扇形：左取消（滑左/上左）、右转文字（滑右/上右）；高亮跟随手势分支
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(VOICE_STAGE_SECTOR_HEIGHT_DP.dp)
            ) {
                val stroke = VOICE_STAGE_SECTOR_STROKE_DP.dp.toPx()
                val radius = stroke
                val leftActive = dragTarget == VoiceDragTarget.CANCEL
                val rightActive = dragTarget == VoiceDragTarget.TRANSCRIBE
                // 左翼：圆心在左下角，弧段 270°→360°；右翼镜像。激活时提亮微扩。
                drawArc(
                    color = if (leftActive) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.45f),
                    startAngle = 270f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(-radius * 0.15f, size.height - radius * 0.15f),
                    size = Size(radius * 2.3f, radius * 2.3f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = if (rightActive) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.45f),
                    startAngle = 180f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(size.width - radius * 2.15f, size.height - radius * 0.15f),
                    size = Size(radius * 2.3f, radius * 2.3f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        textSize = 18.sp.toPx()
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    paint.color = if (leftActive) android.graphics.Color.WHITE else android.graphics.Color.argb(200, 255, 255, 255)
                    paint.isFakeBoldText = leftActive
                    canvas.nativeCanvas.drawText(
                        cancelLabel,
                        size.width * 0.24f,
                        size.height * 0.72f,
                        paint
                    )
                    paint.color = if (rightActive) android.graphics.Color.WHITE else android.graphics.Color.argb(200, 255, 255, 255)
                    paint.isFakeBoldText = rightActive
                    canvas.nativeCanvas.drawText(
                        transcribeLabel,
                        size.width * 0.76f,
                        size.height * 0.72f,
                        paint
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolPermissionPanel(
    request: PendingToolPermission,
    onChoice: (PermissionChoice) -> Unit,
    modifier: Modifier = Modifier,
    sessionTitle: String = "",
    forceCollapse: Boolean = false,
    isScrolling: Boolean = false
) {
    var expanded by remember { mutableStateOf(true) }
    // 余额面板展开时同帧收起本面板，避免叠加
    val effectiveExpanded = expanded && !forceCollapse

    // 滚动弱化：用户上下滑动列表时，悬浮授权弹窗跟随淡化为 40%，避免遮挡用户查看背后消息；停止滑动后平滑恢复
    val panelAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.4f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "permission-panel-alpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            // 只在弱化进行中挂层：常驻离屏图层不随主题切换刷新（同输入栏修复）。
            .then(if (panelAlpha < 1f) Modifier.graphicsLayer { alpha = panelAlpha } else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = request.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = request.toolName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (sessionTitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.chat_perm_session_label, sessionTitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    ExpandableChevronIcon(
                        expanded = effectiveExpanded,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 18.dp
                    )
                }
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    Surface(
                        shape = RoundedCornerShape(Radius.sm),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = request.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (request.details.isNotBlank()) {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(
                                        text = request.details,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    val canRemember = request.rememberablePatterns.isNotEmpty()
                    val rememberLabel = when {
                        !canRemember -> request.rememberDisabledReason ?: stringResource(R.string.chat_perm_single_use_desc)
                        request.rememberablePatterns == listOf("*") -> stringResource(R.string.chat_perm_always_tool_desc)
                        else -> stringResource(R.string.chat_perm_always_prefix) + request.rememberablePatterns.joinToString("、")
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = rememberLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.chat_perm_deny),
                            onClick = { onChoice(PermissionChoice.REJECT) },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Danger
                        )
                        AgentActionButton(
                            text = stringResource(R.string.chat_perm_always_allow),
                            onClick = { onChoice(PermissionChoice.ALWAYS) },
                            modifier = Modifier.weight(1f),
                            enabled = canRemember,
                            tone = AgentActionTone.Neutral
                        )
                        AgentActionButton(
                            text = stringResource(R.string.common_allow),
                            onClick = { onChoice(PermissionChoice.ONCE) },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatusBanner(state: AgentUIState) {
    // 退出动画期间 state 已不是 Error，when 会走 else 渲染空内容，横幅的淡出就变成了瞬间消失；
    // 拿最后一次的错误文本兜住整段退场。
    val lastError = rememberLastNonNull((state as? AgentUIState.Error)?.message)
    AnimatedVisibility(
        visible = state is AgentUIState.Error,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        lastError?.let { ErrorBubble(message = it) }
    }
}

/**
 * 失败错误气泡：风格对齐 ToolPermissionPanel / AskUserQuestionPanel（内联面板，与输入框同宽且两侧留距）。
 * 默认折叠为一行标题（请求失败），点击展开完整错误详情；右上角可一键复制。
 */
@Composable
private fun ErrorBubble(message: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    var copied by remember(message) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_error_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    copyScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("error", message)))
                        copied = true
                    }
                }) {
                    Icon(
                        if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                        tint = if (copied) MaterialTheme.colorScheme.primary else Brand.IconGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 18.dp
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = message,
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

/**
 * 计划审查面板：AI 从 PLAN 模式切回 BUILD 时弹出，展示计划摘要供用户批准或继续反馈。
 * 风格与 ToolPermissionPanel / AskUserQuestionPanel 一致。
 */
@Composable
internal fun PlanApprovalPanel(
    state: PlanApprovalRequest,
    onApprove: () -> Unit,
    onRefine: () -> Unit,
    /** 「定时执行…」：不立即施工，把计划存档为定时 Plan 排期（打开排期弹层）。 */
    onSchedule: (() -> Unit)? = null,
    forceCollapse: Boolean = false
) {
    var expanded by remember { mutableStateOf(true) }
    // 余额面板展开时同帧收起本面板，避免叠加顶开输入框
    val effectiveExpanded = expanded && !forceCollapse

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_plan_completed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp
                )
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    if (state.reason.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.xs))
                        SelectionContainer {
                            Column(
                                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = state.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.chat_continue_feedback),
                            onClick = onRefine,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Neutral
                        )
                        if (onSchedule != null) {
                            AgentActionButton(
                                text = stringResource(R.string.schedule_exec_action),
                                onClick = onSchedule,
                                modifier = Modifier.weight(1f),
                                tone = AgentActionTone.Neutral
                            )
                        }
                        AgentActionButton(
                            text = stringResource(R.string.chat_approve_and_implement),
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}

/**
 * 会话持续目标（goal）的常驻卡片：置于输入框上方，显示目标文本、状态与暂停/恢复、清除操作。
 * 样式与输入框卡片一致（圆角 + 描边）。
 */
@Composable
private fun GoalBannerRow(
    goal: SessionGoalUi,
    onSetPaused: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    val statusLabel = when (goal.status) {
        GoalStatus.ACTIVE -> stringResource(R.string.chat_goal_status_active)
        GoalStatus.PAUSED -> stringResource(R.string.chat_goal_status_paused)
        GoalStatus.COMPLETED -> stringResource(R.string.chat_goal_status_completed)
        GoalStatus.BLOCKED -> stringResource(R.string.chat_goal_status_blocked)
        GoalStatus.EXHAUSTED -> stringResource(R.string.chat_goal_status_exhausted)
    }
    val accent = when (goal.status) {
        GoalStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        GoalStatus.COMPLETED -> MaterialTheme.semanticColors.success
        GoalStatus.BLOCKED, GoalStatus.EXHAUSTED -> MaterialTheme.colorScheme.error
        GoalStatus.PAUSED -> MaterialTheme.semanticColors.subtleText
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Target,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_goal_label, statusLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1
                )
                Text(
                    text = goal.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (goal.status == GoalStatus.ACTIVE || goal.status == GoalStatus.PAUSED) {
                IconButton(
                    onClick = { onSetPaused(goal.status == GoalStatus.ACTIVE) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (goal.status == GoalStatus.ACTIVE) FeatherIcons.Pause else FeatherIcons.Play,
                        contentDescription = stringResource(
                            if (goal.status == GoalStatus.ACTIVE) R.string.chat_goal_pause else R.string.chat_goal_resume
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onClear, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.chat_goal_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 专家团横幅：人员状态表（非会话回放）。
 *
 * 收起态 2 行——团名 + 实时状态摘要、成员实时胶囊；与会话内容正交：
 * 会话按时间排列「过程」，这里按成员聚合「此刻谁在干什么、干到哪一步」。
 *
 * 交互：点击向上展开（上限 [TEAM_PANEL_MAX_HEIGHT_DP]，不撑屏）；内容超高可手动上下滚；
 * 展开且无用户操作时自动向上翻滚（跑马灯），有新活动重置、全员空闲即停。
 */
@Composable
private fun TeamBannerRow(
    runtime: TeamRuntimeUi,
    onChange: () -> Unit,
    onClear: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm)
            .clip(RoundedCornerShape(Radius.lg))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs)
        ) {
            // ── 收起态头部：团名 + 实时摘要 + 操作 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Users,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.team_banner_label, runtime.team.displayName),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // 实时状态摘要：这是会话里没有的信息（几人在忙/几人完成）
                        text = teamStatusSummary(runtime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onChange, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = stringResource(R.string.team_banner_change),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(onClick = onClear, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.team_banner_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // ── 展开态：成员实时列表（固定高度、可滚、空闲自动翻滚）──
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                TeamMemberRuntimePanel(members = runtime.members)
            }
        }
    }
}

/** 展开态的成员实时面板：固定高度可滚 + 无操作自动向上翻滚。 */
@Composable
private fun TeamMemberRuntimePanel(members: List<TeamMemberRuntimeUi>) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val allDone = members.isNotEmpty() && members.all { it.status != TeamMemberStatus.WORKING }
    // 自动翻滚开关：成员状态更新即重置（有新活动就该重新开始转），用户手动拖动即停（用户优先）。
    var autoScroll by remember(members) { androidx.compose.runtime.mutableStateOf(true) }
    // 用户拖动检测走 interactionSource（pointerInput 的 tap 检测抓不到滚动拖拽）。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                autoScroll = false
            }
        }
    }
    LaunchedEffect(autoScroll, allDone, members.size) {
        if (!autoScroll || allDone || members.size <= 1) return@LaunchedEffect
        var cursor = 0
        while (true) {
            kotlinx.coroutines.delay(TEAM_AUTO_SCROLL_INTERVAL_MS)
            // 只在内容超出可视高度时才滚（短列表全可见，滚了没意义）。
            if (!listState.canScrollForward && !listState.canScrollBackward) continue
            cursor = (cursor + 1) % members.size
            runCatching { listState.animateScrollToItem(cursor) }
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = TEAM_PANEL_MAX_HEIGHT_DP.dp)
    ) {
        items(
            count = members.size,
            key = { index -> members[index].name }
        ) { index ->
            val member = members[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (member.status) {
                                TeamMemberStatus.WORKING -> MaterialTheme.colorScheme.primary
                                TeamMemberStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                                TeamMemberStatus.IDLE -> MaterialTheme.colorScheme.surface
                            }
                        )
                        .then(
                            if (member.status == TeamMemberStatus.IDLE) {
                                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            } else Modifier
                        )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    member.activity?.let { activity ->
                        Text(
                            text = activity,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 实时状态摘要：「2/5 在忙 · 1 已完成 · 2 待派」（会话里没有这个聚合视角）。 */
@Composable
private fun teamStatusSummary(runtime: TeamRuntimeUi): String {
    val total = runtime.members.size
    val working = runtime.workingCount
    val done = runtime.doneCount
    val idle = total - working - done
    return buildString {
        append(stringResource(R.string.team_runtime_working, working, total))
        if (done > 0) append(" · ").append(stringResource(R.string.team_runtime_done, done))
        if (idle > 0) append(" · ").append(stringResource(R.string.team_runtime_idle, idle))
    }
}