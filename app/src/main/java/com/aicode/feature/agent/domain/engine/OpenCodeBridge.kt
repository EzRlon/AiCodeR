package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.cliagent.CliAgentCatalog
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenCode CLI 桥接（引擎切换三期）。
 *
 * 每个用户回合在 PRoot 容器内起一个 headless 进程：
 * `opencode run --format json [--auto] --thinking [--dir 项目根] [-s <sessionID>] [-m provider/model]`
 * 提示词经 stdin 传入（位置参数留空），把 stdout 的 NDJSON 事件翻译成 [AgentEvent]，
 * 落库 / 停止 / 队列 / UI 状态机全部复用本体链路。事件形态经 opencode 1.19.0 实测与
 * 仓库 noninteractive.ts 源码核对：step_start / text / reasoning / tool_use / step_finish / error，
 * 每行都带 sessionID（新会话首轮从事件里取，持久化映射后续接 `-s`）。
 *
 * 与 Claude Code 的差异（协议决定，非缺漏）：
 * - 文本按块整发（session.text.ended 触发），无逐 token 增量；工具调用也只在成败后一次性到达，
 *   翻译层立即补发 Started+Finished 配对。
 * - 权限无交互审批：`--auto`（引擎卡开关控制，默认开）自动批准未被显式 deny 的请求；工具成败以 tool_use 事件外显。
 * - 智能体：App 已启用子代理写进 `OPENCODE_CONFIG_CONTENT` 的 `agent` 段（mode=subagent，实测 1.19.0
 *   的 `opencode agent list` 能列出）；与 opencode 自带 agent（build/plan/explore/...）同名的定义跳过。
 * - MCP 无命令行 flag：经 `OPENCODE_CONFIG_CONTENT` 内联 JSON 挂 aicode_bridge（实测会拉起 server；
 *   该变量按官方优先级链合并、不整体替换用户配置，但同键 mcp 会被本桥覆盖）。
 * - 续会话：opencode 不接受外部指定 session id，App sessionId → `ses_...` 映射持久化在桥接目录。
 */
