package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionAuditRecorder
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionScope
import com.aicode.feature.agent.domain.permission.ToolPermissionTier
import com.aicode.feature.agent.domain.tool.ToolApprovalOutcome
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIProviderFactory
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.hooks.domain.HookRunner
import com.aicode.feature.hooks.domain.HooksConfig
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.VerificationModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.agent.domain.provider.ReasoningLearning
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.provider.reasoningAuditOf
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val aiProviderFactory: AIProviderFactory,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val budgetApprovalManager: BudgetApprovalManager,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val verificationModelSettingsRepository: VerificationModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val todoItemDao: TodoItemDao,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val hookRunner: HookRunner,
    private val fileAccess: FileAccessProvider,
    private val auditRecorder: PermissionAuditRecorder
) : AgentWorkflow {

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** 输入框「润色」的提示词文件（纯改写，不执行请求）。 */
        const val TEXT_POLISHER_FILE = "agent/text-polisher.md"
        /** 润色输入的长度上限：输入框草稿不该有这么长，超了只取前段，避免无谓的大请求。 */
        const val TEXT_POLISH_MAX_INPUT_CHARS = 8_000
        /** 收尾独立验收的提示词文件（验收模型专用，见 runCompletionVerification）。 */
        const val VERIFIER_FILE = "agent/verifier.md"
        /** 验收报告落库前的字符上限（模型输出异常超长时截断，防撑爆卡片）。 */
        const val VERIFICATION_REPORT_MAX_CHARS = 4_000
        // 验收输入的预算常量（对话/证据条数与字符上限）与组装逻辑一起搬到了
        // VerificationContextBuilder —— 它们是纯函数，放那儿能单测。
        /** 快照交接的原文尾窗上限（字符）：控制在 ~1 万 token 内，接手会话首屏可承受。 */
        const val INSTANT_HANDOFF_TAIL_LIMIT = 24_000
        /** 模式提醒提示词：复用 prompts 目录文件（用户可自定义覆盖），切换时随消息注入而非进 system。 */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_AUTO_FILE = "agent/auto-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** 模型直出图片落盘目录（与 GenerateImageTool 保持一致）。 */
        const val GENERATED_IMAGE_DIR = "~/.aicode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
        /** 微压缩：发送副本里保留最近 N 条工具结果全文，更早的长结果替换为占位符。 */
        const val MICRO_COMPACT_KEEP_TOOL_RESULTS = 5
        /** 短于该长度的工具结果不值得投影（占位符本身也占 token 且抹掉有用细节）。 */
        const val MICRO_COMPACT_MIN_CHARS = 800
        /** 工作集恢复：超过该字节数的文件不读入（预算只需要头几 K，整份载入大文件是内存尖峰）。 */
        const val WORKING_SET_FILE_MAX_BYTES = 1_000_000L
        // 有界捕获：结果可能是紧凑单行 JSON（无空白分隔），\S+ 会吞到串尾，必须排除 ] 与引号
        val TOOL_OUTPUT_ARCHIVE_PATH = Regex("完整内容已保存到 ([^\\s\\]\"]+)")

        /** 微压缩保留的语义锚点：行数上限与单行长度上限（成本可控，不把占位符变成另一份全文）。 */
        const val ANCHOR_MAX_LINES = 6
        const val ANCHOR_LINE_MAX_CHARS = 200

        /** 锚点行特征：退出码、错误、构建结论、断言失败、堆栈头。 */
        val ANCHOR_PATTERNS = listOf(
            Regex("\\bexit(?:\\s*code)?\\s*[:=]?\\s*\\d+", RegexOption.IGNORE_CASE),
            Regex("\\bBUILD (?:SUCCESSFUL|FAILED)", RegexOption.IGNORE_CASE),
            Regex("^e: "),
            Regex("\\berror\\b[:\\s]", RegexOption.IGNORE_CASE),
            Regex("\\b(?:FAILED|FAILURE|FATAL)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(?:Exception|Error):"),
            Regex("\\b\\d+ (?:failed|passed)\\b", RegexOption.IGNORE_CASE)
        )
        /** 压缩失败熔断：连续失败次数达到该值后暂停尝试。 */
        const val COMPACTION_FAILURES_TO_TRIP = 3
        /** 工具耗时指标（B4）：低于该耗时的成功调用不打日志（一次回合几十次调用，防刷成流水账）。 */
        const val TOOL_DURATION_LOG_THRESHOLD_MS = 1_000L
        /** 同会话连续溢出恢复上限：达到后直接报错并提示快照交接，不再反复压缩空转（用户反馈的嵌套陷阱）。 */
        const val MAX_OVERFLOW_RECOVERIES = 3
        /** 熔断后暂停的 LLM 调用轮数，期满恢复尝试。 */
        const val COMPACTION_PAUSE_ROUNDS = 10
        /** 压缩后工作集恢复：至多重读的最近编辑文件数与总字符预算（头部截断，均分给剩余文件）。 */
        const val WORKING_SET_MAX_FILES = 5
        const val WORKING_SET_TOTAL_MAX_CHARS = 40_000
        /** 工作集恢复消息的标记与说明（喙给模型的系统性内容，同 CONTEXT_COMPACTION_MARKER 风格）。 */
        const val WORKING_SET_RESTORE_MARKER = "[Working set restored after context compaction]"
        const val WORKING_SET_RESTORE_INTRO =
            "【压缩后工作集恢复】上下文刚完成压缩。以下是压缩前最近编辑文件的当前内容快照，用于立即恢复对在改文件的工作记忆；超长部分已按预算截断。"
    }

    /**
     * 每会话「上次已注入模式提醒的模式」。模式提醒只在变化时注入，避免每轮把同一段文本
     * 重复拼到最新用户消息上——那样下一轮重建历史时该位置（Anthropic 缓存断点）内容不一致，
     * 会白白丢掉一段前缀缓存。进程内缓存，重启后首轮再注入一次（无害）。
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /**
     * 每会话连续溢出恢复次数（跨用户轮累计，正常完成一轮清零）：
     * 大会话在小窗口模型上会出现「每轮都溢出→每轮都全量压缩」的循环（用户感知为反复压缩/嵌套陷阱），
     * 据此逐级升级：第 2 次起丢工作集回注，达到上限直接报错并指向 /handoff 快照交接。
     */
    private val overflowRecoveryStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 错误类型码（如服务端 stop_reason），供 UI 换成本地化文案；null 表示直接展示 [error]。 */
        val errorCode: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse, val messageId: String) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED"
    )

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return aiProviderFactory.create(config, sessionId)
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // 绑定的模型可能已被移出该 provider 的模型列表，此时绑定失效、继续往下回退默认模型
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = aiProviderFactory.create(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        // 摘要仍用专用压缩模型（尊重用户选择），但保留最近消息的窗口预算按主聊天模型算，
        // 与自动压缩一致——否则小窗口的压缩模型会让手动压缩保留的最近上下文偏少。
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, windowProvider = provider, onEvent = onEvent)
        if (result.compacted) {
            // 手动压缩没有在跑的 workflow 内存态可注入，下一回合历史从 DB 重建——
            // 恢复消息必须落库且可回放（不排除出历史）才能在下回合生效。
            buildWorkingSetRestoreMessage(sessionId)?.let { restore ->
                val metadata = modelMetadataService.resolve(provider.providerId, when (provider) {
                    is AnthropicAdapter -> ProviderType.ANTHROPIC
                    is GeminiAdapter -> ProviderType.GEMINI
                    else -> ProviderType.OPENAI
                }, provider.model)
                val budget = ModelContextPolicy.effectiveInputBudget(metadata)
                val used = ContextTokenEstimator.estimate("", result.messages, emptyList())
                if (used + ContextTokenEstimator.estimate(restore) < budget) {
                    persistWorkingSetRestore(sessionId, restore, excludeFromHistory = false)
                } else {
                    FileLogger.w(TAG, "手动压缩后工作集恢复超输入预算，跳过注入（used=$used budget=$budget）")
                }
            }
        }
        return result.compacted
    }

    /**
     * 工作集恢复注入前的预算护栏：把恢复消息计入后重估，达到输入预算即放弃注入——
     * 「为救上下文而注入的内容」不能反过来把请求推过预算杀死本轮（小窗口模型尤其如此）。
     * 调用点均在压缩成功后、baseline 清零处，裸估算与 inputBudget 直接比较即等价。
     */
    private suspend fun restoreFitsBudget(
        systemPrompt: String,
        baseMessages: List<AgentMessage>,
        restore: AgentMessage,
        tools: List<AgentTool>,
        inputBudget: Int
    ): Boolean {
        val estimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ContextTokenEstimator.estimate(systemPrompt, baseMessages + restore, tools)
        }
        if (estimate < inputBudget) return true
        FileLogger.w(TAG, "工作集恢复超输入预算，放弃注入（estimate=$estimate budget=$inputBudget）")
        return false
    }

    /**
     * 压缩后工作集恢复：把最近编辑的至多 [WORKING_SET_MAX_FILES] 个文件的当前内容重读，
     * 组装为一条注入消息，定点补偿压缩摘要对在改文件细节的丢失。
     * 总字符预算按剩余文件均分、单文件从头部截断；任一文件读取失败即跳过；
     * 查不到可恢复文件时返回 null。
     */
    private suspend fun buildWorkingSetRestoreMessage(sessionId: String): AgentMessage.UserMessage? {
        val paths = runCatching { checkpointManager.recentEditedFilePaths(sessionId, WORKING_SET_MAX_FILES) }
            .onFailure { FileLogger.w(TAG, "查询最近编辑文件失败，跳过工作集恢复", it) }
            .getOrDefault(emptyList())
        if (paths.isEmpty()) return null
        val filesText = withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            var remaining = WORKING_SET_TOTAL_MAX_CHARS
            for ((index, path) in paths.withIndex()) {
                val perFileBudget = remaining / (paths.size - index)
                if (perFileBudget <= 0) break
                val content = runCatching {
                    if (!fileAccess.exists(path)) null
                    else {
                        // 先预判体积：readFile 是全量读入，大文件为取预算头几 K 不值得整份载入
                        val size = fileAccess.fileSize(path)
                        if (size > WORKING_SET_FILE_MAX_BYTES) {
                            FileLogger.w(TAG, "工作集恢复跳过大文件: $path ($size bytes)")
                            null
                        } else {
                            val acc = StringBuilder()
                            for (line in fileAccess.readLines(path)) {
                                if (acc.length + line.length + 1 > perFileBudget) break
                                acc.appendLine(line)
                            }
                            acc.toString().ifBlank { null }
                        }
                    }
                }.onFailure { FileLogger.w(TAG, "工作集恢复读取文件失败，跳过: $path", it) }.getOrNull()
                if (content.isNullOrBlank()) continue
                sb.append("\n<file path=\"").append(path).append("\">\n").append(content)
                if (content.length == perFileBudget) sb.append("\n…[已按预算截断]")
                sb.append("\n</file>\n")
                remaining -= content.length
            }
            sb.toString()
        }
        if (filesText.isBlank()) return null
        // id 必须稳定且与落库行一致：后续压缩若把该消息划入 head，会校验 id 已持久化。
        return AgentMessage.UserMessage(
            id = UUID.randomUUID().toString(),
            content = "$WORKING_SET_RESTORE_MARKER\n$WORKING_SET_RESTORE_INTRO\n$filesText"
        )
    }

    /**
     * 落库工作集恢复消息。excludeFromHistory=true（自动压缩路径）时仅作归档：
     * 满足后续压缩的持久化 ID 校验，但 buildHistory 不回放，下回合上下文不再包含；
     * false（手动 /compress）时可回放，恢复消息随下回合重建的历史生效。
     * 内容带 BACKGROUND_NOTIFICATION_PREFIX，UI 渲染为轻量提示条而非用户气泡。
     */
    private suspend fun persistWorkingSetRestore(
        sessionId: String?,
        restore: AgentMessage.UserMessage,
        excludeFromHistory: Boolean
    ) {
        if (sessionId == null) return
        runCatching {
            messagePersistenceUseCase.persist(
                sessionId,
                com.aicode.feature.agent.presentation.MessageRole.USER,
                "${com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX}\n${restore.content}",
                id = restore.id,
                isContextExcluded = excludeFromHistory
            )
            messagePersistenceUseCase.invalidateHistory(sessionId)
        }.onFailure { FileLogger.w(TAG, "工作集恢复消息落库失败", it) }
    }

    override suspend fun handoffInputBudget(sessionId: String): Int? = runCatching {
        val provider = getEffectiveProvider(sessionId)
        contextCompactor.effectiveInputBudget(provider)
    }.getOrNull()

    override suspend fun buildHandoffSummary(sessionId: String): String? = runCatching {
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__handoff__")
        val provider = resolveCompactionFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        contextCompactor.buildHandoffSummary(history, provider, sessionId = null)
    }.getOrNull()

    override suspend fun buildInstantHandoffDoc(sessionId: String): String? = runCatching {
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__handoff__")
        if (history.isEmpty()) return@runCatching null

        // 找最近一次压缩的结构化摘要：CONTEXT_COMPACTION_MARKER 用户消息后紧跟的助手消息，
        // 或旧格式带前缀的独立摘要消息（与 ContextCompactor.extractPreviousSummary 同一识别规则）
        var summaryEnd = -1
        var summaryText = ""
        for (index in history.indices.reversed()) {
            val current = history[index]
            val next = history.getOrNull(index + 1)
            if (current is AgentMessage.UserMessage && current.content == CONTEXT_COMPACTION_MARKER && next is AgentMessage.AssistantMessage) {
                summaryEnd = index + 1
                summaryText = next.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart()
                break
            }
            if (current is AgentMessage.AssistantMessage && current.content.startsWith(CONTEXT_SUMMARY_LEGACY_PREFIX)) {
                summaryEnd = index
                summaryText = current.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart()
                break
            }
        }

        // 摘要之后的全部原文；从未压缩过则取全部历史的投影，两者都从尾部截断到上限
        val tailStart = if (summaryEnd >= 0) summaryEnd + 1 else 0
        val tail = if (tailStart < history.size) {
            history.subList(tailStart, history.size).joinToString("\n\n") { CompactionText.project(it) }.trim()
        } else ""
        val tailCapped = if (tail.length > INSTANT_HANDOFF_TAIL_LIMIT) tail.takeLast(INSTANT_HANDOFF_TAIL_LIMIT) else tail
        if (summaryText.isBlank() && tailCapped.isBlank()) return@runCatching null

        buildString {
            append("<handoff>\n")
            append("你是接手者。以下材料来自上一会话在交接时点的原样快照（未经模型改写）。请通读后向用户简短确认接手，然后继续未完成的工作。\n\n")
            if (summaryText.isNotBlank()) {
                append("## 最近一次压缩后的结构化摘要（权威基线）\n")
                append(summaryText).append("\n\n")
            }
            if (tailCapped.isNotBlank()) {
                if (summaryText.isNotBlank()) {
                    append("## 摘要之后的最新进展（原文节选）\n")
                } else {
                    append("## 最近进展（原文节选，本会话从未压缩）\n")
                }
                append(tailCapped).append("\n\n")
            }
            append("## 交接说明\n")
            append("- 快照在交接时点原样截取，未做压缩改写；")
            append(if (summaryText.isNotBlank()) "含最近一次压缩摘要与其后的全部原文。" else "本会话从未压缩，仅含最近的原文窗口；如需更早细节请向用户索取。")
            append("\n</handoff>")
        }
    }.getOrNull()

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    id = action.messageId,
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images,
                    inputTokens = action.response.inputTokens
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // 拒答/上下文超限：正文可能为空，静默结束会让用户看到空白气泡以为卡死。
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isTruncated) {
                        val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                        newState = newState.copy(messages = newState.messages + continuation)
                        effects.add(AgentSideEffect.PersistUser(continuation))
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                    // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // 已批准未执行（已收到 ToolCallStarted）的工具需补发完成事件，
                        // 否则 UI 与落库消息会一直停留在「执行中」。
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // 策略/系统拒绝（如 PLAN 模式禁止执行）：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // 模式提醒仅在模式变化时随最新用户消息注入一次（不进 system，避免切换时 system 前缀变化打断缓存）。
        val modeReminder = takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    id = currentContext.inputMessageId,
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        val providerStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "provider.start", details = "operation=$providerStartedNs")
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        FileLogger.memoryCheckpoint(
            TAG,
            "provider.ready",
            elapsedMs = (System.nanoTime() - providerStartedNs) / 1_000_000,
            details = "operation=$providerStartedNs"
        )
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        // 提示词构建会读取技能/子代理/记忆等文件（远程模式经 SFTP），必须离开收集本工作流的线程（Main），
        // 否则远程模式下每次会话首次发消息都会在主线程做多次文件/网络往返。
        // 当前模型名与上下文窗口在此注入运行时上下文：会话内稳定（换模型才变），不打断 KV Cache。
        val promptStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "prompt.start", details = "operation=$promptStartedNs")
        val systemPrompt = withContext(Dispatchers.IO) {
            promptProvider.build(
                currentContext.copy(
                    modelName = aiProvider.model,
                    modelContextTokens = metadata.contextTokens,
                    // 本轮输入尚未进入 history，显式传入供世界书等按输入召回的来源使用。
                    currentUserRequest = userRequest
                )
            )
        }
        FileLogger.memoryCheckpoint(
            TAG,
            "prompt.ready",
            elapsedMs = (System.nanoTime() - promptStartedNs) / 1_000_000,
            details = "operation=$promptStartedNs promptChars=${systemPrompt.length}"
        )
        // 压缩失败熔断：连续失败未达阈值时容忍重试（可能是偶发故障），
        // 达到阈值后暂停 N 轮 LLM 调用不试压缩，避免反复白试烧 token；暂停期满自动恢复尝试。
        var compactionFailures = 0
        var compactionPauseRounds = 0
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        // 思考档位随模型走：每次请求刷新，适配器据此判断能否如实透传 max/xhigh 或需降级。
        aiProvider.reasoningLevels = metadata.reasoningEffortOptions
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        var baselineEstimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ContextTokenEstimator.estimate(systemPrompt,
                currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
        }
        var baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
            (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
        } else 0
        // 连续溢出恢复（跨轮升级，见 overflowRecoveryStreak 字段注释）：正常完成一轮即清零。
        val overflowSessionId = currentContext.sessionId
        fun overflowRecoveryStreakNow(): Int = overflowSessionId?.let { overflowRecoveryStreak[it] } ?: 0
        fun noteOverflowRecovery() {
            overflowSessionId?.let { overflowRecoveryStreak[it] = (overflowRecoveryStreak[it] ?: 0) + 1 }
        }
        fun clearOverflowStreak() {
            overflowSessionId?.let { overflowRecoveryStreak.remove(it) }
        }
        // 单次任务 token 预算：本轮（一次用户请求）内全部 LLM 调用的 input+output 累计，
        // 触达预算时挂起询问用户；放行后本轮不再提示（budgetExempted），避免反复打断。
        var taskTokensUsed = 0
        var budgetExempted = false
        // 预算超限且用户选择停止后置位：收尾跳过独立验收（任务是被叫停的，不是完成）。
        var stoppedByBudget = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.aicode.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        val providerInUse = aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (compactionPauseRounds > 0) {
                            compactionPauseRounds--
                        } else {
                            val estimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                            }
                            val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId,
                                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                                currentInputTokens = predictedInput) { event ->
                                if (event is AgentEvent.CompactionFailed) {
                                    compactionFailures++
                                    if (compactionFailures >= COMPACTION_FAILURES_TO_TRIP) {
                                        compactionFailures = 0
                                        compactionPauseRounds = COMPACTION_PAUSE_ROUNDS
                                    }
                                }
                                send(event)
                            }
                            compactedMessages = compaction.messages
                            if (compaction.compacted) {
                                // 压缩刚抹掉大部分历史：立即把最近编辑文件的当前内容注回本轮上下文。
                                // 落库为归档行（isContextExcluded）：保证后续压缩的持久化 ID 校验可过，
                                // 且不回放进后续回合（定点补偿只服务本轮继续执行）。
                                val restore = currentContext.sessionId?.let { buildWorkingSetRestoreMessage(it) }
                                    ?.takeIf { restoreFitsBudget(systemPrompt, compaction.messages, it, currentTools, inputBudget) }
                                if (restore != null) {
                                    state = state.copy(messages = compaction.messages + restore)
                                    compactedMessages = state.messages
                                    persistWorkingSetRestore(currentContext.sessionId, restore, excludeFromHistory = true)
                                } else {
                                    state = state.copy(messages = compaction.messages)
                                }
                                baselineUsage = 0
                                baselineEstimate = 0
                                compactionFailures = 0
                            }
                        }
                        // 微压缩：只投影发送副本（在全量压缩之后、估算之前），state/DB/摘要输入均保持原文。
                        compactedMessages = microCompactToolResults(compactedMessages)
                        val requestEstimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            ContextTokenEstimator.estimate(systemPrompt, compactedMessages, currentTools)
                        }
                        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
                        send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
                        if (predictedInput >= inputBudget) {
                            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                            continue
                        }

                        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var callError: String? = null
                        var callCompleted = false
                        val stream = StreamAccumulator(callStartElapsed) { send(it) }

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = sanitizeImagesForModel(compactedMessages, supportsVision)
                            // 清空上一轮的实发档位；适配器构造请求时会写入本轮实际发出的值，供调用记录落库。
                            providerInUse.reasoningWireUsed = null
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort)
                                .collect { stream.onChunk(it) }
                            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
                            stream.flush()
                            val aiResponse = stream.finalResponse ?: AIResponse(content = stream.text.toString())
                            // 模型直出图片（Gemini 图像模型）不随流式增量到达，整块在 Final 里：
                            // 先把 base64 落盘到 ~/.aicode/generated-images/ 并构造 UI 附件（只存路径不存 base64），
                            // 再随 LlmResponse 把带 path 的 images 交 reduce 挂上 AssistantMessage 供下一回放。
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) persistModelImages(aiResponse.images) else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // 自愈：本轮实际发出的档位被服务端接受，即可推翻历史「被拒」记录，
                            // 防止一次抽风 400 造成的降级长期粘住（见 ReasoningLearning.markUsed）。
                            ReasoningLearning.markUsed(providerInUse.providerId, providerInUse.model, providerInUse.reasoningWireUsed)
                            if (aiResponse.stopReason == "model_context_window_exceeded" && overflowRecoveryStreakNow() < MAX_OVERFLOW_RECOVERIES) {
                                noteOverflowRecovery()
                                // 连续第 2 次起跳过工作集回注：回注会把刚压缩完的上下文重新吹胖，
                                // 是「每轮都溢出→每轮都全量压缩」循环的主因。
                                val skipWorkingSetRestore = overflowRecoveryStreakNow() >= 2
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    val restore = if (skipWorkingSetRestore) null else currentContext.sessionId?.let { buildWorkingSetRestoreMessage(it) }
                                        ?.takeIf { restoreFitsBudget(systemPrompt, recovery.messages, it, currentTools, inputBudget) }
                                    val restored = if (restore != null) recovery.messages + restore else recovery.messages
                                    if (restore != null) {
                                        persistWorkingSetRestore(currentContext.sessionId, restore, excludeFromHistory = true)
                                    }
                                    state = state.copy(messages = restored)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(restored))
                                    continue
                                }
                            }
                            baselineEstimate = requestEstimate
                            baselineUsage = aiResponse.inputTokens
                            clearOverflowStreak()
                            send(AgentEvent.ContextUsage(
                                if (baselineUsage > 0) baselineUsage else requestEstimate, inputBudget, baselineUsage <= 0
                            ))
                            val responseMessageId = UUID.randomUUID().toString()
                            val responseWithReasoning = if (stream.reasoning.isNotEmpty()) {
                                aiResponse.copy(reasoning = stream.reasoning.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        stream.reasoning.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments,
                                        messageId = responseMessageId,
                                        persisted = persisted
                                    )
                                )
                                persisted.await()
                            }
                            // 单次任务 token 预算：累计本轮用量；任务仍将继续（还有工具/截断续写）且
                            // 触达预算时挂起询问。停止路径直接收尾——本次响应不入 LlmResponse 动作，
                            // 已落库的 assistant 消息（含未配对 tool_call 声明）由 buildHistory 悬挂项过滤兜底。
                            taskTokensUsed += (aiResponse.inputTokens + aiResponse.outputTokens).coerceAtLeast(0)
                            if (!budgetExempted && (aiResponse.toolCalls.isNotEmpty() || aiResponse.isTruncated)) {
                                val budget = generalSettingsRepository.tokenBudget()
                                if (budget > 0 && taskTokensUsed >= budget) {
                                    when (budgetApprovalManager.awaitApproval(taskTokensUsed, budget)) {
                                        BudgetApprovalChoice.CONTINUE -> budgetExempted = true
                                        BudgetApprovalChoice.STOP -> {
                                            stoppedByBudget = true
                                            state = state.copy(isFinished = true)
                                            send(AgentEvent.BudgetStopped(taskTokensUsed, budget))
                                            continue
                                        }
                                    }
                                }
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning,
                                    messageId = responseMessageId
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (stream.text.isEmpty() && stream.reasoning.isEmpty() && isContextOverflow(e) && overflowRecoveryStreakNow() < MAX_OVERFLOW_RECOVERIES) {
                                noteOverflowRecovery()
                                // 连续第 2 次起跳过工作集回注（同 stop_reason 溢出路径）。
                                val skipWorkingSetRestore = overflowRecoveryStreakNow() >= 2
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    val restore = if (skipWorkingSetRestore) null else currentContext.sessionId?.let { buildWorkingSetRestoreMessage(it) }
                                        ?.takeIf { restoreFitsBudget(systemPrompt, recovery.messages, it, currentTools, inputBudget) }
                                    val restored = if (restore != null) recovery.messages + restore else recovery.messages
                                    if (restore != null) {
                                        persistWorkingSetRestore(currentContext.sessionId, restore, excludeFromHistory = true)
                                    }
                                    state = state.copy(messages = restored)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(restored))
                                    callError = e.message
                                    continue
                                }
                            }
                            val partial = stream.text.toString()
                            val reasoning = stream.reasoning.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            // 多 Key 的自动切换与重发已在 adapter 内完成（见 AIProvider.keySwitcher）；
                            // 走到这里说明不是 Key 问题、或候选 Key 已全部失败，直接上报原始错误。
                            // 溢出多次仍放不下：给用户明确的出路（快照交接），避免无限压缩空转。
                            val overflowAdvice = if (isContextOverflow(e)) {
                                "；上下文多次溢出且压缩后仍放不下，请用「准备交接」或 /handoff 快照交接开启新会话"
                            } else ""
                            val errorText = "LLM 调用失败: ${e.message}$overflowAdvice"
                            actionQueue.addLast(AgentAction.LlmError(errorText))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = stream.finalResponse
                            runCatching {
                                val reasoningAudit = reasoningAuditOf(
                                    currentContext.reasoningEffort,
                                    providerInUse.reasoningWireUsed
                                )
                                llmCallRecordDao.insert(
                                    LlmCallRecordEntity(
                                        sessionId = currentContext.sessionId,
                                        providerId = providerInUse.providerId.ifBlank { null },
                                        model = providerInUse.model,
                                        reasoningEffort = currentContext.reasoningEffort,
                                        reasoningWire = reasoningAudit.wire,
                                        reasoningClampedFrom = reasoningAudit.clampedFrom,
                                        kind = callKind,
                                        inputTokens = usage?.inputTokens ?: 0,
                                        outputTokens = usage?.outputTokens ?: 0,
                                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                        ttfbMillis = stream.ttfbElapsed?.toInt(),
                                        durationMillis = durationMillis,
                                        status = when {
                                            callCompleted -> "success"
                                            callError != null -> "error"
                                            else -> "cancelled"
                                        },
                                        errorMessage = callError,
                                        stopReason = usage?.stopReason,
                                        retryCount = stream.retryAttempts,
                                        createdAt = callStartWall
                                    )
                                )
                            }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.permissionMode, currentContext.sessionId)

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "用户拒绝了本轮工具调用，该调用未执行。",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 并行执行本批已批准的工具。先统一记录 checkpoint（editFile/writeFile 修改前快照），
                        // 再并行执行；mode 切换检查在结果收集后于主协程串行处理（planApproval 单例）。
                        val toolCalls = effect.toolCalls
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                val pathLocks = ConcurrentHashMap<String, Mutex>()
                                toolCalls.map { toolCall ->
                                    async {
                                        val run: suspend () -> ToolRunResult = {
                                            // PreToolUse 钩子：脚本非零退出即否决该次调用（仅本地模式 + 已配钩子时生效）
                                            val preHook = hookRunner.run(
                                                HooksConfig.EVENT_PRE_TOOL_USE,
                                                toolCall.name,
                                                toolCall.arguments.toString()
                                            )
                                            if (preHook.blocked) {
                                                ToolRunResult(
                                                    ToolResult.Error(preHook.message ?: "被 PreToolUse 钩子否决", "HOOK_BLOCKED").toTransportString(),
                                                    true
                                                )
                                            } else {
                                                val tool = toolRegistry.getTool(toolCall.name)
                                                val result = if (tool is StreamingAgentTool) {
                                                    runToolStream(tool, toolCall, currentContext) { send(it) }
                                                } else {
                                                    runToolSync(tool, toolCall, currentContext)
                                                }
                                                // 钩子输出并入结果（Pre 非否决输出 + Post 输出）：供模型与 UI 查看
                                                val postHook = hookRunner.run(
                                                    HooksConfig.EVENT_POST_TOOL_USE,
                                                    toolCall.name,
                                                    toolCall.arguments.toString()
                                                )
                                                val hookTexts = listOfNotNull(
                                                    preHook.message?.takeIf { it.isNotBlank() }
                                                        ?.let { "[hook:PreToolUse]\n$it" },
                                                    postHook.message?.takeIf { it.isNotBlank() }
                                                        ?.let { "[hook:PostToolUse]\n$it" }
                                                )
                                                if (hookTexts.isEmpty()) {
                                                    result
                                                } else {
                                                    result.copy(raw = mergeHookOutput(result.raw, hookTexts.joinToString("\n\n")))
                                                }
                                            }
                                        }
                                        // 同一批里针对同一路径的调用必须串行：并行会让「读-改-写」互相覆盖，
                                        // 并让写入方的回读校验看到别人的内容而误报失败。不同路径仍并行。
                                        val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull
                                        if (path.isNullOrBlank()) run()
                                        else pathLocks.computeIfAbsent(path) { Mutex() }.withLock { run() }
                                    }
                                }.awaitAll()
                            }
                        }

                        // 串行处理 mode 切换并组装批量结果。
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // 退出 PLAN 时挂起 workflow，等待用户在计划审查面板批准后才继续
                                if (currentContext.mode == AgentMode.PLAN && newCtx.mode != AgentMode.PLAN) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        // system 与 mode 已解耦（SystemPromptProvider 不再注入模式提示词），
                                        // 切换不重建 systemPrompt，避免 system 前缀变化打断缓存；模式状态通过工具结果与下轮消息提醒告知。
                                        rawResult += buildModeSwitchNotice(newCtx.mode)
                                    } else {
                                        // 用户选择继续反馈，回滚到 PLAN 模式，修正工具结果让 AI 知道切换被取消并等待用户反馈
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        rawResult = ToolResult.Error(
                                            "用户希望补充说明或调整方案，当前保持在 PLAN 模式。请等待用户输入具体的补充或修改意见，不要自行臆测修改，待用户明确反馈后再继续。",
                                            "MODE_SWITCH_REJECTED"
                                        ).toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    rawResult += buildModeSwitchNotice(newCtx.mode)
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        // 本轮内到达的后台任务/子代理完成通知：搭在本批最后一条工具结果上立即送达，
                        // AI 当轮即可感知，不必等本轮结束再起新一轮。ack 放到完成事件发出（结果已落库）之后：
                        // 中途被取消时通知仍留在队列里，由后续批次或本轮结束的兜底路径送达。
                        val notifySessionId = currentContext.sessionId
                        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
                            agentNotificationCenter.peek(notifySessionId)
                        } else {
                            emptyList()
                        }
                        if (notifications.isNotEmpty()) {
                            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
                            val newMode = modeChange?.newMode
                            if (newMode != null) {
                                // 用户在工作期间切换模式：本轮后续批次的权限判定立即改用新模式。
                                // 与 SessionUseCase.updateMode 保持同一套语义：进入 PLAN 记下进入前的模式，其余情况清空。
                                val prevMode = currentContext.mode
                                currentContext = if (newMode == AgentMode.PLAN) {
                                    currentContext.copy(
                                        mode = AgentMode.PLAN,
                                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                                    )
                                } else {
                                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                                }
                            }
                            val last = batchResults.last()
                            var injected = eventInjector.inject(last.result, notifications)
                            if (newMode != null) {
                                // 模式约束提示随工具结果落库，留在历史里供后续轮沿用。
                                injected += buildExternalModeSwitchNotice(newMode)
                            }
                            batchResults[batchResults.lastIndex] = last.copy(result = injected)
                        }

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError,
                                attachments = br.attachments, persisted = persisted))
                            persisted.await()
                        }
                        if (notifySessionId != null && notifications.isNotEmpty()) {
                            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        // 收尾独立验收：正常完成且未被预算叫停时，用独立验收模型对目标清单做交叉检查。
        // 失败静默跳过——验收是附加价值，绝不能反过来让主任务失败。
        if (state.error == null && !stoppedByBudget) {
            try {
                val report = runCompletionVerification(currentContext, state)
                if (report != null) {
                    send(AgentEvent.VerificationReport(report))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "收尾独立验收失败", e)
            }
        }
        // 失败与完成互斥：Failed 已把状态置 Error 并归档 outcome=failed，
        // 这里再发 Completed 会让父代理收到「子任务完成」，且 completed 归档会覆盖 failed。
        if (state.error != null) {
            send(AgentEvent.Failed(state.error, state.errorCode))
        } else {
            send(AgentEvent.Completed)
        }
    }

    /**
     * 流式响应累计器：把 [AIProvider.completeStream] 的增量按时间窗口节流后推送，并保留最终响应与调用统计。
     *
     * 独立成类是为了不让 [executeEvents] 的协程状态机为每个增量都插入挂起点——那个 `channelFlow` 主体
     * 是本工程最大的协程方法，规模过大会被 ART 长期交给解释器执行。
     *
     * 节流：上游每个 chunk 都携带完整累积文本，逐条推送会让 ViewModel 端每秒重建几十次状态、
     * UI 端反复重启打字机协程。按时间窗口合并，窗口内只保留最新累积文本，到窗口边界才发送，
     * 把下游事件频率压到 ~16/s；打字机渲染本身有 100ms 节流，少发中间态无感知。
     */
    private class StreamAccumulator(
        private val callStartElapsed: Long,
        private val emit: suspend (AgentEvent) -> Unit
    ) {
        private companion object {
            const val DELTA_THROTTLE_MS = 60L
        }

        /** 累积正文（节流推送与最终落库都用它）。 */
        val text = StringBuilder()

        /** 累积思考内容。 */
        val reasoning = StringBuilder()

        var finalResponse: AIResponse? = null
        var ttfbElapsed: Long? = null
        var retryAttempts = 0

        private var pendingText: String? = null
        private var pendingReasoning: String? = null
        private var lastTextSentAt = 0L
        private var lastReasoningSentAt = 0L

        suspend fun onChunk(chunk: AIStreamChunk) {
            when (chunk) {
                is AIStreamChunk.TextDelta -> {
                    markTtfb()
                    text.append(chunk.text)
                    pendingText = text.toString()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTextSentAt >= DELTA_THROTTLE_MS) {
                        flushText()
                        lastTextSentAt = now
                    }
                }
                is AIStreamChunk.ReasoningDelta -> {
                    // 思考内容也算首字（推理模型先吐思考再吐正文）
                    markTtfb()
                    reasoning.append(chunk.text)
                    pendingReasoning = reasoning.toString()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReasoningSentAt >= DELTA_THROTTLE_MS) {
                        flushReasoning()
                        lastReasoningSentAt = now
                    }
                }
                is AIStreamChunk.Retrying -> {
                    retryAttempts = maxOf(retryAttempts, chunk.attempt)
                    reset()
                    emit(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                }
                is AIStreamChunk.KeySwitched -> {
                    reset()
                    emit(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                }
                is AIStreamChunk.Final -> {
                    // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                    markTtfb()
                    finalResponse = chunk.response
                }
                is AIStreamChunk.ToolCallDeclared -> {
                    // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                    // 免得长参数流式期间一直停在「正在思考」。
                    markTtfb()
                    emit(AgentEvent.ToolCallPreparing(chunk.name))
                }
            }
        }

        /** 补发窗口内压着的最后一段文本（流结束时调用）。 */
        suspend fun flush() {
            flushText()
            flushReasoning()
        }

        private fun markTtfb() {
            if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
        }

        /** 重试/换 Key 后重放：丢弃已推送内容，避免新旧文本拼接。 */
        private fun reset() {
            text.setLength(0)
            reasoning.setLength(0)
            pendingText = null
            pendingReasoning = null
            lastTextSentAt = 0L
            lastReasoningSentAt = 0L
        }

        private suspend fun flushText() {
            val t = pendingText ?: return
            pendingText = null
            emit(AgentEvent.AssistantDelta(t))
        }

        private suspend fun flushReasoning() {
            val t = pendingReasoning ?: return
            pendingReasoning = null
            emit(AgentEvent.ReasoningDelta(t))
        }
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    /**
     * 把钩子输出并入 transport JSON 顶层的 hook_output 字段：结果保持合法 JSON，
     * UI 的 formatToolResult 与各结构化解析不退化；raw 已非 JSON 时退化为文本追加。
     */
    private fun mergeHookOutput(raw: String, text: String): String {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return raw + "\n\n" + text
        val prev = (obj["hook_output"] as? JsonPrimitive)?.contentOrNull
        val merged = if (prev.isNullOrBlank()) text else "$prev\n\n$text"
        return JsonObject(obj + ("hook_output" to JsonPrimitive(merged))).toString()
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        // 工具耗时指标（B4）：只在执行体结束后统一结算；慢工具（≥1s）与失败必须留下痕迹，
        // 日常琐碎工具（todo 等）不打日志防刷屏。含 PreToolUse 前的排队时间无意义，从执行体起算。
        val startedAt = System.currentTimeMillis()
        return try {
            // 工具实现在此被调用；工作流本身在主线程收集，故统一切到 IO——避免远程模式下工具里的
            // 同步文件/网络 IO 阻塞主线程。依赖主线程的工具（浏览器）在自身实现内已切回 Main。
            withContext(Dispatchers.IO) {
                val result = tool.executeWithContext(toolCall.arguments, context)
                val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                logToolDuration(name, startedAt, processed is ToolResult.Error)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logToolDuration(name, startedAt, isError = true)
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 工具耗时指标行（对齐 ContextCompactor 的「压缩指标」风格）：
     * 只记 ≥1s 的慢调用与全部失败——一次回合几十次工具调用，全量记录会把日志刷成工具流水账；
     * 而「为什么这轮这么慢」「哪个工具卡住了」这类排查只关心慢的和错的。
     */
    private fun logToolDuration(name: String, startedAtMillis: Long, isError: Boolean) {
        val elapsed = System.currentTimeMillis() - startedAtMillis
        if (elapsed < TOOL_DURATION_LOG_THRESHOLD_MS && !isError) return
        FileLogger.i(TAG, "工具指标: 工具=$name 结果=${if (isError) "失败" else "成功"} 耗时ms=$elapsed")
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * 微压缩：保留最近 [MICRO_COMPACT_KEEP_TOOL_RESULTS] 条工具结果全文，更早的长结果替换为占位符。
     * 只影响本次发送（同 sanitizeImagesForModel 模式）：state.messages 与 DB 始终是原文，
     * 全量压缩摘要、溢出恢复、回滚、下一回合重建全部读到原文。
     * 占位符保留工具名与存档路径（长输出的 output_path 提示不能丢）；措辞区分读写类工具，
     * 避免诱导模型盲目重放 editFile/terminal 等非幂等调用。必须 map/copy 产出新列表：
     * buildHistory 返回进程级共享缓存实例，不可原地改写；且输出确定性保证跨轮前缀缓存字节稳定。
     */
    private fun microCompactToolResults(messages: List<AgentMessage>): List<AgentMessage> {
        val toolResultIndexes = messages.withIndex()
            .filter { it.value is AgentMessage.ToolResultMessage }
            .map { it.index }
        val cutoff = toolResultIndexes.getOrNull(toolResultIndexes.size - MICRO_COMPACT_KEEP_TOOL_RESULTS)
            ?: return messages
        return messages.mapIndexed { index, msg ->
            val toolResult = msg as? AgentMessage.ToolResultMessage
            if (toolResult == null || index >= cutoff || toolResult.result.length <= MICRO_COMPACT_MIN_CHARS) {
                msg
            } else {
                val archivePath = TOOL_OUTPUT_ARCHIVE_PATH.find(toolResult.result)?.groupValues?.getOrNull(1)
                // 语义锚点（3.4）：压缩后仍要可判错——保留退出码/错误首行/关键结论，而不是纯截断。
                // 只取很短的锚点行，成本可控；丢它会让模型把「构建失败」当成「命令没输出」。
                val anchors = extractSemanticAnchors(toolResult.result)
                val placeholder = buildString {
                    append("[工具结果已清理（上下文微压缩）：工具 ")
                    append(toolResult.toolName)
                    append("。读取类工具可直接重新调用取回；写类/有副作用的工具请先核实当前状态，勿盲目重放。")
                    if (anchors.isNotEmpty()) {
                        append("\n关键信息（原文）：")
                        append(anchors)
                    }
                    if (archivePath != null) {
                        append("\n完整内容已存档：")
                        append(archivePath)
                    }
                }
                toolResult.copy(result = placeholder, modelResult = null)
            }
        }
    }

    /**
     * 从工具输出里抽取语义锚点（3.4 压缩保真）：退出码、错误首行、BUILD/FAILED 类结论行。
     * 最多 [ANCHOR_MAX_LINES] 行、每行截断，总长可控；抽不到锚点返回空串（不硬造内容）。
     */
    private fun extractSemanticAnchors(result: String): String {
        val lines = result.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return ""
        val picked = LinkedHashSet<String>()
        for (line in lines) {
            if (picked.size >= ANCHOR_MAX_LINES) break
            val hit = ANCHOR_PATTERNS.any { it.containsMatchIn(line) }
            if (hit) picked.add(line.take(ANCHOR_LINE_MAX_CHARS))
        }
        return picked.joinToString("\n")
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return aiProviderFactory.create(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return aiProviderFactory.create(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 独立验收专用 provider 解析。未配置（providerId/model 为空）或配置不可用时返回 null，
     * 视为关闭收尾验收——本项没有「跟随当前聊天模型」的回退：同一模型验收自己等于没有交叉检查。
     */
    private suspend fun resolveVerificationProvider(sessionId: String): AIProvider? {
        val providerId = verificationModelSettingsRepository.getVerificationProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = verificationModelSettingsRepository.getVerificationModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return aiProviderFactory.create(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "title",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    /**
     * 润色输入框草稿：修正错别字、理顺逻辑、换成更专业的表述，**不改变原意、不执行请求**。
     *
     * 与 [generateTitle] 同一套「一次性调用」写法：走会话当前供应商/模型（用户选的模型通常
     * 比固定小模型更会写文案），提示词来自 `agent/text-polisher.md`，并把这次调用记进
     * `llm_call_records`（kind=`polish`），让使用量统计里能看到它的花销。
     *
     * 拿不到结果（网络失败、模型拒答、空输出）一律返回 null，由调用方提示失败并保留原文。
     */
    override suspend fun polishText(sessionId: String, text: String): String? = runCatching {
        val input = text.trim().take(TEXT_POLISH_MAX_INPUT_CHARS)
        if (input.isEmpty()) return@runCatching null
        val provider = getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TEXT_POLISHER_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = input)),
                tools = emptyList()
            ).also {
                usage = it
                callCompleted = true
            }
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "polish",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        sanitizePolishedText(response.content)
    }.onFailure { e ->
        FileLogger.w(TAG, "润色输入失败", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成提交信息失败", e)
    }.getOrNull()

    /**
     * 收尾独立验收：由「验收模型」核对本会话目标清单的完成度，返回报告文本（无则 null，
     * 由调用方发送 [AgentEvent.VerificationReport] 交 ViewModel 落库为验收卡片）。
     *
     * 触发条件（全部满足才跑）：主会话（非子代理）、配置了可用的验收模型、会话有 todo 清单
     * （目标拆解的载体，见 TodoTool）。异常由调用方兜底，本方法只负责正常路径。
     */
    private suspend fun runCompletionVerification(context: AgentContext, state: AgentSessionState): String? {
        val sessionId = context.sessionId ?: return null
        // 子代理会话不做验收：其产出由父会话收尾统一把关，也避免任务链无谓变长。
        if (sessionUseCase.getSessionById(sessionId)?.parentId != null) return null
        val todos = todoItemDao.getBySessionOnce(sessionId)
        if (todos.isEmpty()) return null
        val provider = resolveVerificationProvider(sessionId) ?: return null

        val todoText = todos.joinToString("\n") { item ->
            val status = when (item.status.lowercase()) {
                "completed" -> "已完成"
                "in_progress" -> "进行中"
                else -> "待办"
            }
            val desc = if (item.description.isNotBlank()) "（${item.description}）" else ""
            "- [$status] ${item.subject}$desc"
        }
        val evidence = VerificationContextBuilder.buildEvidence(state.messages)
        val input = buildString {
            append("目标清单（todo）：\n").append(todoText)
            if (evidence.isNotBlank()) {
                append("\n\n工具实际返回（证据，非助手自述，以此为准）：\n").append(evidence)
            }
            append("\n\n最近对话记录（助手自述，仅作补充）：\n")
                .append(VerificationContextBuilder.buildConversation(state.messages))
        }
        val prompt = promptProvider.resolvePrompt(VERIFIER_FILE).replace(LEADING_COMMENT, "")

        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = input)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "verification",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        // 取消口径与主聊天路径对齐：取消记 cancelled（errorMessage 留空），异常记 error。
                        status = when {
                            callCompleted -> "success"
                            callError == null -> "cancelled"
                            else -> "error"
                        },
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        return response.content.trim().take(VERIFICATION_REPORT_MAX_CHARS).ifEmpty { null }
    }

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        // 工具耗时指标（B4）：与 runToolSync 同一口径，从执行体起算。
        val startedAt = System.currentTimeMillis()
        return try {
            // 同 runToolSync：流式工具也可能在主线程收集上下文里被驱动，统一切 IO。
            withContext(Dispatchers.IO) {
                val live = StringBuilder()
                var lastEmitMs = 0L
                var finalResult: ToolResult? = null
                tool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                logToolDuration(toolCall.name, startedAt, processed is ToolResult.Error)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logToolDuration(toolCall.name, startedAt, isError = true)
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 把模型直出的图片（base64）落盘到 `~/.aicode/generated-images/`，返回带容器路径的 images
     * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // 进入 PLAN 时记住当前模式；退出时恢复到它（AUTO→PLAN 的规划往返结束后回到 AUTO 而非 BUILD）。
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * 当前模式提醒：随最新用户消息注入（借鉴 opencode SessionReminders 的思路）。
     * 模式提示词不进 system——一旦切换就要重建 system、打断前缀缓存；
     * 改为消息级提醒：每次用户请求拼在最新用户消息末尾，位置在消息流尾部，前缀保持稳定。
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.AUTO -> promptProvider.resolvePrompt(MODE_REMINDER_AUTO_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.BUILD -> null
    }

    /**
     * 把待送通知注入工具结果：优先作为 transport JSON 顶层的 `notifications` 字段，结果仍是合法 JSON，
     * UI 的 formatToolResult（只读 data/message）与各类结构化解析不受影响。
     * raw 已被模式切换提示等纯文本追加过、不再是合法 JSON 时，退化为文本追加。
     */
    /**
     * 取本次应注入的模式提醒：仅当模式与上次注入时不同（含首次）才返回文本并记录，否则为 null。
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** 工具调用成功后拼进 planMode 工具结果的模式状态通知（当轮即可见，无需等下一条用户消息）。 */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】计划已获用户批准，你已切换到 BUILD（构建）模式，可以开始执行计划。"
        AgentMode.AUTO -> "\n\n【模式切换】计划已获用户批准，你已恢复到 AUTO（自动）模式，可以开始执行计划（工具调用将自动放行）。"
    }

    /** 用户在外部（界面）手动切换模式时拼进工具结果的提示；与 AI 自切的 [buildModeSwitchNotice] 区分，避免误称「计划已获批准」。 */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】用户已将模式切换为 BUILD（构建）模式，可以正常执行写操作。"
        AgentMode.AUTO -> "\n\n【模式切换】用户已将模式切换为 AUTO（自动）模式。"
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // planMode 退出 PLAN 时，后续会有计划审查面板兜底用户决策，
        // 此处权限弹窗冗余，直接放行；进入 PLAN 的方向无后续审查面板，仍走权限弹窗。
        if (tool.name == "planMode" && mode == AgentMode.PLAN) {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (action == "exit") {
                return PermissionCheckResult(true)
            }
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)

        // 守夜自动放行：写授权记录（放行 + 记录）。
        eval.watchAutoAllow?.let { reason ->
            auditRecorder.recordWatchAllow(
                sessionId = sessionId,
                toolName = tool.name,
                summary = tool.buildPermissionRequest(callId, arguments, argsPreview).summary,
                reason = reason
            )
        }

        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            if (code == "SYSTEM_DENIED") {
                // 授权记录：系统自动拒绝（档位禁止/规则/内置防护）；PLAN 拦截属模式设计行为，不记录。
                auditRecorder.recordSystemDeny(
                    sessionId = sessionId,
                    toolName = tool.name,
                    summary = tool.buildPermissionRequest(callId, arguments, argsPreview).summary,
                    reason = reason
                )
            }
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE &&
            policyEngine.tierOf(tool.name) != ToolPermissionTier.ASK) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty()
                )
                when (val outcome = permissionManager.awaitApproval(request)) {
                    is ToolApprovalOutcome.WatchTimedOut -> PermissionCheckResult(
                        false,
                        "守夜模式：用户不在场，本次授权请求超时已自动拒绝。请改用可逆或更简单的替代方案继续推进；若无安全替代，跳过此项并继续完成其余任务。",
                        "WATCH_TIMEOUT"
                    )
                    is ToolApprovalOutcome.Decided -> when (outcome.choice) {
                        PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                        PermissionChoice.ONCE -> PermissionCheckResult(true)
                        PermissionChoice.ALWAYS -> {
                            if (eval.rememberablePatterns.isNotEmpty()) {
                                policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                            }
                            PermissionCheckResult(true)
                        }
                    }
                }
            }
        }
    }
}
