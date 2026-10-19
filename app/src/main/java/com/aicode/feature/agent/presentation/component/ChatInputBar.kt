package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.core.ui.ProgressNode
import com.aicode.core.ui.ProgressNodeState
import com.aicode.core.ui.TaskProgressBorder
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandKind
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.presentation.SessionGoalUi
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
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
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Pause
import compose.icons.feathericons.Play
import compose.icons.feathericons.Target
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
private val INPUT_FIELD_MIN_HEIGHT = 44.dp
private val INPUT_FIELD_MAX_HEIGHT = 140.dp

/**
 * M3 无标签 TextField 的默认 contentPadding（上下左右各 16dp）。
 * 文本是否超过输入框高度要先用相同约束实测一遍，反推文本可视区域得靠这个值。
 */
private val INPUT_FIELD_CONTENT_PADDING = 16.dp

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
    /** 松手：结束收音并等待识别结果。 */
    onVoiceHoldFinish: () -> Unit = {},
    /** 上滑取消：放弃本次语音。 */
    onVoiceHoldCancel: () -> Unit = {},
    /** 识别处理中（浮层显示「正在识别…」）。 */
    voiceProcessing: Boolean = false,
    /** 点击麦克风的应用内聆听进行中（浮层显示「正在聆听…再点一下结束」）。 */
    voiceTapActive: Boolean = false,
    workspaceViewModel: WorkspaceViewModel?,
    hasRunningSessions: () -> Boolean,
    onSwitchWorkspaceConfirmed: () -> Unit = {},
    activeProvider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    /** 长按模式图标：按当前模式弹「定时任务 / 定时 Plan」创建面板；null 时不响应。 */
    onModePillLongClick: (() -> Unit)? = null,
    /** 本条消息类型键（控制行类型图标按钮）；null=未分类。 */
    intentType: String? = null,
    onIntentTypeChange: (String?) -> Unit = {},
    currentGoal: SessionGoalUi? = null,
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
    onVoiceInput: () -> Unit = {},
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
    tokenProgress: Float = 0f,
    tokenEstimated: Boolean = false,
    dashboardState: ProviderDashboardState = ProviderDashboardState.Idle,
    onRefreshDashboard: () -> Unit = {},
    onRefreshDashboardByButton: () -> Unit = {},
    onDashboardExpandedChange: (Boolean) -> Unit = {},
    todoItems: List<TodoItem> = emptyList(),
    /** 此刻真实在做的事（正在执行的工具/阶段文案），拼在「进行中」节点提示里。 */
    activeToolLabel: String? = null,
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
    modifier: Modifier = Modifier
) {
    val hasContent = value.isNotBlank() || pendingAttachments.isNotEmpty()
    val canSend = hasContent
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
    val voiceContext = LocalContext.current
    var voiceHoldActive by remember { mutableStateOf(false) }
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

    // 文本是否已经超出输入框高度（超出后框内滚动，展开按钮才有意义）。
    // 按换行符数硬编码阈值在长行自动折行时会判断错，这里按 TextField 的实际宽度实测文本高度。
    val textMeasurer = rememberTextMeasurer()
    val inputDensity = LocalDensity.current
    var inputFieldWidthPx by remember { mutableStateOf(0) }
    val inputTextStyle = MaterialTheme.typography.bodyLarge
    val inputOverflows = remember(value, inputFieldWidthPx, inputDensity, inputTextStyle) {
        val horizontalPaddingPx = with(inputDensity) { INPUT_FIELD_CONTENT_PADDING.toPx() }
        val contentWidthPx = inputFieldWidthPx - (horizontalPaddingPx * 2).toInt()
        val maxTextHeightPx = with(inputDensity) {
            (INPUT_FIELD_MAX_HEIGHT - INPUT_FIELD_CONTENT_PADDING * 2).toPx()
        }
        contentWidthPx > 0 && value.isNotEmpty() && textMeasurer.measure(
            text = AnnotatedString(value),
            style = inputTextStyle,
            constraints = Constraints(maxWidth = contentWidthPx)
        ).size.height > maxTextHeightPx
    }

    Surface(
        color = Color.Transparent,
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(INPUT_BAR_MASK_HEIGHT + imeInset)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                            ),
                            startY = 0f,
                            endY = maskGradientEndY
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
                    // 顶部预留进度环的外扩空间：本 Column 经 graphicsLayer 图层渲染，
                    // 绘制在图层 bounds 外会被裁掉，卡片上方无兄弟节点时环顶边会缺失。
                    .padding(top = 6.dp)
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
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

            if (voiceHoldActive || voiceProcessing || voiceTapActive) {
                VoiceHoldOverlay(processing = voiceProcessing, tapMode = voiceTapActive)
            }

            if (queuedRequests.isNotEmpty()) {
                QueuedRequestPanel(
                    queuedRequests = queuedRequests,
                    sessionId = sessionId,
                    forceCollapse = forceCollapseDashboard,
                    onRemoveQueued = onRemoveQueued,
                    onMoveQueued = onMoveQueued,
                    onEditQueued = onEditQueued,
                    onInterjectQueued = onInterjectQueued
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
                    forceCollapse = forceCollapseDashboard
                )
            }

            currentGoal?.let { goal ->
                GoalBannerRow(
                    goal = goal,
                    onSetPaused = onSetGoalPaused,
                    onClear = onClearGoal
                )
            }

            // AI 工作中：卡片外围的任务进度线框——完全由 todo 驱动（节点=条目、弧长=完成比例）；
            // 无 todo 时不渲染（不摆假进度）；节点点击在卡片上方悬浮显示对应条目文字。
            var progressTipIndex by remember { mutableStateOf<Int?>(null) }
            // 每次点击自增：同节点重复点击也能重置 2.5s 计时（LaunchedEffect 以值为 key，同值不重启）
            var progressTipTick by remember { mutableStateOf(0) }
            // 进行中节点拼接「此刻真实在做的事」（正在执行的工具/阶段）——点击节点即可确认当前动作
            val activeIndex = todoItems.indexOfFirst { it.status == TodoStatus.IN_PROGRESS }
            val progressNodes = if (isBusy) {
                todoItems.take(12).mapIndexed { index, item ->
                    ProgressNode(
                        state = when (item.status) {
                            TodoStatus.COMPLETED -> ProgressNodeState.COMPLETED
                            TodoStatus.IN_PROGRESS -> ProgressNodeState.ACTIVE
                            TodoStatus.PENDING -> ProgressNodeState.PENDING
                        },
                        label = if (index == activeIndex && !activeToolLabel.isNullOrBlank()) {
                            "${item.subject} · $activeToolLabel"
                        } else {
                            item.subject
                        }
                    )
                }
            } else emptyList()
            LaunchedEffect(progressTipTick) {
                if (progressTipIndex != null) {
                    delay(2500)
                    progressTipIndex = null
                }
            }
            // 本轮结束（busy→idle）后清空残留提示：新一轮的节点列表长度可能不同，索引失效会渲染空气泡
            LaunchedEffect(isBusy) {
                if (!isBusy) progressTipIndex = null
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(Radius.lg)
                        )
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                ) {
                    PendingAttachmentPreviewList(
                        attachments = pendingAttachments,
                        onRemoveAttachment = onRemoveAttachment
                    )

                    // 模式切换不再弹「已切换」Toast（验收反馈：底栏模式说明条已承载模式提示，弹窗多余）。

                    // 模式说明条（验收反馈）：底栏向上拓展展示当前模式规则。
                    // 默认：切到计划/自动自动展开；构建默认收起，再次点击已点亮的模式图标开关本说明。
                    var modeInfoVisible by rememberSaveable(sessionId) { mutableStateOf(currentMode != AgentMode.BUILD) }
                    LaunchedEffect(currentMode) { modeInfoVisible = currentMode != AgentMode.BUILD }
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
                                imageVector = modeIcon(currentMode),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(modeBannerRes(currentMode)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        if (currentMode == AgentMode.PLAN) R.string.chat_mode_longpress_hint_plan
                                        else R.string.chat_mode_longpress_hint
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (currentMode == AgentMode.BUILD) modeInfoVisible = false
                                    else onToggleMode(AgentMode.BUILD)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    FeatherIcons.X,
                                    contentDescription = stringResource(
                                        if (currentMode == AgentMode.BUILD) R.string.common_collapse
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
                        TextField(
                            value = inputFieldValue,
                            onValueChange = { new ->
                                inputFieldValue = new
                                onValueChange(new.text)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = INPUT_FIELD_MIN_HEIGHT, max = INPUT_FIELD_MAX_HEIGHT)
                                .onSizeChanged { inputFieldWidthPx = it.width },
                            placeholder = {
                                Text(
                                    stringResource(if (isBusy) R.string.chat_queue_hint else R.string.chat_input_placeholder),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            enabled = true,
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
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )

                        if (inputOverflows) {
                            IconButton(
                                onClick = { showFullScreenInput = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    FeatherIcons.Maximize,
                                    contentDescription = stringResource(R.string.chat_input_expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 控制行高度保底：上层 Column 总高溢出时防测量挤压（发送按钮变形，用户反馈）。
                            .heightIn(min = 44.dp)
                            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 模式区：三个纯图标按钮（构建 / 计划 / 自动），尺寸与右侧图标统一
                            // （36dp 按钮 / 20dp 图标）。点击未选中图标直接切换；点击已点亮图标开关
                            // 底部模式说明；长按（三个按钮任一）进「定时任务 / 定时 Plan」面板。
                            AgentMode.entries.forEach { mode ->
                                val isSelected = mode == currentMode
                                val modeTint = when (mode) {
                                    AgentMode.BUILD -> MaterialTheme.semanticColors.success
                                    AgentMode.PLAN -> MaterialTheme.colorScheme.primary
                                    AgentMode.AUTO -> MaterialTheme.colorScheme.error
                                }
                                Icon(
                                    imageVector = modeIcon(mode),
                                    contentDescription = stringResource(modeLabelRes(mode)),
                                    tint = if (isSelected) modeTint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .combinedClickable(
                                            onClick = {
                                                if (isSelected) modeInfoVisible = !modeInfoVisible else onToggleMode(mode)
                                            },
                                            onLongClick = onModePillLongClick
                                        )
                                        .semantics { this.selected = isSelected }
                                        .padding(8.dp)
                                )
                            }
                            Spacer(Modifier.width(Spacing.xs))

                            ModelIconButton(
                                provider = activeProvider,
                                providers = providers,
                                modelMetadata = modelMetadata,
                                onSelectModel = onSelectModel,
                                forceOpenSheet = forceOpenModelSheet,
                                onSelectSuccessInOnboarding = onSelectModelInOnboarding,
                                onSheetDismiss = onModelSheetDismiss,
                                modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_MODEL_PICKER)
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

                            // 元数据未命中时（如中转站改了模型名）退回全部档位由用户自选：
                            // 这类模型可能强制开启推理，隐藏按钮会导致无法调档。
                            val availableEfforts = remember(activeProvider, modelMetadata) {
                                activeProvider?.let { provider ->
                                    modelMetadata[modelMetadataKey(provider.id, provider.effectiveModel)]?.reasoningEffortOptions
                                        ?.let { ReasoningEffort.fromValues(it) }
                                }.orEmpty().ifEmpty { ReasoningEffort.entries }
                            }
                            ReasoningEffortSelector(
                                effort = reasoningEffort,
                                availableEfforts = availableEfforts,
                                onChange = onReasoningEffortChange,
                                enabled = !isBusy
                            )

                            Box {
                                IntentIconButton(
                                    intentType = intentType,
                                    onClick = { showIntentPicker = true }
                                )
                                DropdownMenu(
                                    expanded = showIntentPicker,
                                    onDismissRequest = { showIntentPicker = false }
                                ) {
                                    MessageIntentMenuItem(
                                        intent = null,
                                        selected = intentType == null,
                                        onClick = {
                                            showIntentPicker = false
                                            onIntentTypeChange(null)
                                        }
                                    )
                                    MessageIntentGroupHeader(stringResource(R.string.intent_picker_coding))
                                    MessageIntentCatalog.coding.forEach { item ->
                                        MessageIntentMenuItem(
                                            intent = item,
                                            selected = item.key == intentType,
                                            onClick = {
                                                showIntentPicker = false
                                                onIntentTypeChange(item.key)
                                            }
                                        )
                                    }
                                    MessageIntentGroupHeader(stringResource(R.string.intent_picker_general))
                                    MessageIntentCatalog.general.forEach { item ->
                                        MessageIntentMenuItem(
                                            intent = item,
                                            selected = item.key == intentType,
                                            onClick = {
                                                showIntentPicker = false
                                                onIntentTypeChange(item.key)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        // 工作中也可用：附件/语音输入随消息排队发送（AIChatPanel 统一队列链路）。
                        UploadIconButton(
                            enabled = true,
                            icon = FeatherIcons.Plus,
                            contentDescription = stringResource(R.string.chat_add_attachment),
                            onClick = { showAttachmentSheet = true }
                        )
                        UploadIconButton(
                            enabled = true,
                            icon = FeatherIcons.Mic,
                            contentDescription = stringResource(R.string.chat_voice_input),
                            onClick = onVoiceInput
                        )
                        SendButton(
                            canSend = canSend,
                            hasContent = hasContent,
                            isBusy = isBusy,
                            tokenProgress = tokenProgress,
                            tokenEstimated = tokenEstimated,
                            onSend = onSend,
                            onStop = onStop,
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
                            onVoiceHoldFinish = {
                                if (voiceHoldActive) {
                                    voiceHoldActive = false
                                    onVoiceHoldFinish()
                                }
                            },
                            onVoiceHoldCancel = {
                                if (voiceHoldActive) {
                                    voiceHoldActive = false
                                    onVoiceHoldCancel()
                                }
                            }
                        )
                    }
                }

                if (isBusy) {
                    // 任务一开始就拉起外框：todo 还没产生时画呼吸段（indeterminate），
                    // 有了 todo 才切换为真实节点与完成弧。
                    TaskProgressBorder(
                        modifier = Modifier.matchParentSize(),
                        progress = if (todoItems.isEmpty()) 0f
                        else todoItems.count { it.status == TodoStatus.COMPLETED }.toFloat() / todoItems.size,
                        nodes = progressNodes,
                        cornerRadius = Radius.lg,
                        activeColor = MaterialTheme.colorScheme.primary,
                        // 完成弧与完成节点弱化：亮绿太抢眼（用户 2026-10-15 反馈），向表面色混合降饱和。
                        completedColor = lerp(
                            MaterialTheme.semanticColors.success,
                            MaterialTheme.colorScheme.surfaceVariant,
                            0.4f
                        ),
                        pendingColor = MaterialTheme.colorScheme.outlineVariant,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        indeterminate = todoItems.isEmpty()
                    )
                    if (progressNodes.isNotEmpty()) {
                        // 节点点击热点：独立小层只拦截节点附近触摸，避免整层覆盖独占卡片内交互
                        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
                            val density = LocalDensity.current
                            val w = with(density) { maxWidth.toPx() }
                            val h = with(density) { maxHeight.toPx() }
                            val hotPx = with(density) { 30.dp.toPx() }
                            val perimeter = 2f * (w + h)
                            progressNodes.forEachIndexed { index, _ ->
                                val d = perimeter * (index + 1) / (progressNodes.size + 1)
                                val p = pointOnRectPerimeter(d, w, h)
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset((p.x - hotPx).toInt(), (p.y - hotPx).toInt()) }
                                        .size(60.dp)
                                        .pointerInput(index) {
                                            detectTapGestures {
                                                progressTipIndex = index
                                                progressTipTick++
                                            }
                                        }
                                )
                            }
                        }
                    }
                    progressTipIndex?.let { tip ->
                        progressNodes.getOrNull(tip)?.takeIf { it.label.isNotBlank() }?.let { node ->
                            Surface(
                                shape = RoundedCornerShape(Radius.sm),
                                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = (-40).dp)
                                    .zIndex(1f)
                            ) {
                                Text(
                                    text = node.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                                )
                            }
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

/** 消息类型菜单条目：图标 + 名称紧凑单行，选中打勾。 */
@Composable
private fun MessageIntentMenuItem(
    intent: MessageIntent?,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(intent?.labelRes ?: R.string.intent_none),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = intent?.icon ?: FeatherIcons.Tag,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    FeatherIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            null
        },
        onClick = onClick
    )
}

/** 消息类型菜单分组标题（左缘与菜单条目图标列对齐）。 */
@Composable
private fun MessageIntentGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.xs)
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

private fun modeIcon(mode: AgentMode): ImageVector = when (mode) {
    AgentMode.BUILD -> FeatherIcons.Tool
    AgentMode.PLAN -> FeatherIcons.Clipboard
    AgentMode.AUTO -> FeatherIcons.Zap
}

/** 矩形周界上距左上角 d 像素的点（顺时针，与进度环路径方向一致）。 */
private fun pointOnRectPerimeter(d: Float, w: Float, h: Float): Offset {
    val per = 2f * (w + h)
    if (per <= 0f) return Offset.Zero
    val dd = ((d % per) + per) % per
    return when {
        dd <= w -> Offset(dd, 0f)
        dd <= w + h -> Offset(w, dd - w)
        dd <= 2 * w + h -> Offset(2 * w + h - dd, h)
        else -> Offset(0f, per - dd)
    }
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
    onVoiceHoldFinish: () -> Unit = {},
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
    // 前景色相对按钮底色而非主题：深色主题下的亮绿底用主题 onSurface（近白）会看不清。
    val iconTint = when {
        !clickable -> MaterialTheme.colorScheme.onSurfaceVariant
        buttonColor.luminance() > 0.5f -> Color(0xFF1C1C1E)
        else -> Color.White
    }
    val arcColor = buttonColor.copy(alpha = 0.85f)
    val clampedProgress = tokenProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .padding(Spacing.xs)
            .size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (clampedProgress > 0f) {
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
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clampedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
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
                    if (showStop) {
                        Modifier.clickable(enabled = clickable, onClick = onStop)
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
                                    if (clickable) onSend()
                                    return@awaitEachGesture
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onVoiceHoldStart()
                                var cancelled = false
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
                                    cancelled = dy < -VOICE_CANCEL_SLIDE_DP.dp.toPx()
                                    change.consume()
                                }
                                if (released && !cancelled) onVoiceHoldFinish() else onVoiceHoldCancel()
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

/** 语音浮层：长按「按住说话」与点击聆听共用；识别期间文案切换，点击模式提示再点一下结束。 */
@Composable
private fun VoiceHoldOverlay(processing: Boolean, tapMode: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (processing) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.semanticColors.success
                    )
            )
            Text(
                text = stringResource(
                    when {
                        processing -> R.string.chat_voice_processing
                        tapMode -> R.string.chat_voice_listening_hint
                        else -> R.string.chat_voice_release_send
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = FeatherIcons.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
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
            .graphicsLayer { alpha = panelAlpha },
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