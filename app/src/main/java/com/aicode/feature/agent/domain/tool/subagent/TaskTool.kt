package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentDeliveryTracker
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.subagent.SubAgentReportCatalog
import com.aicode.feature.agent.domain.subagent.TaskBriefRepository
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.model.MessageRole
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ModelCapabilityRepository
import com.aicode.feature.settings.data.repository.ModelRoutingTable
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理工具 `task`：统一管理子代理的生命周期。
 *
 * 通过 `action` 参数区分操作类型：
 * - `create`（默认）：创建一个子代理会话并让 AI 替用户向其发消息，子代理自动开始回复。
 * - `send`：向指定子代理发送一条消息（可反复发送）。运行中的子代理会在下一批工具结果里搭车收到，
 *   已完成的子代理会被重新唤醒；消息按发送顺序送达。
 * - `read`：读取指定子代理的最后输出（最后一条助手回复）。
 * - `stop`：停止指定子代理的执行（取消其 AI 任务）。
 * - `del`：删除指定子代理会话及其全部消息。
 * - `list`：列出当前会话的全部子代理及其状态。
 *
 * 最多同时允许 5 个运行中的子代理（create 时检查上限）。
 * 子代理不能嵌套创建子代理（其工具集中不含 `task`）。
 *
 * `create` 可用 `agent` 参数指定自定义子代理定义（`agents/<name>.md`），
 * 由定义决定该子会话的模型、工具集与系统提示词；省略则用继承父会话模型的默认通用子代理。
 */
