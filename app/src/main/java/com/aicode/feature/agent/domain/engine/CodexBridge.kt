package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.cliagent.CliTabInjection
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.prompt.CliKnowledgeInjection
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.agent.domain.workflow.AgentEvent
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.aicode.feature.agent.domain.provider.NormalizedUsage
import com.aicode.feature.agent.domain.provider.UsageNormalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI Codex CLI 桥接（引擎切换三期）。
 *
 * 每个用户回合在 PRoot 容器内起一个 headless 进程：
 * `codex exec --json --skip-git-repo-check --sandbox <workspace-write|danger-full-access> [-C 项目根] [resume <threadId>] -`
 * 提示词经 stdin 传入（`-`），把 stdout 的 JSONL（ThreadEvent 协议，已在 codex-cli 0.162.1 实测：
 * thread.started / item.started|completed / turn.completed|failed）翻译成 [AgentEvent]，
 * 落库 / 停止 / 队列 / UI 状态机全部复用本体链路。
 *
 * 与 Claude Code 的差异（协议决定，非缺漏）：
 * - 无逐 token 文本增量：助手文本在 item.completed(type=agent_message) 整段到达，直接发 AssistantText。
 * - 无交互审批钩子（exec 模式不弹审批）：命令在沙箱内自动执行（档位由引擎卡开关控制），不走本体授权中心；
 *   仅 aicode_bridge 数据工具经 [PermissionBridgeServer] 的 kind:data 通道回调 App。
 * - MCP 无 --mcp-config flag：用 `-c mcp_servers.<name>.*` 内联覆盖挂载（实测会拉起 server）。
 * - 续会话用 `exec resume <thread_id>`；App sessionId 与 codex thread_id 的映射持久化在桥接目录
 *   codex-threads.json（codex 不接受外部指定 session id，与 claude 的 --session-id 不同）。
 */
