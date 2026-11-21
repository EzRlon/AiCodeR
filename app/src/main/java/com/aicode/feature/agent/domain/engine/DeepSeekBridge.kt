package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.cliagent.CliTabInjection
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.prompt.CliKnowledgeInjection
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek Harness（dsh）桥接（引擎切换四期）。
 *
 * 每个用户回合在 PRoot 容器内起一个 headless 一次性进程：
 * `dsh --profile headless --json --permission-mode <workspace-write|danger-full-access>
 *  --approval-policy never [--session-id <dshSid>] [--patch <overlay>]`
 * 提示词经 stdin 传入（位置参数有 MAX_ARG_STRLEN 上限），stdout 的 NDJSON 事件流
 * （session → status → tool_call/tool_result → text/thinking → final，已在 dsh 0.2.x
 * headless README 核实）翻译成 [AgentEvent]，落库 / 停止 / 队列 / UI 状态机全部复用本体链路。
 *
 * 与 Codex 桥的同构与差异（协议决定，非缺漏）：
 * - 同构：一次性进程 + NDJSON stdout；无逐 token 文本增量（text 在步骤提交点整段到达）；
 *   无实时审批通道（headless 的 --approval-policy 只有 ask|never，必须 never），仅
 *   aicode_bridge 数据工具经 [PermissionBridgeServer] 的 kind:data 通道回调 App。
 * - 凭据：headless 禁用托管凭据存储，只认启动环境变量 DEEPSEEK_API_KEY（env → cwd/.env →
 *   $DSH_HOME/.env）。App 内 DeepSeek 型供应商的 Key 经进程环境注入。
 * - 会话续接：`--session-id` 直接接受外部 id（要求 ^[A-Za-z0-9][A-Za-z0-9_-]{7,}$，且续接时
 *   cwd/preset 必须一致；未知 id 直接报错）。App 侧以事件 session.sessionId 为准持久化映射
 *   （dsh-threads.json），传入 id 仅作为兜底骨架。
 * - MCP 与模型选择都没有独立 flag：统一走 `--patch` Cordis overlay——aicode_bridge 与用户
 *   自装 MCP 以 @deepseek-ai/dsh-mcp-client entry 挂载（工具名 mcp__<server>__<tool>，与
 *   Claude Code 同形）；会话选定模型经 @deepseek-ai/dsh-agent-default-model 覆盖默认路由。
 * - 图片不支持：headless 只接受文本 task（未提供 -i 类入口），发图会静默忽略并记日志。
 * - 无 todo / 子代理事件：dsh headless 不暴露对应事件，App 侧不镜像待办、不派发子代理
 *   （编排守则里成员仅作分工参考，与 Codex 同策略）。
 */