class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus,
    private val deliveryTracker: SubAgentDeliveryTracker,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val taskBriefRepository: TaskBriefRepository,
    private val subAgentReportCatalog: SubAgentReportCatalog,
    private val modelCapabilityRepository: ModelCapabilityRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30

        /** `list` 返回的归档报告条数上限（避免历史堆积后把工具结果撞爆上下文）。 */
        const val ARCHIVE_LIST_LIMIT = 20
        /** 未指定 agent 时子会话记录的类型标识。 */
        const val DEFAULT_SUBAGENT_TYPE = "subagent"
    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "read", "list", "send" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description = "管理子代理：创建、发消息、读取结果、停止、删除、列表。子代理拥有独立上下文与完整工具能力，可并行工作，最多同时运行 5 个。完成后会收到后台通知，不要轮询。用 send 可反复追加指令或对已完成子代理继续追问；子代理运行中也可能主动发消息。create 可用 agent 指定自定义子代理、brief 引用任务书模板、tools 收敛本任务工具集。子代理结束会自动归档报告，list 可查归档（含子会话已删的），read 对已删除会话回退读归档。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：create（默认，创建子代理并执行任务）/ send（向子代理发一条消息，可反复发送）/ read（读取子代理的最后输出）/ stop（停止子代理的执行）/ del（删除子代理会话及其消息）/ list（列出当前会话的全部子代理）",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "子会话 id（read/send/stop/del 必填，取自 task 返回的 id）",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "子代理任务描述（create 用，作为子会话标题，如「修复登录 bug」）",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "create 必填：子代理的任务指令正文，作为它的第一条用户消息（子代理看到全新上下文）。注意字段名是 prompt 不是 message——message 只用于 send 向已存在的子代理追加消息",
            required = false
        ),
        "agent" to ToolParameter(
            name = "agent",
            type = ParameterType.STRING,
            description = "自定义子代理名（create 可选）：取系统提示词「可用子代理」清单中的名称，按其专属提示词、模型与工具集运行；省略则用继承本会话模型的默认通用子代理",
            required = false
        ),
        "tools" to ToolParameter(
            name = "tools",
            type = ParameterType.STRING,
            description = "本任务允许子代理使用的工具名（create 可选，逗号分隔，支持 `mcp__server__*` 通配）。只约束本次派发：省略则继承子代理定义的工具集。任务只需要读时给 readFile,list,search 可省 token 并防误调",
            required = false
        ),
        "brief" to ToolParameter(
            name = "brief",
            type = ParameterType.STRING,
            description = "任务书模板名（create 可选）：`~/.aicode/task-briefs/<名>.md` 或项目级同名模板，正文会前置到 prompt 前面",
            required = false
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给子代理的消息正文（send 必填）。可反复调用；运行中的子代理会尽快收到，已完成的会被重新唤醒",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "create" -> createSubagent(args, context)
            "send" -> sendToSubagent(args, context)
            "read" -> readSubagent(args, context)
            "stop" -> stopSubagent(args, context)
            "del" -> deleteSubagent(args, context)
            "list" -> listSubagents(context)
            else -> ToolResult.Error("未知的 action：$action，支持：create / send / read / stop / del / list", "INVALID_ARGS")
        }
    }

    /** 创建子代理并启动执行。 */
    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")

        // 模型在 create 场景常把指令写进 message（那是 send 的参数名）：意图无歧义，
        // 按 prompt 采纳。不容错的话，模型一旦落入这个参数模式就会反复失败且无法自纠
        // （实测同一错误模式连钟六轮，报错里不回显实际参数时模型完全意识不到）。
        val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        if (prompt == null) {
            val received = args.keys.joinToString(",").ifEmpty { "（无任何参数）" }
            return ToolResult.Error(
                "参数无效：create 需要 prompt（子代理的任务指令）。本次收到参数：$received；" +
                    "prompt 与 message 均为空，请把指令文本放进 prompt 重试。",
                "INVALID_ARGS"
            )
        }

        // 任务书模板（可选）：把模板正文前置到任务书，省得每次重写五要素；
        // 写错模板名直接报错并列出可用名，不静默忽略（否则会以为模板生效了）。
        val briefName = (args["brief"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val fullPrompt = if (briefName == null) {
            prompt
        } else {
            val brief = taskBriefRepository.find(parentSession.workspacePath, briefName)
            if (brief == null) {
                val available = taskBriefRepository.listAll(parentSession.workspacePath).map { it.name }
                val hint = if (available.isEmpty()) "未配置任何任务书模板（~/.aicode/task-briefs/）"
                else "可用：${available.joinToString(", ")}"
                return ToolResult.Error("任务书模板不存在：$briefName（$hint）", "BRIEF_NOT_FOUND")
            }
            "${TaskBriefRepository.BRIEF_HEADER}\n${brief.body}\n\n$prompt"
        }

        // 任务期工具准入（step-owned）：只收窄本次派发的工具集，不放开定义里已有的限制。
        val requestedTools = (args["tools"] as? JsonPrimitive)?.contentOrNull
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TASK_DESCRIPTION_MAX)
            ?: "子代理任务"

        // 指定 agent 时必须能找到定义：写错名字就报错并列出可用名，不静默回退成通用子代理，
        // 否则会拿着错的工具集与提示词跑完整个任务。
        val agentName = (args["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        var definition: AgentDefinition? = null
        if (agentName != null) {
            definition = agentDefinitionRepository.find(agentName)
            if (definition == null) {
                val available = agentDefinitionRepository.listEnabled().map { it.definition.name }
                val hint = if (available.isEmpty()) "当前未定义任何自定义子代理" else "可用：${available.joinToString(", ")}"
                return ToolResult.Error("子代理定义不存在：$agentName（$hint）", "AGENT_NOT_FOUND")
            }
        }

        // 模型覆盖优先级：自定义 agent 定义里的显式配置 > 设置页「子代理模型」覆盖 > 路由表档位（4.2）> 继承父会话。
        // 默认通用子代理的覆盖 key 为空串（与设置页、仓库约定一致）。
        val overrideKey = definition?.name ?: ""
        val override = defaultModelSettingsRepository.subAgentModelOverridesSnapshot()[overrideKey]
        // 定义/覆盖都按 provider+model 成对采用，避免拼出「A 家 provider + B 家 model」的幽灵组合。
        val definitionOwnsModel = definition != null && (definition.providerId != null || definition.model != null)
        // 路由表（4.2 静态路由）：只在前面几级都没指定模型时生效；表是配置数据，未配置就是空表（行为与改动前一致）。
        val routedModel = if (!definitionOwnsModel && override == null) {
            runCatching {
                val table = modelCapabilityRepository.routingTable()
                table.modelFor(definition?.name ?: ModelRoutingTable.DEFAULT_TASK)
            }.getOrNull()
        } else null
        // 并发名额：检查与占位在同一锁内原子完成。同一批工具调用是并行执行的，
        // 「先读计数再创建」会让并行的 task(create) 双双通过检查后超额创建。
        if (!eventBus.tryReserveSlots(1)) {
            return ToolResult.Error(
                "子代理已达上限（全局最多 ${SubAgentEventBus.MAX_RUNNING} 个同时运行，当前已有 ${eventBus.activeCount} 个在运行），" +
                    "请先等待其中某个完成或用 stop 停止后再创建",
                "MAX_SUBAGENTS_REACHED"
            )
        }
        // 占位在 emit(SPAWNED) 时转为正式活跃；中途任何提前返回都必须归还名额。
        var slotHandedOver = false
        try {
            // 创建子代理会话
            val subSession = sessionUseCase.newSubSessionEntity(
                title = description,
                parentId = parentSessionId,
                parent = parentSession,
                subagentType = definition?.name ?: DEFAULT_SUBAGENT_TYPE,
                providerId = when {
                    definitionOwnsModel -> definition?.providerId?.let { resolveProviderId(it) }
                    override != null -> override.first.let { resolveProviderId(it) }
                    else -> routedModel?.providerId?.let { resolveProviderId(it) }
                },
                model = when {
                    definitionOwnsModel -> definition?.model
                    override != null -> override.second
                    else -> routedModel?.model
                },
                reasoningEffort = definition?.reasoningEffort?.let { effort ->
                    ReasoningEffort.entries.firstOrNull { it.apiValue == effort }?.name
                },
                mode = definition?.mode
            )
            sessionUseCase.upsertSession(subSession)
            val subSessionId = subSession.id

            // 登记任务期工具限制（在 SPAWNED 之前，避免子代理启动读不到）
            eventBus.setStepToolLimit(subSessionId, requestedTools)

            // 通知 ViewModel 在子会话上启动 AI 工作流
            eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSessionId,
                    parentSessionId = parentSessionId,
                    type = SubAgentEventType.SPAWNED,
                    detail = fullPrompt
                )
            )
            slotHandedOver = true
            FileLogger.i(TAG, "子代理已创建: session=$subSessionId parent=$parentSessionId agent=${definition?.name ?: "-"}")

            return ToolResult.Success(
                buildJsonObject {
                    put("id", subSessionId)
                    put("state", "running")
                    definition?.let { put("agent", it.name) }
                    put("message", "子代理已创建并开始执行，任务完成后会收到通知。可用 task(action=\"read\", id=...) 读取输出，task(action=\"stop\", id=...) 主动关闭。")
                }
            )
        } finally {
            if (!slotHandedOver) eventBus.releaseSlots(1)
        }
    }

    /**
     * 向指定子代理发送一条消息（可反复发送）。
     *
     * 事件交由 ViewModel 按收件人状态分发：运行中的子代理把消息入通知队列、在下一批工具结果里搭车送达；
     * 已完成的子代理则被重新唤醒并起新一轮。不在工具层直接投递，以复用同一套忙碌/空闲分发逻辑。
     */
    private suspend fun sendToSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != parentSessionId) {
            return ToolResult.Error("只能向当前会话派生的子代理发消息", "NOT_YOUR_SUBAGENT")
        }

        // 编队投递语义：登记送达追踪（送达时限 2 分钟，超时由宿主重投/提醒发起方）
        val messageId = java.util.UUID.randomUUID().toString()
        deliveryTracker.track(
            messageId = messageId,
            fromSessionId = parentSessionId,
            toSessionId = subSessionId,
            requireReply = false,
            content = message
        )
        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PARENT,
                detail = message,
                messageId = messageId
            )
        )
        FileLogger.i(TAG, "向子代理发送消息: session=$subSessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "sent")
                put("messageId", messageId)
                put("message", "消息已发出并进入送达追踪：运行中的子代理将在下一批工具结果里收到，已完成的将被重新唤醒；若超时未送达会自动重投并提醒你。")
            }
        )
    }

    /**
     * 把定义里的 provider 字段解析为真实 provider id：先按 id 精确匹配，再按名称忽略大小写匹配，
     * 让用户在 frontmatter 里能直接写设置里看到的提供商名。都匹不上则返回 null（继承父会话）。
     */
    private suspend fun resolveProviderId(raw: String): String? {
        aiProviderRepository.getProviderById(raw)?.let { return it.id }
        val all = aiProviderRepository.getAllProviders().first()
        return all.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
    }

    /** 读取指定子代理的最后输出。子会话已被删除时回退到报告 catalog（归档结论仍可查）。 */
    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
        if (sub == null) {
            val archived = subAgentReportCatalog.read(subSessionId)
                ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
            return ToolResult.Success(
                buildJsonObject {
                    put("id", subSessionId)
                    put("state", "archived")
                    put("lastOutput", archived)
                    put("message", "子会话已删除，以下为归档的报告全文。")
                }
            )
        }
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能读取当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        val messages = agentMessageDao.getMessagesBySessionOnce(subSessionId)
        // 取最后一条有内容的助手回复（跳过 reasoning-only 的中间消息）
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "（子代理尚未回复）请求内容：${fallback.content.take(500)}"
            else -> "（子代理会话为空）"
        }
        val last = runCatching { messages.lastOrNull()?.timestamp ?: 0L }.getOrDefault(0L)
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("title", sub.title)
                put("updatedAt", last)
                put("lastOutput", content)
            }
        )
    }

    /** 停止指定子代理的执行。 */
    private suspend fun stopSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能关闭当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.STOPPED
            )
        )
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "stopping")
                put("message", "已请求停止子代理，正在取消其 AI 任务。")
            }
        )
    }

    /** 删除指定子代理会话（含其消息）。若仍在运行先请求停止。 */
    private suspend fun deleteSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能删除当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }
        if (eventBus.activeSubSessionIds.value.contains(subSessionId)) {
            eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSessionId,
                    parentSessionId = context.sessionId!!,
                    type = SubAgentEventType.DELETED
                )
            )
        }
        // 子代理会话是任务期产物，结束后应当彻底删除：进回收站只会堆一堆没人要的子会话
        sessionUseCase.purgeSession(subSessionId)
        FileLogger.i(TAG, "子代理已删除: session=$subSessionId parent=${context.sessionId}")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "deleted")
                put("message", "子代理已删除（含其全部消息）。")
            }
        )
    }

    /** 列出当前会话的全部子代理。 */
    private suspend fun listSubagents(context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val activeIds = eventBus.activeSubSessionIds.value

        val jsonArray = buildJsonArray {
            subs.forEach { entity ->
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (entity.id in activeIds) "running" else "completed")
                    // 运行中的报出已跑时长：LLM 回合可能静默卡死（不报错不结束，state 永远 running），
                    // 有时长才能判断「还在干活」还是「已经死了该 stop 重派」。
                    eventBus.runningMillis(entity.id)?.let { put("runningSeconds", it / 1000) }
                    put("createdAt", entity.createdAt)
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", jsonArray)
                put("count", subs.size)
                put("runningCount", activeIds.size)
                put("maxRunning", SubAgentEventBus.MAX_RUNNING)
                // 已归档报告（含子会话已删除的）：同范围审查可先查这里，避免重复整轮派发
                val archived = subAgentReportCatalog.listEntries().take(ARCHIVE_LIST_LIMIT)
                put("archivedReports", buildJsonArray {
                    archived.forEach { entry ->
                        addJsonObject {
                            put("id", entry.subSessionId)
                            put("agent", entry.agentType)
                            put("title", entry.title)
                            put("outcome", entry.outcome)
                            put("finishedAt", entry.finishedAt)
                        }
                    }
                })
                put("archivedCount", archived.size)
            }
        )
    }
}