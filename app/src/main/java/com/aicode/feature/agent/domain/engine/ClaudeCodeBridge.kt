package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.cliagent.CliTabInjection
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.prompt.CliKnowledgeInjection
import com.aicode.feature.agent.domain.provider.NormalizedUsage
import com.aicode.feature.agent.domain.provider.UsageNormalizer
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** stream-json 模式下 CLI 提前退出且 stderr 命中续接会话缺失的特征时抛出，触发一次无续接重试。 */
internal class ResumeSessionMissingException(message: String) : RuntimeException(message)

/**
 * Claude Code CLI 桥接（引擎切换一期）。
 *
 * 每个用户回合在 PRoot 容器内起一个 headless 进程：
 * `claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages`
 * `--session-id <本体sessionId> --permission-prompt-tool mcp__permission_bridge__request_permission --mcp-config <生成的配置>`
 * `[--agents <生成的智能体配置>]`
 * 把 stdout 的 NDJSON 逐行翻译成 [AgentEvent]——落库 / 停止 / 队列 / UI 状态机全部复用本体链路。
 *
 * 续接：用本体 sessionId 强制为 CLI 的 session id（`--session-id`），后续回合 `--resume` 直接续上
 * `~/.claude` 里的会话历史（不迁库）；rootfs 重装后 CLI 侧历史丢失，检测到续接失败自动转全新回合重试一次。
 * 权限：`--permission-prompt-tool` 经 [PermissionBridgeServer] 桥到本体授权中心。
 * 智能体：App 已启用子代理（设置 → 子代理）随回合生成到桥目录 agents.json 并经 `--agents` 传入
 * （能力全域打通批二），不写用户的 `.claude/agents/`；两侧工具/模型命名体系不同，只映射描述与提示词。
 */