@Singleton
class DeepSeekBridge @Inject constructor(
    private val containerEngine: LinuxContainerEngine,
    private val permissionBridgeServer: PermissionBridgeServer,
    cliAgentManager: CliAgentManager,
    private val fileAccess: FileAccessProvider,
    pathHomeResolver: PathHomeResolver,
    containerSettingsRepository: ContainerSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val deliveryRegistry: CliDeliveryRegistry,
    private val orchestrationPrompt: CliOrchestrationPrompt,
    private val processRegistry: EngineProcessRegistry,
    private val sessionLock: EngineSessionLock
) : AbstractCliBridge(cliAgentManager, pathHomeResolver, containerSettingsRepository) {

    override val cliCommand: String = "dsh"

    private companion object {
        const val TAG = "DeepSeekBridge"

        /** App sessionId → dsh session id 映射文件（容器内路径）。 */
        const val THREADS_FILE = "/deepseek-threads.json"

        /** 自定义 provider 路由的凭据环境变量名（OpenAI/Anthropic 兼容端点透传）。 */
        const val KEY_ENV_CUSTOM = "AICODE_DSH_PROVIDER_KEY"

        /** Cordis patch overlay 文件（容器内路径），每回合重写（env token 每回合轮换）。 */
        const val PATCH_FILE = "/deepseek-patch.yml"

        /** 与 ClaudeCodeBridge 一致的空闲上限。 */
        const val IDLE_TIMEOUT_MS = 30 * 60_000L
    }

    /** 会话选定模型解析出的 dsh 路由（见 [resolveModelRoute]）。 */
    private data class DshModelRoute(val providerName: String, val bareModel: String?, val custom: Boolean)

    private val json = Json { ignoreUnknownKeys = true }
    private val assetsMutex = Mutex()

    /** NDJSON 翻译器（纯逻辑，单测直测）：send_file 交付物经 deliveryRegistry 提取。 */
    private val translator = DeepSeekEventTranslator { sessionId -> deliveryRegistry.drain(sessionId) }

    private fun threadsPath(): String = bridgeDir() + THREADS_FILE

    private val threadIds = ConcurrentHashMap<String, String>()
    private val mappingMutex = Mutex()

    /**
     * 认证状态：null = 已可用（App 内有可透传供应商 —— DeepSeek 型 / OpenAI 兼容 /
     * Anthropic 兼容，或容器 .env 已带 DEEPSEEK_API_KEY）；非 null = 未认证引导文案。
     * headless 只认环境变量，故探测三处 .env 而非 dsh 托管凭据。
     */
    suspend fun authHint(providerId: String? = null): String? {
        if (isRemote()) return null
        if (resolveTurnProvider(providerId) != null) return null
        if (authHintCacheFresh()) cachedAuthHint?.let { return it }
        val hint = probeAuthHint()
        rememberAuthHint(hint)
        return hint
    }

    private suspend fun probeAuthHint(): String? {
        val probe = runCatching {
            containerEngine.runCommandSyncIfReady(
                command = "for f in \"\$HOME/.dsh/.env\" \"\$HOME/workspace/.env\" \"\$HOME/.aicode/.env\"; do " +
                    "test -f \"\$f\" && grep -q \"DEEPSEEK_API_KEY=\" \"\$f\" && echo present && break; done",
                projectPath = null,
                timeoutMs = 8_000L
            )
        }.getOrNull() ?: return null // 容器未就绪等场景不拦截，交给可用性/流中错误链路
        if (probe.output.contains("present")) return null
        return "DeepSeek 引擎尚未配置凭据：headless 模式只认环境变量。三选一——" +
            "① 设置 → 模型 添加 DeepSeek 类型供应商（platform.deepseek.com 创建，切引擎时自动透传）；" +
            "② 直接复用已添加的 OpenAI/Anthropic 兼容供应商（DeepSeek 官方、SiliconFlow 等均自动经自定义路由透传）；" +
            "③ 在容器 ~/.dsh/.env 写入 DEEPSEEK_API_KEY=sk-...。"
    }

    /** App 内已配置可用 Key 的 DeepSeek 供应商（终端标签注入用：DEEPSEEK_API_KEY 同名直透）。 */
    private suspend fun transmittableDeepSeekProvider(providerId: String? = null): AIProviderConfig? =
        runCatching {
            val all = aiProviderRepository.getAllProviders().first()
            providerId?.let { id ->
                all.firstOrNull { it.id == id && it.type == ProviderType.DEEPSEEK && it.isEnabled && it.hasUsableApiKey }
            } ?: all.firstOrNull {
                it.type == ProviderType.DEEPSEEK && it.isEnabled && it.hasUsableApiKey
            }
        }.getOrNull()

    /**
     * 回合内可透传的供应商（对齐三引擎口径）：DeepSeek 官方端点直透 DEEPSEEK_API_KEY；
     * OpenAI/Anthropic 兼容端点经 dsh `--patch` 的自定义 provider 路由（apiKeyEnv 指向
     * [KEY_ENV_CUSTOM]）透传。指定 [providerId] 时严格按 id 匹配，否则 DeepSeek 型优先。
     */
    private suspend fun resolveTurnProvider(providerId: String? = null): AIProviderConfig? =
        runCatching {
            val all = aiProviderRepository.getAllProviders().first()
            providerId?.let { id ->
                all.firstOrNull {
                    it.id == id && it.isEnabled && it.hasUsableApiKey &&
                        (it.type == ProviderType.DEEPSEEK || it.type == ProviderType.OPENAI || it.type == ProviderType.ANTHROPIC)
                }
            } ?: all.firstOrNull {
                it.type == ProviderType.DEEPSEEK && it.isEnabled && it.hasUsableApiKey
            } ?: all.firstOrNull {
                (it.type == ProviderType.OPENAI || it.type == ProviderType.ANTHROPIC) && it.isEnabled && it.hasUsableApiKey
            }
        }.getOrNull()

    /** 顶栏「dsh」终端标签的供应商注入：headless 同源认证（DEEPSEEK_API_KEY 环境变量）。 */
    suspend fun cliTabInjection(providerId: String? = null): CliTabInjection? = runCatching {
        val provider = transmittableDeepSeekProvider(providerId) ?: return@runCatching null
        CliTabInjection(env = mapOf("DEEPSEEK_API_KEY" to provider.firstUsableApiKey))
    }.onFailure { e -> FileLogger.w(TAG, "CLI 标签供应商注入失败: ${e.message}") }.getOrNull()

    /**
     * 跑一个用户回合。[resume] 为 true 时先尝试 `--session-id` 续接（映射缺失或续接失败自动转全新回合）。
     * [model] 经 patch overlay 配置 agent-default-model；[providerId] 对齐透传的 Key。
     * [mode] 为会话工作模式，映射到 `--permission-mode` 档位。
     * [images] 被 headless 忽略（只接受文本 task）；[attachments] 以容器路径告知。
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
        attachments: List<AgentAttachment> = emptyList()
    ): Flow<AgentEvent> = flow {
        if (images.isNotEmpty()) {
            FileLogger.i(TAG, "dsh headless 不支持图片输入，忽略 ${images.size} 张图（sid=$sessionId）")
        }
        // 跨进程同会话串行：锁被其它存活进程持有时等待（最多 60s），绝不并行 spawn。
        val acquired = withTimeoutOrNull(60_000L) {
            while (!sessionLock.tryAcquire(sessionId)) delay(500)
            true
        } ?: false
        if (!acquired) {
            emit(AgentEvent.Failed(error = "同一会话仍有引擎回合在运行，请稍后重试"))
            return@flow
        }
        try {
            if (resume) {
                var emittedAny = false
                try {
                    runTurnOnce(sessionId, userRequest, projectPath, model, resume = true, providerId = providerId, engineMode = engineMode, attachments = attachments).collect {
                        emittedAny = true
                        emit(it)
                    }
                } catch (e: ResumeSessionMissingException) {
                    // 已向下游发过事件就不重试：重试会重复落库/重复展示（与 ClaudeCodeBridge 同一纪律）
                    if (emittedAny) throw RuntimeException("续接回合中途失败且已产出事件，不重试: ${e.message}", e)
                    FileLogger.w(TAG, "dsh 续接会话缺失，转全新回合重试: ${e.message}")
                    runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, engineMode = engineMode, attachments = attachments).collect { emit(it) }
                }
            } else {
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = false, providerId = providerId, engineMode = engineMode, attachments = attachments).collect { emit(it) }
            }
        } finally {
            sessionLock.release(sessionId)
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
        attachments: List<AgentAttachment> = emptyList()
    ): Flow<AgentEvent> = flow {
        val bridgeToken = permissionBridgeServer.newToken()
        val port = permissionBridgeServer.ensureStarted(token = bridgeToken, sessionId = sessionId)

        val provider = resolveTurnProvider(providerId)

        // 模型路由解析：落库的 deepseekModelRef（"deepseek/<m>" 或 "<providerKey>/<m>"）
        // 或引擎自带裸名（default/deepseek-chat/...，视作内置 deepseek 路由）。
        val route = resolveModelRoute(model, provider)

        // 工作模式 → --permission-mode 档位，直接用引擎原生取值。engineMode 为空时取默认档。
        val permissionMode = EngineModeCatalog.normalize(AgentEngines.DEEPSEEK, engineMode)

        // patch overlay：aicode_bridge + 用户自装 MCP + 模型路由，每回合重写（token 轮换）
        writePatchOverlay(port, sessionId, token = bridgeToken, projectPath = projectPath, route = route, provider = provider)

        val resumeThreadId = if (resume) readThreadId(sessionId) else null
        val effectiveResume = resume && resumeThreadId != null
        if (resume && resumeThreadId == null) {
            FileLogger.i(TAG, "无 dsh session 映射，按全新回合执行: sid=$sessionId")
        }

        // dsh 的外部 session id 契约：^[A-Za-z0-9][A-Za-z0-9_-]{7,}$。App sessionId 常为 UUID，
        // 统一 sanitize 成合法形态；真实续接 id 以事件 session.sessionId 为准（映射落盘）。
        val fallbackDshSid = "ai-" + sessionId.replace(Regex("[^A-Za-z0-9_-]"), "-")

        val args = buildList {
            // --patch 是 launcher 层 flag，必须在 --profile 之前（--profile 之后的参数
            // 归 headless app，它不认 --patch，实测报 unknown option）；实测中该顺序
            // 决定 patch overlay 是否被加载，模型路由/桥工具都会静默失效。
            add("--patch"); add(bridgeDir() + PATCH_FILE)
            // 商店安装的 MCP（EngineTargetInstaller 管理，append-only 块）；不存在时由
            // writePatchOverlay 保底建为空列表，避免 launcher 层因缺文件报错。
            add("--patch"); add(com.aicode.feature.store.domain.EngineTargetInstaller.DSH_STORE_MCP_PATCH)
            add("--profile"); add("headless")
            add("--json")
            add("--permission-mode"); add(permissionMode)
            // headless 无实时审批通道：固定 never，可写性由 --permission-mode 档位控制
            add("--approval-policy"); add("never")
            if (effectiveResume) { add("--session-id"); add(requireNotNull(resumeThreadId)) }
        }

        val extraEnv = buildMap {
            put("PERMISSION_BRIDGE_PORT", port.toString())
            put("PERMISSION_BRIDGE_SESSION", sessionId)
            put("PERMISSION_BRIDGE_TOKEN", bridgeToken)
            put("AICODE_HOME", pathHomeResolver.aicodeRoot().trimEnd('/'))
            put("AICODE_PROJECT_ROOT", if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())
            when (provider?.type) {
                ProviderType.DEEPSEEK -> put("DEEPSEEK_API_KEY", provider.firstUsableApiKey)
                ProviderType.OPENAI, ProviderType.ANTHROPIC ->
                    // 自定义路由凭据（apiKeyEnv 指向本变量；dsh-mcp-client 的环境清洗
                    // 只作用于 MCP 子进程，dsh 主进程读得到）
                    put(KEY_ENV_CUSTOM, provider.firstUsableApiKey)
                else -> Unit
            }
        }

        // dsh 无系统指令扩展点：语言指令 + 知识块 + 编排守则拼进 task 正文（以分隔线隔开用户消息）
        val task = buildString {
            val appInstructions = listOfNotNull(
                EngineLocale.replyLanguageDirective(),
                // 注意传宿主路径：CliKnowledgeInjection 按宿主文件系统读 notes/lorebook，
                // 传容器路径（/root/workspace）会读不到，注入内容恒为空。
                CliKnowledgeInjection.build(projectPath ?: "", userRequest ?: ""),
                // 三引擎共用同一套编排守则；绑了专家团时附带团长指令（DeepSeek 成员仅分工参考）
                orchestrationPrompt.build(sessionId, AgentEngines.DEEPSEEK)
            ).joinToString("\n\n")
            if (appInstructions.isNotBlank()) {
                append("[App 系统指令]\n")
                append(appInstructions)
                append("\n\n---\n\n")
            }
            append(userRequest)
            append(attachmentSuffix(attachments))
        }

        FileLogger.i(TAG, "启动 dsh 桥接进程: resume=$effectiveResume mode=$permissionMode route=${route.providerName}/${route.bareModel} provider=${provider?.type} project=$projectPath authEnv=${extraEnv.keys.filter { it.contains("KEY") }}")
        // spawn 失败（dsh 未装/容器未就绪）时若不清理，token 会残留到下一次同会话回合（qa P2-1）；孵化阻塞，移出主线程（optimizer P1-1）
        val process = withContext(Dispatchers.IO) {
            try {
                // 清掉上一个 App 进程遗留的同会话 CLI，避免「另一个进程正在处理同一内容」。
                processRegistry.killForeignSessionProcesses(sessionId)
                containerEngine.startStdioProcess("dsh", args, projectPath, extraEnv, resolveRuntimeProfile())
                    .also { processRegistry.register(sessionId, it, engine = AgentEngines.DEEPSEEK) }
            } catch (e: Exception) {
                permissionBridgeServer.removeToken(bridgeToken)
                throw e
            }
        }
        val killOnCancel = currentCoroutineContext()[Job]?.invokeOnCompletion {
            permissionBridgeServer.removeToken(bridgeToken)
            processRegistry.kill(sessionId, process)
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
                    FileLogger.w(TAG, "空闲超时（${IDLE_TIMEOUT_MS / 60_000} 分钟无输出），中止 dsh 进程")
                    runCatching { process.destroy() }
                    break
                }
            }
        }

        val acc = translator.turnAccumulator(sessionId)
        try {
            withContext(Dispatchers.IO) {
                val w = process.outputStream.bufferedWriter()
                w.write(task)
                w.write("\n")
                w.flush()
                w.close()
            }

            var gotResult = false
            val reader = process.inputStream.bufferedReader()
            var persistedThreadId: String? = null
            while (!gotResult) {
                val line = withContext(Dispatchers.IO) {
                    lastActivity.set(System.currentTimeMillis())
                    reader.readLine()
                } ?: break
                if (line.isBlank()) continue
                for (event in translator.parseLine(line, acc)) {
                    acc.emittedAny = true
                    emit(event)
                    if (event is AgentEvent.Completed || event is AgentEvent.Failed) gotResult = true
                }
                // session 事件一出现就落盘：回合中途 App 被杀也能续接，不只依赖 finally
                acc.threadId?.takeIf { it != persistedThreadId }?.let { id ->
                    persistedThreadId = id
                    persistThreadId(sessionId, id)
                }
                // 整轮用量落账（status.step_start 承载 token 用量）：发一条空内容 AssistantText 承载，
                // 复用 VM 既有 addTokenUsage 链路，记忆图谱/统计页由此看到 DeepSeek 会话消耗。
                acc.pendingUsage?.let { usage ->
                    acc.pendingUsage = null
                    if (usage.inputTokens > 0 || usage.outputTokens > 0) {
                        emit(
                            AgentEvent.AssistantText(
                                content = "",
                                inputTokens = usage.inputTokens,
                                outputTokens = usage.outputTokens,
                                cachedInputTokens = usage.cachedInputTokens,
                                cacheCreationTokens = usage.cacheCreationTokens
                            )
                        )
                    }
                }
            }

            if (!gotResult) {
                val tail = synchronized(stderrTail) { stderrTail.toString() }
                throw translator.classifyEarlyExit(tail, acc.lastError)
            }
        } finally {
            killOnCancel?.dispose()
            stderrDrain.cancel()
            watchdog.cancel()
            processRegistry.unregister(sessionId, process)
            runCatching { process.destroyForcibly() }
            // session 事件在认证前就发出：即便回合失败，映射也已建立，下回合可续接
            acc.threadId?.let { persistThreadId(sessionId, it) }
        }
    }

    // ── Cordis patch overlay 写盘 ────────────────────────────────

    /**
     * 会话模型 → dsh 路由解析。三种落库形态：
     * - "deepseek/<m>"：DeepSeek 官方端点，走 dsh 内置 deepseek provider（DEEPSEEK_API_KEY 直透）；
     * - "<providerKey>/<m>"：自定义端点，经 --patch 的 @deepseek-ai/dsh-llm-pi-ai providers
     *   段注册路由（dict key 即路由名，apiKeyEnv 指向 App 注入的环境变量）；
     * - 裸名（default/deepseek-chat/...）：引擎自带模型，视作内置 deepseek 路由。
     * 供应商解析失败（如已删）时降级内置 deepseek 路由，凭据交由 dsh 自身 .env/报错链路。
     */
    private fun resolveModelRoute(model: String?, provider: AIProviderConfig?): DshModelRoute {
        val bare = model?.takeIf { it.isNotBlank() && it != "default" }
        if (bare.isNullOrBlank()) return DshModelRoute(providerName = "deepseek", bareModel = null, custom = false)
        val slash = bare.indexOf('/')
        if (slash <= 0 || slash == bare.length - 1) {
            return DshModelRoute(providerName = "deepseek", bareModel = bare, custom = false)
        }
        val prefix = bare.substring(0, slash)
        val rest = bare.substring(slash + 1)
        if (prefix == "deepseek") {
            return DshModelRoute(providerName = "deepseek", bareModel = rest, custom = false)
        }
        if (provider == null) {
            FileLogger.w(TAG, "模型引用 $bare 指向的自定义供应商已不可用，降级内置 deepseek 路由")
            return DshModelRoute(providerName = "deepseek", bareModel = rest, custom = false)
        }
        return DshModelRoute(providerName = prefix, bareModel = rest, custom = true)
    }

    /** pi-ai 的 openai-completions 语义：请求打 `<baseURL>/chat/completions`，base 需含 /v1。 */
    private fun normalizeOpenAiCompatBase(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/v1")) b else "$b/v1"
    }

    /**
     * 每回合重写 `--patch` overlay：aicode_bridge + 用户自装 MCP（mcp-client entry）+
     * 模型路由（llm-pi-ai 自定义 provider 段 + agent-default-model entry）。
     * 不写用户 ~/.dsh，失败只影响桥工具，不阻断回合。
     */
    private suspend fun writePatchOverlay(
        port: Int,
        sessionId: String,
        token: String,
        projectPath: String?,
        route: DshModelRoute,
        provider: AIProviderConfig?
    ) = withContext(Dispatchers.IO) {
        assetsMutex.withLock {
            runCatching {
                val script = dataBridgeScript()
                fileAccess.writeFile(
                    script,
                    AICODE_BRIDGE_SCRIPT_SOURCE,
                    overwrite = true,
                )
                // 商店 MCP patch 文件保底：不存在则建空列表，避免 launcher 层因缺文件报错
                if (!fileAccess.exists(com.aicode.feature.store.domain.EngineTargetInstaller.DSH_STORE_MCP_PATCH)) {
                    fileAccess.writeFile(
                        com.aicode.feature.store.domain.EngineTargetInstaller.DSH_STORE_MCP_PATCH,
                        "[]\n",
                        overwrite = true,
                    )
                }
                val entries = StringBuilder()
                entries.append("- insert:\n")
                entries.append("  - id: aicode-bridge\n")
                entries.append("    name: '@deepseek-ai/dsh-mcp-client'\n")
                entries.append("    config:\n")
                entries.append("      serverName: aicode_bridge\n")
                entries.append("      transport: stdio\n")
                entries.append("      command: node\n")
                entries.append("      args:\n")
                entries.append("        - ${yamlSingle(script)}\n")
                entries.append("      env:\n")
                entries.append("        PERMISSION_BRIDGE_PORT: ${yamlSingle(port.toString())}\n")
                entries.append("        PERMISSION_BRIDGE_SESSION: ${yamlSingle(sessionId)}\n")
                entries.append("        PERMISSION_BRIDGE_TOKEN: ${yamlSingle(token)}\n")
                entries.append("        AICODE_HOME: ${yamlSingle(pathHomeResolver.aicodeRoot().trimEnd('/'))}\n")
                entries.append("        AICODE_PROJECT_ROOT: ${yamlSingle(if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())}\n")
                // 用户自装 MCP 自动映射（与 Codex/CC 同规则：跳过 OAuth、非法名、aicode_bridge 本体）
                for (server in mcpConfigRepository.getEffectiveServers()) {
                    if (!server.enabled || !McpServerConfig.isValidName(server.name)) continue
                    if (server.name == "aicode_bridge") continue
                    if (server.auth != null) {
                        FileLogger.w(TAG, "MCP 服务器 ${server.name} 使用 OAuth，暂不映射到 CLI 引擎")
                        continue
                    }
                    val sid = "mcp-" + server.name
                    if (server.isStdio) {
                        val cmd = server.command
                        if (cmd.isNullOrBlank()) {
                            FileLogger.w(TAG, "MCP stdio 服务器 ${server.name} 缺 command，跳过映射")
                            continue
                        }
                        entries.append("  - id: ${yamlSingle(sid)}\n")
                        entries.append("    name: '@deepseek-ai/dsh-mcp-client'\n")
                        entries.append("    config:\n")
                        entries.append("      serverName: ${yamlSingle(server.name)}\n")
                        entries.append("      transport: stdio\n")
                        entries.append("      command: ${yamlSingle(cmd)}\n")
                        if (server.args.isNotEmpty()) {
                            entries.append("      args:\n")
                            for (a in server.args) entries.append("        - ${yamlSingle(a)}\n")
                        } else {
                            entries.append("      args: []\n")
                        }
                        if (server.env.isNotEmpty()) {
                            entries.append("      env:\n")
                            for ((k, v) in server.env) entries.append("        ${yamlSingle(k)}: ${yamlSingle(v)}\n")
                        }
                    } else if (!server.url.isNullOrBlank()) {
                        entries.append("  - id: ${yamlSingle(sid)}\n")
                        entries.append("    name: '@deepseek-ai/dsh-mcp-client'\n")
                        entries.append("    config:\n")
                        entries.append("      serverName: ${yamlSingle(server.name)}\n")
                        entries.append("      transport: streamable-http\n")
                        entries.append("      url: ${yamlSingle(requireNotNull(server.url))}\n")
                    }
                }
                // 会话选定模型 → dsh 路由（dsh 无 -m flag；default/空用 dsh 内置默认）。
                // patch 的直接顶层条目不生效（实测 dump-config 合并结果不含），对已有
                // 插件的 config 覆盖同样走 `- insert:`（同 id 覆盖，实测生效）：
                // 自定义端点先经 @deepseek-ai/dsh-llm-pi-ai 注册路由（providers dict key
                // 即路由名；apiKeyEnv 指向 App 注入的环境变量），再让 agent-default-model 指向它。
                if (route.custom && provider != null && !route.bareModel.isNullOrBlank()) {
                    val wireApi = when (provider.type) {
                        ProviderType.ANTHROPIC -> "anthropic-messages"
                        else -> "openai-completions"
                    }
                    val baseURL = when (provider.type) {
                        ProviderType.ANTHROPIC -> provider.baseUrl.trim().trimEnd('/')
                        else -> normalizeOpenAiCompatBase(provider.baseUrl)
                    }
                    entries.append("  - id: llm-pi-ai\n")
                    entries.append("    name: '@deepseek-ai/dsh-llm-pi-ai'\n")
                    entries.append("    config:\n")
                    entries.append("      providers:\n")
                    entries.append("        ${yamlSingle(route.providerName)}:\n")
                    entries.append("          apiKeyEnv: ${yamlSingle(KEY_ENV_CUSTOM)}\n")
                    entries.append("          api: ${yamlSingle(wireApi)}\n")
                    entries.append("          baseURL: ${yamlSingle(baseURL)}\n")
                    entries.append("          models:\n")
                    entries.append("            - id: ${yamlSingle(requireNotNull(route.bareModel))}\n")
                    entries.append("              name: ${yamlSingle(requireNotNull(route.bareModel))}\n")
                }
                val routeName = if (route.custom) route.providerName else "deepseek"
                if (!route.bareModel.isNullOrBlank()) {
                    entries.append("  - id: agent-default-model\n")
                    entries.append("    name: '@deepseek-ai/dsh-agent-default-model'\n")
                    entries.append("    config:\n")
                    entries.append("      provider: ${yamlSingle(routeName)}\n")
                    entries.append("      model: ${yamlSingle(requireNotNull(route.bareModel))}\n")
                }
                fileAccess.writeFile(bridgeDir() + PATCH_FILE, entries.toString(), overwrite = true)
            }.onFailure { e -> FileLogger.w(TAG, "patch overlay 写盘失败（回合继续，仅桥工具不可用）: ${e.message}") }
        }
    }

    // ── App sessionId ↔ dsh session id 映射 ────────────────────────

    private suspend fun readThreadId(appSessionId: String): String? = withContext(Dispatchers.IO) {
        threadIds[appSessionId] ?: runCatching {
            val f = fileAccess.readFile(threadsPath()) ?: return@runCatching null
            val obj = runCatching { json.parseToJsonElement(f).jsonObject }.getOrNull() ?: return@runCatching null
            obj[appSessionId]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private suspend fun persistThreadId(appSessionId: String, threadId: String) = withContext(Dispatchers.IO) {
        threadIds[appSessionId] = threadId
        mappingMutex.withLock {
            runCatching {
                val existing = runCatching {
                    fileAccess.readFile(threadsPath())?.let {
                        json.parseToJsonElement(it).jsonObject.toMap()
                    }
                }.getOrNull() ?: emptyMap()
                val merged = kotlinx.serialization.json.buildJsonObject {
                    existing.forEach { (k, v) -> put(k, v) }
                    put(appSessionId, kotlinx.serialization.json.JsonPrimitive(threadId))
                }
                fileAccess.writeFile(threadsPath(), merged.toString(), overwrite = true)
            }.onFailure { e -> FileLogger.w(TAG, "thread 映射落盘失败: ${e.message}") }
        }
    }
}

/** YAML 单引号字面量：内部单引号双写转义。 */
internal fun yamlSingle(value: String): String = "'" + value.replace("'", "''") + "'"
