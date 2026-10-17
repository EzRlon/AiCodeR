package com.aicode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.core.util.toUserMessage
import com.aicode.core.util.formatCostUsd
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.floatball.FloatBallCommandCenter
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.voice.VoskModelManager
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.settings.data.repository.VoiceEngine
import com.aicode.feature.settings.data.repository.VoiceSettingsRepository
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.asDirtySignal
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.workflow.BudgetApprovalChoice
import com.aicode.feature.agent.domain.workflow.BudgetApprovalManager
import com.aicode.feature.agent.domain.workflow.BudgetApprovalRequest
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.takeTailLines
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.team.TeamDefinitionEntry
import com.aicode.feature.agent.domain.team.TeamRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.mention.FileMentionRepository
import com.aicode.feature.agent.domain.mention.FileMentionState
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

/** 交接提醒状态：上下文占用达到阈值后展示在输入区上方，确认则生成接手摘要并开新会话继续。 */
data class HandoffPrompt(
    val sessionId: String,
    val percent: Int,
    /** 正在生成接手摘要（按钮禁用、显示进度）。 */
    val generating: Boolean = false,
    /** 已达硬阈值：当前会话暂停接受新消息，面板不可「暂不」。 */
    val paused: Boolean = false,
    /** 暂停态下正在生成可复制的交接内容。 */
    val preparingCopy: Boolean = false
)

/** 交接检测结果：无动作 / 软提醒 / 硬阈值自动交接。 */
enum class HandoffAction { NONE, PROMPT, AUTO_HANDOFF }

/**
 * 交接阈值判定（纯函数，便于单测）：
 * - 硬阈值（自动交接开启时）优先，且不受软提醒次数影响；
 * - 软提醒达到阈值后每会话最多 [maxReminders] 次。
 */
