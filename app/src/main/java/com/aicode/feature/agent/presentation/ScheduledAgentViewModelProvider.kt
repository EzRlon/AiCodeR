package com.aicode.feature.agent.presentation

import android.content.Context
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mention.FileMentionRepository
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.team.TeamRepository
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.workflow.BudgetApprovalManager
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.VoiceSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无 UI 场景（定时任务 Worker）装配 [AIAgentViewModel] 实例的桥。
 *
 * 常规路径由 Hilt 的 ViewModelComponent 提供 VM；后台 Worker 没有 Activity / ViewModelStoreOwner，
 * 这里把 VM 的全部构造依赖从 SingletonComponent 取出，经 dagger 生成的 [AIAgentViewModel_Factory]
 * 组装——装配顺序由生成的工厂签名约束，VM 构造变化时此处会编译报错，保证与 UI 路径完全同源。
 *
 * 调用方负责实例的生命周期：用 [androidx.lifecycle.ViewModelStore] 托管实例，
 * 结束后 `store.clear()` 触发 `onCleared()`（viewModelScope 取消、保活释放）。
 */
@Singleton
class ScheduledAgentViewModelProvider @Inject constructor(
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
    private val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val voskModelManager: com.aicode.feature.agent.domain.voice.VoskModelManager,
    @param:ApplicationContext private val context: Context
) {

    fun create(): AIAgentViewModel = AIAgentViewModel_Factory.newInstance(
        agentWorkflow,
        toolRegistry,
        agentMessageDao,
        chatSessionDao,
        llmCallRecordDao,
        modelCostCalculator,
        aiProviderRepository,
        defaultModelSettingsRepository,
        modelReasoningEffortRepository,
        toolPermissionManager,
        askUserQuestionManager,
        containerEngine,
        sessionUseCase,
        messagePersistenceUseCase,
        planApprovalManager,
        budgetApprovalManager,
        terminalSessionManager,
        slashCommandRegistry,
        fileMentionRepository,
        checkpointManager,
        checkpointDao,
        backupManager,
        mcpManager,
        agentSoundSettings,
        generalSettingsRepository,
        keepaliveSettings,
        subAgentEventBus,
        agentNotificationCenter,
        agentDefinitionRepository,
        teamRepository,
        todoItemDao,
        fileAccess,
        fileChangeHub,
        scheduledTaskRepository,
        voiceSettingsRepository,
        voskModelManager,
        context
    )
}