@Singleton
class ClaudeCodeBridge @Inject constructor(
    private val containerEngine: LinuxContainerEngine,
    private val permissionBridgeServer: PermissionBridgeServer,
    cliAgentManager: CliAgentManager,
    private val fileAccess: FileAccessProvider,
    pathHomeResolver: PathHomeResolver,
    containerSettingsRepository: ContainerSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val agentDefinitionRepository: com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository,
    private val deliveryRegistry: CliDeliveryRegistry,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val todoMirror: EngineTodoMirror
) : AbstractCliBridge(cliAgentManager, pathHomeResolver, containerSettingsRepository) {

    override val cliCommand: String = "claude"

    private companion object {
        const val TAG = "ClaudeCodeBridge"

        /** aicode_bridge 的交付工具名；CLI 侧实际工具名形如 mcp__aicode_bridge__send_file，用包含匹配。 */
        const val SEND_FILE_TOOL = "send_file"

        /** Claude 原生待办工具名：todos 数组经 EngineTodoMirror 镜像进 App 待办表。 */
        const val CLAUDE_TODO_TOOL = "TodoWrite"

        /** 后台子代理完成时 CLI 注入的纯文本通知标签（headless stream-json 的 user 消息）。 */
        const val TASK_NOTIFICATION_TAG = "<task-notification>"

        const val MCP_SERVER_NAME = "permission_bridge"
        const val MCP_TOOL_NAME = "request_permission"

        /** 两行输出之间的空闲上限：claude 执行长工具期间 stdout 可能长时间静默（但 stderr 心跳仍在），
         *  上限取 30 分钟并同时在 stderr 侧刷新活跃时间，避免误杀合法长任务。 */
        const val IDLE_TIMEOUT_MS = 30 * 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val assetsMutex = Mutex()

    // bridgeDir / containerWorkspacePath / dataBridgeScript 由 AbstractCliBridge 提供。
    // 注意：containerWorkspacePath 的语义说明——调用方传的 projectPath 是宿主真实目录
    //（仅用于 proot `-b` 绑定），直接透给容器内脚本会找不到，故桥接环境变量一律用容器视角路径。
    private fun bridgeScript(): String = bridgeDir() + "/claude-permission-bridge.mjs"
    private fun mcpConfigPath(): String = bridgeDir() + "/mcp-config.json"

    private fun ccKeyBase(providerId: String): String = providerId.replace(Regex("[^\\w.-]"), "_")

    /** 挂给 claude `--settings` 的私有设置（当前只有 apiKeyHelper）。 */
    private fun ccSettingsFile(): String = bridgeDir() + "/cc-settings.json"

    private fun ccKeyFile(providerId: String): String =
        bridgeDir() + "/cc-key-" + ccKeyBase(providerId) + ".key"

    /** 密钥经 claude 的 apiKeyHelper 从私有文件读（每回合重写，容器 rootfs 重置自愈）；写失败返回 null（回退 env 透传）。 */
    private suspend fun writeCcKeySettings(providerId: String, keyContent: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val keyFile = ccKeyFile(providerId)
                fileAccess.writeFile(keyFile, keyContent, overwrite = true)
                val settingsPath = ccSettingsFile()
                fileAccess.writeFile(settingsPath, """{"apiKeyHelper":"cat '$keyFile'"}""", overwrite = true)
                settingsPath
            }.onFailure { e -> FileLogger.w(TAG, "写引擎密钥文件失败: ${e.message}") }.getOrNull()
        }

    /** App 智能体定义生成给 claude `--agents` 的文件（App 管理区，不写用户 .claude/agents）。 */
    private fun agentsConfigPath(): String = bridgeDir() + "/agents.json"

    // 可用性/认证缓存字段与 isAvailable() 由 AbstractCliBridge 提供
    //（缓存 TTL：可用性 5 分钟，认证提示 60 秒级，登录态变化频率极低，避免每条消息拉起容器进程）。

    /**
     * 认证状态：null = 已可用（oauth 凭据在，或将透传 App 内 Anthropic key）；
     * 非 null = 未认证引导文案（供发送前拦截与引擎卡片展示）。
     * 探测为一条轻量 test 命令（不拉起 claude、不耗 token）。
     */
    suspend fun authHint(providerId: String? = null): String? {
        if (isRemote()) return null
        if (resolveCcProvider(providerId) != null) return null
        if (authHintCacheFresh()) cachedAuthHint?.let { return it }
        val hint = probeAuthHint()
        rememberAuthHint(hint)
        return hint
    }

    private suspend fun probeAuthHint(): String? {
        // 容器内展开 $HOME，避免硬编码 /root 与 PathHomeResolver 的 home 解析约定不一致
        val probe = runCatching {
            containerEngine.runCommandSyncIfReady(
                command = "test -f \"\$HOME/.claude/.credentials.json\" && echo present || echo absent",
                projectPath = null,
                timeoutMs = 8_000L
            )
        }.getOrNull() ?: return null // 容器未就绪等场景不拦截，交给可用性/流中错误链路
        if (probe.output.contains("present")) return null
        return "Claude Code 尚未登录：请二选一——① 在终端执行 aicode（或直接运行 claude）完成一次交互式登录；" +
            "② 在 设置 → 模型 添加 Anthropic 类型的提供商（OpenAI 类型也支持：厂商端点直连或本地协议转换）并填入 API Key（经私有文件注入，不进环境变量），切引擎时会自动透传给 claude。" +
            "若你已在容器内自行配置 ANTHROPIC_API_KEY，可忽略本提示。"
    }

    /** CC 引擎第三方直连解析三态：Anthropic 型直透（route=null, viaConverter=false）；OpenAI 型 host 命中
     *  厂商端点表（route≠null）自动换算；OpenAI 型未命中走本地协议转换网关（viaConverter=true）。 */
    private class CcResolution(
        val provider: com.aicode.feature.settings.domain.model.AIProviderConfig,
        val route: VendorAnthropicRoute?,
        val viaConverter: Boolean,
    )

    private suspend fun resolveCcProvider(providerId: String? = null): CcResolution? =
        runCatching {
            val all = aiProviderRepository.getAllProviders().first()
            fun usable(p: com.aicode.feature.settings.domain.model.AIProviderConfig) =
                p.isEnabled && p.hasUsableApiKey && !p.useFullUrl
            val match = providerId?.let { id ->
                all.firstOrNull { it.id == id && (usable(it) || (it.type == ProviderType.OPENAI && it.isEnabled && it.hasUsableApiKey)) }
            } ?: all.firstOrNull { it.type == ProviderType.ANTHROPIC && usable(it) }
                ?: all.firstOrNull { it.type == ProviderType.OPENAI && it.isEnabled && it.hasUsableApiKey }
            match?.let { p ->
                if (p.type == ProviderType.ANTHROPIC) CcResolution(p, null, false)
                else {
                    // 命中厂商端点表→直连（真 Key），未命中才走本地协议转换网关
                    val route = EngineVendorCompat.routeForHost(p.baseUrl)
                    CcResolution(p, route, route == null)
                }
            }
        }.getOrNull()

    /** 当前激活 profile（本地模式；与 McpManager 同一解析规则）。 */
    /**
     * 跑一个用户回合。命令文本即 [userRequest]；[model] 透传 `--model`（CLI 侧模型名或 App 供应商模型名）；
     * [providerId] 为会话选定的 App 供应商（对齐透传的 Key/baseURL，null = 引擎自带模型）；
     * [mode] 为会话工作模式，映射到 CLI 的 `--permission-mode` / `--permission-prompts`；
     * [images] 随消息以 base64 图片块传入；[attachments] 以容器路径告知（引擎用自身工具读取）；
     * [resume] 为 true 时先尝试 `--resume` 续接，失败自动转全新回合。
     */
    fun runTurn(
        sessionId: String,
        userRequest: String,
        projectPath: String?,
        model: String?,
        resume: Boolean,
        providerId: String? = null,
        engineMode: String? = null,
        images: List<com.aicode.feature.agent.domain.model.AgentImage> = emptyList(),
        attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList()
    ): Flow<AgentEvent> = flow {
        if (resume) {
            var emittedAny = false
            try {
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = true, providerId = providerId, engineMode = engineMode, images = images, attachments = attachments).collect {
                    emittedAny = true
                    emit(it)
                }
            } catch (e: ResumeSessionMissingException) {
                // 已向下游发过事件就不重试：重试会重复落库/重复展示（qa P2-3）
                if (emittedAny) throw RuntimeException("续接回合中途失败且已产出事件，不重试: ${e.message}", e)
                FileLogger.w(TAG, "续接会话缺失，转全新回合重试: ${e.message}")
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, engineMode = engineMode, images = images, attachments = attachments).collect { emit(it) }
            }
        } else {
            runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, engineMode = engineMode, images = images, attachments = attachments).collect { emit(it) }
        }
    }.catch { e ->
        emit(AgentEvent.Failed(error = e.message ?: e.javaClass.simpleName))
    }

    /** 供应商注入准备结果：本地网关已就绪、apiKeyHelper 设置已写。 */
    private class ProviderInjection(
        val provider: com.aicode.feature.settings.domain.model.AIProviderConfig,
        val route: VendorAnthropicRoute?,
        val viaConverter: Boolean,
        val settingsPath: String?,
        val converterBase: String?,
        val passthruBase: String?,
    )

    /**
     * 解析供应商并完成注入准备（起本地网关 + 写 apiKeyHelper 设置）。
     * 无可用供应商、或转换网关起不来时返回 null，调用方按未注入处理。
     */
    private suspend fun prepareProviderInjection(providerId: String?): ProviderInjection? {
        val cc = resolveCcProvider(providerId) ?: return null
        val p = cc.provider
        var settingsPath: String? = null
        var converterBase: String? = null
        var passthruBase: String? = null
        if (cc.viaConverter) {
            // 本地 OpenAI→Anthropic 协议转换网关：上游 Key 注册在 App 侧，不经过 claude 环境；
            // claude 需要非空 Key 才不走登录，写占位（网关忽略其认证头）
            val started = runCatching {
                val port = OpenAiCompatGateway.ensureStarted()
                val key = ccKeyBase(p.id)
                OpenAiCompatGateway.registerUpstream(key, p.baseUrl, p.firstUsableApiKey, p.useFullUrl)
                converterBase = OpenAiCompatGateway.converterBaseUrl(key, port)
            }.onFailure { e -> FileLogger.w(TAG, "启动协议转换网关失败: ${e.message}") }.isSuccess && converterBase != null
            if (!started) {
                // 网关不可用：不注入（占位 Key+OpenAI 模型名直连官方只会 401/400），交给上层报错
                FileLogger.w(TAG, "转换网关不可用，跳过供应商注入: ${p.id}")
                return null
            }
            settingsPath = writeCcKeySettings(p.id, "cc-openai-compat")
        } else {
            // 厂商路由/ANTHROPIC 型直连也经本地透传网关：真 Key 注册在 App 侧，网关替换认证头并补齐
            // 残缺流尾（deepseek 等端点长回答时流尾不干净，claude 会整段撤回或挂死，cc 实测语义）
            val upstreamBase = when {
                cc.route != null -> cc.route.baseUrl
                !p.useFullUrl -> p.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
                else -> ""
            }
            if (!upstreamBase.isNullOrBlank() && !upstreamBase.contains("api.anthropic.com")) {
                passthruBase = runCatching {
                    val port = OpenAiCompatGateway.ensureStarted()
                    val key = OpenAiCompatGateway.passthruKey(p.id)
                    OpenAiCompatGateway.registerUpstream(key, upstreamBase, p.firstUsableApiKey, fullUrl = false)
                    OpenAiCompatGateway.passthruBaseUrl(key, port)
                }.onFailure { e -> FileLogger.w(TAG, "启动透传网关失败: ${e.message}") }.getOrNull()
            }
            settingsPath = writeCcKeySettings(p.id, p.firstUsableApiKey)
        }
        return ProviderInjection(p, cc.route, cc.viaConverter, settingsPath, converterBase, passthruBase)
    }

    /**
     * 供应商侧 claude 环境变量：BaseUrl、档位映射、厂商变量；apiKeyHelper 已就位时不回退 Key 进 env。
     * 回合注入（runTurnOnce）与终端标签（buildCliTabCommand）共用，避免两处漂移。
     */
    private fun providerEnv(inj: ProviderInjection, model: String?): Map<String, String> = buildMap {
        val p = inj.provider
        if (inj.settingsPath == null && !inj.viaConverter) {
            put("ANTHROPIC_API_KEY", p.firstUsableApiKey) // 多 Key 模式恒取首把可用 Key，与 App 内轮换调度不同步（见 P2-6）
        }
        val base = when {
            inj.viaConverter -> inj.converterBase
            else -> inj.passthruBase
                ?: inj.route?.baseUrl
                ?: p.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        }
        if (!base.isNullOrEmpty() && !base.contains("api.anthropic.com")) {
            put("ANTHROPIC_BASE_URL", base)
        }
        // 第三方端点不认 claude-* 档位别名，不映射的话子代理的 haiku 档请求直接 400；
        // 厂商路由用表内档位，转换器路径无 fastModel 概念，全部档位映射到同一模型
        // 模型串同样条件剥前缀（与 --model 同源，档位 env 不能漏）
        val main = model?.takeIf { it.isNotBlank() }?.let { CliModelRef.bareModelName(it, p.name, p.id) }
            ?: inj.route?.defaultModel
            ?: p.selectedModel.takeIf { it.isNotBlank() }?.let { CliModelRef.bareModelName(it, p.name, p.id) }
            ?: p.models.firstOrNull { it.isNotBlank() }
        val fast = inj.route?.fastModel ?: main
        if (inj.route != null || inj.viaConverter || inj.passthruBase != null) {
            if (main != null) {
                put("ANTHROPIC_MODEL", main)
                put("ANTHROPIC_DEFAULT_OPUS_MODEL", main)
                put("ANTHROPIC_DEFAULT_SONNET_MODEL", main)
            }
            fast?.let { f ->
                put("ANTHROPIC_DEFAULT_HAIKU_MODEL", f)
                put("ANTHROPIC_SMALL_FAST_MODEL", f)
            }
            inj.route?.extraEnv?.forEach { (k, v) -> put(k, v) }
        }
    }

    /**
     * 顶栏「Claude Code」终端标签的供应商注入：复用同一套供应商解析/网关/apiKeyHelper。
     * [providerId] 传 App 默认供应商（避免“列表第一个可用”选中失效供应商）；无可用供应商时返回 null，标签按裸命令启动。
     */
    suspend fun cliTabInjection(providerId: String? = null): CliTabInjection? = runCatching {
        val inj = prepareProviderInjection(providerId) ?: return@runCatching null
        val env = providerEnv(inj, model = null) + ("CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT" to "1")
        CliTabInjection(env = env, argsSuffix = inj.settingsPath?.let { " --settings '$it'" } ?: "")
    }.onFailure { e -> FileLogger.w(TAG, "CLI 标签供应商注入失败: ${e.message}") }.getOrNull()

    private fun runTurnOnce(
        sessionId: String,
        userRequest: String,
        projectPath: String?,
        model: String?,
        resume: Boolean,
        providerId: String? = null,
        engineMode: String? = null,
        images: List<com.aicode.feature.agent.domain.model.AgentImage> = emptyList(),
        attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList()
    ): Flow<AgentEvent> = flow {
        val bridgeToken = permissionBridgeServer.newToken()
        val port = permissionBridgeServer.ensureStarted(token = bridgeToken, sessionId = sessionId)
        writeBridgeAssets(port, sessionId, token = bridgeToken, projectPath = projectPath)
        val hasCliAgents = writeCliAgentsConfig()

        // 供应商解析前置：settings（apiKeyHelper）与 env（BaseUrl/档位映射）两处共用
        val ccProvider = prepareProviderInjection(providerId)
        val ccSettingsPath = ccProvider?.settingsPath

        // 工作模式 → CLI 原生权限模式。engineMode 为空时取该引擎默认档（manual），
        // 保证与改造前行为一致。args 与环境变量两处共用。
        val nativeMode = EngineModeCatalog.normalize(AgentEngines.CLAUDE_CODE, engineMode)

        val args = buildList {
            add("-p")
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--input-format"); add("stream-json")
            add("--include-partial-messages")
            add("--permission-mode"); add(nativeMode)
            // 无人应答的档位（plan / auto / dontAsk / bypassPermissions）：prompts=none 让请求
            // 直接拒绝而非挂起；manual / acceptEdits 保持默认 host 走权限桥。
            val prompts = EngineModeMapping.claudePermissionPrompts(EngineModeCatalog.semantic(AgentEngines.CLAUDE_CODE, nativeMode))
            if (prompts != "host") { add("--permission-prompts"); add(prompts) }
            // 用本体 sessionId 对齐 CLI 侧会话 id：新回合 --session-id 设定，续接回合 --resume 直接续上
            if (resume) {
                add("--resume"); add(sessionId)
            } else {
                add("--session-id"); add(sessionId)
            }
            add("--permission-prompt-tool"); add("mcp__${MCP_SERVER_NAME}__${MCP_TOOL_NAME}")
            add("--mcp-config"); add(mcpConfigPath())
            // 密钥不进环境变量：claude 的每个 Bash 子进程都继承 env，模型一句 env 就能把 key 打进
            // 会话记录（cccodroid 实测踩坑）。apiKeyHelper 输出同时放 x-api-key 与 Bearer 两种头，
            // 官方与第三方（Bearer）全部覆盖
            ccSettingsPath?.let { add("--settings"); add(it) }
            // 中文系统下让模型用中文回复（Claude Code 界面本身没有本地化，官方 issue #31413；
            // settings.json 的 language 只影响回复语言且不随命令行生效，用原生 append 扩展点最直接）。
            // 同时注入知识块：项目知识库索引 + 世界书命中（与内置引擎同源），并附使用引导
            val systemExtras = listOfNotNull(
                EngineLocale.replyLanguageDirective(),
                CliKnowledgeInjection.build(projectPath ?: "", userRequest ?: "")
            )
            if (systemExtras.isNotEmpty()) {
                add("--append-system-prompt"); add(systemExtras.joinToString("\n\n"))
            }
            // App 智能体定义随回合生成（能力全域打通批二）；无可用定义时不传该 flag
            if (hasCliAgents) { add("--agents"); add(agentsConfigPath()) }
            if (!model.isNullOrBlank()) {
                // 会话存的模型串可能带「供应商名或id/」前缀；仅前缀命中当前供应商时剥离（z-ai/ 等真 id 不动）
                val bareModel = CliModelRef.bareModelName(model, ccProvider?.provider?.name, ccProvider?.provider?.id)
                add("--model"); add(bareModel)
            }
        }
        val extraEnv = buildMap {
            put("PERMISSION_BRIDGE_PORT", port.toString())
            put("PERMISSION_BRIDGE_SESSION", sessionId)
            put("PERMISSION_BRIDGE_TOKEN", sessionId)
            // 本体能力桥的数据工具需要知道容器内 .aicode 根与当前项目根（容器内路径）
            put("AICODE_HOME", pathHomeResolver.aicodeRoot().trimEnd('/'))
            put("AICODE_PROJECT_ROOT", if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())
            // 供应商凭据经 apiKeyHelper 从私有文件读（不进 env）；env 只透 BaseUrl/档位映射/厂商变量。
            // 密钥文件写入失败的回合回退 env 透传保功能（转换器路径 env 里无真 Key，回退仅用于直透/厂商路由）
            ccProvider?.let { cc -> putAll(providerEnv(cc, model)) }
            // claude CLI 2.1.29x 对模型目录外的第三方模型名启用「上下文窗口强制」：不发请求、
            // 等待 catalog 校验 → 会话无响应（实测）。豁免后恢复「等 API」行为，对官方模型无影响。
            put("CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT", "1")
            // 容器内 CLI 以 root 运行，而 claude 对 root 下的 bypassPermissions（AUTO）直接秒退
            // （"--dangerously-skip-permissions cannot be used with root/sudo privileges"）。
            putAll(EngineModeMapping.claudeExtraEnv(nativeMode))
        }

        FileLogger.i(TAG, "启动 claude 桥接进程: resume=$resume model=$model project=$projectPath auth=helper:${ccSettingsPath != null} envFallback:${extraEnv.containsKey("ANTHROPIC_API_KEY")}")
        // 进程孵化阻塞，移出主线程（发版 optimizer P1-1）
        val process = withContext(Dispatchers.IO) {
            containerEngine.startStdioProcess("claude", args, projectPath, extraEnv, resolveRuntimeProfile())
        }

        // 取消即杀进程：本体停止按钮取消 collect 时，进程必须立刻死，否则僵尸 claude 继续烧 token
        val killOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { process.destroy() }
        }

        val stderrTail = StringBuilder()
        val lastActivity = AtomicLong(System.currentTimeMillis())
        val stderrDrain = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    // stderr 心跳同样算活跃：claude 执行长工具期间 stdout 静默但 stderr 仍有诊断输出
                    lastActivity.set(System.currentTimeMillis())
                    synchronized(stderrTail) {
                        stderrTail.appendLine(line)
                        if (stderrTail.length > 8000) stderrTail.delete(0, stderrTail.length - 4000)
                    }
                }
            }
        }

        // 空闲看门狗：本回合无任何输出超过上限即杀进程（stdout EOF 后走早退分类 → Failed）。
        val watchdog = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                delay(60_000)
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    FileLogger.w(TAG, "空闲超时（${IDLE_TIMEOUT_MS / 60_000} 分钟无输出），中止 claude 进程")
                    runCatching { process.destroy() }
                    break
                }
            }
        }

        try {
            // 回合只有一个用户输入：写完即关 stdin，claude 处理完输出 result 后自行退出。
            // 有图片时 content 用块数组（text + image base64 块），附件以容器路径告知（引擎用自身工具读取）
            withContext(Dispatchers.IO) {
                val w = process.outputStream.bufferedWriter()
                val message = buildJsonObject {
                    put("role", "user")
                    if (images.isEmpty()) {
                        put("content", userRequest + attachmentSuffix(attachments))
                    } else {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", userRequest + attachmentSuffix(attachments))
                            })
                            // Anthropic 单图约 5MiB 上限：超限跳过并记日志，避免回合以不直观的 API 错误收场（qa P2-4）
                            val maxBase64 = 6 * 1024 * 1024 // base64 长度折算解压后约 4.5MiB
                            for (img in images) {
                                if (img.base64Data.length > maxBase64) {
                                    FileLogger.w(TAG, "图片超过 Anthropic 大小限制（base64 ${img.base64Data.length} 字符），跳过")
                                    continue
                                }
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", img.mimeType)
                                        put("data", img.base64Data)
                                    })
                                })
                            }
                        })
                    }
                }
                val payload = buildJsonObject {
                    put("type", "user")
                    put("message", message)
                }
                w.write(payload.toString())
                w.write("\n")
                w.flush()
                w.close()
            }

            var gotResult = false
            val acc = TurnAccumulator(sessionId)
            val reader = process.inputStream.bufferedReader()
            // claude 的启动类致命错误（如 resume 会话不存在）可能打到 stdout 而非 stderr，
            // 退出分类必须两者都看，否则“续接会话缺失”会被当成普通提前退出、错过全新回合重试
            val stdoutTail = StringBuilder()
            while (!gotResult) {
                val line = withContext(Dispatchers.IO) {
                    lastActivity.set(System.currentTimeMillis())
                    reader.readLine()
                } ?: break
                if (line.isBlank()) continue
                stdoutTail.appendLine(line)
                if (stdoutTail.length > 4000) stdoutTail.delete(0, stdoutTail.length - 4000)
                // claude 2.1.29x 把启动类失败（resume 会话缺失、未认证等）包装成 error_during_execution
                // 的 result 事件优雅输出而非裸退出——本回合无任何内容输出时按启动失败分类，
                // 否则 classifyEarlyExit 被绕过、无续接重试永不触发
                if (!acc.emittedAny && line.contains("\"error_during_execution\"")) {
                    gotResult = true
                    throw classifyEarlyExit(
                        synchronized(stderrTail) { stderrTail.toString() } + "\n" + stdoutTail.toString()
                    )
                }
                for (event in parseLine(line, acc)) {
                    acc.emittedAny = true
                    emit(event)
                    if (event is AgentEvent.Completed || event is AgentEvent.Failed) gotResult = true
                }
                // 每条 assistant 消息 = 一次真实 API 调用：按归一化后的用量补一条调用记录，
                // 让「设置 → Token 统计」能看到外部引擎（此前桥接完全不写这张表）。
                acc.pendingUsage?.let { usage ->
                    acc.pendingUsage = null
                    if (usage.inputTokens > 0 || usage.outputTokens > 0) {
                        runCatching {
                            llmCallRecordDao.insert(
                                LlmCallRecordEntity(
                                    sessionId = sessionId,
                                    providerId = AgentEngines.CLAUDE_CODE,
                                    model = acc.model ?: model,
                                    kind = "chat",
                                    inputTokens = usage.inputTokens,
                                    outputTokens = usage.outputTokens,
                                    cachedInputTokens = usage.cachedInputTokens,
                                    cacheCreationTokens = usage.cacheCreationTokens,
                                    status = "success",
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        }.onFailure { e -> FileLogger.w(TAG, "写入 Claude 调用用量失败: ${e.message}") }
                    }
                }
                // TodoWrite 全量待办镜像落库（快照式替换）：待办卡片的拉起/刷新/自动收起走既有 Flow
                acc.pendingTodos.takeIf { it.isNotEmpty() }?.let { todos ->
                    acc.pendingTodos = emptyList()
                    todoMirror.mirror(sessionId, todos)
                }
            }

            if (!gotResult) {
                val tail = synchronized(stderrTail) { stderrTail.toString() } + "\n" + stdoutTail.toString()
                throw classifyEarlyExit(tail)
            }
        } finally {
            killOnCancel?.dispose()
            stderrDrain.cancel()
            watchdog.cancel()
            permissionBridgeServer.removeToken(bridgeToken)
            runCatching { process.destroy() }
        }
    }

    /** 无 result 行即退出：区分「续接会话不存在」（可重试）与其它启动/执行失败。 */
    private fun classifyEarlyExit(tail: String): Exception {
        val lower = tail.lowercase()
        // 认证类失败（未登录/无 API key）：给出可操作指引，而不是让用户看裸报错
        if (lower.contains("api key") || lower.contains("login") || lower.contains("unauthorized") || lower.contains("not logged in") || lower.contains("oauth")) {
            return RuntimeException(
                "Claude Code 尚未认证：请在终端打开一次交互式 claude 并完成登录（或配置 ANTHROPIC_API_KEY），" +
                    "然后回到会话重发消息。原始错误：" + tail.takeLast(300).trim()
            )
        }
        return if (lower.contains("no conversation found") || lower.contains("session not found") ||
            (lower.contains("resume") && lower.contains("not found"))
        ) {
            ResumeSessionMissingException(tail.takeLast(400).trim())
        } else {
            RuntimeException("Claude Code 提前退出: " + tail.takeLast(400).trim().ifEmpty { "（无 stderr 输出）" })
        }
    }

    // ── stream-json → AgentEvent 翻译 ────────────────────────────────

    /** 单回合累积状态：流式增量需拼成「全量累积文本」才能对齐 AgentEvent 契约（消费端是整体替换）。 */
    private class TurnAccumulator(val sessionId: String) {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        /** tool_use id → 工具名：tool_result 回来时补齐 Finished 的 toolName。 */
        val toolNames = mutableMapOf<String, String>()
        var emittedAny = false
        /** 当前 assistant 消息自带的模型名（CLI 实际调用模型），用于用量明细。 */
        var model: String? = null
        /** 刚解析到的单次调用用量；消费端落账后清空，避免同一行重复写记录。 */
        var pendingUsage: NormalizedUsage? = null
        /** 刚解析到的 TodoWrite 全量待办；消费端镜像落库后清空（与 pendingUsage 同一节奏）。 */
        var pendingTodos: List<EngineTodoMirror.NativeTodo> = emptyList()
        /** 已后台启动、尚未收到完成通知的子代理 tool_use id（Claude 异步 Task）。 */
        val asyncToolIds = mutableSetOf<String>()
        fun resetBlocks() { text.clear(); reasoning.clear() }
    }

    private fun parseLine(line: String, acc: TurnAccumulator): List<AgentEvent> {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        if (obj == null) {
            // stdout 只走协议；非 JSON 行多为启动横幅，记 INFO 便于排查
            FileLogger.i(TAG, "跳过非 JSON 行: ${line.take(200)}")
            return emptyList()
        }
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "stream_event" -> parseStreamEvent(obj, acc)
            "assistant" -> parseAssistantMessage(obj, acc)
            "user" -> parseToolResult(obj, acc)
            "system" -> listOfNotNull(parseSystem(obj))
            "result" -> parseResult(obj, acc)
            else -> emptyList()
        }
    }

    /**
     * 部分流事件：text/thinking 增量必须**累积后**下发（AssistantDelta/ReasoningDelta 契约是全量累积文本，
     * 消费端整体替换，直接吐单块会闪烁错乱——qa P0-1）；tool_use 块开始 → ToolCallPreparing。
     */
    private fun parseStreamEvent(obj: JsonObject, acc: TurnAccumulator): List<AgentEvent> {
        val event = obj["event"] as? JsonObject ?: return emptyList()
        return when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_start" -> {
                val block = event["content_block"] as? JsonObject
                if (block?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                    val name = block["name"]?.jsonPrimitive?.contentOrNull
                    // 参数流式阶段就把 Task/Agent 说成「正在启动子代理」，与内置引擎一致
                    if (name != null) {
                        listOf(AgentEvent.ToolCallPreparing(toolName = EngineSubagent.normalizeToolName(name)))
                    } else {
                        emptyList()
                    }
                } else emptyList()
            }
            "content_block_delta" -> {
                val delta = event["delta"] as? JsonObject ?: return emptyList()
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> {
                        acc.text.append(delta["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        listOf(AgentEvent.AssistantDelta(accumulated = acc.text.toString()))
                    }
                    "thinking_delta" -> {
                        acc.reasoning.append(delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
                        listOf(AgentEvent.ReasoningDelta(accumulated = acc.reasoning.toString()))
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    /** 完整 assistant 消息：AssistantText（带 tool_use 声明）→ 逐个补发 ToolCallStarted（qa P1-3）。 */
    private fun parseAssistantMessage(obj: JsonObject, acc: TurnAccumulator): List<AgentEvent> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val blocks = message["content"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (block in blocks) {
            val b = block as? JsonObject ?: continue
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(b["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "thinking" -> reasoning.append(b["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
                "tool_use" -> {
                    val input = b["input"] as? JsonObject ?: JsonObject(emptyMap())
                    val id = b["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = b["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    // Claude 的 Task/Agent 归一成内置的 task：图标、场景文案、成员名复用同一条链路
                    val displayName = EngineSubagent.normalizeToolName(name)
                    acc.toolNames[id] = displayName
                    // Claude 的 TodoWrite（todos 数组）镜像进 App 待办表：待办卡片由此拉起/刷新/自动收起
                    if (name.equals(CLAUDE_TODO_TOOL, ignoreCase = true)) {
                        acc.pendingTodos = todoMirror.parseTodos(input["todos"] as? kotlinx.serialization.json.JsonArray)
                    }
                    toolCalls.add(ToolCall(id = id, name = displayName, arguments = input))
                }
            }
        }
        val rawUsage = message["usage"] as? JsonObject
        // CLI 的 input_tokens 只含未命中缓存的部分；按 Anthropic 语义补齐 cache_read / cache_creation，
        // 否则会话总账、上下文占用与缓存命中率都会严重偏低（实测上下文 30 万时只记 1 千）。
        val usage = UsageNormalizer.anthropic(
            input = rawUsage?.intOf("input_tokens") ?: 0,
            cacheRead = rawUsage?.intOf("cache_read_input_tokens"),
            cacheCreation = rawUsage?.intOf("cache_creation_input_tokens"),
            output = rawUsage?.intOf("output_tokens") ?: 0
        )
        acc.model = message["model"]?.jsonPrimitive?.contentOrNull ?: acc.model
        acc.pendingUsage = usage
        val events = mutableListOf<AgentEvent>(
            AgentEvent.AssistantText(
                content = text.toString(),
                toolCalls = toolCalls,
                reasoning = reasoning.toString(),
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cachedInputTokens = usage.cachedInputTokens
            )
        )
        // 本体链路靠 ToolCallStarted 落「执行中」工具行与参数预览：CLI 工具在 CLI 侧执行，
        // 这里在声明时补发 Started，Finished 由 tool_result 回来时配对。
        toolCalls.forEach { tc ->
            events.add(AgentEvent.ToolCallStarted(id = tc.id, toolName = tc.name, argsPreview = tc.arguments.toString()))
        }
        // 本条消息的流式累积已随 AssistantText 落库，下一条消息从零累积
        acc.resetBlocks()
        return events
    }

    /** CLI 自执行工具后回传的 tool_result（以 user 角色出现）→ 每个结果一条 ToolCallFinished（qa P1-2）。 */
    private fun parseToolResult(obj: JsonObject, acc: TurnAccumulator): List<AgentEvent> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val content = message["content"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val events = mutableListOf<AgentEvent>()
        // 后台子代理完成时 CLI 会把 <task-notification> 以纯文本 user 消息注入。
        // 原实现只认 tool_result 块，这类通知被静默丢弃；这里把它配对到待完成的工具行。
        val notification = content.mapNotNull { block ->
            (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.firstOrNull { it.contains(TASK_NOTIFICATION_TAG) }
        if (notification != null && acc.asyncToolIds.isNotEmpty()) {
            val id = acc.asyncToolIds.first()
            acc.asyncToolIds.remove(id)
            events += AgentEvent.ToolCallFinished(
                id = id,
                toolName = acc.toolNames[id] ?: EngineSubagent.TOOL_NAME,
                result = notification.take(4000),
                isError = notification.contains("status>failed", ignoreCase = true)
            )
        }
        content.forEach { block ->
            val b = block as? JsonObject ?: return@forEach
            if (b["type"]?.jsonPrimitive?.contentOrNull != "tool_result") return@forEach
            val toolUseId = b["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val name = acc.toolNames[toolUseId] ?: ""
            val failed = b["is_error"]?.jsonPrimitive?.contentOrNull == "true" ||
                (b["is_error"] as? JsonPrimitive)?.content == "true"
            val resultText = extractText(b["content"])
            // 后台子代理：Task 工具立即返回 async_launched，子代理仍在跑。
            // 此时不能把工具行判为完成，保持「正在启动子代理」，等完成通知或回合结束再收尾。
            if (!failed && EngineSubagent.isSubagentTool(name) && EngineSubagent.isAsyncLaunch(resultText)) {
                acc.asyncToolIds += toolUseId
                return@forEach
            }
            // send_file 的交付语义在 App 侧登记（见 PermissionBridgeServer.handleSendFile），
            // 这里取出来挂成文件卡片；失败时不取，避免把半份交付渲染出来。
            val attachments = if (!failed && name.contains(SEND_FILE_TOOL)) {
                deliveryRegistry.drain(acc.sessionId)
            } else {
                emptyList()
            }
            events += AgentEvent.ToolCallFinished(
                id = toolUseId,
                toolName = name,
                result = resultText,
                isError = failed,
                attachments = attachments
            )
        }
        return events
    }

    /** tool_result.content 可能是字符串或 [{type:"text",text:...}] 数组。 */
    private fun extractText(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull ?: ""
        is kotlinx.serialization.json.JsonArray -> content.joinToString("\n") { item ->
            (item as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        }
        else -> ""
    }

    /** system/init：确认 MCP 权限桥已连上（连不上时权限请求会被 claude 自动拒绝，提前告知）。 */
    private fun parseSystem(obj: JsonObject): AgentEvent? {
        if (obj["subtype"]?.jsonPrimitive?.contentOrNull != "init") return null
        val mcpServers = obj["mcp_servers"] as? kotlinx.serialization.json.JsonArray
        val bridge = mcpServers?.firstOrNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull == MCP_SERVER_NAME } as? JsonObject
        val status = bridge?.get("status")?.jsonPrimitive?.contentOrNull
        if (status != null && status != "connected") {
            FileLogger.w(TAG, "权限桥 MCP 未连上（status=$status），CLI 权限请求将失败")
        }
        return null
    }

    /** 回合收尾：success → Completed；error_* / is_error → Failed。 */
    private fun parseResult(obj: JsonObject, acc: TurnAccumulator): List<AgentEvent> {
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val isError = (obj["is_error"] as? JsonPrimitive)?.content == "true" || subtype.startsWith("error")
        // 回合结束前仍未收到通知的后台子代理：收尾工具行，避免 UI 永远停在「执行中」。
        // 子代理进程可能仍在 CLI 里跑，文案如实说明，不伪造成功/失败。
        val events = mutableListOf<AgentEvent>()
        acc.asyncToolIds.forEach { id ->
            events += AgentEvent.ToolCallFinished(
                id = id,
                toolName = acc.toolNames[id] ?: EngineSubagent.TOOL_NAME,
                result = "子代理已转入后台运行，本轮在它回报前结束；完成情况以引擎日志为准。",
                isError = false
            )
        }
        acc.asyncToolIds.clear()
        if (isError) {
            val detail = obj["result"]?.jsonPrimitive?.contentOrNull
                ?: obj["error"]?.jsonPrimitive?.contentOrNull
                ?: (obj["errors"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: "Claude Code 运行失败（$subtype）"
            // claude 2.1.29x 把启动类失败包装成 error_during_execution 的 result 事件而非裸退出；
            // 无论本回合是否已产出流式事件都按启动失败分类，是否重试由外层「已产出事件」门把关
            if (subtype == "error_during_execution") throw classifyEarlyExit(detail)
            events += AgentEvent.Failed(error = detail)
        } else {
            events += AgentEvent.Completed
        }
        return events
    }

    private fun JsonObject.intOf(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    // ── 桥接资产（脚本 + MCP 配置）──────────────────────────────────

    /**
     * 把 App 已启用子代理写成本回合的 `--agents` 文件；返回是否有可映射定义。
     * 定义枚举要读容器内文件，故建在 IO 线程；失败不阻断回合（只是本回合不带 App 智能体）。
     */
    private suspend fun writeCliAgentsConfig(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val mapping = CliAgentMapping.partition(agentDefinitionRepository.listEnabled().map { it.definition })
            mapping.skipped.forEach { FileLogger.w(TAG, "子代理 ${it.agent.name} 不映射到 claude：${it.reason}") }
            if (mapping.mapped.isNotEmpty()) {
                fileAccess.writeFile(
                    agentsConfigPath(),
                    CliAgentMapping.claudeAgentsJson(mapping.mapped).toString(),
                    overwrite = true
                )
            }
            mapping.mapped.isNotEmpty()
        }.getOrElse { e ->
            FileLogger.w(TAG, "生成 claude 智能体配置失败，本回合不带 App 智能体", e)
            false
        }
    }

    /** 每回合确保脚本与 MCP 配置就位（幂等写入，端口/会话可能变化）。 */
    private suspend fun writeBridgeAssets(port: Int, sessionId: String, token: String, projectPath: String?) = assetsMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
            fileAccess.writeFile(bridgeScript(), BRIDGE_SCRIPT_SOURCE, overwrite = true)
            fileAccess.writeFile(dataBridgeScript(), AICODE_BRIDGE_SCRIPT_SOURCE, overwrite = true)
            val bridgeEnv = buildJsonObject {
                put("PERMISSION_BRIDGE_PORT", port.toString())
                put("PERMISSION_BRIDGE_SESSION", sessionId)
                put("PERMISSION_BRIDGE_TOKEN", token)
                put("AICODE_HOME", pathHomeResolver.aicodeRoot().trimEnd('/'))
                put("AICODE_PROJECT_ROOT", if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())
            }
            val config = buildJsonObject {
                put("mcpServers", buildJsonObject {
                    put(MCP_SERVER_NAME, buildJsonObject {
                        put("type", "stdio")
                        put("command", "node")
                        put("args", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(bridgeScript()))))
                        put("env", bridgeEnv)
                    })
                    // 本体能力桥：记忆/规则/技能（容器文件直读）+ 账本查询与状态推进（经 App 侧数据通道）
                    put("aicode_bridge", buildJsonObject {
                        put("type", "stdio")
                        put("command", "node")
                        put("args", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(dataBridgeScript()))))
                        put("env", bridgeEnv)
                    })
                    // 用户在 App 配置的 MCP 服务器随回合自动映射（能力全域打通批一）：
                    // 本文件每回合由 App 重新生成，天然是 App 管理区，无覆盖冲突；OAuth 型服务器无法委托凭据，跳过
                    for (server in mcpConfigRepository.getEffectiveServers()) {
                        if (!server.enabled || !McpServerConfig.isValidName(server.name)) continue
                        if (server.name == MCP_SERVER_NAME || server.name == "aicode_bridge") continue
                        if (server.auth != null) {
                            FileLogger.w(TAG, "MCP 服务器 ${server.name} 使用 OAuth，暂不映射到 CLI 引擎")
                            continue
                        }
                        if (server.isStdio) {
                            put(server.name, buildJsonObject {
                                put("type", "stdio")
                                put("command", server.command!!)
                                put("args", kotlinx.serialization.json.JsonArray(server.args.map { JsonPrimitive(it) }))
                                if (server.env.isNotEmpty()) {
                                    put("env", buildJsonObject { for ((k, v) in server.env) put(k, v) })
                                }
                            })
                        } else if (!server.url.isNullOrBlank()) {
                            put(server.name, buildJsonObject {
                                put("type", "http")
                                put("url", server.url)
                                if (server.headers.isNotEmpty()) {
                                    put("headers", buildJsonObject { for ((k, v) in server.headers) put(k, v) })
                                }
                            })
                        }
                    }
                })
            }
            fileAccess.writeFile(mcpConfigPath(), config.toString(), overwrite = true)
            }
        }.onFailure { e ->
            FileLogger.e(TAG, "写桥接资产失败", e)
            throw RuntimeException("初始化权限桥资产失败: ${e.message}", e)
        }
    }
}

/** 附件以容器路径告知 CLI 引擎（引擎用自身文件工具读取），拼在正文末尾；三个桥共用。 */
internal fun attachmentSuffix(attachments: List<com.aicode.feature.agent.presentation.AgentAttachment>): String {
    if (attachments.isEmpty()) return ""
    return "\n\n" + attachments.joinToString("\n") { a ->
        "[附件：${a.fileName}（${a.mimeType}，${a.sizeBytes} 字节），容器路径：${a.containerPath}]"
    }
}

/** 随包内置的本体能力桥 MCP 小服务：容器内文件直读 + 经 App 数据通道查账本（零依赖 Node 脚本）。 */
internal const val AICODE_BRIDGE_SCRIPT_SOURCE = """/** AiCode 本体能力桥：记忆/规则/技能（容器内文件）+ 标注与修复账本（经 App 数据通道）。 */
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';

const HOME = process.env.AICODE_HOME || '/root';
const PROJECT = process.env.AICODE_PROJECT_ROOT || '';
const PORT = parseInt(process.env.PERMISSION_BRIDGE_PORT || '0', 10);
const SESSION = process.env.PERMISSION_BRIDGE_SESSION || '';
const TOKEN = process.env.PERMISSION_BRIDGE_TOKEN || '';
const TIMEOUT_MS = 5 * 60 * 1000;

const MEMORY_DIRS = [path.join(HOME, 'memory')].concat(PROJECT ? [path.join(PROJECT, '.aicode', 'memory')] : []);
const SKILL_DIRS = [path.join(HOME, 'skills')].concat(PROJECT ? [path.join(PROJECT, '.aicode', 'skills')] : []);
const RULE_FILES = [path.join(HOME, 'AGENTS.md'), path.join(HOME, 'CLAUDE.md')]
  .concat(PROJECT ? [path.join(PROJECT, '.aicode', 'AGENTS.md'), path.join(PROJECT, '.aicode', 'CLAUDE.md')] : []);

function readIfExists(p) { try { return fs.readFileSync(p, 'utf8'); } catch { return null; } }
function listMdFiles(dir) {
  try { return fs.readdirSync(dir).filter((f) => f.endsWith('.md')).map((f) => path.join(dir, f)); } catch { return []; }
}

function memoryList() {
  const out = [];
  for (const dir of MEMORY_DIRS) {
    const scope = dir.startsWith(HOME) ? 'global' : 'project';
    for (const f of listMdFiles(dir)) {
      const st = fs.statSync(f);
      out.push(scope + ':' + path.basename(f) + ' (' + st.size + 'B, ' + st.mtime.toISOString() + ')');
    }
  }
  return out.length ? out.join('\n') : '（暂无记忆文件）';
}

function resolveMemory(p) {
  // realpathSync 归一后再比对前缀：挡 .. 与目录内符号链接逃逸（纵深防御，claude 本就有完整 shell）
  let r;
  try { r = fs.realpathSync(path.resolve(p)); } catch { return null; }
  if (!MEMORY_DIRS.some((dir) => { try { return r.startsWith(fs.realpathSync(dir) + path.sep); } catch { return false; } })) return null;
  return r;
}

function skillDirsOf(name) {
  if (!/^[A-Za-z0-9_-]+$/.test(name)) return null;
  return SKILL_DIRS.map((d) => path.join(d, name, 'SKILL.md')).find((p) => fs.existsSync(p)) || null;
}

function skillList() {
  const out = [];
  for (const dir of SKILL_DIRS) {
    const scope = dir.startsWith(HOME) ? 'global' : 'project';
    let names = [];
    try { names = fs.readdirSync(dir); } catch { continue; }
    for (const n of names) {
      const meta = readIfExists(path.join(dir, n, 'SKILL.md'));
      if (meta == null) continue;
      const desc = (meta.match(/^description:\s*(.+)$/m) || [, ''])[1].trim();
      out.push(scope + ':' + n + ' - ' + desc.slice(0, 120));
    }
  }
  return out.length ? out.join('\n') : '（暂无技能）';
}

function askApp(tool, input) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; try { sock.destroy(); } catch {} resolve(v); } };
    const sock = net.connect(PORT, '127.0.0.1', () => {
      sock.write(JSON.stringify({ token: TOKEN, session: SESSION, kind: 'data', tool_name: tool, input }) + '\n');
    });
    let buf = '';
    sock.on('data', (d) => {
      buf += d.toString();
      const i = buf.indexOf('\n');
      if (i >= 0) { try { finish(JSON.parse(buf.slice(0, i))); } catch { finish({ ok: false, message: '数据通道响应解析失败' }); } }
    });
    sock.on('error', (e) => finish({ ok: false, message: '数据通道连接失败: ' + e.message }));
    setTimeout(() => finish({ ok: false, message: '数据通道响应超时' }), TIMEOUT_MS);
  });
}
let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (line) handleLine(line);
  }
});

function send(obj) { try { process.stdout.write(JSON.stringify(obj) + '\n'); } catch {} }

const TOOLS = [
  { name: 'memory_list', description: 'List memory files (global and current project) with sizes and mtime', inputSchema: { type: 'object', properties: {} } },
  { name: 'memory_read', description: 'Read one memory file by absolute path (must be under a memory dir)', inputSchema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] } },
  { name: 'memory_write', description: 'Create or update a memory file under a memory dir', inputSchema: { type: 'object', properties: { path: { type: 'string' }, content: { type: 'string' } }, required: ['path', 'content'] } },
  { name: 'rules_read', description: 'Read global and project rule files (AGENTS.md / CLAUDE.md)', inputSchema: { type: 'object', properties: {} } },
  { name: 'skills_list', description: 'List installed skills (name + description, global and project)', inputSchema: { type: 'object', properties: {} } },
  { name: 'skill_read', description: 'Read one skill SKILL.md by skill name', inputSchema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] } },
  { name: 'ledger_list', description: 'List annotation ledger and fix ledger entries with statuses (queried via the AiCode app)', inputSchema: { type: 'object', properties: {} } },
  { name: 'ledger_advance', description: 'Advance an annotation and its linked fix-ledger entry to FIXING/REVIEW via the AiCode app (closing is user-only)', inputSchema: { type: 'object', properties: { annotationId: { type: 'string' }, target: { type: 'string', enum: ['FIXING', 'REVIEW'] } }, required: ['annotationId', 'target'] } },
  { name: 'recall_search', description: 'Full-text search archived history sessions via the AiCode app, including messages compacted out of context (FTS for ASCII, fuzzy for CJK)', inputSchema: { type: 'object', properties: { query: { type: 'string' }, scope: { type: 'string', enum: ['session', 'workspace', 'all'] }, limit: { type: 'integer' } }, required: ['query'] } },
  { name: 'graph_search', description: 'Search the project conclusion graph via the AiCode app: decisions, verified pitfalls and pending items already settled in this project, with their source sessions. Use recall_search for raw message details instead.', inputSchema: { type: 'object', properties: { query: { type: 'string' }, includeInvalid: { type: 'boolean' }, limit: { type: 'integer' } } } },
  { name: 'send_file', description: 'Deliver existing workspace files to the user: they appear as file cards in the chat (tap to open, APKs can be installed directly) and are listed on the Deliverables page. Use it when you finish a build or produce an artifact the user should receive. All paths must exist; the whole call fails if any path is invalid.', inputSchema: { type: 'object', properties: { paths: { type: 'array', items: { type: 'string' }, description: 'Container paths, e.g. ~/workspace/app/build/outputs/apk/release/app-release.apk' } }, required: ['paths'] } }
];

function toolsCall(name, args) {
  if (name === 'memory_list') return memoryList();
  if (name === 'memory_read') {
    const r = resolveMemory(args.path || '');
    if (r == null) return '错误：路径必须在记忆目录内';
    const c = readIfExists(r);
    return c == null ? '错误：文件不存在：' + r : c;
  }
  if (name === 'memory_write') {
    const r = resolveMemory(args.path || '');
    if (r == null) return '错误：路径必须在记忆目录内';
    fs.mkdirSync(path.dirname(r), { recursive: true });
    fs.writeFileSync(r, String(args.content || ''), 'utf8');
    return '已写入 ' + r + '（' + String(args.content || '').length + ' 字符）';
  }
  if (name === 'rules_read') {
    const out = [];
    for (const f of RULE_FILES) { const c = readIfExists(f); if (c != null) out.push('=== ' + f + ' ===\n' + c); }
    return out.length ? out.join('\n\n') : '（未找到规则文件）';
  }
  if (name === 'skills_list') return skillList();
  if (name === 'skill_read') {
    const p = skillDirsOf(args.name || '');
    if (p == null) return '错误：技能不存在或名称非法';
    return readIfExists(p) || '';
  }
  return null; // 账本类工具走 App 数据通道
}

function handleLine(line) {
  let msg; try { msg = JSON.parse(line); } catch { return; }
  if (msg.id === undefined || msg.id === null) return;
  const id = msg.id; const method = msg.method; const params = msg.params || {};
  // 工具体内任何未捕获异常（EISDIR/磁盘满等）只回 JSON-RPC error，不杀 MCP server 进程（发版 qa P1-2）
  try {
  if (method === 'initialize') {
    send({ jsonrpc: '2.0', id, result: {
      protocolVersion: params.protocolVersion || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'aicode_bridge', version: '1.0.0' }
    } });
  } else if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: TOOLS } });
  } else if (method === 'tools/call') {
    const name = params.name || '';
    const args = params.arguments || {};
    const local = toolsCall(name, args);
    if (local != null) {
      send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: local }] } });
      return;
    }
    if (name === 'ledger_list' || name === 'ledger_advance' || name === 'recall_search' || name === 'graph_search' || name === 'send_file') {
      askApp(name, (name === 'recall_search' || name === 'graph_search') ? Object.assign({ projectRoot: PROJECT }, args) : args).then((res) => {
        send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: res.ok ? (res.result || '成功') : ('错误：' + (res.message || '未知错误')) }] } });
      }).catch((e) => {
        send({ jsonrpc: '2.0', id, error: { code: -32603, message: '数据通道失败: ' + (e && e.message ? e.message : String(e)) } });
      });
    } else {
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Unknown tool: ' + name } });
    }
  } else {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
  }
  } catch (e) {
    send({ jsonrpc: '2.0', id, error: { code: -32603, message: '工具执行失败: ' + (e && e.message ? e.message : String(e)) } });
  }
}
"""

/** 随包内置的权限桥 MCP 小服务（零依赖 Node 脚本，容器内由 claude 经 --mcp-config 拉起）。 */
internal const val BRIDGE_SCRIPT_SOURCE = """/** AiCode 权限桥：把 claude 的权限请求经本机 TCP 转给 App 授权中心。 */
import net from 'node:net';

const PORT = parseInt(process.env.PERMISSION_BRIDGE_PORT || '0', 10);
const SESSION = process.env.PERMISSION_BRIDGE_SESSION || '';
const TOKEN = process.env.PERMISSION_BRIDGE_TOKEN || '';
const TIMEOUT_MS = 5 * 60 * 1000;

function askApp(toolName, input) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; try { sock.destroy(); } catch {} resolve(v); } };
    const sock = net.connect(PORT, '127.0.0.1', () => {
      sock.write(JSON.stringify({ token: TOKEN, session: SESSION, tool_name: toolName, input }) + '\n');
    });
    let buf = '';
    sock.on('data', (d) => {
      buf += d.toString();
      const i = buf.indexOf('\n');
      if (i >= 0) { try { finish(JSON.parse(buf.slice(0, i))); } catch { finish({ allow: false, message: '权限桥响应解析失败' }); } }
    });
    sock.on('error', (e) => finish({ allow: false, message: '权限桥连接失败: ' + e.message }));
    setTimeout(() => finish({ allow: false, message: '权限桥响应超时' }), TIMEOUT_MS);
  });
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (line) handleLine(line);
  }
});

function send(obj) { try { process.stdout.write(JSON.stringify(obj) + '\n'); } catch {} }

function handleLine(line) {
  let msg; try { msg = JSON.parse(line); } catch { return; }
  if (msg.id === undefined || msg.id === null) return; // 通知无需应答
  const { id, method, params } = msg;
  if (method === 'initialize') {
    send({ jsonrpc: '2.0', id, result: {
      protocolVersion: (params && params.protocolVersion) || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'permission_bridge', version: '1.0.0' }
    } });
  } else if (method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: { tools: [{
      name: 'request_permission',
      description: 'Ask the user to approve a tool use via the AiCode permission center',
      inputSchema: { type: 'object', properties: { tool_name: { type: 'string' }, input: { type: 'object' } }, required: ['tool_name', 'input'] }
    }] } });
  } else if (method === 'tools/call') {
    const toolName = (params && params.arguments && params.arguments.tool_name) || '';
    const input = (params && params.arguments && params.arguments.input) || {};
    askApp(toolName, input).then((res) => {
      const behavior = res.allow
        ? { behavior: 'allow', updatedInput: input }
        : { behavior: 'deny', message: res.message || 'denied' };
      send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(behavior) }] } });
    }).catch((e) => {
      send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify({ behavior: 'deny', message: '权限桥异常: ' + (e && e.message ? e.message : String(e)) }) }] } });
    });
  } else {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
  }
}
"""