internal fun decideHandoffAction(
    percent: Int,
    promptPercent: Int,
    reminderEnabled: Boolean,
    autoEnabled: Boolean,
    autoPercent: Int,
    dismissedCount: Int,
    maxReminders: Int = 2
): HandoffAction = when {
    autoEnabled && percent >= autoPercent -> HandoffAction.AUTO_HANDOFF
    reminderEnabled && percent >= promptPercent && dismissedCount < maxReminders -> HandoffAction.PROMPT
    else -> HandoffAction.NONE
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val budgetApprovalManager: BudgetApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val fileMentionRepository: FileMentionRepository,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val teamRepository: TeamRepository,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val scheduledTaskRepository: com.aicode.feature.schedule.data.ScheduledTaskRepository,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val voskModelManager: VoskModelManager,
    private val floatBallStateStore: com.aicode.feature.floatball.FloatBallStateStore,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()

    /** 语音输入引擎（设置里可切换；下一次录音生效）。 */
    val voiceEngine: StateFlow<VoiceEngine> = voiceSettingsRepository.engineFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VoiceEngine.SYSTEM)

    // ── 语音可用性探测与自动降级（验收反馈 F2/F7）─────────────────────

    /** 离线语音模型安装状态（引导卡片/设置页进度共用）。 */
    val voiceModelState: StateFlow<VoskModelManager.ModelState> = voskModelManager.state

    /** 系统识别可用性探测结果（null = 尚未探测；false = 本机无可用识别服务，如无谷歌设备）。 */
    val systemSttAvailable: StateFlow<Boolean?> = voiceSettingsRepository.systemSttAvailableFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 最近一次模型下载失败原因（引导卡片展示；无失败为 null）。 */
    val voiceModelFailureReason: String?
        get() = voskModelManager.lastFailureReason

    /** 进程内只尝试一次：后台静默下载的节流标志。 */
    private var voiceAutoDownloadAttempted = false

    /** 探测系统识别可用性并持久化（瞬时同步调用，不耗资源；聊天页冷启动与语音失败时触发）。 */
    fun refreshSystemSttAvailability() {
        val available = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)
        if (systemSttAvailable.value != available) {
            viewModelScope.launch { voiceSettingsRepository.setSystemSttAvailable(available) }
        }
    }

    /**
     * 系统引擎语音识别失败（网络/服务类错误）时的处理：更新探测；离线模型已就绪 →
     * 自动切离线并返回 false（调用方提示重试）；未就绪 → 返回 true（调用方弹引导卡片）。
     */
    fun onSystemVoiceFailure(): Boolean {
        refreshSystemSttAvailability()
        if (VoskModelManager.isModelReady(context)) {
            viewModelScope.launch { voiceSettingsRepository.setEngine(VoiceEngine.OFFLINE) }
            return false
        }
        return true
    }

    /** 用户在引导卡片点「立即下载」。 */
    fun startVoiceModelDownload() = voskModelManager.startDownload()

    /**
     * 后台静默下载（每个进程至多一次）：现场探测确认系统识别不可用 ∧ 模型缺失 ∧ 不计费网络（WiFi）。
     * 计费网络不静默下载（42MB），交给用户在引导卡片里主动确认。
     */
    fun maybeAutoDownloadVoiceModel() {
        if (voiceAutoDownloadAttempted) return
        if (VoskModelManager.isModelReady(context)) return
        val systemSttAvailable = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)
        if (systemSttAvailable) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        voiceAutoDownloadAttempted = true
        // 顺手持久化探测结果，供设置页/后续判断使用
        viewModelScope.launch { voiceSettingsRepository.setSystemSttAvailable(false) }
        FileLogger.i(TAG, "系统识别不可用且模型缺失，WiFi 下后台静默下载离线模型")
        voskModelManager.startDownload()
    }

    private val _handoffPrompt = MutableStateFlow<HandoffPrompt?>(null)
    val handoffPrompt: StateFlow<HandoffPrompt?> = _handoffPrompt.asStateFlow()

    /** 每个会话已点「暂不」的次数：软提醒最多两次，两次后不再打扰。 */
    private val dismissedHandoffCounts = mutableMapOf<String, Int>()

    /** 已达硬阈值而暂停的会话（sessionId → 触发时的占用百分比）：拒绝新请求，直至交接或用户明确继续。 */
    private val pausedHandoffSessions = mutableMapOf<String, Int>()

    /**
     * agent 执行期间持有的 CPU 唤醒锁：熄屏后系统会挂起进程，使流式响应中断、工具调用卡死。
     * 不计数（setReferenceCounted(false)），多会话共用一把锁，最后一个任务结束时统一释放。
     */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:AgentWorkflow")
            .apply { setReferenceCounted(false) }
    }

    /** 设置里用户手动开启的常驻保活；agent 任务收尾时不能把它一并关掉。 */
    @Volatile
    private var userKeepaliveEnabled = false

    /**
     * 定时任务离屏实例：关闭 agent 完成通知（由任务完成通知统一告知，避免双通知）。
     * 仅由 [com.aicode.feature.schedule.domain.ScheduledAiTaskWorker] 在离屏装配后置位。
     */
    var suppressAgentCompletionNotification = false

    /** WakeLock 续期协程：单个任务可能超过一次 acquire 的超时，需在到期前重新续上。 */
    private var wakeLockRenewJob: Job? = null

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    /**
     * 各会话「最后一轮是否正常完成」：只有正常收尾（收到完成标记）才在轮末回复下挂「复制 / 更多」
     * 按钮；主动暂停 / 出错 / 未开始都不挂，避免按钮挂在半截输出上。
     */
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
        floatBallStateStore.syncAgentStates(_agentStates.value)
    }

    /**
     * 失败文案：服务端给了类型码（如拒答/上下文超限）时用本地化说明，
     * 并附上服务端的具体理由（如果有）；无类型码时直接展示原错误文本。
     */
    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini 的 finishReason 全大写，这几种与 Anthropic 的 refusal 同义（内容策略拦截）。
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30
    private val loadedMessageLimits = mutableMapOf<String, Int>()

    /** 聊天记录搜索：命中条数上限、输入防抖、片段上下文宽度、定位时预留的分页余量。 */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val snippetContext = 40
    private val messageLimitMargin = 5

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        val id = _currentSessionId.value ?: return
        val editor = draftPrefs.edit()
        if (text.isEmpty()) {
            _inputDrafts.value = _inputDrafts.value - id
            editor.remove(id)
        } else {
            _inputDrafts.value = _inputDrafts.value + (id to text)
            editor.putString(id, text)
        }
        editor.apply()
    }

    /**
     * 各会话草稿的「消息类型」标记（仅内存、不持久化）：类型描述的是「这一条」，
     * 发送后随清草稿一并重置；未分类即键不存在。
     */
    private val _intentDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val draftIntentType: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else _intentDrafts.map { it[id] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 设置/清除当前会话草稿的消息类型；null 清除（未分类）。 */
    fun updateDraftIntentType(type: String?) {
        val id = _currentSessionId.value ?: return
        _intentDrafts.value = if (type == null) {
            _intentDrafts.value - id
        } else {
            _intentDrafts.value + (id to type)
        }
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
        _intentDrafts.value = _intentDrafts.value - id
    }

    /** 外部投递进来的文本（系统分享等）：会话未就绪时先暂存，聊天面板在会话就绪后消费并合并进输入草稿。 */
    private val _pendingExternalText = MutableStateFlow<String?>(null)
    val pendingExternalText: StateFlow<String?> = _pendingExternalText.asStateFlow()

    fun queueExternalText(text: String) {
        val existing = _pendingExternalText.value
        _pendingExternalText.value = if (existing.isNullOrBlank()) text else "$existing\n\n$text"
    }

    fun consumePendingExternalText() {
        _pendingExternalText.value = null
    }

    /** 外部投递进来的附件（截图标注汇总等）：会话就绪后由聊天面板读文件转成待发附件。 */
    private val _pendingExternalAttachments = MutableStateFlow<List<AgentAttachment>>(emptyList())
    val pendingExternalAttachments: StateFlow<List<AgentAttachment>> = _pendingExternalAttachments.asStateFlow()

    fun queueExternalAttachments(attachments: List<AgentAttachment>) {
        if (attachments.isEmpty()) return
        _pendingExternalAttachments.value = _pendingExternalAttachments.value + attachments
    }

    fun consumePendingExternalAttachments(): List<AgentAttachment> {
        val current = _pendingExternalAttachments.value
        _pendingExternalAttachments.value = emptyList()
        return current
    }

    /**
     * 工具调用（分组头与单条工具卡片）的手动展开态：key = 分组 key（`toolgroup:<组内首条消息 id>`）
     * 或单条消息 id。
     *
     * 放在 ViewModel 而不是组合里：窄窗下打开设置 / 终端 / Git / 编辑器都是全屏路由，聊天页整棵
     * 组合被 dispose，`remember` 的 map 与按 message.id 的 remember 会一起丢——展开过的工具
     * 一离开视线（滚出屏幕被回收、切页返回）就缩回默认态。这里按 App 进程的内存保留，
     * 会话间互不影响（key 取消息 id，全局唯一），不落盘。
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次手动展开/收起（取值由调用方按当前可见态取反后传入）。 */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * 整轮任务折叠（外层「执行中 / 已完成」头）的手动展开态：key = `turn:<轮首消息 id>`。
     *
     * 与 [toolExpansionOverrides] 同款：按 App 进程内存保留、不落盘。没记录时按「末轮运行中默认展开、
     * 其余默认收起」推默认值（见 AIChatPanel.buildChatItems 的 activeTurnKey）；一旦用户手动点过，
     * 就以其选择为准，任务完成自动收起也不会覆盖它。
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次整轮折叠的手动展开/收起。 */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * 思考过程展开态的手动选择：key = 助手消息 id。与 [toolExpansionOverrides] 同款（进程内存、不落盘），
     * 使思考卡片划出视口回收、切页返回后仍保持展开/收起。
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次思考卡片的展开/收起。 */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /** 思考过程耗时（毫秒）：key = 助手消息 id。记录流式思考的实际耗时，供落库后的卡片展示一位小数秒数。 */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val state = messagesState.value
        if (state.sessionId != sid || !state.loaded || !state.hasMore) return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        if (loadedMessageLimits[sid] != currentLimit) return
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        // 切到新工作区：恢复该工作区上次持久化的展开状态（无记录则只展开根）。
        _expandedPaths.value = loadExpansion(path)
        // 搜索限定当前工作区，切区后旧结果无意义，一并清空。
        _chatSearchQuery.value = ""
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有根会话的子代理，按父会话 id 分组，供侧边栏会话行就地展开。
     * 用全量查询而非逐行惰加载：同一张表一次读完，避开每展开一行开一个 Flow 的订阅风暴。
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── 聊天记录全局搜索（限当前工作区）──
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLike(keyword),
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * 打开一条搜索命中：切到对应会话，把该会话分页上限抬到足够包含目标消息，并登记待定位消息。
     * 实际滚动由聊天页在消息就绪后完成。搜索词与结果保留，重开侧边栏仍停在结果列表。
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** 转义 LIKE 通配符，配合 SQL 里的 ESCAPE '!'（转义字符本身需最先处理）。 */
    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** 以命中词为中心截取片段：折叠换行空白，两端按需加省略号；未命中时退回开头一段。 */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(snippetContext * 2)
        val start = (index - snippetContext).coerceAtLeast(0)
        val end = (index + keyword.length + snippetContext).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }

    /** 侧边栏「文件」Tab 已展开的目录集合（容器路径）。含工作区根：根也可折叠，默认展开；按工作区持久化。 */
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))

    /** 文件树展开状态按工作区持久化（重启保留）；key 为工作区路径，值为已展开的容器路径集合。 */
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)

    /** 读取某工作区持久化的展开集；无记录时默认只展开工作区根。 */
    private fun loadExpansion(workspace: String): Set<String> =
        expansionPrefs.getStringSet(workspace, null)?.toSet()
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    /** 持久化当前工作区的展开集（传新集合副本，SharedPreferences 禁止复用已存实例）。 */
    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** 正在展开、等待列目录返回的目录路径（远程 SSH 下 listFiles 可能耗时数秒）；UI 在该行显示等待动画。 */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** 正在执行写操作（新建/重命名/删除/复制/移动）的条目路径集合；UI 在对应行与工具栏显示等待动画。 */
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()

    /** 手动刷新信号：远程模式无 inotify，只能靠它；本地模式作为兜底。 */
    private val _browseRefresh = MutableStateFlow(0)

    /**
     * 扁平化的可见文件树。listFiles 在本地是阻塞 IO、远程是网络调用，必须跑 IO 调度器。
     * 监听工作区根与所有已展开目录：任一发生变动（不限 AI，终端与其它 App 同样算）或手动刷新都会重建树。
     * 只监听展开中的目录、不递归整棵树，避免大仓库开出大量 inotify 句柄。
     */
    val browseState: StateFlow<FileBrowseState> = _expandedPaths
        .flatMapLatest { expanded ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map { fileChangeHub.watchWorkspace(it).asDirtySignal() }.merge().debounce(BROWSE_DEBOUNCE_MS),
                // drop(1) 丢掉 StateFlow 重建时的当前值，否则刚展开就会多读一次
                _browseRefresh.drop(1).map { }
            )
            flow {
                // 首次产出前由 stateIn 初值 Loading 占位；后续展开/折叠/刷新不再回到 Loading，
                // StateFlow 保留上一份 Success 直到新树就绪，避免闪加载动画与滚动位置丢失。
                emit(buildBrowseTree(expanded))
                triggers.collect { emit(buildBrowseTree(expanded)) }
            }.flowOn(Dispatchers.IO)
        }
        // 新树已就绪：清掉展开等待态（无论成功/出错都清，避免转圈卡死）。
        .onEach { _expandingPath.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    /** 读取工作区根并按 [expanded] 递归展开，产出扁平的可见节点列表；根读取失败则整体报错。 */
    private fun buildBrowseTree(expanded: Set<String>): FileBrowseState {
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val rootEntries = runCatching { fileAccess.listFiles(root) }.getOrElse { e ->
            FileLogger.w(TAG, "列目录失败: $root", e)
            return FileBrowseState.Error(e.message)
        }
        val ignorePatterns = loadRootGitignore(root)
        val rootExpanded = root in expanded
        val nodes = mutableListOf<FileTreeNode>()
        nodes += FileTreeNode(
            entry = FileEntry(name = "workspace", isDirectory = true, size = 0, lastModified = 0),
            path = root,
            depth = 0,
            isRoot = true,
            isExpanded = rootExpanded
        )
        if (rootExpanded) {
            appendBrowseChildren(root, rootEntries, depth = 1, expanded = expanded, ignorePatterns = ignorePatterns, relParts = emptyList(), out = nodes)
        }
        return FileBrowseState.Success(nodes)
    }

    /** 读工作区根 .gitignore（本地/远程均可）；去空行/注释/否定行，读不到则空。仅根 .gitignore，不处理嵌套。 */
    private fun loadRootGitignore(root: String): List<String> = runCatching {
        val path = "$root/.gitignore"
        if (!fileAccess.exists(path)) return emptyList()
        fileAccess.readFile(path).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
            .toList()
    }.getOrDefault(emptyList())

    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        // 按名字去重：文件系统的 readdir 在 FUSE 存储（外部工作区/模拟存储）上可能重复返回同一条目，
        // AOSP 的 ReaddirHelper 亦做同样处理。同名条目会产生重复的节点 path，撞坏 LazyColumn 的 key。
        for (entry in entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)) {
            val path = "$parent/${entry.name}"
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = false, ignored = ignored)
                continue
            }
            val children = runCatching { fileAccess.listFiles(path) }.getOrNull()
            out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    fun refreshBrowse() {
        _browseRefresh.value++
    }

    /** 展开/折叠目录；折叠时连同其所有后代一并移出展开集，避免残留监听与再展开时意外深开。改变后按工作区持久化。
     *  展开需等列目录返回，期间标记 [expandingPath] 让 UI 显示等待动画，并忽略对同一目录的重复点击（否则会把它折回去）。 */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith("$path/") }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
    }

    /** 文件树定位（存储空间等入口跳转用）：展开指定容器路径的父目录链，让目标在「文件」页签中可见。 */
    fun revealPathInTree(containerPath: String) {
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val normalized = containerPath.trimEnd('/')
        if (!normalized.startsWith(root)) return
        val rel = normalized.removePrefix(root).trim('/')
        if (rel.isEmpty()) return
        val parts = rel.split('/')
        val expand = buildSet {
            add(root)
            var acc = root
            for (i in 0 until parts.size - 1) {
                acc = "$acc/${parts[i]}"
                add(acc)
            }
        }
        _expandedPaths.value = _expandedPaths.value + expand
    }

    /**
     * 文件浏览的写操作共用包装：跑 IO 调度器，成功后主动重读目录（远程模式无 inotify）。
     * [busyPaths] 为本次操作涉及的条目路径，操作期间加入 [fileOpPaths] 让 UI 显示等待动画。
     * [block] 返回 false 表示名称非法或同名已存在，抛异常表示 IO 失败，两者均回报失败。
     */
    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                runCatching(block)
                    .onFailure { FileLogger.w(TAG, "文件操作失败", it) }
                    .getOrDefault(false)
            }
            if (success) refreshBrowse()
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    /** [parent] 目录下的子路径；名称非法时返回 null。 */
    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) "$parent/${name.trim()}" else null

    /** 在 [parent] 目录新建空文件。 */
    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    /** 在 [parent] 目录新建文件夹。 */
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    /** 重命名条目（仅同目录内改名，不跨目录移动）。 */
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) {
                false
            } else {
                fileAccess.rename(path, "$parent/${newName.trim()}")
                true
            }
        }

    /** 删除条目；目录连同内容递归删除。 */
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    // region 文件复制 / 剪切 / 粘贴

    /** 文件浏览剪切板：复制或剪切后暂存源条目，供粘贴到其它目录。 */
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()

    /** 复制条目进剪切板（剪切板只能存一项，直接覆盖旧的）。 */
    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    /** 剪切条目进剪切板（粘贴成功后删除源，且只允许粘贴一次）。 */
    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    /** 清空剪切板。 */
    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    /** 粘贴冲突（目标已存在同名项）待用户确认覆盖。持有源路径与目标目录，确认后调 [pasteBrowseEntryOverwrite]。 */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    /** 把剪切板内容粘贴到 [targetDir]。目标已存在同名项时，发 [pasteConflict] 让 UI 弹窗询问是否覆盖，
     *  不执行粘贴；否则直接粘贴。粘贴成功即清空剪切板（无论复制/剪切，一次粘贴后失效），失败保留供重试。 */
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    /** 用户确认覆盖后强制粘贴（目标已存在同名项也会覆盖）。 */
    fun pasteBrowseEntryOverwrite() {
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    /** 清空粘贴冲突待确认状态（用户点了「取消」时）。 */
    fun clearPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths = busyPaths, onResult = { success ->
            // 无论复制还是剪切，粘贴成功即清空剪切板，避免重复粘贴；失败保留，允许重试。
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            if (clip.isCut) {
                fileAccess.move(clip.sourcePath, target, overwrite)
                true
            } else {
                fileAccess.copy(clip.sourcePath, target, overwrite)
                true
            }
        }
    }

    // endregion

    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = list.asSequence()
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false
                )
            }.flowOn(Dispatchers.Default)
                .onEach { loadedMessageLimits[id] = limit }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 模型正在流式产出、还没开始执行的工具名（如 `editFile`）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。上游在工具名一出现就上报（[AgentEvent.ToolCallPreparing]），
     * UI 据此把状态换成具体场景（「正在编辑文件」）。工具真正开始执行后由 [runningTool] 接管。
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) _reasoningTimings.value = _reasoningTimings.value - sessionId
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    /** 按 sessionId 维护的多 Key 切换提示；重新出内容或本轮结束时置 null。 */
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在等待用户授权的会话 id 集合，供侧边栏会话行点亮橙色指示灯。 */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    val pendingBudgetApproval: StateFlow<BudgetApprovalRequest?> = budgetApprovalManager.pendingApproval

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        if (sessions.value.any { sessionJobs[it.id]?.isActive == true }) return true
        // 子代理会话不在 sessions（只含根会话）里：漏掉它们会在父代理这一轮结束时
        // 提前释放唤醒锁与前台服务，仍在跑的子代理会被系统挂起。
        return subSessionsByParent.value.values.any { subs ->
            subs.any { sessionJobs[it.id]?.isActive == true }
        }
    }

    /** 任务开始：拿 CPU 唤醒锁并拉起前台保活通知，避免熄屏或切后台时进程被挂起、回收。 */
    private fun acquireKeepalive() {
        TerminalKeepaliveService.enablePersistent(context)
        if (!wakeLock.isHeld) {
            runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
                .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
        }
        startWakeLockRenewal()
    }

    /** 任务收尾：释放唤醒锁；仅当既无后台终端任务、用户也没手动开保活时才停前台服务。 */
    private fun releaseKeepalive() {
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    /**
     * 在 WakeLock 到期前持续续期，直到没有正在运行的任务。单次 acquire 的超时覆盖不了超长任务
     * （子代理串行执行可超过一小时），过期后 CPU 可休眠，后台收包会停摆。
     */
    private fun startWakeLockRenewal() {
        if (wakeLockRenewJob?.isActive == true) return
        wakeLockRenewJob = viewModelScope.launch {
            while (isActive) {
                delay(KEEPALIVE_TIMEOUT_MS - WAKE_LOCK_RENEW_MARGIN_MS)
                if (!hasRunningSessionsInCurrentWorkspace()) break
                runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
                    .onFailure { FileLogger.e(TAG, "renew wakeLock failed", it) }
            }
        }
    }

    /** App 退到后台时 Agent 完成，弹一条可点击的系统通知（标题=任务完成，正文=用户消息）。 */
    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    /**
     * 系统通知正文：普通用户消息直接展示；后台回调触发的轮次不裸露
     * 「[系统通知 - 非用户输入]…」内部构造文本，改为展示任务标题。
     */
    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"

        /** 50% 软提醒的会话级上限：两次提醒后不再打扰。 */
        const val MAX_HANDOFF_REMINDERS = 2
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        /** wakeLock 超时保险：构建、装依赖类工具动辄十几分钟，给足 60 分钟；任务正常结束会主动释放。 */
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L

        /** WakeLock 续期提前量：在超时前这么早重新 acquire，避免任务中途丢锁。 */
        private const val WAKE_LOCK_RENEW_MARGIN_MS = 10 * 60 * 1000L
        /** 从后台任务通知文本中提取 <title> 内容，供系统通知正文展示。 */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        /** 文件浏览排序：目录在前，同类按名称不区分大小写。 */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 写文件会连珠触发多个 inotify 事件，合并后再重读目录。 */
        const val BROWSE_DEBOUNCE_MS = 300L
    }

    init {
        // F2/F7：离线模型就绪且系统识别确认不可用时自动切离线引擎，语音输入立即可用。
        viewModelScope.launch {
            voskModelManager.state
                .filter { it is VoskModelManager.ModelState.Ready }
                .collect {
                    if (systemSttAvailable.value == false && voiceEngine.value == VoiceEngine.SYSTEM) {
                        voiceSettingsRepository.setEngine(VoiceEngine.OFFLINE)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.voice_auto_ready_toast),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        // 冷启动收尾：上次进程被杀时残留的「执行中」工具回填为「已中断」。在后台执行，不阻塞首屏与会话展示。
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // 启动与工作区切换时重新扫描技能（项目级技能随工作区变化），刷新 `/` 命令菜单。
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // 立即确定并设置当前会话，确保首帧 UI 秒开、侧边栏立即出现新会话，彻底消除转圈卡顿。
                // 「打开最近会话」有历史就直接进最近那个（按 updatedAt，不受置顶影响）；
                // 「新开会话」复用还没发过消息的空会话（避免每次启动都堆一个空会话），其余情况新建。
                val targetId = when {
                    recent == null -> createAndUpsertSession(path)
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // 异步回收多余的空会话（保留当前 targetId），在后台执行，不卡主线程与首帧渲染。
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        viewModelScope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        // 订阅子代理生命周期事件（类比 terminal 的 notify=true 异步回调）：
        // - SPAWNED：task 工具已创建子会话并替用户发消息，这里在子会话上自动启动 AI 工作流；
        // - STOPPED：task(action="stop") 请求停止子代理，取消对应会话的 AI 任务；
        // - COMPLETED/FAILED：由子会话工作流结束时发出（见 handleSubAgentFinished），注入父会话通知。
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.STOPPED -> stopAgentSession(event.subSessionId)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }
    }

    /**
     * 子代理已创建（task 工具已创建子会话）：在子会话上启动 AI 工作流。
     * 消息由 executeAgentRequestStream 统一落库（与用户手动发消息一致），
     * 标题保留 task 传入的 description（skipTitleUpdate）。
     */
    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "子代理父会话不存在: ${event.parentSessionId}")
            return
        }
        // 子会话可能已被用户删除，跳过
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            return
        }
        // 子代理运行中不允许重复启动（同一会话已有活跃 job）
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
        )
    }

    /**
     * 子代理完成/失败通知：父会话忙碌时先入队（由本轮内下一批工具结果搭车送达，见
     * [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]），空闲时立即注入一条系统通知消息触发新一轮。
     * 父代理据此得知子代理结束，可 task(action="read") 取回结果。与 terminal 后台通知同机制。
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        viewModelScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = if (event.type == SubAgentEventType.FAILED) {
                    NotificationOutcome.FAILED
                } else {
                    NotificationOutcome.COMPLETED
                },
                detail = event.detail.takeIf { it.isNotBlank() && event.type == SubAgentEventType.FAILED }
            )
        }
    }

    /**
     * 主会话发给运行中/已完成子代理的消息：收件人忙碌时入队搭车，空闲时触发新一轮。
     * 与 [notifyParentSubAgentFinished] 同分发逻辑，方向相反。
     */
    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true
        )
    }

    /** 子代理发给主会话的消息：同上，收件人为主会话。 */
    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false
        )
    }

    /**
     * 投递一条代理间消息：收件人忙碌时入 [AgentNotificationCenter]（本轮内工具结果搭车，或整轮结束后兜底），
     * 空闲时以一条通知消息触发其新一轮。消息正文随通知一并送达，收件方无需再另行读取。
     */
    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent
        )
        deliverSystemEvent(recipientSessionId, item)
    }

    /**
     * 向父会话投递一条子代理结束通知：父会话忙碌时入队搭车，空闲时立即注入触发新一轮。
     * 失败与被终止都要带上原因，否则父代理只知道「没成」，还得再 read 一次才可能拿到线索。
     */
    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail
        )
        deliverSystemEvent(parentSessionId, item)
    }

    /**
     * 后台命令（notify=true）结束后的回调。
     *
     * - 会话忙碌：入队 [agentNotificationCenter]，由本轮内下一批工具结果搭车送达（AI 当轮即可感知，
     *   省掉「等本轮结束再起一轮」的 LLM 往返）；整轮再没有工具调用时由 [flushPendingNotifications] 兜底。
     * - 会话空闲：立即以一条系统通知消息触发 Agent 新一轮。
     *
     * 兜底路径用 user 消息而非 assistant(tool_call) + tool_result 消息对：后者会与原 terminal 工具调用的
     * tool 结果在落库顺序上错位（后台回调异步触发，可能抢先于原 terminal 结果落库），导致 messages
     * 违反 OpenAI「assistant(tool_calls) → tool 结果紧跟」的配对约束，上游返回 400。user 消息无需与
     * 任何 tool_call 配对，天然不破坏顺序。通知文本带围栏说明，防止 AI 误判为用户的新指令或批准；
     * AI 据此用 terminal(read) 取回完整输出。
     *
     * 不自行 persist 通知、用 isAutoTrigger=false 走 enqueueAgentRequest 正常流程：由
     * executeAgentRequestStream 统一 persist 这条 user 消息，workflow 的 InitRequest 追加的同一条
     * UserMessage 即是它，避免重复落库或出现空占位消息。
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        val item = event.toPendingNotification()
        deliverSystemEvent(sessionId, item)
    }

    /**
     * 统一投递一条系统事件给指定会话：忙碌则入 [agentNotificationCenter]，由本轮内工具结果搭车送达；
     * 空闲则以一条系统通知消息触发新一轮。后台任务完成、子代理结束、代理间消息、模式切换共用此分发，
     * 避免各处重复判断忙碌/空闲。
     */
    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        if (sessionJobs[sessionId]?.isActive == true) {
            agentNotificationCenter.enqueue(sessionId, item)
            return
        }
        viewModelScope.launch {
            val accepted = enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(listOf(item)),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
            if (!accepted) {
                // 硬阈值暂停拦截：通知回收入中心，解除暂停后由 flushPendingNotifications 补投。
                agentNotificationCenter.enqueue(sessionId, item)
            }
        }
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    /**
     * 本轮结束后的兜底送达：把仍留在队列里的通知合并成一条消息发送。
     * 已被工具结果搭车送达的通知此时已 ack 移除，取到空则什么都不做。
     */
    private fun flushPendingNotifications(sessionId: String) {
        // 暂停会话不 drain：通知保留在中心，解除暂停/交接后下一轮可补投，避免丢失。
        if (sessionId in pausedHandoffSessions) return
        val items = agentNotificationCenter.drain(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(TAG, "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}")
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    /**
     * 提交一条 agent 请求：空闲直接执行、忙碌入队；返回 false 表示被硬阈值暂停拦截（调用方应保留草稿）。
     */
    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null,
        intentType: String? = null
    ): Boolean {
        val sid = targetSessionId ?: _currentSessionId.value
        // 硬阈值暂停：拒绝一切新请求（含系统通知），避免继续膨胀上下文污染项目；
        // 同时把交接面板重新亮出来（用户手动切回旧会话时告知原因与出口）。
        if (sid != null && sid in pausedHandoffSessions) {
            _handoffPrompt.value = HandoffPrompt(sid, pausedHandoffSessions.getValue(sid), paused = true)
            return false
        }
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger,
                intentType = intentType
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger,
                intentType = intentType
            )
        }
        return true
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sid to reordered)
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.map { req ->
            if (req.id != id) req
            else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
        })
    }

    /**
     * 用编辑后的文本重建 modelRequest：无附件注入时与 request 同步；有附件注入时
     * （拼成 `request + "\n\n" + 附件清单`）只替换文本前缀、保留附件部分。
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    /**
     * 把当前会话队列中某条消息立即插入正在运行的轮次：从队列移除，转成用户插话通知交给
     * [deliverSystemEvent]——忙碌时搭车注入本批工具结果，AI 当前轮即可感知；空闲时作为
     * 新一轮用户消息发送。
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
        deliverSystemEvent(
            sid,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sid,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        )
    }

    private fun processNextInQueue(sessionId: String) {
        // 暂停会话不消费队列：保留待发请求，交接后用户到新会话重新发起。
        if (sessionId in pausedHandoffSessions) return
        // 已有活跃 job（可能是本次收尾前由通知合并/flush 等入口启动的）时不消费，
        // 避免队列被多个收尾入口重复消费、同一会话并发跑两个 job。
        if (sessionJobs[sessionId]?.isActive == true) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger,
            intentType = next.intentType
        )
    }

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再按类型派发——
     * 内置命令走本地动作，技能则把其正文作为本轮指令触发 agent 回合。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        /** 本条用户消息的类型标签键（仅 USER 行落库）；模型侧瞬态说明由调用方并入 modelRequest。 */
        intentType: String? = null,
        /** 子代理等场景：已预设会话标题，跳过首条消息的标题推导/生成，保留预设标题。 */
        skipTitleUpdate: Boolean = false
    ): Job = viewModelScope.launch {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过请求")
            return@launch
        }
        // 硬阈值暂停兜底：排队消费/直调等所有执行入口在此统一拒绝（队列保留不丢）。
        if (sessionId in pausedHandoffSessions) {
            _handoffPrompt.value = HandoffPrompt(sessionId, pausedHandoffSessions.getValue(sessionId), paused = true)
            return@launch
        }
        // 命令分流：命中斜杠命令（内置命令或技能）时，不走 agent workflow，直接执行命令操作
        // （命令文本已作为用户消息落库，进入对话上下文）。不注册 sessionJobs，
        // 因此 isRunning 保持 false，/compress 等命令内部的自检可以正常工作。
        if (request.startsWith("/")) {
            slashCommandRegistry.resolve(request)?.let { command ->
                runResolvedCommand(command, request, sessionId)
                return@launch
            }
        }
        // 兼容历史会话：若会话尚未持久化绑定 providerId/model，发消息时将其固化，避免后续默认模型变动影响已有会话
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession != null && (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        setAgentState(sessionId, AgentUIState.Streaming)
        acquireKeepalive()

        try {
            var failed = false
            // 必须在插入本次用户消息之前读取历史：workflow 会自己 add(userRequest)，避免重复。
            val historyStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(
                TAG,
                "history.start",
                details = "operation=$historyStartedNs requestChars=${request.length} attachments=${inputAttachments.size}"
            )
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            FileLogger.memoryCheckpoint(
                TAG,
                "history.ready",
                elapsedMs = (System.nanoTime() - historyStartedNs) / 1_000_000,
                details = "operation=$historyStartedNs messages=${history.size}"
            )
            val isFirst = history.isEmpty()

            val userMsgId = UUID.randomUUID().toString()
            if (!isAutoTrigger) {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, request, id = userMsgId,
                    attachments = inputAttachments, intentType = intentType)
                checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    // 后台异步用 LLM 生成更贴切的标题替换临时标题；失败/取不到时保留临时标题
                    viewModelScope.launch {
                        agentWorkflow.generateTitle(sessionId, request)?.let { sessionUseCase.updateTitle(sessionId, it) }
                    }
                }
            } else {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, modelRequest, id = userMsgId,
                    attachments = inputAttachments)
            }
            messagePersistenceUseCase.invalidateHistory(sessionId)
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD
            // 子会话的 subagentType 存的是自定义 agent 名；能查到定义时提示词与工具集都按它组装。
            // 定义来自目录扫描（远程模式经 SFTP），故切到 IO，避免阻塞主线程。
            val agentDefinition = withContext(Dispatchers.IO) {
                sessionEntity?.takeIf { it.parentId != null }
                    ?.subagentType
                    ?.let { agentDefinitionRepository.findIncludingDisabled(it) }
            }

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                inputMessageId = userMsgId,
                lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
                teamName = sessionEntity?.teamName,
                mode = mode,
                modeBeforePlan = sessionDomain?.modeBeforePlan,
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
                agentDefinition = agentDefinition
            )

            val allTools = toolRegistry.getAvailableTools()
            val isSub = sessionEntity?.parentId != null
            val tools = when {
                agentDefinition != null -> {
                    val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                    allTools.filter { it.name in allowed }
                }
                isSub -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
                else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
            }

            var currentReasoningStart: Long? = null
            var currentReasoningEnd: Long? = null
            fun finishReasoning() {
                val start = currentReasoningStart ?: return
                if (currentReasoningEnd == null) {
                    val end = System.currentTimeMillis()
                    currentReasoningEnd = end
                    _reasoningTimings.value = _reasoningTimings.value + (sessionId to (start to end))
                }
            }
            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        if (event.accumulated.hasVisibleContent()) finishReasoning()
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        if (currentReasoningStart == null) {
                            currentReasoningStart = System.currentTimeMillis()
                            currentReasoningEnd = null
                        }
                        _reasoningTimings.value = _reasoningTimings.value +
                            (sessionId to (currentReasoningStart!! to currentReasoningEnd))
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        finishReasoning()
                        // 工具名先于参数到达：让 UI 把「正在思考」换成具体场景（「正在编辑文件」）。
                        // 参数流完、工具真正开始执行后由 ToolCallStarted 清掉，改由工具行表达。
                        setPreparingTool(sessionId, event.toolName)
                    }
                    is AgentEvent.Retrying -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // 重试会从头重新流式输出：清掉已展示的正文/思维链气泡，
                        // 否则重连后思维链重新生成而旧正文残留（workflow 已同步清空累积器）。
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                    }
                    is AgentEvent.CompactionStarted -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        _contextUsages.value = _contextUsages.value - sessionId
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        // 落库为无配对的 TOOL 消息：UI 渲染失败卡片，buildHistory 回放自动丢弃，不进模型上下文。
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.reason,
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true
                        )
                    }
                    is AgentEvent.VerificationReport -> {
                        // 独立验收结论：落库为无配对的 TOOL 消息（专用卡片渲染，buildHistory 回放自动丢弃）。
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.content,
                            toolName = VERIFICATION_REPORT_TOOL_NAME,
                            isError = false
                        )
                    }
                    is AgentEvent.BudgetStopped -> {
                        // 用户因预算用尽选择停止：落一条说明，让对话流对「为何停下」有迹可循。
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            context.getString(R.string.agent_budget_stopped, event.usedTokens, event.budgetTokens)
                        )
                    }
                    is AgentEvent.AssistantText -> {
                        finishReasoning()
                        val reasoningDuration = currentReasoningStart?.let { start ->
                            currentReasoningEnd?.minus(start)
                        }
                        currentReasoningStart = null
                        currentReasoningEnd = null

                        // 流式收尾：在落库并触发 UI messages 更新之前，先同步清空流式状态，
                        // 避免落库消息先行发射导致 UI 出现「落库消息与流式气泡同屏并存」的时差。
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        val msgId = event.messageId
                        if (reasoningDuration != null && reasoningDuration > 0) {
                            reasoningDurations[msgId] = reasoningDuration
                        }
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            id = msgId,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                            attachments = event.attachments,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            cachedInputTokens = event.cachedInputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                            // 同步写库：工具循环下一轮 CallLlm 前会重读 lastInputTokens 判断压缩，
                            // 异步写库可能读到压缩前的旧大值导致重复触发压缩。
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        }
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        setPreparingTool(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(describeFailure(event)))
                        // 子代理会话失败时通知父会话
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error
                                    )
                                )
                            }
                        }
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        // 子代理会话完成时通知父会话（异步回调）
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.COMPLETED
                                    )
                                )
                            }
                        }
                        // 仅当 App 不在前台时发 agent 完成通知（避免打扰正在看对话的用户）。
                        // 定时任务离屏实例由任务完成通知统一告知，不在此重复。
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!suppressAgentCompletionNotification && !inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                    }
                    is AgentEvent.ModeChanged -> {
                        // 模式切换事件：PlanApprovalManager 已在 workflow 层面挂起等待用户批准
                        // 这里只更新 streamingText 显示
                    }
                }
            }

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            // 仅当本 job 仍持有忙状态时才置完成态：并发场景下队列/通知可能已启动新的 job
            // 并把状态改为 Streaming，不能被先结束的 job 误覆盖成 Result（按钮会提前变回发送）。
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            throw e
        } catch (e: Exception) {
             FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage()))
             _completedSessions.value = _completedSessions.value - sessionId
        } finally {
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                sessionJobs.remove(sessionId)
            }
            _runningTools.value = _runningTools.value - sessionId
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setPreparingTool(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setKeySwitchState(sessionId, null)
            // 取消/失败路径不会走到 AssistantDelta 的 finishReasoning：
            // 这里把未结束的计时封口，否则思考气泡上的「正在思考（耗时 N 秒）会一直走表。
            val timing = _reasoningTimings.value[sessionId]
            if (timing != null && timing.second == null) {
                _reasoningTimings.value = _reasoningTimings.value +
                    (sessionId to (timing.first to System.currentTimeMillis()))
            }

            // 本轮未能搭车送达的后台通知：本轮结束且 job 已移除后，合并成一条发送
            flushPendingNotifications(sessionId)

            // 交接提醒：仅在本轮成功结束、无排队继续时评估（纯提示，失败静默）
            if (isOwnJob &&
                _agentStates.value[sessionId] is AgentUIState.Result &&
                _queuedRequests.value[sessionId].isNullOrEmpty()
            ) {
                maybePromptHandoff(sessionId)
            }

            // 正常完成时先回到 Idle，再处理队列；队列若有下一轮会重新设 Streaming
            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                processNextInQueue(sessionId)
            }
            // 放在队列处理之后：flushPendingNotifications / processNextInQueue 会同步注册接替的 job，
            // 此时 hasRunningSessions 才能反映真实状态，不会刚释放又立即重新获取。
            if (!hasRunningSessionsInCurrentWorkspace()) {
                releaseKeepalive()
            }
        }
    }.also { job ->
        // 同步注册 job：launch 内的 sessionJobs 赋值是异步的，finally 中 flushPendingNotifications
        // 与 processNextInQueue 会在赋值前都看到 isActive=false 而双消费启动两个 job，
        // 先结束的 job 把状态置 Idle/Result 覆盖仍在跑的 job 的 Streaming。
        if (targetSessionId != null && slashCommandRegistry.resolve(request) == null) {
            sessionJobs[targetSessionId] = job
        }
    }

    /**
     * 交接提醒评估：只在回合成功结束时调用，任何一步失败都静默（纯提示，不影响主流程）。
     * - 硬阈值（默认 75%）：暂停当前会话并自动生成接手摘要、开新会话继续（可在设置关闭）；
     * - 软阈值（默认 50%）：最多提醒两次，提示模型推理能力即将下降。
     */
    private suspend fun maybePromptHandoff(sessionId: String) {
        runCatching {
            // 只对根会话生效：子代理会话的上下文由父会话统一管理，不单独提醒/暂停。
            val session = sessionUseCase.getSessionById(sessionId) ?: return
            if (session.parentId != null) return
            val usedTokens = session.lastInputTokens
            if (usedTokens <= 0) return
            val budget = agentWorkflow.handoffInputBudget(sessionId) ?: return
            val percent = (usedTokens * 100L / budget).toInt()
            val action = decideHandoffAction(
                percent = percent,
                promptPercent = generalSettingsRepository.handoffPromptPercentFlow.first(),
                reminderEnabled = generalSettingsRepository.handoffPromptEnabledFlow.first(),
                autoEnabled = generalSettingsRepository.handoffAutoEnabledFlow.first(),
                autoPercent = generalSettingsRepository.handoffAutoPercentFlow.first(),
                dismissedCount = dismissedHandoffCounts[sessionId] ?: 0,
                maxReminders = MAX_HANDOFF_REMINDERS
            )
            if (action == HandoffAction.NONE) return
            // 挂起读取设置后复查运行状态：flush 通知可能已拉起新一轮，不能按「本轮已结束」处理。
            if (sessionJobs[sessionId]?.isActive == true) return
            if (_agentStates.value[sessionId] is AgentUIState.Streaming) return
            if (!_queuedRequests.value[sessionId].isNullOrEmpty()) return
            when (action) {
                HandoffAction.AUTO_HANDOFF -> {
                    if (sessionId in pausedHandoffSessions) return
                    pausedHandoffSessions[sessionId] = percent
                    _handoffPrompt.value = HandoffPrompt(sessionId, percent, paused = true, generating = true)
                    if (!handoffToNewSession(sessionId, closePromptOnSuccess = true)) {
                        _handoffPrompt.value = HandoffPrompt(sessionId, percent, paused = true)
                    }
                }
                HandoffAction.PROMPT -> _handoffPrompt.value = HandoffPrompt(sessionId, percent)
                HandoffAction.NONE -> Unit
            }
        }.onFailure { FileLogger.w(TAG, "交接提醒评估失败", it) }
    }

    /**
     * 生成接手摘要并新建「（接手）旧标题」会话继续；成功后切到新会话。
     * 失败返回 false（调用方决定面板如何恢复）。
     */
    private suspend fun handoffToNewSession(oldSessionId: String, closePromptOnSuccess: Boolean): Boolean = runCatching {
        val summary = agentWorkflow.buildHandoffSummary(oldSessionId)
        check(!summary.isNullOrBlank()) { "接手摘要生成失败" }
        val old = sessionUseCase.getSessionById(oldSessionId)
        checkNotNull(old) { "原会话不存在" }
        val newEntity = sessionUseCase.newSessionEntity(
            workspacePath = old.workspacePath,
            providerId = old.providerId,
            model = old.model,
            reasoningEffort = old.reasoningEffort
        ).copy(title = handoffTitle(old.title))
        sessionUseCase.upsertSession(newEntity)
        messagePersistenceUseCase.persist(newEntity.id, MessageRole.USER, CONTEXT_COMPACTION_MARKER)
        messagePersistenceUseCase.persist(newEntity.id, MessageRole.ASSISTANT, summary)
        messagePersistenceUseCase.invalidateHistory(newEntity.id)
        sessionUseCase.touch(newEntity.id, messagePersistenceUseCase.nextTimestamp())
        dismissedHandoffCounts.remove(oldSessionId)
        // 只清理属于旧会话的提示，避免误清期间新产生的其它会话提醒。
        if (closePromptOnSuccess && _handoffPrompt.value?.sessionId == oldSessionId) {
            _handoffPrompt.value = null
        }
        selectSession(newEntity.id)
        true
    }.getOrElse {
        FileLogger.w(TAG, "交接开新会话失败", it)
        false
    }

    /** 接手会话标题：本地化前缀，避免重复交接叠加前缀。 */
    private fun handoffTitle(original: String): String {
        val prefix = context.getString(R.string.session_handoff_title_prefix)
        return if (original.startsWith(prefix)) original else prefix + original
    }

    /** 确认交接：生成接手摘要并新建「（接手）旧标题」会话继续；失败时恢复提示卡。 */
    fun confirmHandoff() {
        val prompt = _handoffPrompt.value ?: return
        if (prompt.generating || prompt.preparingCopy) return
        _handoffPrompt.value = prompt.copy(generating = true)
        viewModelScope.launch {
            val ok = handoffToNewSession(prompt.sessionId, closePromptOnSuccess = true)
            if (!ok) {
                val current = _handoffPrompt.value
                if (current?.sessionId == prompt.sessionId) {
                    _handoffPrompt.value = current.copy(generating = false)
                }
            }
        }
    }

    /** 暂停态：把接手摘要复制到剪贴板，作为「一键可复制」的交接内容。 */
    fun copyHandoffContent() {
        val prompt = _handoffPrompt.value ?: return
        if (prompt.generating || prompt.preparingCopy) return
        _handoffPrompt.value = prompt.copy(preparingCopy = true)
        viewModelScope.launch {
            val summary = runCatching { agentWorkflow.buildHandoffSummary(prompt.sessionId) }.getOrNull()
            val current = _handoffPrompt.value
            if (current?.sessionId == prompt.sessionId) {
                _handoffPrompt.value = current.copy(preparingCopy = false)
            }
            if (summary.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    context, context.getString(R.string.handoff_copy_failed), android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AiCode handoff", summary))
            android.widget.Toast.makeText(
                context, context.getString(R.string.handoff_copy_done), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 暂不交接：软提醒每个会话最多两次，两次后不再打扰；硬阈值暂停态或生成中不可暂不。 */
    fun dismissHandoff() {
        val prompt = _handoffPrompt.value ?: return
        if (prompt.paused || prompt.generating || prompt.preparingCopy) return
        dismissedHandoffCounts[prompt.sessionId] = (dismissedHandoffCounts[prompt.sessionId] ?: 0) + 1
        _handoffPrompt.value = null
    }

    /** 用户明确选择在已暂停的会话里继续（自行承担上下文超限风险）。 */
    fun continueAfterPause() {
        val prompt = _handoffPrompt.value ?: return
        if (!prompt.paused || prompt.generating || prompt.preparingCopy) return
        pausedHandoffSessions.remove(prompt.sessionId)
        _handoffPrompt.value = null
    }

    private val _teamName = MutableStateFlow<String?>(null)

    /** 当前会话召唤的编队名；null 表示未使用编队。 */
    val teamName: StateFlow<String?> = _teamName.asStateFlow()

    private val floatBallSendBridge: (String) -> Boolean = { text ->
        text.isNotBlank() && enqueueAgentRequest(text.trim())
    }

    private val floatBallOpenAppBridge: () -> Unit = {
        runCatching {
            val intent = android.content.Intent(context, com.aicode.MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    init {
        // 跟随会话切换/冷启动刷新当前编队（单一同步点）
        viewModelScope.launch {
            _currentSessionId.collect { id ->
                _teamName.value = if (id.isNullOrBlank()) null else sessionUseCase.getSessionById(id)?.teamName
            }
        }
        // 悬浮球命令桥：面板「发送 / 回到 App」经此进入主会话流程（享受队列/暂停拦截等既有保护）。
        // 保留实例引用：onCleared 时仅在自己仍持有桥时才清空，避免误清新 ViewModel 的注册。
        FloatBallCommandCenter.send = floatBallSendBridge
        FloatBallCommandCenter.openApp = floatBallOpenAppBridge
    }

    override fun onCleared() {
        // 退出 App 后悬浮球面板仍可能被点按：清掉指向本 VM 的桥，面板侧据此提示「请先打开 App」。
        if (FloatBallCommandCenter.send === floatBallSendBridge) FloatBallCommandCenter.send = null
        if (FloatBallCommandCenter.openApp === floatBallOpenAppBridge) FloatBallCommandCenter.openApp = null
        super.onCleared()
    }

    /** 更新当前会话的编队（null 清除）。 */
    fun setSessionTeam(name: String?) = viewModelScope.launch {
        val sid = _currentSessionId.value
        if (sid.isNullOrBlank()) return@launch
        sessionUseCase.updateTeamName(sid, name)
        _teamName.value = name
    }

    /** 编队定义列表（供选择器展示；实时扫描，用户改动文件后立即可见）。 */
    fun listTeams(): List<TeamDefinitionEntry> = teamRepository.listAll()

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        // 取消前先抓快照：cancel 后内存态即被清空，未完成工具/流式文本需落库收尾
        // （与 stopAgentSession 同语义），否则同进程内切回该会话仍显示「执行中」。
        val activeIds = sessionJobs.filterValues { it.isActive }.keys.toList()
        val stoppedStates = activeIds.mapNotNull { snapshotStoppedState(it) }
        // 子代理并发槽位兜底回收：取消路径不会发完成事件，不回收会让槽位虚高（task 误报已达上限）。
        activeIds.forEach { subAgentEventBus.release(it) }
        // cancel 在 Main.immediate 上可能同步跑完旧 job 的 finally 并经 flush/队列路径注册接替 job；
        // 迭代两轮把它们一并取消，避免 clear 后出现无人持引用的游离任务。
        repeat(2) { sessionJobs.values.toList().forEach { it.cancel() } }
        sessionJobs.clear()
        agentNotificationCenter.clearAll()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _reasoningTimings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
        stoppedStates.forEach { state -> viewModelScope.launch { persistStoppedContent(state) } }
    }

    /**
     * 主动打断当前会话正在运行的 agent：取消协程（会一并取消挂起的网络请求与容器命令进程），
     * 并把「执行中」的工具占位行收尾为「已停止」，避免悬挂的 spinner 与孤儿记录。
     */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * 停止指定会话的 AI 任务（子代理停止/用户手动停止共用）。
     * 取消 job 并把未完成的流式内容落库为「已停止」；队列下一条照常执行。
     */
    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        // 用户在界面上手动停止运行中的子代理：交回并发槽位并告知父代理，否则槽位泄漏到进程重启，
        // 且父代理会一直等一条永不到达的完成通知。TaskTool 的 stop/del 已在 emit(STOPPED) 时释放过，
        // 那条路径下 release 返回 false，不会重复通知。
        if (subAgentEventBus.release(sessionId)) {
            viewModelScope.launch {
                val sub = sessionUseCase.getSessionById(sessionId)
                val parentId = sub?.parentId
                if (parentId != null) {
                    notifyParentSubAgentFinished(
                        parentSessionId = parentId,
                        subSessionId = sessionId,
                        title = sub.title,
                        outcome = NotificationOutcome.STOPPED,
                        detail = "用户在界面上手动停止了这个子代理，任务未完成。"
                    )
                }
            }
        }
        val stoppedState = snapshotStoppedState(sessionId)
        val pendingNotifs = agentNotificationCenter.pendingCount(sessionId)
        FileLogger.d(TAG, "stopAgent: sid=$sessionId runningTools=${stoppedState?.runningTools?.size ?: 0} pendingPerm=${stoppedState?.pendingPermission?.id} pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}")
        // cancel() 在 Dispatchers.Main.immediate 上可能立即恢复挂起协程
        // （如 awaitApproval 的 CompletableDeferred.await），旧 job 的 finally →
        // flushPendingNotifications 在 cancel() 调用栈内同步执行并可能启动新 job。
        // 不预先清除待送通知——它们应由 finally 正常 flush 给新 job 处理。
        job.cancel()
        // cancel 可能已同步执行完 finally（flush 启动了新 job 并注册到 sessionJobs），
        // 此时不能再覆盖新 job 的状态；仅当无新 job 接管时才做清理。
        if (sessionJobs[sessionId]?.isActive != true) {
            agentNotificationCenter.clear(sessionId)
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        viewModelScope.launch {
            if (stoppedState != null) persistStoppedContent(stoppedState)
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            // 点「停止」= 跳过当前轮，立即执行队列下一条
            processNextInQueue(sessionId)
        }
    }

    /**
     * 停止收尾快照（须在 cancel 前抓取）：cancel 后内存态即被清空，未完成工具/流式文本/
     * 待授权调用需用快照落库收尾；无任何未完成状态时返回 null。
     */
    private fun snapshotStoppedState(sessionId: String): StoppedSessionState? {
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val streamingText = _streamingTexts.value[sessionId]
        val streamingReasoning = _streamingReasonings.value[sessionId]
        val pendingPermission = toolPermissionManager.pendingForSession(sessionId)
        if (runningTools.isEmpty() && streamingText.isNullOrEmpty() &&
            streamingReasoning.isNullOrEmpty() && pendingPermission == null
        ) {
            return null
        }
        return StoppedSessionState(sessionId, runningTools, streamingText, streamingReasoning, pendingPermission)
    }

    /** 把被停止会话的未完成工具/流式文本/待授权调用落库为终态（UI 状态清理由调用方负责）。 */
    private suspend fun persistStoppedContent(state: StoppedSessionState) {
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        if (state.runningTools.isNotEmpty()) {
            // 并行执行被中止：所有未完成的工具都落库为「已停止」
            state.runningTools.forEach { running ->
                val partial = running.text.trimEnd()
                val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                messagePersistenceUseCase.persist(
                    sessionId = state.sessionId,
                    role = MessageRole.TOOL,
                    content = content,
                    id = running.messageId,
                    toolCallId = running.messageId.removePrefix("tool_"),
                    toolName = running.toolName.ifBlank { null },
                    toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                    isError = true
                )
                toolArgsByMsgId.remove(running.messageId)
            }
        } else if (!state.streamingText.isNullOrEmpty() || !state.streamingReasoning.isNullOrEmpty()) {
            val partial = (state.streamingText ?: "").trimEnd()
            val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
            val reasoning = state.streamingReasoning?.takeIf { it.hasVisibleContent() }
            messagePersistenceUseCase.persist(
                sessionId = state.sessionId,
                role = MessageRole.ASSISTANT,
                content = content,
                reasoning = reasoning
            )
        }
        // 授权弹窗挂起中的工具调用：awaitApproval 挂起期间 _runningTools 为空
        // （ToolCallStarted 在授权通过后才发出），但 AssistantText 已落库了带
        // tool_call 声明的 assistant 消息。不补结果会导致该 tool_call 成为
        // 孤立记录，被 buildHistory 的 validIds 交集过滤掉，AI 不知道自己曾调用过。
        if (state.pendingPermission != null) {
            val msgId = "tool_${state.pendingPermission.id}"
            messagePersistenceUseCase.persist(
                sessionId = state.sessionId,
                role = MessageRole.TOOL,
                content = stoppedText,
                id = msgId,
                toolCallId = state.pendingPermission.id,
                toolName = state.pendingPermission.toolName,
                isError = true
            )
        }
    }

    private data class StoppedSessionState(
        val sessionId: String,
        val runningTools: List<RunningToolOutput>,
        val streamingText: String?,
        val streamingReasoning: String?,
        val pendingPermission: PendingToolPermission?
    )

    // region 会话管理

    /** 新建会话；若当前会话还是空的则直接复用，避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(_currentWorkspace.value)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
            // 工作期间切换：把新模式作为系统事件投递给运行中的 agent，本轮即时生效（见 StatefulAgentWorkflow）。
            // 空闲时无需投递——下一轮开轮会按新模式注入一次提醒。
            if (sessionJobs[sid]?.isActive == true) {
                agentNotificationCenter.enqueue(
                    sid,
                    PendingNotification(
                        kind = AgentNotificationKind.MODE_CHANGE,
                        sourceId = "user",
                        title = mode.name,
                        outcome = NotificationOutcome.COMPLETED,
                        newMode = mode
                    )
                )
            }
        }
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // 同步记忆到模型级默认档位，供后续新建会话沿用
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            _contextUsages.value = _contextUsages.value - sid
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 输入框 `@` 文件引用菜单状态。 */
    private val _fileMentionState = MutableStateFlow<FileMentionState>(FileMentionState.Idle)
    val fileMentionState: StateFlow<FileMentionState> = _fileMentionState.asStateFlow()

    private var fileMentionJob: Job? = null

    /**
     * 打开 `@` 菜单：当前无清单时拉取一次；一次 `@` 会话内（[dismissFileMention] 前）不重复拉取。
     */
    fun requestFileMention() {
        val current = _fileMentionState.value
        if (current is FileMentionState.Ready || current is FileMentionState.Loading) return
        fileMentionJob?.cancel()
        _fileMentionState.value = FileMentionState.Loading
        fileMentionJob = viewModelScope.launch {
            val result = fileMentionRepository.loadFiles(_currentWorkspace.value)
            _fileMentionState.value = when (result) {
                is FileMentionRepository.LoadResult.Success -> FileMentionState.Ready(result.files)
                FileMentionRepository.LoadResult.Unavailable -> FileMentionState.Unavailable
                is FileMentionRepository.LoadResult.Failed -> FileMentionState.Error(result.message)
            }
        }
    }

    /** 关闭 `@` 菜单：丢弃清单，下次打开重新拉取，保证拿到最新文件列表。 */
    fun dismissFileMention() {
        fileMentionJob?.cancel()
        fileMentionJob = null
        _fileMentionState.value = FileMentionState.Idle
    }

    /** 在已就绪的清单里匹配当前查询；未就绪返回空。 */
    fun matchFileMentions(query: String): List<String> {
        val files = (_fileMentionState.value as? FileMentionState.Ready)?.files ?: return emptyList()
        return fileMentionRepository.match(files, query)
    }

    /** 暴露给 UI：输入框 `/` 菜单展示的命令列表（内置命令 + 已启用技能）。 */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** 重新扫描技能并刷新命令菜单；进入 `/` 菜单时调用，反映技能的增删改。 */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init —— 触发 agent 回合，分析代码库并生成/改进项目规则文件。 */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = prompt,
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** 技能触发 —— 把技能正文作为本轮指令执行。 */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = SlashCommandRegistry.buildSkillPrompt(skill, args),
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = loadUsageSummary(todayStart)
            val allTime = loadUsageSummary(0L)
            val table = buildString {
                appendLine("| 项目 | 今日 | 累计 |")
                appendLine("|---|---|---|")
                appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
                appendLine("| 输入 tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
                appendLine("| 输出 tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
                appendLine("| 缓存命中 tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
                appendLine("| 预估费用 | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
            }
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
        }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 汇总自 [start] 起的调用次数、token 与预估费用；费用按「渠道 + 模型」分组逐组估算，才取得到自定义单价。 */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> {
                            setCompacting(sid, false)
                            _contextUsages.value = _contextUsages.value - sid
                        }
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sid, false)
                            // 与自动压缩一致：落库无配对的 TOOL 消息渲染失败卡片，buildHistory 回放丢弃。
                            messagePersistenceUseCase.persist(
                                sessionId = sid,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                // 压缩成功时 marker 分隔线 + 摘要卡片已提供反馈，不再落库重复的提示气泡；
                // 仅「无需压缩」（head 为空/无可压缩内容）时给出一条提示。
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "手动压缩失败: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // 压缩是异步流程，结束后接续队列中排队的下一条
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    /** 用户批准计划，唤醒 workflow 继续在 BUILD 模式执行。 */
    fun approvePlanAndBuild() {
        planApprovalManager.resolve(PlanApprovalChoice.APPROVE)
    }

    /** 用户选择继续反馈，唤醒 workflow 回滚到 PLAN 模式。 */
    fun refinePlan() {
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
    }

    /** 预算面板：用户选择后回传决策，唤醒 workflow 继续执行或停止本轮任务。 */
    fun resolveBudgetApproval(id: String, choice: BudgetApprovalChoice) {
        budgetApprovalManager.resolve(id, choice)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                subAgentEventBus.release(id)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            setStreamingReasoning(sid, null)
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            _intentDrafts.value = _intentDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        // 删掉运行中的子代理时先取它的父会话与标题（删库后就取不到了），稍后告知父代理。
        // 删的是父会话时无需通知（父会话本身也没了），但级联删掉的子会话仍要交回并发槽位。
        val deletedSession = sessionUseCase.getSessionById(id)
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            setStreamingReasoning(sid, null)
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            _intentDrafts.value = _intentDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        stoppedSubAgent?.let { sub ->
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。"
            )
        }
    }

    // Checkpoint Rewind 选中的目标消息 id
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    /**
     * 最近一次「回滚代码」因文件已被其他会话/用户改过而跳过的文件清单（逻辑路径）。
     *
     * 用一次性状态而不是事件流：UI 消费后调用 [clearRewindConflicts] 置空，避免重组时重复提示。
     */
    private val _rewindConflicts = MutableStateFlow<List<String>>(emptyList())
    val rewindConflicts: StateFlow<List<String>> = _rewindConflicts.asStateFlow()

    /** UI 已展示过冲突提示，清空以免重复弹。 */
    fun clearRewindConflicts() {
        _rewindConflicts.value = emptyList()
    }

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. 停止当前正在运行的 Agent 任务及后续排队
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)

        // 2. 重置会话运行、流式与检查点状态
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        _contextUsages.value = _contextUsages.value - sessionId
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                    _rewindConflicts.value = result.skipped
                }
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                    _rewindConflicts.value = result.skipped
                }
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** 置顶/取消置顶会话。置顶后排在列表最前（置顶分组），不改 updatedAt。 */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * 定时任务运行（Worker 离屏调用）：在工作区新建预设标题与运行模式的会话，返回会话 id。
     * 标题带「⏰」前缀与执行时间，与普通会话区分（见 scheduled-ai-task-plan §2.4）。
     */
    suspend fun createScheduledRunSession(workspacePath: String, title: String, runMode: AgentMode): String {
        val s = createSession(workspacePath).copy(title = title, mode = runMode.name)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /** 会话最近一轮是否成功完成（定时任务 Worker 读取运行结果用）。 */
    fun scheduledRunSucceeded(sessionId: String): Boolean = sessionId in _completedSessions.value

    // region 定时任务（会话侧创建与管理）

    /**
     * 创建一条 AI 定时任务（轻量型入口）。[schedule] 为 null 表示待排期（设置页可补配）。
     * 返回创建的任务，供 UI 弹提示（含排期描述）。
     */
    fun createScheduledAiTask(
        name: String,
        prompt: String,
        runMode: AgentMode,
        categories: Set<com.aicode.feature.schedule.domain.ScheduledAuthCategory>,
        schedule: com.aicode.feature.schedule.domain.TaskSchedule?
    ): com.aicode.feature.schedule.domain.ScheduledTask {
        val task = com.aicode.feature.schedule.domain.ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { sessionUseCase.deriveTitle(prompt) },
            type = com.aicode.feature.schedule.domain.ScheduledTaskType.AI_TASK,
            prompt = prompt.trim(),
            runMode = runMode.name,
            authCategories = categories.toList(),
            workspacePath = _currentWorkspace.value,
            schedule = schedule ?: com.aicode.feature.schedule.domain.TaskSchedule(
                mode = com.aicode.feature.schedule.domain.ScheduleMode.UNSET
            )
        )
        viewModelScope.launch {
            scheduledTaskRepository.upsert(task)
            com.aicode.feature.schedule.domain.TaskScheduling.schedule(context, task)
        }
        return task
    }

    /**
     * 把当前审批面板中的计划排期为定时 Plan（「定时执行…」确认后调用）：
     * 存档计划正文并以 REFINE 解除本轮的审查挂起（会话留在 PLAN 模式，不立即施工）。
     */
    fun scheduleCurrentPlan(
        name: String,
        planText: String,
        categories: Set<com.aicode.feature.schedule.domain.ScheduledAuthCategory>,
        schedule: com.aicode.feature.schedule.domain.TaskSchedule?
    ): com.aicode.feature.schedule.domain.ScheduledTask? {
        // 计划正文缺失（审批状态已变化等边界）：不创建任务，静默跳过
        if (planText.isBlank()) return null
        val task = com.aicode.feature.schedule.domain.ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { sessionUseCase.deriveTitle(planText) },
            type = com.aicode.feature.schedule.domain.ScheduledTaskType.AI_PLAN,
            planText = planText,
            sourceSessionId = _currentSessionId.value,
            runMode = AgentMode.BUILD.name,
            authCategories = categories.toList(),
            workspacePath = _currentWorkspace.value,
            schedule = schedule ?: com.aicode.feature.schedule.domain.TaskSchedule(
                mode = com.aicode.feature.schedule.domain.ScheduleMode.UNSET
            )
        )
        viewModelScope.launch {
            scheduledTaskRepository.upsert(task)
            com.aicode.feature.schedule.domain.TaskScheduling.schedule(context, task)
        }
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
        return task
    }

    /**
     * 「定时 Plan」面板的「准备计划」：切到 PLAN 模式（复用空会话或新建），
     * 把目标预填入输入草稿，由用户聊完计划后在审批面板排期。
     */
    fun startPlanDraftingSession(goal: String) {
        viewModelScope.launch {
            if (_currentWorkspace.value.isBlank()) return@launch
            val curId = _currentSessionId.value
            val targetId = if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
                sessionUseCase.updateMode(curId, AgentMode.PLAN.name)
                curId
            } else {
                val sid = createAndUpsertSession(_currentWorkspace.value)
                sessionUseCase.updateMode(sid, AgentMode.PLAN.name)
                _currentSessionId.value = sid
                sid
            }
            _inputDrafts.value = _inputDrafts.value + (targetId to goal)
        }
    }

    // endregion

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        )
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新消息失败", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg != null && msg.role == MessageRole.USER.name) {
                agentMessageDao.deleteMessagesAfterTimestamp(msg.sessionId, msg.timestamp)
            }
            agentMessageDao.deleteMessageById(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除消息失败", e)
        }
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }
}