@Singleton
class CodexBridge @Inject constructor(
    private val containerEngine: LinuxContainerEngine,
    private val permissionBridgeServer: PermissionBridgeServer,
    cliAgentManager: CliAgentManager,
    private val fileAccess: FileAccessProvider,
    pathHomeResolver: PathHomeResolver,
    containerSettingsRepository: ContainerSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val deliveryRegistry: CliDeliveryRegistry,
    private val todoMirror: EngineTodoMirror,
    private val orchestrationPrompt: CliOrchestrationPrompt
) : AbstractCliBridge(cliAgentManager, pathHomeResolver, containerSettingsRepository) {

    override val cliCommand: String = "codex"

    private companion object {
        const val TAG = "CodexBridge"

        /** aicode_bridge 的交付工具名；CLI 侧实际工具名可能带前缀，用包含匹配。 */
        const val SEND_FILE_TOOL = "send_file"

        /** 发 ToolCallStarted 的 item 类型白名单（与 parseItemCompleted 的 Finished 配对）。 */
        val TOOL_ITEM_TYPES = setOf(
            "command_execution", "file_change", "mcp_tool_call", "web_search", "todo_list", "collab_tool_call"
        )

        /** 终端标签专用 CODEX_HOME：与用户 ~/.codex 隔离，避免覆盖其自定义 provider 与登录态。 */
        const val CODEX_TAB_HOME_SUFFIX = "/codex-tab"

        /** App sessionId → codex thread_id 映射文件（容器内路径）。 */
        const val THREADS_FILE = "/codex-threads.json"

        /** 与 ClaudeCodeBridge 一致的空闲上限。 */
        const val IDLE_TIMEOUT_MS = 30 * 60_000L

        /** collab_tool_call 完成事件里可能承载子代理结论的字段（按序取第一个非空文本）。
         *  协议未公开，字段名以实测为准；都取不到时明确落「未返回正文」而不是伪造内容。 */
        val COLLAB_RESULT_KEYS = listOf("result", "output", "last_message", "message")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val assetsMutex = Mutex()

    // bridgeDir / containerWorkspacePath / dataBridgeScript 由 AbstractCliBridge 提供
    //（containerWorkspacePath 用于 `-C` 参数与桥接环境变量，必须是容器视角路径）。
    private fun threadsPath(): String = bridgeDir() + THREADS_FILE

    /** 内存映射缓存；进程被杀/应用重启后回退读 threads 文件。并发回合（悬浮球/定时任务）下也有读写。 */
    private val threadIds = ConcurrentHashMap<String, String>()

    // 可用性/认证缓存字段与 isAvailable() 由 AbstractCliBridge 提供。

    /**
     * 认证状态：null = 已可用（ChatGPT 登录凭据在，或将透传 App 内 OpenAI key）；
     * 非 null = 未认证引导文案。探测为轻量 test 命令，不拉起 codex。
     */
    suspend fun authHint(providerId: String? = null): String? {
        if (isRemote()) return null
        if (transmittableOpenAIProvider(providerId) != null) return null
        if (authHintCacheFresh()) cachedAuthHint?.let { return it }
        val hint = probeAuthHint()
        rememberAuthHint(hint)
        return hint
    }

    private suspend fun probeAuthHint(): String? {
        val probe = runCatching {
            containerEngine.runCommandSyncIfReady(
                command = "test -f \"\$HOME/.codex/auth.json\" && echo present || echo absent",
                projectPath = null,
                timeoutMs = 8_000L
            )
        }.getOrNull() ?: return null // 容器未就绪等场景不拦截，交给可用性/流中错误链路
        if (probe.output.contains("present")) return null
        return "Codex 尚未登录：请二选一——① 在终端执行 codex 完成一次 ChatGPT 登录；" +
            "② 在 设置 → 模型 添加 OpenAI 类型的提供商并填入 API Key，切引擎时会自动透传给 codex。" +
            "若你已在容器内自行配置 OPENAI_API_KEY 或 config.toml 自定义 provider，可忽略本提示。"
    }

    /** App 内已配置可用 Key 的 OpenAI 提供商（用于透传；无则 null，codex 走自身登录态）。
     *  [providerId] 非空时优先解析该供应商（会话选定的 App 供应商模型），不可用再回退首个可用。 */
    private suspend fun transmittableOpenAIProvider(providerId: String? = null): com.aicode.feature.settings.domain.model.AIProviderConfig? =
        runCatching {
            val all = aiProviderRepository.getAllProviders().first()
            providerId?.let { id ->
                all.firstOrNull { it.id == id && it.type == ProviderType.OPENAI && it.isEnabled && it.hasUsableApiKey }
            } ?: all.firstOrNull {
                it.type == ProviderType.OPENAI && it.isEnabled && it.hasUsableApiKey
            }
        }.getOrNull()

    /**
     * 顶栏「Codex」终端标签的供应商注入：复用与 exec 相同的供应商解析与转换网关，往专用 CODEX_HOME
     * 写 config.toml（不碰用户 ~/.codex），返回 env（CODEX_HOME + Key）供标签启动命令前置。
     * [providerId] 传 App 默认供应商；无可用供应商或网关不可用时返回 null，标签按裸命令启动。
     */
    suspend fun cliTabInjection(providerId: String? = null): CliTabInjection? = runCatching {
        val provider = transmittableOpenAIProvider(providerId) ?: return@runCatching null
        val directResponsesBase = provider.takeIf { !it.useFullUrl }
            ?.baseUrl?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && !it.contains("api.openai.com") }
            ?.let { if (it.substringAfterLast('/').matches(Regex("v\\d+[a-z]*"))) it else "$it/v1" }
        val needsConverter = directResponsesBase != null && !provider.useResponseApi
        val providerBaseUrl = if (needsConverter) {
            val port = OpenAiCompatGateway.ensureStarted()
            val key = OpenAiCompatGateway.codexKey(provider.id)
            OpenAiCompatGateway.registerUpstream(key, provider.baseUrl, provider.firstUsableApiKey, provider.useFullUrl)
            OpenAiCompatGateway.codexConverterBaseUrl(key, port)
        } else {
            directResponsesBase
        } ?: return@runCatching null
        // 模型串可能带「供应商名或 id/」前缀，仅前缀命中当前供应商时剥离（聚合商真模型 id 里的 / 不动）
        val bareModel = provider.selectedModel.takeIf { it.isNotBlank() }
            ?.let { CliModelRef.bareModelName(it, provider.name, provider.id) }
            ?: provider.models.firstOrNull { it.isNotBlank() }
        val home = bridgeDir() + CODEX_TAB_HOME_SUFFIX
        fileAccess.writeFile(
            "$home/config.toml",
            buildString {
                bareModel?.let { append("model = ${tomlString(it)}\n") }
                append("model_provider = \"aicode_provider\"\n")
                append("check_for_update_on_startup = false\n")
                append("[model_providers.aicode_provider]\n")
                append("name = \"aicode\"\n")
                append("base_url = ${tomlString(providerBaseUrl)}\n")
                append("env_key = \"AICODE_CODEX_PROVIDER_KEY\"\n")
            },
            overwrite = true,
        )
        CliTabInjection(
            env = mapOf(
                "CODEX_HOME" to home,
                "AICODE_CODEX_PROVIDER_KEY" to provider.firstUsableApiKey,
                "OPENAI_API_KEY" to provider.firstUsableApiKey,
            ),
        )
    }.onFailure { e -> FileLogger.w(TAG, "CLI 标签供应商注入失败: ${e.message}") }.getOrNull()

    /**
     * 跑一个用户回合。[resume] 为 true 时先尝试 `exec resume` 续接（映射缺失或续接失败自动转全新回合）。
     * [model] 透传 `-m`（CLI 侧模型名或 App 供应商模型名）；[providerId] 对齐透传的 Key/baseURL。
     * [mode] 为会话工作模式，映射到 `--sandbox` 档位。
     * [images] 解码落盘到容器桥目录后经 `-i` 传入；[attachments] 以容器路径告知。
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
                // 已向下游发过事件就不重试：重试会重复落库/重复展示（与 ClaudeCodeBridge 同一纪律）
                if (emittedAny) throw RuntimeException("续接回合中途失败且已产出事件，不重试: ${e.message}", e)
                FileLogger.w(TAG, "codex 续接会话缺失，转全新回合重试: ${e.message}")
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, engineMode = engineMode, images = images, attachments = attachments).collect { emit(it) }
            }
        } else {
            runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, engineMode = engineMode, images = images, attachments = attachments).collect { emit(it) }
        }
    }.catch { e ->
        emit(AgentEvent.Failed(error = e.message ?: e.javaClass.simpleName))
    }

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

        val resumeThreadId = if (resume) readThreadId(sessionId) else null
        val effectiveResume = resume && resumeThreadId != null
        if (resume && resumeThreadId == null) {
            FileLogger.i(TAG, "无 codex thread 映射，按全新回合执行: sid=$sessionId")
        }

        // 工作模式 → 沙箱档位，直接用引擎原生取值。engineMode 为空时取该引擎默认档
        // （workspace-write），与改造前 BUILD 默认一致。
        val sandboxMode = EngineModeCatalog.normalize(AgentEngines.CODEX, engineMode)
        // codex 0.16x 实测无视 OPENAI_BASE_URL（仍连官方 wss 端点）：第三方 baseUrl 必须经
        // -c model_providers 注入（codex 在 base_url 后直接拼 /responses，需保证末尾是版本段）；
        // Key 另存专用环境变量供 env_key 读取
        val provider = transmittableOpenAIProvider(providerId)
        val directResponsesBase = provider?.takeIf { !it.useFullUrl }
            ?.baseUrl?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && !it.contains("api.openai.com") }
            ?.let { if (it.substringAfterLast('/').matches(Regex("v\\d+[a-z]*"))) it else "$it/v1" }
        // 供应商未声明 Responses API 支持时，经容器内转换网关（Responses→chat）接入。
        // directResponsesBase 非 null 蕴含 provider 非 null——局部非空绑定替代 provider!!，
        // 避免跨 4 行推断链在重构时被破坏。
        val converterProvider = directResponsesBase?.let { provider }
        val needsConverter = converterProvider != null && provider?.useResponseApi == false
        val gatewayPort = if (needsConverter) OpenAiCompatGateway.ensureStarted() else 0
        if (converterProvider != null && needsConverter) {
            OpenAiCompatGateway.registerUpstream(
                OpenAiCompatGateway.codexKey(converterProvider.id),
                converterProvider.baseUrl,
                converterProvider.firstUsableApiKey,
                converterProvider.useFullUrl,
            )
        }
        val providerBaseUrl = when {
            needsConverter -> OpenAiCompatGateway.codexConverterBaseUrl(
                OpenAiCompatGateway.codexKey(requireNotNull(converterProvider).id), gatewayPort
            )
            else -> directResponsesBase
        }
        val providerKeyEnv = providerBaseUrl?.let { "AICODE_CODEX_PROVIDER_KEY" }
        val args = buildList {
            add("exec")
            add("--json")
            add("--skip-git-repo-check")
            add("--sandbox"); add(sandboxMode)
            if (!projectPath.isNullOrBlank()) { add("-C"); add(containerWorkspacePath()) }
            // MCP 无 --mcp-config flag，-c 内联覆盖挂载 aicode_bridge（0.162.1 实测会拉起 server）
            add("-c"); add("mcp_servers.aicode_bridge.command=\"node\"")
            add("-c"); add("mcp_servers.aicode_bridge.args=[\"${dataBridgeScript()}\"]")
            add("-c"); add(mcpEnvOverride(port, sessionId, token = bridgeToken, projectPath))
            // 中文系统下让模型用中文回复（Codex CLI 界面本身没有本地化，官方 issue #30060；
            // 能控制的只有模型输出语言，走原生 developer_instructions 扩展点，不改用户 config.toml）。
            // 同时注入知识块（知识库索引 + 世界书命中）；TOML 重复 key 会覆盖，语言指令与知识块合并成一条
            val developerExtras = listOfNotNull(
                EngineLocale.replyLanguageDirective(),
                // 注意传宿主路径：CliKnowledgeInjection 按宿主文件系统读 notes/lorebook，
                    // 传容器路径（/root/workspace）会读不到，注入内容恒为空。
                    CliKnowledgeInjection.build(projectPath ?: "", userRequest ?: ""),
                // 三引擎共用同一套编排守则；绑了专家团时附带团长指令（内置引擎不走这里）
                orchestrationPrompt.build(sessionId, AgentEngines.CODEX)
            )
            val appInstructions = developerExtras.joinToString("\n\n")
            if (appInstructions.isNotBlank()) {
                // -c developer_instructions 是「覆盖」不是「追加」：用户 config.toml 里已有的指令
                // 必须保留（解析不了就本回合跳过注入，宁可少注入也不毁用户配置）
                val existing = readUserDeveloperInstructions()
                when {
                    existing == null -> {
                        add("-c"); add("developer_instructions=${tomlString(appInstructions)}")
                    }
                    existing.text != null -> {
                        add(
                            "-c"
                        ); add(
                            "developer_instructions=${tomlString(existing.text + "\n\n" + appInstructions)}"
                        )
                    }
                    else -> FileLogger.w(
                        TAG,
                        "用户 config.toml 已配置 developer_instructions 且无法安全解析，本回合跳过编排/知识注入（避免覆盖）"
                    )
                }
            }
            // 用户自装 MCP 自动映射（能力全域打通批一）：与 aicode_bridge 同样用 -c 内联，不写用户配置文件
            for (server in mcpConfigRepository.getEffectiveServers()) {
                if (!server.enabled || !com.aicode.feature.agent.domain.mcp.McpServerConfig.isValidName(server.name)) continue
                if (server.name == "aicode_bridge") continue
                if (server.auth != null) {
                    FileLogger.w(TAG, "MCP 服务器 ${server.name} 使用 OAuth，暂不映射到 CLI 引擎")
                    continue
                }
                if (server.isStdio) {
                    // env 键拼进 TOML 键路径，含非法字符会使整条 -c 解析失败（发版 qa P2-4）
                    if (server.env.keys.any { !it.matches(Regex("[A-Za-z0-9_]+")) }) {
                        FileLogger.w(TAG, "MCP 服务器 ${server.name} 的 env 键名非法，跳过映射")
                        continue
                    }
                    // stdio 型 server 缺 command 属配置损坏：跳过并告警，不用 !! 把坏数据炸成进程崩溃
                    val cmd = server.command
                    if (cmd.isNullOrBlank()) {
                        FileLogger.w(TAG, "MCP stdio 服务器 ${server.name} 缺 command，跳过映射")
                        continue
                    }
                    add("-c"); add("mcp_servers.${server.name}.command=${tomlString(cmd)}")
                    if (server.args.isNotEmpty()) {
                        val tomlArgs = server.args.joinToString(",") { tomlString(it) }
                        add("-c"); add("mcp_servers.${server.name}.args=[$tomlArgs]")
                    }
                    if (server.env.isNotEmpty()) {
                        val tomlEnv = server.env.entries.joinToString(",") { "\"${it.key}\"=${tomlString(it.value)}" }
                        add("-c"); add("mcp_servers.${server.name}.env={$tomlEnv}")
                    }
                } else if (!server.url.isNullOrBlank()) {
                    add("-c"); add("mcp_servers.${server.name}.url=${tomlString(server.url)}")
                }
            }
            // 图片：base64 解码落盘到桥目录后经 -i 传入（codex 只接受文件路径）
            writeImageAssets(sessionId, images).forEach { path -> add("-i"); add(path) }
            if (!model.isNullOrBlank() && model != "default") {
                // 会话存的模型串可能带「供应商名或id/」前缀；仅前缀命中当前供应商时剥离（z-ai/ 等真 id 不动）
                val bareModel = CliModelRef.bareModelName(model, provider?.name, provider?.id)
                add("-m"); add(bareModel)
            }
            if (providerBaseUrl != null) {
                add("-c"); add("model_provider=aicode_provider")
                add("-c"); add("model_providers.aicode_provider.name=\"aicode\"")
                add("-c"); add("model_providers.aicode_provider.base_url=${tomlString(providerBaseUrl)}")
                add("-c"); add("model_providers.aicode_provider.env_key=\"AICODE_CODEX_PROVIDER_KEY\"")
            }
            // 关启动更新自检：CLI 内置 Update now 走 npm 先删后装，弱网中断会把 codex 删空（曾致引擎 not found）
            add("-c"); add("check_for_update_on_startup=false")
            if (effectiveResume) { add("resume"); add(requireNotNull(resumeThreadId)) }
            add("-") // 提示词走 stdin
        }
        val extraEnv = buildMap {
            put("PERMISSION_BRIDGE_PORT", port.toString())
            put("PERMISSION_BRIDGE_SESSION", sessionId)
            put("PERMISSION_BRIDGE_TOKEN", sessionId)
            put("AICODE_HOME", pathHomeResolver.aicodeRoot().trimEnd('/'))
            put("AICODE_PROJECT_ROOT", if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())
            // 会话选定 App 供应商模型时按 providerId 对齐，避免误用首个可用供应商的凭据
            provider?.let { p ->
                put("OPENAI_API_KEY", p.firstUsableApiKey)
                providerKeyEnv?.let { put(it, p.firstUsableApiKey) }
            }
        }
        // 附件以容器路径告知（正文末尾，引擎用自身工具读取）
        val prompt = userRequest + attachmentSuffix(attachments)

        FileLogger.i(TAG, "启动 codex 桥接进程: resume=$effectiveResume model=$model project=$projectPath authViaKey=${extraEnv.containsKey("OPENAI_API_KEY")}")
        // spawn 失败（codex 未装/容器未就绪）时若不清理，token 会残留到下一次同会话回合（qa P2-1）；孵化阻塞，移出主线程（optimizer P1-1）
        val process = withContext(Dispatchers.IO) {
            try {
                containerEngine.startStdioProcess("codex", args, projectPath, extraEnv, resolveRuntimeProfile())
            } catch (e: Exception) {
                permissionBridgeServer.removeToken(bridgeToken)
                throw e
            }
        }

        val killOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { process.destroy() }
        }

        val stderrTail = StringBuilder()
        val lastActivity = AtomicLong(System.currentTimeMillis())
        val stderrDrain = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    lastActivity.set(System.currentTimeMillis())
                    synchronized(stderrTail) {
                        stderrTail.appendLine(line)
                        if (stderrTail.length > 8000) stderrTail.delete(0, stderrTail.length - 4000)
                    }
                }
            }
        }

        val watchdog = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                delay(60_000)
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    FileLogger.w(TAG, "空闲超时（${IDLE_TIMEOUT_MS / 60_000} 分钟无输出），中止 codex 进程")
                    runCatching { process.destroy() }
                    break
                }
            }
        }

        val acc = TurnAccumulator(sessionId)
        try {
            withContext(Dispatchers.IO) {
                val w = process.outputStream.bufferedWriter()
                w.write(prompt)
                w.write("\n")
                w.flush()
                w.close()
            }

            var gotResult = false
            val reader = process.inputStream.bufferedReader()
            while (!gotResult) {
                val line = withContext(Dispatchers.IO) {
                    lastActivity.set(System.currentTimeMillis())
                    reader.readLine()
                } ?: break
                if (line.isBlank()) continue
                for (event in parseLine(line, acc)) {
                    acc.emittedAny = true
                    emit(event)
                    if (event is AgentEvent.Completed || event is AgentEvent.Failed) gotResult = true
                }
                // todo_list 全量待办镜像落库（快照式替换）：待办卡片的拉起/刷新/自动收起走既有 Flow
                acc.pendingTodos.takeIf { it.isNotEmpty() }?.let { todos ->
                    acc.pendingTodos = emptyList()
                    todoMirror.mirror(sessionId, todos)
                }
                // 整轮用量落账（codex 只在 turn.completed 给一次）：发一条空内容 AssistantText 承载用量，
                // 复用 VM 既有 addTokenUsage 链路，记忆图谱/统计页由此看到 Codex 会话消耗。
                acc.pendingUsage?.let { usage ->
                    acc.pendingUsage = null
                    if (usage.inputTokens > 0 || usage.outputTokens > 0) {
                        emit(
                            AgentEvent.AssistantText(
                                content = "",
                                inputTokens = usage.inputTokens,
                                outputTokens = usage.outputTokens,
                                cachedInputTokens = usage.cachedInputTokens
                            )
                        )
                    }
                }
            }

            if (!gotResult) {
                val tail = synchronized(stderrTail) { stderrTail.toString() }
                throw classifyEarlyExit(tail, acc.lastError)
            }
        } finally {
            killOnCancel?.dispose()
            stderrDrain.cancel()
            watchdog.cancel()
            permissionBridgeServer.removeToken(bridgeToken)
            runCatching { process.destroy() }
            // thread.started 在认证前就发出：即便回合失败，映射也已建立，下回合可续接
            acc.threadId?.let { persistThreadId(sessionId, it) }
        }
    }

    /** 无 result 行即退出：区分「续接会话不存在」（可重试）与认证/其它失败；[lastStdoutError] 兕住只走 stdout 的致命错误（发版 qa P2-2）。 */
    private fun classifyEarlyExit(stderrTail: String, lastStdoutError: String? = null): Exception {
        val lower = (stderrTail + "\n" + (lastStdoutError ?: "")).lowercase()
        if ("api key" in lower || "login" in lower || "unauthorized" in lower || "not logged in" in lower || "401" in lower) {
            return RuntimeException(
                "Codex 尚未认证：请在终端打开一次交互式 codex 并完成 ChatGPT 登录（或配置 OPENAI_API_KEY），" +
                    "然后回到会话重发消息。原始错误：" + stderrTail.takeLast(300).trim()
            )
        }
        return if ("no session found" in lower || "session not found" in lower || "no thread found" in lower) {
            ResumeSessionMissingException(stderrTail.takeLast(400).trim())
        } else {
            RuntimeException("Codex 提前退出: " + stderrTail.takeLast(400).trim().ifEmpty { "（无 stderr 输出）" })
        }
    }

    // ── ThreadEvent JSONL → AgentEvent 翻译 ────────────────────────

    /** 单回合累积状态：codex 无文本增量，文本/工具按 item 整段交付。 */
    private class TurnAccumulator(val sessionId: String) {
        val reasoning = StringBuilder()
        /** item id → 展示工具名：item.completed 回来时补齐 Finished 的 toolName。 */
        val toolNames = mutableMapOf<String, String>()
        var threadId: String? = null
        /** 刚解析到的 todo_list 全量待办；消费端镜像落库后清空。 */
        var pendingTodos: List<EngineTodoMirror.NativeTodo> = emptyList()
        /** 刚解析到的整轮用量（turn.completed）；消费端落账后清空。 */
        var pendingUsage: NormalizedUsage? = null
        /** 最近一条顶层 error 文本：EOF 分类时兕底（发版 qa P2-2）。 */
        var lastError: String? = null
        var emittedAny = false
        /** item id → 外部子代理会话：collab_tool_call 声明时建，item.completed 时收尾。 */
        val subagents = mutableMapOf<String, EngineSubagentSession>()
        /** 本回合已建过子会话的 item id（完成后仍保留）：重连重放同一调用时不重复建。 */
        val seenSubagentIds = mutableSetOf<String>()
    }

    private fun parseLine(line: String, acc: TurnAccumulator): List<AgentEvent> {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        if (obj == null) {
            FileLogger.i(TAG, "跳过非 JSON 行: ${line.take(200)}")
            return emptyList()
        }
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "thread.started" -> {
                acc.threadId = obj["thread_id"]?.jsonPrimitive?.contentOrNull
                emptyList()
            }
            "turn.started" -> emptyList()
            // 顶层 error 多为重连等瞬态（实测 403 重连流），只有 turn.failed 才是回合失败；
            // 但致命错误若只走 stdout（无 turn.failed），EOF 分类时用它兕底（发版 qa P2-2）
            "error" -> {
                acc.lastError = obj["message"]?.jsonPrimitive?.contentOrNull ?: acc.lastError
                emptyList()
 }
            "item.started" -> parseItemStarted(obj, acc)
            "item.completed" -> parseItemCompleted(obj, acc)
            "turn.completed" -> {
                // 用量在 turn.completed 的 usage 里整轮给一次（codex 无逐条消息用量）。
                // 字段语义同 Anthropic：input_tokens 不含缓存，需补 cached/cache_write，
                // 统一走 UsageNormalizer.anthropic，禁止在此另起口径。
                (obj["usage"] as? JsonObject)?.let { u ->
                    acc.pendingUsage = UsageNormalizer.anthropic(
                        input = u.intOf("input_tokens") ?: 0,
                        cacheRead = u.intOf("cached_input_tokens"),
                        cacheCreation = u.intOf("cache_write_input_tokens"),
                        output = u.intOf("output_tokens") ?: 0
                    )
                }
                listOf(AgentEvent.Completed)
            }
            "turn.failed" -> listOf(parseTurnFailed(obj))
            else -> emptyList()
        }
    }

    private fun parseItemStarted(obj: JsonObject, acc: TurnAccumulator): List<AgentEvent> {
        val item = obj["item"] as? JsonObject ?: return emptyList()
        val type = item["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        // 只有工具型 item 发 Started：agent_message/reasoning/error 也带 id 但无工具语义，
        // 否则每条助手消息都会落一条永不配对 Finished 的「执行中」工具行（qa P1-1）
        if (type !in TOOL_ITEM_TYPES) return emptyList()
        val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val display = displayToolName(type, item)
        acc.toolNames[id] = display
        val events = mutableListOf<AgentEvent>(
            AgentEvent.ToolCallStarted(id = id, toolName = display, argsPreview = argsPreview(item))
        )
        // collab_tool_call 里只有真正的新派发建子会话；等待/追加消息/关闭复用已有子代理。
        // 同一 item 重放（重连流）不重复建：子会话 id 是随机的，只能在这里挡。
        if (type == "collab_tool_call" && id !in acc.seenSubagentIds && EngineSubagent.isSpawnCall(item.toString())) {
            val sub = newEngineSubagentSession(item.toString())
            acc.seenSubagentIds += id
            acc.subagents[id] = sub
            events += AgentEvent.SubagentSpawned(
                subSessionId = sub.subSessionId,
                memberName = sub.memberName,
                title = sub.title,
                prompt = sub.prompt
            )
        }
        return events
    }

    private fun parseItemCompleted(obj: JsonObject, acc: TurnAccumulator): List<AgentEvent> {
        val item = obj["item"] as? JsonObject ?: return emptyList()
        when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "agent_message" -> {
                val text = item["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val reasoning = acc.reasoning.toString()
                acc.reasoning.clear()
                return listOf(
                    AgentEvent.AssistantText(content = text, reasoning = reasoning)
                )
            }
            "reasoning" -> {
                acc.reasoning.append(item["text"]?.jsonPrimitive?.contentOrNull ?: "")
                return listOf(AgentEvent.ReasoningDelta(accumulated = acc.reasoning.toString()))
            }
            "error" -> return emptyList() // item 级非致命错误（如传输降级提示），回合照常推进
            "todo_list" -> {
                // Codex 原生待办（update_plan 产出的 items: [{step, status}]）镜像进 App 待办表：
                // 待办卡片由此拉起/刷新/自动收起
                acc.pendingTodos = todoMirror.parseTodos(item["items"] as? kotlinx.serialization.json.JsonArray)
                return emptyList()
            }
            else -> {
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val status = item["status"]?.jsonPrimitive?.contentOrNull
                val name = acc.toolNames[id]
                    ?: displayToolName(item["type"]?.jsonPrimitive?.contentOrNull, item)
                val failed = status == "failed"
                // 与内置 sendFile 同一语义：交付物在 App 侧登记，这里取出来挂成文件卡片
                val attachments = if (!failed && name.contains(SEND_FILE_TOOL)) {
                    deliveryRegistry.drain(acc.sessionId)
                } else {
                    emptyList()
                }
                val result = itemResult(item, status)
                val events = mutableListOf<AgentEvent>(
                    AgentEvent.ToolCallFinished(
                        id = id,
                        toolName = name,
                        result = result,
                        isError = failed,
                        attachments = attachments
                    )
                )
                acc.subagents.remove(id)?.let { sub ->
                    events += AgentEvent.SubagentFinished(
                        subSessionId = sub.subSessionId,
                        result = result.take(4000),
                        isError = failed
                    )
                }
                return events
            }
        }
    }

    private fun parseTurnFailed(obj: JsonObject): AgentEvent {
        val error = obj["error"] as? JsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Codex 运行失败"
        return AgentEvent.Failed(error = message)
    }

    /** 命令执行→bash、文件改动→file_edit、MCP 工具→server.tool、子代理协作→task，其余用协议类型名。 */
    private fun displayToolName(type: String?, item: JsonObject): String = when (type) {
        "command_execution" -> "bash"
        "file_change" -> "file_edit"
        // Codex 的 collab_tool_call 就是派发/操控子代理，归一成内置的 task（图标/文案/成员名同源）
        "collab_tool_call" -> EngineSubagent.TOOL_NAME
        "mcp_tool_call" -> {
            val server = item["server"]?.jsonPrimitive?.contentOrNull ?: ""
            val tool = item["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            if (server.isEmpty()) "mcp_tool" else "$server.$tool"
        }
        else -> type ?: "tool"
    }

    private fun JsonObject.intOf(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun argsPreview(item: JsonObject): String = when (item["type"]?.jsonPrimitive?.contentOrNull) {
        "command_execution" -> item["command"]?.jsonPrimitive?.contentOrNull ?: ""
        // 子代理：优先展示任务描述，取不到再退回完整参数，避免工具行只有协议名
        "collab_tool_call" -> EngineSubagent.memberName(item.toString())
            ?: (item["prompt"]?.jsonPrimitive?.contentOrNull ?: item.toString())
        "mcp_tool_call" -> item["arguments"]?.toString() ?: ""
        "file_change" -> (item["changes"] as? kotlinx.serialization.json.JsonArray)
            ?.joinToString(", ") { (it as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull ?: "" }
            ?: ""
        else -> ""
    }.take(400)

    private fun itemResult(item: JsonObject, status: String?): String = when (item["type"]?.jsonPrimitive?.contentOrNull) {
        "command_execution" -> {
            val output = item["aggregated_output"]?.jsonPrimitive?.contentOrNull ?: ""
            val exit = item["exit_code"]
            val exitText = (exit as? JsonPrimitive)?.content?.let { "（exit=$it）" } ?: ""
            if (status == "declined") "命令未执行（declined）" else (output.ifBlank { "（无输出）" } + exitText)
        }
        "mcp_tool_call" -> {
            val err = (item["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            if (err != null) err
            else {
                val result = item["result"] as? JsonObject
                val content = result?.get("content") as? kotlinx.serialization.json.JsonArray
                content?.joinToString("\n") { c ->
                    (c as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                }?.ifBlank { null } ?: result?.get("structured_content")?.toString() ?: "（无结果）"
            }
        }
        "file_change" -> (item["changes"] as? kotlinx.serialization.json.JsonArray)
            ?.joinToString("\n") { c ->
                val o = c as? JsonObject
                "${o?.get("kind")?.jsonPrimitive?.contentOrNull ?: "update"}: ${o?.get("path")?.jsonPrimitive?.contentOrNull ?: ""}"
            }
            ?.ifBlank { null } ?: "（无变更）"
        // 子代理协作：优先取引擎给的文本结果；取不到时明确说明「未返回正文」，
        // 不再把整条调用参数 JSON 当报告落进子会话。
        "collab_tool_call" -> COLLAB_RESULT_KEYS
            .firstNotNullOfOrNull { key ->
                (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
            ?: "（引擎未返回子代理正文；原始调用：${item.toString().take(300)}）"
        else -> item["text"]?.jsonPrimitive?.contentOrNull
            ?: item.toString().take(400)
    }

    // ── thread 映射持久化 ──────────────────────────────────────────

    private fun readThreadId(appSessionId: String): String? {
        threadIds[appSessionId]?.let { return it }
        return runCatching {
            if (!fileAccess.exists(threadsPath())) return null
            val map = json.parseToJsonElement(fileAccess.readFile(threadsPath()))
                .let { it as? JsonObject } ?: return null
            map[appSessionId]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun persistThreadId(appSessionId: String, threadId: String) {
        threadIds[appSessionId] = threadId
        runCatching {
            fileAccess.writeFile(
                threadsPath(),
                buildJsonObject {
                    for ((k, v) in threadIds) put(k, JsonPrimitive(v))
                }.toString(),
                overwrite = true
            )
        }.onFailure { e -> FileLogger.w(TAG, "持久化 thread 映射失败: ${e.message}") }
    }

    // ── 桥接资产 ──────────────────────────────────────────────────

    /**
     * 每回合确保 aicode_bridge 脚本就位。与 ClaudeCodeBridge 共用同一 mjs 源（internal const），
     * 无需写 mcp-config.json：codex 经 -c 内联挂载。
     */
    private suspend fun writeBridgeAssets(port: Int, sessionId: String, token: String, projectPath: String?) =
        assetsMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    fileAccess.writeFile(dataBridgeScript(), AICODE_BRIDGE_SCRIPT_SOURCE, overwrite = true)
                }.onFailure { e ->
                    FileLogger.e(TAG, "写桥接资产失败", e)
                    throw RuntimeException("初始化数据桥资产失败: ${e.message}", e)
                }
            }
        }

    /** aicode_bridge 的 env 内联 TOML 覆盖值：{K="V",...}（键均为固定大写标识，值不含引号字符）。 */
    private fun mcpEnvOverride(port: Int, sessionId: String, token: String, projectPath: String?): String {
        val env = buildJsonObject {
            put("PERMISSION_BRIDGE_PORT", port.toString())
            put("PERMISSION_BRIDGE_SESSION", sessionId)
            put("PERMISSION_BRIDGE_TOKEN", token)
            put("AICODE_HOME", pathHomeResolver.aicodeRoot().trimEnd('/'))
            put("AICODE_PROJECT_ROOT", if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())
        }
        val inner = env.entries.joinToString(",") { "\"${it.key}\"=\"${it.value.jsonPrimitive.content}\"" }
        return "mcp_servers.aicode_bridge.env={$inner}"
    }

    /** TOML 基本串转义（qa P2-1）：配置值含引号/反斜杠/换行时正常透传而非解析失败。 */
    /** 转义实现见 [CliToml.string]（抽出去是为了能单测——转义错一个字符整条 -c 参数就废了）。 */
    private fun tomlString(value: String): String = CliToml.string(value)

    /** 用户已配置的 developer_instructions：present 表示配置里出现过该键；text 为可安全解析的值（解析不了为 null）。 */
    private data class ExistingDevInstructions(val present: Boolean, val text: String?)

    /**
     * 读取 `~/.codex/config.toml` 里的 developer_instructions。
     *
     * Codex 的 `-c` 是覆盖语义：直接用 App 的文本会把用户自定义指令整段替换掉。这里只在
     * 能可靠解析单行基本字符串时做「用户指令 + App 指令」拼接；出现该键但格式复杂（多行串/
     * 字面量串）时返回 present=true、text=null，让调用方跳过注入，避免毁掉用户配置。
     */
    private suspend fun readUserDeveloperInstructions(): ExistingDevInstructions? = withContext(Dispatchers.IO) {
        val path = "~/.codex/config.toml"
        val content = runCatching {
            if (fileAccess.exists(path)) fileAccess.readFile(path) else null
        }.getOrNull() ?: return@withContext null
        if (!content.contains("developer_instructions")) return@withContext ExistingDevInstructions(false, null)
        val match = Regex("""(?m)^\s*developer_instructions\s*=\s*"((?:[^"\\]|\\.)*)"""")
            .find(content)
            ?: return@withContext ExistingDevInstructions(true, null)
        val decoded = match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        ExistingDevInstructions(true, decoded)
    }

    /**
     * 图片落盘到容器桥目录（codex -i 只接受文件路径），返回容器内路径列表。
     * 目录内旧图片先清理（不依赖回合结束，进程可能早退）。
     */
    private suspend fun writeImageAssets(sessionId: String, images: List<com.aicode.feature.agent.domain.model.AgentImage>): List<String> {
        if (images.isEmpty()) return emptyList()
        // 解码+写盘可能数 MB：移出主线程（qa P1-2）
        return withContext(Dispatchers.IO) {
            val dir = bridgeDir() + "/images"
            runCatching {
                if (fileAccess.exists(dir)) {
                    // 只清本会话的旧图：并发会话的图片可能尚未被 CLI 读取（qa P1-1）
                    fileAccess.listFiles(dir).filter { it.name.startsWith("img-$sessionId-") }.forEach { fileAccess.delete(dir + "/" + it.name) }
                }
            }
            images.mapIndexedNotNull { index, img ->
                runCatching {
                    val ext = img.mimeType.substringAfter('/', "bin").takeWhile { it.isLetterOrDigit() }
                    val path = "$dir/img-$sessionId-$index.$ext"
                    fileAccess.writeBytes(path, java.util.Base64.getDecoder().decode(img.base64Data))
                    path
                }.onFailure { e -> FileLogger.w(TAG, "图片落盘失败（跳过）: ${e.message}") }.getOrNull()
            }
        }
    }
}
