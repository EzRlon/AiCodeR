package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.cliagent.CliAgentCatalog
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.cliagent.CliTabInjection
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.EngineOptionsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
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
    private val cliAgentManager: CliAgentManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val engineOptionsRepository: EngineOptionsRepository
) {
    private companion object {
        const val TAG = "CodexBridge"

        /** 发 ToolCallStarted 的 item 类型白名单（与 parseItemCompleted 的 Finished 配对）。 */
        val TOOL_ITEM_TYPES = setOf(
            "command_execution", "file_change", "mcp_tool_call", "web_search", "todo_list", "collab_tool_call"
        )

        const val BRIDGE_DIR_SUFFIX = "/.aicode/bridge"

        /** 终端标签专用 CODEX_HOME：与用户 ~/.codex 隔离，避免覆盖其自定义 provider 与登录态。 */
        const val CODEX_TAB_HOME_SUFFIX = "/codex-tab"

        /** App sessionId → codex thread_id 映射文件（容器内路径）。 */
        const val THREADS_FILE = "/codex-threads.json"

        /** 与 ClaudeCodeBridge 一致的空闲上限。 */
        const val IDLE_TIMEOUT_MS = 30 * 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val assetsMutex = Mutex()

    private fun bridgeDir(): String = pathHomeResolver.aicodeRoot().trimEnd('/') + BRIDGE_DIR_SUFFIX

    /**
     * 容器内的工作区路径：CLI 参数（`-C`）与桥接环境变量都必须是容器视角路径。
     * 调用方传的 projectPath 是宿主真实目录（仅用于 proot `-b` 绑定），直接透给容器内 CLI 会 ENOENT。
     */
    private fun containerWorkspacePath(): String = pathHomeResolver.home().trimEnd('/') + "/workspace"
    private fun dataBridgeScript(): String = bridgeDir() + "/aicode-bridge.mjs"
    private fun threadsPath(): String = bridgeDir() + THREADS_FILE

    /** 内存映射缓存；进程被杀/应用重启后回退读 threads 文件。并发回合（悬浮球/定时任务）下也有读写。 */
    private val threadIds = ConcurrentHashMap<String, String>()

    @Volatile private var cachedAvailable: Boolean? = null
    @Volatile private var cachedAtMs: Long = 0

    @Volatile private var cachedAuthHint: String? = null
    @Volatile private var cachedAuthAtMs: Long = 0

    /** 引擎是否可用：本地 PRoot 容器 + codex 已安装。远程 SSH 不支持。 */
    suspend fun isAvailable(): Boolean {
        if (resolveRuntimeProfile().mode == ExecutionMode.REMOTE_SSH) return false
        cachedAvailable?.let { cached ->
            if (System.currentTimeMillis() - cachedAtMs < 5 * 60_000L) return cached
        }
        val codex = CliAgentCatalog.ALL.firstOrNull { it.command == "codex" } ?: return false
        val available = cliAgentManager.detectVersion(codex) != null
        cachedAvailable = available
        cachedAtMs = System.currentTimeMillis()
        return available
    }

    /**
     * 认证状态：null = 已可用（ChatGPT 登录凭据在，或将透传 App 内 OpenAI key）；
     * 非 null = 未认证引导文案。探测为轻量 test 命令，不拉起 codex。
     */
    suspend fun authHint(providerId: String? = null): String? {
        if (resolveRuntimeProfile().mode == ExecutionMode.REMOTE_SSH) return null
        if (transmittableOpenAIProvider(providerId) != null) return null
        cachedAuthHint?.let { cached ->
            if (System.currentTimeMillis() - cachedAuthAtMs < 5 * 60_000L) return cached
        }
        val hint = probeAuthHint()
        cachedAuthHint = hint
        cachedAuthAtMs = System.currentTimeMillis()
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

    private suspend fun resolveRuntimeProfile(): com.aicode.feature.agent.domain.container.ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: com.aicode.feature.agent.domain.container.ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 跑一个用户回合。[resume] 为 true 时先尝试 `exec resume` 续接（映射缺失或续接失败自动转全新回合）。
     * [model] 透传 `-m`（CLI 侧模型名或 App 供应商模型名）；[providerId] 对齐透传的 Key/baseURL。
     * [images] 解码落盘到容器桥目录后经 `-i` 传入；[attachments] 以容器路径告知。
     */
    fun runTurn(
        sessionId: String,
        userRequest: String,
        projectPath: String?,
        model: String?,
        resume: Boolean,
        providerId: String? = null,
        images: List<com.aicode.feature.agent.domain.model.AgentImage> = emptyList(),
        attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList()
    ): Flow<AgentEvent> = flow {
        if (resume) {
            var emittedAny = false
            try {
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = true, providerId = providerId, images = images, attachments = attachments).collect {
                    emittedAny = true
                    emit(it)
                }
            } catch (e: ResumeSessionMissingException) {
                // 已向下游发过事件就不重试：重试会重复落库/重复展示（与 ClaudeCodeBridge 同一纪律）
                if (emittedAny) throw RuntimeException("续接回合中途失败且已产出事件，不重试: ${e.message}", e)
                FileLogger.w(TAG, "codex 续接会话缺失，转全新回合重试: ${e.message}")
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, images = images, attachments = attachments).collect { emit(it) }
            }
        } else {
            runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, images = images, attachments = attachments).collect { emit(it) }
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

        // exec 非交互模式无审批弹窗：沙箱档位由引擎卡开关决定（默认 workspace-write，让工作区内命令自动执行、
        // 工作区外被挡；开关放开为 danger-full-access 时整个容器可读写）
        val sandboxMode = if (engineOptionsRepository.isCodexSandboxFullAccess()) "danger-full-access" else "workspace-write"
        // codex 0.16x 实测无视 OPENAI_BASE_URL（仍连官方 wss 端点）：第三方 baseUrl 必须经
        // -c model_providers 注入（codex 在 base_url 后直接拼 /responses，需保证末尾是版本段）；
        // Key 另存专用环境变量供 env_key 读取
        val provider = transmittableOpenAIProvider(providerId)
        val directResponsesBase = provider?.takeIf { !it.useFullUrl }
            ?.baseUrl?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && !it.contains("api.openai.com") }
            ?.let { if (it.substringAfterLast('/').matches(Regex("v\\d+[a-z]*"))) it else "$it/v1" }
        // 供应商未声明 Responses API 支持时，经容器内转换网关（Responses→chat）接入
        val needsConverter = directResponsesBase != null && provider?.useResponseApi == false
        val gatewayPort = if (needsConverter) OpenAiCompatGateway.ensureStarted() else 0
        if (needsConverter) {
            OpenAiCompatGateway.registerUpstream(
                OpenAiCompatGateway.codexKey(provider!!.id),
                provider.baseUrl,
                provider.firstUsableApiKey,
                provider.useFullUrl,
            )
        }
        val providerBaseUrl = when {
            needsConverter -> OpenAiCompatGateway.codexConverterBaseUrl(OpenAiCompatGateway.codexKey(provider!!.id), gatewayPort)
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
            // 能控制的只有模型输出语言，走原生 developer_instructions 扩展点，不改用户 config.toml）
            EngineLocale.replyLanguageDirective()?.let { directive ->
                add("-c"); add("developer_instructions=${tomlString(directive)}")
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
                    add("-c"); add("mcp_servers.${server.name}.command=${tomlString(server.command!!)}")
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
            if (effectiveResume) { add("resume"); add(resumeThreadId!!) }
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

        val acc = TurnAccumulator()
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
    private class TurnAccumulator {
        val reasoning = StringBuilder()
        /** item id → 展示工具名：item.completed 回来时补齐 Finished 的 toolName。 */
        val toolNames = mutableMapOf<String, String>()
        var threadId: String? = null
        /** 最近一条顶层 error 文本：EOF 分类时兕底（发版 qa P2-2）。 */
        var lastError: String? = null
        var emittedAny = false
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
            "turn.completed" -> listOf(AgentEvent.Completed)
            "turn.failed" -> listOfNotNull(parseTurnFailed(obj))
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
        return listOf(AgentEvent.ToolCallStarted(id = id, toolName = display, argsPreview = argsPreview(item)))
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
            else -> {
                val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                val status = item["status"]?.jsonPrimitive?.contentOrNull
                return listOf(
                    AgentEvent.ToolCallFinished(
                        id = id,
                        toolName = acc.toolNames[id] ?: displayToolName(item["type"]?.jsonPrimitive?.contentOrNull, item),
                        result = itemResult(item, status),
                        isError = status == "failed"
                    )
                )
            }
        }
    }

    private fun parseTurnFailed(obj: JsonObject): AgentEvent {
        val error = obj["error"] as? JsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "Codex 运行失败"
        return AgentEvent.Failed(error = message)
    }

    /** 命令执行→bash、文件改动→file_edit、MCP 工具→server.tool，其余用协议类型名。 */
    private fun displayToolName(type: String?, item: JsonObject): String = when (type) {
        "command_execution" -> "bash"
        "file_change" -> "file_edit"
        "mcp_tool_call" -> {
            val server = item["server"]?.jsonPrimitive?.contentOrNull ?: ""
            val tool = item["tool"]?.jsonPrimitive?.contentOrNull ?: ""
            if (server.isEmpty()) "mcp_tool" else "$server.$tool"
        }
        else -> type ?: "tool"
    }

    private fun argsPreview(item: JsonObject): String = when (item["type"]?.jsonPrimitive?.contentOrNull) {
        "command_execution" -> item["command"]?.jsonPrimitive?.contentOrNull ?: ""
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
    private fun tomlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

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