@Singleton
class OpenCodeBridge @Inject constructor(
    private val containerEngine: LinuxContainerEngine,
    private val permissionBridgeServer: PermissionBridgeServer,
    private val cliAgentManager: CliAgentManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val engineOptionsRepository: EngineOptionsRepository,
    private val agentDefinitionRepository: com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
) {
    private companion object {
        const val TAG = "OpenCodeBridge"

        const val BRIDGE_DIR_SUFFIX = "/.aicode/bridge"

        /** App sessionId → opencode sessionID 映射文件（容器内路径）。 */
        const val SESSIONS_FILE = "/opencode-sessions.json"

        const val MCP_SERVER_NAME = "aicode_bridge"

        /**
         * OPENCODE_CONFIG_CONTENT 长度上限：内容是单个环境变量值，实测单条 env 超过 ~128KB（131072 字节）
         * 时进程孵化直接失败（E2BIG）。留一半余量，超限则本回合不映射 agent。
         */
        const val MAX_CONFIG_CHARS = 64 * 1024

        const val IDLE_TIMEOUT_MS = 30 * 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val assetsMutex = Mutex()

    private fun bridgeDir(): String = pathHomeResolver.aicodeRoot().trimEnd('/') + BRIDGE_DIR_SUFFIX

    /**
     * 容器内的工作区路径：CLI 参数（`--dir`）与桥接环境变量都必须是容器视角路径。
     * 调用方传的 projectPath 是宿主真实目录（仅用于 proot `-b` 绑定），直接透给容器内 CLI 会报目录不存在。
     */
    private fun containerWorkspacePath(): String = pathHomeResolver.home().trimEnd('/') + "/workspace"
    private fun dataBridgeScript(): String = bridgeDir() + "/aicode-bridge.mjs"
    private fun sessionsPath(): String = bridgeDir() + SESSIONS_FILE

    private val sessionIds = ConcurrentHashMap<String, String>()

    @Volatile private var cachedAvailable: Boolean? = null
    @Volatile private var cachedAtMs: Long = 0

    @Volatile private var cachedAuthHint: String? = null
    @Volatile private var cachedAuthAtMs: Long = 0

    /** 引擎是否可用：本地 PRoot 容器 + opencode 已安装。远程 SSH 不支持。 */
    suspend fun isAvailable(): Boolean {
        if (resolveRuntimeProfile().mode == ExecutionMode.REMOTE_SSH) return false
        cachedAvailable?.let { cached ->
            if (System.currentTimeMillis() - cachedAtMs < 5 * 60_000L) return cached
        }
        val opencode = CliAgentCatalog.ALL.firstOrNull { it.command == "opencode" } ?: return false
        val available = cliAgentManager.detectVersion(opencode) != null
        cachedAvailable = available
        cachedAtMs = System.currentTimeMillis()
        return available
    }

    /**
     * 认证状态：null = 已可用（auth.json 凭据在，或将透传 App 内 OpenAI/Anthropic key）；
     * 非 null = 未认证引导文案。探测为轻量 test 命令，不拉起 opencode。
     */
    suspend fun authHint(providerId: String? = null): String? {
        if (resolveRuntimeProfile().mode == ExecutionMode.REMOTE_SSH) return null
        if (providerId != null && resolveProvider(providerId) != null) return null
        if (transmittableProvider(ProviderType.OPENAI) != null || transmittableProvider(ProviderType.ANTHROPIC) != null) return null
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
                command = "test -f \"\$HOME/.local/share/opencode/auth.json\" && echo present || echo absent",
                projectPath = null,
                timeoutMs = 8_000L
            )
        }.getOrNull() ?: return null // 容器未就绪等场景不拦截，交给可用性/流中错误链路
        if (probe.output.contains("present")) return null
        return "OpenCode 尚未登录：请二选一——① 在终端执行 opencode auth login 配置 provider 凭据；" +
            "② 在 设置 → 模型 添加 OpenAI 或 Anthropic 类型提供商并填入 API Key，切引擎时会自动透传。" +
            "若你已在容器内自行配置环境变量或 opencode.json 自定义 provider，可忽略本提示。"
    }

    /** App 内已配置可用 Key 的指定类型提供商（用于透传；无则 null）。 */
    private suspend fun transmittableProvider(type: ProviderType): com.aicode.feature.settings.domain.model.AIProviderConfig? =
        runCatching {
            aiProviderRepository.getAllProviders().first().firstOrNull {
                it.type == type && it.isEnabled && it.hasUsableApiKey
            }
        }.getOrNull()

    /** 按 id 解析 App 供应商（仅 OpenAI/Anthropic 型且启用可用；其余返回 null）。 */
    private suspend fun resolveProvider(providerId: String?): com.aicode.feature.settings.domain.model.AIProviderConfig? {
        if (providerId.isNullOrBlank()) return null
        return runCatching {
            aiProviderRepository.getAllProviders().first().firstOrNull {
                it.id == providerId &&
                    (it.type == ProviderType.OPENAI || it.type == ProviderType.ANTHROPIC) &&
                    it.isEnabled && it.hasUsableApiKey
            }
        }.getOrNull()
    }

    private suspend fun resolveRuntimeProfile(): com.aicode.feature.agent.domain.container.ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: com.aicode.feature.agent.domain.container.ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 跑一个用户回合。[resume] 为 true 时先尝试 `-s` 续接（映射缺失或续接失败自动转全新回合）。
     * [model] 透传 `-m`（provider/model 形态或引擎内置名）；[providerId] 为会话选定的 App 供应商
     * （自定义端点时注入 provider 段并对齐 Key）；[images] 落盘后经 `-f` 传入；[attachments] 以容器路径告知。
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
                FileLogger.w(TAG, "opencode 续接会话缺失，转全新回合重试: ${e.message}")
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

        // 会话选定 App 供应商：自定义端点时需注入 provider 段（模型引用串指向注入键），官方端点只透 Key
        val selectedProvider = resolveProvider(providerId)
        val customProviderJson = customProviderSection(selectedProvider, model)

        val resumeSessionId = if (resume) readMappedSessionId(sessionId) else null
        val effectiveResume = resume && resumeSessionId != null
        if (resume && resumeSessionId == null) {
            FileLogger.i(TAG, "无 opencode session 映射，按全新回合执行: sid=$sessionId")
        }

        // headless 权限请求无人应答：--auto 自动批准未被显式 deny 的请求；由引擎卡开关控制（默认开）
        val autoApprove = engineOptionsRepository.isOpenCodeAutoApprove()
        val args = buildList {
            add("run")
            add("--format"); add("json")
            if (autoApprove) add("--auto")
            // 思考过程事件（session.reasoning.ended）需要该开关才外发
            add("--thinking")
            if (!projectPath.isNullOrBlank()) { add("--dir"); add(containerWorkspacePath()) }
            if (effectiveResume) { add("-s"); add(resumeSessionId!!) }
            // 会话模型格式为 provider/model（如 openai/gpt-5.3-codex）；default/空 不传，跟随 CLI 配置
            if (!model.isNullOrBlank() && model != "default") { add("-m"); add(model) }
            // 图片：base64 解码落盘到桥目录后经 -f 传入（opencode 非 text mime 按附件处理）
            writeImageAssets(sessionId, images).forEach { path -> add("-f"); add(path) }
        }
        val extraEnv = buildMap {
            put("PERMISSION_BRIDGE_PORT", port.toString())
            put("PERMISSION_BRIDGE_SESSION", sessionId)
            put("PERMISSION_BRIDGE_TOKEN", sessionId)
            put("AICODE_HOME", pathHomeResolver.aicodeRoot().trimEnd('/'))
            put("AICODE_PROJECT_ROOT", if (projectPath.isNullOrBlank()) "" else containerWorkspacePath())
            // MCP 挂载：官方优先级链合并（不整体替换用户配置；mcp 同键会被覆盖）
            // 配置内容要枚举 App 智能体定义（读容器文件），移出主线程
            put("OPENCODE_CONFIG_CONTENT", withContext(Dispatchers.IO) { configContent(sessionId, customProviderJson) })
            // App 内已配 key 时透传（opencode 识别标准 provider 环境变量，实测 OPENAI_API_KEY 生效）；
            // 会话选定供应商时只透传该供应商的 Key，避免误用其它供应商的凭据
            if (selectedProvider != null) {
                if (selectedProvider.type == ProviderType.OPENAI) {
                    put("OPENAI_API_KEY", selectedProvider.firstUsableApiKey)
                } else {
                    put("ANTHROPIC_API_KEY", selectedProvider.firstUsableApiKey)
                }
            } else {
                transmittableProvider(ProviderType.OPENAI)?.let { p ->
                    put("OPENAI_API_KEY", p.firstUsableApiKey)
                }
                transmittableProvider(ProviderType.ANTHROPIC)?.let { p ->
                    put("ANTHROPIC_API_KEY", p.firstUsableApiKey)
                }
            }
        }

        FileLogger.i(TAG, "启动 opencode 桥接进程: resume=$effectiveResume model=$model project=$projectPath authViaEnv=${extraEnv.containsKey("OPENAI_API_KEY") || extraEnv.containsKey("ANTHROPIC_API_KEY")}")
        // spawn 失败（未装/容器未就绪）时若不清理，token 会残留到下一次同会话回合（与 CodexBridge 同修）；孵化阻塞，移出主线程（optimizer P1-1）
        val process = withContext(Dispatchers.IO) {
            try {
                containerEngine.startStdioProcess("opencode", args, projectPath, extraEnv, resolveRuntimeProfile())
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
                    FileLogger.w(TAG, "空闲超时（${IDLE_TIMEOUT_MS / 60_000} 分钟无输出），中止 opencode 进程")
                    runCatching { process.destroy() }
                    break
                }
            }
        }

        // 附件以容器路径告知（正文末尾，引擎用自身工具读取）
        val prompt = userRequest + attachmentSuffix(attachments)

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
                // opencode 成功回合没有结束事件（v1.19.0 源码：事件循环内部收尾后仅置退出码，不再输出），
                // EOF 后按退出码判定：0 = 正常完成；非 0 / 零事件走错误分类（保住认证/续接缺失识别）。
                // waitFor 最长阻塞 10s，必须移出主线程（发版 qa P1-1）
                val exitCode = withContext(Dispatchers.IO) {
                    val exited = runCatching { process.waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
                    if (!exited) runCatching { process.destroy() }
                    if (exited) runCatching { process.exitValue() }.getOrDefault(-1) else -1
                }
                if (exitCode == 0 && acc.emittedAny) {
                    emit(AgentEvent.Completed)
                } else {
                    throw classifyEarlyExit(tail)
                }
            }
        } finally {
            killOnCancel?.dispose()
            stderrDrain.cancel()
            watchdog.cancel()
            permissionBridgeServer.removeToken(bridgeToken)
            runCatching { process.destroy() }
            // 事件行带 sessionID：即便回合失败，映射也已建立，下回合可续接
            acc.remoteSessionId?.let { persistSessionId(sessionId, it) }
        }
    }

    /** 无完成事件即退出：区分「续接会话不存在」（可重试）与认证/其它失败。 */
    private fun classifyEarlyExit(stderrTail: String): Exception {
        val lower = stderrTail.lowercase()
        if ("api key" in lower || "unauthorized" in lower || "401" in lower || "credential" in lower || "not authenticated" in lower) {
            return RuntimeException(
                "OpenCode 尚未认证：请在终端执行 opencode auth login（或配置 OPENAI_API_KEY/ANTHROPIC_API_KEY），" +
                    "然后回到会话重发消息。原始错误：" + stderrTail.takeLast(300).trim()
            )
        }
        return if ("session not found" in lower || "no session" in lower) {
            ResumeSessionMissingException(stderrTail.takeLast(400).trim())
        } else {
            RuntimeException("OpenCode 提前退出: " + stderrTail.takeLast(400).trim().ifEmpty { "（无 stderr 输出）" })
        }
    }

    // ── run --format json NDJSON → AgentEvent 翻译 ─────────────────

    /** 单回合累积状态。 */
    private class TurnAccumulator {
        val reasoning = StringBuilder()
        var remoteSessionId: String? = null
        var emittedAny = false
    }

    private fun parseLine(line: String, acc: TurnAccumulator): List<AgentEvent> {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        if (obj == null) {
            FileLogger.i(TAG, "跳过非 JSON 行: ${line.take(200)}")
            return emptyList()
        }
        (obj["sessionID"]?.jsonPrimitive?.contentOrNull)?.let { acc.remoteSessionId = it }
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "step_start", "step_finish" -> emptyList() // 步进边界与 token/cost 暂不接（无预算基准，见台账待办）
            "text" -> {
                val part = obj["part"] as? JsonObject ?: return emptyList()
                // 思考随首块正文落库并清空（与 CodexBridge 一致；qa P1-1）
                val reasoning = acc.reasoning.toString()
                acc.reasoning.clear()
                listOf(
                    AgentEvent.AssistantText(
                        content = part["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        reasoning = reasoning
                    )
                )
            }
            "reasoning" -> {
                val part = obj["part"] as? JsonObject ?: return emptyList()
                acc.reasoning.append(part["text"]?.jsonPrimitive?.contentOrNull ?: "")
                listOf(AgentEvent.ReasoningDelta(accumulated = acc.reasoning.toString()))
            }
            "tool_use" -> parseToolUse(obj)
            "error" -> {
                val error = obj["error"] as? JsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: (error?.get("data") as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "OpenCode 运行失败"
                listOf(AgentEvent.Failed(error = message))
            }
            else -> emptyList()
        }
    }

    /**
     * 工具调用只在成败后一次性到达（session.tool.success/failed）：补发 Started+Finished 同 id 配对，
     * 保证下游「执行中」工具行被 REPLACE 而非残留。
     */
    private fun parseToolUse(obj: JsonObject): List<AgentEvent> {
        val part = obj["part"] as? JsonObject ?: return emptyList()
        val state = part["state"] as? JsonObject ?: return emptyList()
        val id = part["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val toolName = part["tool"]?.jsonPrimitive?.contentOrNull ?: "tool"
        val input = state["input"] as? JsonObject ?: JsonObject(emptyMap())
        val isError = state["status"]?.jsonPrimitive?.contentOrNull == "error"
        val result = state["output"]?.jsonPrimitive?.contentOrNull
            ?: (state["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: (state["error"] as? JsonPrimitive)?.contentOrNull
            ?: state["output"]?.toString() // 结构化输出兑底（qa P2-2）
            ?: "（无结果）"
        return listOf(
            AgentEvent.ToolCallStarted(id = id, toolName = toolName, argsPreview = input.toString().take(400)),
            AgentEvent.ToolCallFinished(id = id, toolName = toolName, result = result.take(4000), isError = isError)
        )
    }

    // ── session 映射持久化 ────────────────────────────────────────

    private fun readMappedSessionId(appSessionId: String): String? {
        sessionIds[appSessionId]?.let { return it }
        return runCatching {
            if (!fileAccess.exists(sessionsPath())) return null
            val map = json.parseToJsonElement(fileAccess.readFile(sessionsPath()))
                .let { it as? JsonObject } ?: return null
            map[appSessionId]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun persistSessionId(appSessionId: String, remoteSessionId: String) {
        sessionIds[appSessionId] = remoteSessionId
        runCatching {
            fileAccess.writeFile(
                sessionsPath(),
                buildJsonObject {
                    for ((k, v) in sessionIds) put(k, JsonPrimitive(v))
                }.toString(),
                overwrite = true
            )
        }.onFailure { e -> FileLogger.w(TAG, "持久化 session 映射失败: ${e.message}") }
    }

    // ── 桥接资产 ──────────────────────────────────────────────────

    /** 每回合确保 aicode_bridge 脚本就位（与 ClaudeCodeBridge/CodexBridge 共用同一 mjs 源）。 */
    private suspend fun writeBridgeAssets(port: Int, sessionId: String, token: String, projectPath: String?) =
        assetsMutex.withLock {
            runCatching {
                fileAccess.writeFile(dataBridgeScript(), AICODE_BRIDGE_SCRIPT_SOURCE, overwrite = true)
            }.onFailure { e ->
                FileLogger.e(TAG, "写桥接资产失败", e)
                throw RuntimeException("初始化数据桥资产失败: ${e.message}", e)
            }
        }

    /**
     * aicode_bridge + 用户自装 MCP + App 智能体定义的 OPENCODE_CONFIG_CONTENT 内联值
     * （不写用户配置文件；mcp/agent 键按官方优先级覆盖，同名项以本内容为准）。
     */
    private suspend fun configContent(sessionId: String, customProviderJson: JsonObject? = null): String {
        // App 智能体定义随回合映射（能力全域打通批二）：App 定义为子代理语义，mode=subagent；
        // 跳过与 opencode 自带 agent（build/plan/explore/general/...）同名的定义，不覆盖引擎原生体系
        // agent 枚举失败不致命（与 Claude 桥同口径）：MCP 枚举异常仍让回合失败，agent 异常只丢本回合映射
        val mapping = runCatching {
            CliAgentMapping.partitionForOpenCode(agentDefinitionRepository.listEnabled().map { it.definition })
        }.getOrElse { e ->
            FileLogger.w(TAG, "枚举 App 子代理失败，本回合不映射 agent", e)
            CliAgentMapping.Result(emptyList(), emptyList())
        }
        mapping.skipped.forEach { FileLogger.w(TAG, "子代理 ${it.agent.name} 不映射到 opencode：${it.reason}") }
        val agentsJson = if (mapping.mapped.isEmpty()) null else CliAgentMapping.openCodeAgentsJson(mapping.mapped)

        val content = renderConfig(sessionId, agentsJson, customProviderJson)
        if (content.length <= MAX_CONFIG_CHARS) return content
        FileLogger.w(TAG, "智能体配置过大（>${MAX_CONFIG_CHARS / 1024}KB），本回合不映射 agent")
        return renderConfig(sessionId, null, customProviderJson)
    }

    /** OPENCODE_CONFIG_CONTENT 内容（mcp 段 + 可选 agent 段 + 可选 App 供应商 provider 段）。 */
    private suspend fun renderConfig(sessionId: String, agentsJson: JsonObject?, customProviderJson: JsonObject? = null): String = buildJsonObject {
        put("mcp", buildJsonObject {
            put(MCP_SERVER_NAME, buildJsonObject {
                put("type", "local")
                put("command", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("node"), JsonPrimitive(dataBridgeScript()))))
                put("enabled", true)
            })
            // 用户在 App 配置的 MCP 服务器随回合自动映射（能力全域打通批一）；OAuth 型服务器无法委托凭据，跳过。
            // 不包 runCatching：与 Claude/Codex 桥对齐，仓库异常让回合失败而非静默丢 MCP（qa P2-3）
            for (server in mcpConfigRepository.getEffectiveServers()) {
                if (!server.enabled || !com.aicode.feature.agent.domain.mcp.McpServerConfig.isValidName(server.name)) continue
                if (server.name == MCP_SERVER_NAME) continue
                if (server.auth != null) {
                    FileLogger.w(TAG, "MCP 服务器 ${server.name} 使用 OAuth，暂不映射到 CLI 引擎")
                    continue
                }
                if (server.isStdio) {
                    put(server.name, buildJsonObject {
                        put("type", "local")
                        put("command", kotlinx.serialization.json.JsonArray(
                            listOf(JsonPrimitive(server.command!!)) + server.args.map { JsonPrimitive(it) }
                        ))
                        if (server.env.isNotEmpty()) {
                            put("environment", buildJsonObject { for ((k, v) in server.env) put(k, v) })
                        }
                        put("enabled", true)
                    })
                } else if (!server.url.isNullOrBlank()) {
                    put(server.name, buildJsonObject {
                        put("type", "remote")
                        put("url", server.url)
                        if (server.headers.isNotEmpty()) {
                            put("headers", buildJsonObject { for ((k, v) in server.headers) put(k, v) })
                        }
                        put("enabled", true)
                    })
                }
            }
        })
        if (agentsJson != null) put("agent", agentsJson)
        if (customProviderJson != null) put("provider", customProviderJson)
    }.toString()

    /**
     * 会话选定 App 供应商且模型引用指向注入键（自定义端点）时，构造 provider 段：
     * 官方端点/内置名引用返回 null（走内置 provider + 环境变量 Key，无需注入）；
     * useFullUrl 的 base 是完整 endpoint 无法安全还原，同样不注入。
     */
    private fun customProviderSection(
        provider: com.aicode.feature.settings.domain.model.AIProviderConfig?,
        model: String?
    ): JsonObject? {
        if (provider == null || model.isNullOrBlank()) return null
        if (provider.useFullUrl) return null
        val key = CliModelRef.providerKey(provider.name, provider.id)
        if (model.substringBefore('/') != key) return null
        val modelId = model.substringAfter('/', "")
        if (modelId.isBlank()) return null
        val npm = if (provider.type == ProviderType.ANTHROPIC) "@ai-sdk/anthropic" else "@ai-sdk/openai-compatible"
        return buildJsonObject {
            put(key, buildJsonObject {
                put("npm", npm)
                put("name", provider.name)
                put("options", buildJsonObject {
                    put("baseURL", provider.baseUrl.trim().trimEnd('/').removeSuffix("/v1"))
                    put("apiKey", provider.firstUsableApiKey)
                })
                put("models", buildJsonObject { put(modelId, buildJsonObject { put("name", modelId) }) })
            })
        }
    }

    /**
     * 图片落盘到容器桥目录（opencode -f 需要容器内文件路径，≤10MiB/个），返回容器内路径列表。
     * 目录内旧图片先清理。
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
            val maxBytes = 8 * 1024 * 1024L // opencode 限制 10MiB，留余量
            images.mapIndexedNotNull { index, img ->
                runCatching {
                    val bytes = java.util.Base64.getDecoder().decode(img.base64Data)
                    if (bytes.size > maxBytes) {
                        FileLogger.w(TAG, "图片 ${index + 1} 超过 opencode 附件限制（${bytes.size} 字节），跳过")
                        return@mapIndexedNotNull null
                    }
                    val ext = img.mimeType.substringAfter('/', "bin").takeWhile { it.isLetterOrDigit() }
                    val path = "$dir/img-$sessionId-$index.$ext"
                    fileAccess.writeBytes(path, bytes)
                    path
                }.onFailure { e -> FileLogger.w(TAG, "图片落盘失败（跳过）: ${e.message}") }.getOrNull()
            }
        }
    }
}
