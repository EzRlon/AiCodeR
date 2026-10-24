package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.cliagent.CliAgentCatalog
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * 把 stdout 的 NDJSON 逐行翻译成 [AgentEvent]——落库 / 停止 / 队列 / UI 状态机全部复用本体链路。
 *
 * 续接：用本体 sessionId 强制为 CLI 的 session id（`--session-id`），后续回合 `--resume` 直接续上
 * `~/.claude` 里的会话历史（不迁库）；rootfs 重装后 CLI 侧历史丢失，检测到续接失败自动转全新回合重试一次。
 * 权限：`--permission-prompt-tool` 经 [PermissionBridgeServer] 桥到本体授权中心。
 */
@Singleton
class ClaudeCodeBridge @Inject constructor(
    private val containerEngine: LinuxContainerEngine,
    private val permissionBridgeServer: PermissionBridgeServer,
    private val cliAgentManager: CliAgentManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private companion object {
        const val TAG = "ClaudeCodeBridge"

        /** 容器内桥接资产目录：/root/.aicode 独立于 rootfs（升级不丢），且是 node 可用的绝对路径。 */
        const val BRIDGE_DIR_SUFFIX = "/.aicode/bridge"

        const val MCP_SERVER_NAME = "permission_bridge"
        const val MCP_TOOL_NAME = "request_permission"

        /** 两行输出之间的空闲上限：claude 执行长工具期间 stdout 可能长时间静默（但 stderr 心跳仍在），
         *  上限取 30 分钟并同时在 stderr 侧刷新活跃时间，避免误杀合法长任务。 */
        const val IDLE_TIMEOUT_MS = 30 * 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val assetsMutex = Mutex()

    private fun bridgeDir(): String = pathHomeResolver.aicodeRoot().trimEnd('/') + BRIDGE_DIR_SUFFIX
    private fun bridgeScript(): String = bridgeDir() + "/claude-permission-bridge.mjs"
    private fun mcpConfigPath(): String = bridgeDir() + "/mcp-config.json"

    /** 引擎可用性缓存（5 分钟 TTL）：避免每次打开模型弹窗都拉起容器进程跑版本检测（optimizer #6）。 */
    @Volatile private var cachedAvailable: Boolean? = null
    @Volatile private var cachedAtMs: Long = 0

    /** 安装后失效可用性缓存：下次进引擎页立即重新检测（用户反馈：装完仍显示未安装）。 */
    fun invalidateAvailability() {
        cachedAvailable = null
        cachedAtMs = 0
    }

    /** 引擎是否可用：本地 PRoot 容器 + claude 已安装。远程 SSH 一期不支持。 */
    suspend fun isAvailable(): Boolean {
        if (resolveRuntimeProfile().mode == ExecutionMode.REMOTE_SSH) return false
        cachedAvailable?.let { cached ->
            if (System.currentTimeMillis() - cachedAtMs < 5 * 60_000L) return cached
        }
        val claude = CliAgentCatalog.ALL.firstOrNull { it.command == "claude" } ?: return false
        val available = cliAgentManager.detectVersion(claude) != null
        cachedAvailable = available
        cachedAtMs = System.currentTimeMillis()
        return available
    }

    /** 一键安装：开终端标签跑安装命令（复用 CLI 智能体管理器的安装链路）。 */
    suspend fun installCli(): String =
        cliAgentManager.install(CliAgentCatalog.ALL.first { it.command == "claude" })

    /** 当前激活 profile（本地模式；与 McpManager 同一解析规则）。 */
    private suspend fun resolveRuntimeProfile(): com.aicode.feature.agent.domain.container.ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: com.aicode.feature.agent.domain.container.ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 跑一个用户回合。命令文本即 [userRequest]；[model] 透传 `--model`（CLI 侧模型名，如 sonnet）；
     * [resume] 为 true 时先尝试 `--resume` 续接，失败自动转全新回合。
     */
    fun runTurn(
        sessionId: String,
        userRequest: String,
        projectPath: String?,
        model: String?,
        resume: Boolean
    ): Flow<AgentEvent> = flow {
        if (resume) {
            var emittedAny = false
            try {
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = true).collect {
                    emittedAny = true
                    emit(it)
                }
            } catch (e: ResumeSessionMissingException) {
                // 已向下游发过事件就不重试：重试会重复落库/重复展示（qa P2-3）
                if (emittedAny) throw RuntimeException("续接回合中途失败且已产出事件，不重试: ${e.message}", e)
                FileLogger.w(TAG, "续接会话缺失，转全新回合重试: ${e.message}")
                runTurnOnce(sessionId, userRequest, projectPath, model, resume = false).collect { emit(it) }
            }
        } else {
            runTurnOnce(sessionId, userRequest, projectPath, model, resume = false).collect { emit(it) }
        }
    }.catch { e ->
        emit(AgentEvent.Failed(error = e.message ?: e.javaClass.simpleName))
    }

    private fun runTurnOnce(
        sessionId: String,
        userRequest: String,
        projectPath: String?,
        model: String?,
        resume: Boolean
    ): Flow<AgentEvent> = flow {
        val port = permissionBridgeServer.ensureStarted(token = sessionId)
        writeBridgeAssets(port, sessionId, token = sessionId)

        val args = buildList {
            add("-p")
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--input-format"); add("stream-json")
            add("--include-partial-messages")
            // 用本体 sessionId 对齐 CLI 侧会话 id：新回合 --session-id 设定，续接回合 --resume 直接续上
            if (resume) {
                add("--resume"); add(sessionId)
            } else {
                add("--session-id"); add(sessionId)
            }
            add("--permission-prompt-tool"); add("mcp__${MCP_SERVER_NAME}__${MCP_TOOL_NAME}")
            add("--mcp-config"); add(mcpConfigPath())
            if (!model.isNullOrBlank()) { add("--model"); add(model) }
        }
        val extraEnv = mapOf(
            "PERMISSION_BRIDGE_PORT" to port.toString(),
            "PERMISSION_BRIDGE_SESSION" to sessionId,
            "PERMISSION_BRIDGE_TOKEN" to sessionId
        )

        FileLogger.i(TAG, "启动 claude 桥接进程: resume=$resume model=$model project=$projectPath")
        val process = containerEngine.startStdioProcess("claude", args, projectPath, extraEnv, resolveRuntimeProfile())

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
            // 回合只有一个用户输入：写完即关 stdin，claude 处理完输出 result 后自行退出
            withContext(Dispatchers.IO) {
                val w = process.outputStream.bufferedWriter()
                val payload = buildJsonObject {
                    put("type", "user")
                    put("message", buildJsonObject {
                        put("role", "user")
                        put("content", userRequest)
                    })
                }
                w.write(payload.toString())
                w.write("\n")
                w.flush()
                w.close()
            }

            var gotResult = false
            val acc = TurnAccumulator()
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
                throw classifyEarlyExit(tail)
            }
        } finally {
            killOnCancel?.dispose()
            stderrDrain.cancel()
            watchdog.cancel()
            permissionBridgeServer.removeToken(sessionId)
            runCatching { process.destroy() }
        }
    }

    /** 无 result 行即退出：区分「续接会话不存在」（可重试）与其它启动/执行失败。 */
    private fun classifyEarlyExit(stderrTail: String): Exception {
        val lower = stderrTail.lowercase()
        // 认证类失败（未登录/无 API key）：给出可操作指引，而不是让用户看裸报错
        if ("api key" in lower || "login" in lower || "unauthorized" in lower || "not logged in" in lower || "oauth" in lower) {
            return RuntimeException(
                "Claude Code 尚未认证：请在终端打开一次交互式 claude 并完成登录（或配置 ANTHROPIC_API_KEY），" +
                    "然后回到会话重发消息。原始错误：" + stderrTail.takeLast(300).trim()
            )
        }
        return if ("no conversation found" in lower || "session not found" in lower || "resume" in lower && "not found" in lower) {
            ResumeSessionMissingException(stderrTail.takeLast(400).trim())
        } else {
            RuntimeException("Claude Code 提前退出: " + stderrTail.takeLast(400).trim().ifEmpty { "（无 stderr 输出）" })
        }
    }

    // ── stream-json → AgentEvent 翻译 ────────────────────────────────

    /** 单回合累积状态：流式增量需拼成「全量累积文本」才能对齐 AgentEvent 契约（消费端是整体替换）。 */
    private class TurnAccumulator {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        /** tool_use id → 工具名：tool_result 回来时补齐 Finished 的 toolName。 */
        val toolNames = mutableMapOf<String, String>()
        var emittedAny = false
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
            "result" -> listOfNotNull(parseResult(obj))
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
                    if (name != null) listOf(AgentEvent.ToolCallPreparing(toolName = name)) else emptyList()
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
                    acc.toolNames[id] = name
                    toolCalls.add(ToolCall(id = id, name = name, arguments = input))
                }
            }
        }
        val usage = message["usage"] as? JsonObject
        val events = mutableListOf<AgentEvent>(
            AgentEvent.AssistantText(
                content = text.toString(),
                toolCalls = toolCalls,
                reasoning = reasoning.toString(),
                inputTokens = usage?.intOf("input_tokens") ?: 0,
                outputTokens = usage?.intOf("output_tokens") ?: 0,
                cachedInputTokens = usage?.intOf("cache_read_input_tokens") ?: 0
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
        return content.mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            if (b["type"]?.jsonPrimitive?.contentOrNull != "tool_result") return@mapNotNull null
            val toolUseId = b["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            AgentEvent.ToolCallFinished(
                id = toolUseId,
                toolName = acc.toolNames[toolUseId] ?: "",
                result = extractText(b["content"]),
                isError = b["is_error"]?.jsonPrimitive?.contentOrNull == "true" ||
                    (b["is_error"] as? JsonPrimitive)?.content == "true"
            )
        }
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
    private fun parseResult(obj: JsonObject): AgentEvent? {
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: return null
        val isError = (obj["is_error"] as? JsonPrimitive)?.content == "true" || subtype.startsWith("error")
        return if (isError) {
            val err = obj["result"]?.jsonPrimitive?.contentOrNull
                ?: obj["error"]?.jsonPrimitive?.contentOrNull
                ?: "Claude Code 运行失败（$subtype）"
            AgentEvent.Failed(error = err)
        } else {
            AgentEvent.Completed
        }
    }

    private fun JsonObject.intOf(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    // ── 桥接资产（脚本 + MCP 配置）──────────────────────────────────

    /** 每回合确保脚本与 MCP 配置就位（幂等写入，端口/会话可能变化）。 */
    private suspend fun writeBridgeAssets(port: Int, sessionId: String, token: String) = assetsMutex.withLock {
        runCatching {
            fileAccess.writeFile(bridgeScript(), BRIDGE_SCRIPT_SOURCE, overwrite = true)
            val config = buildJsonObject {
                put("mcpServers", buildJsonObject {
                    put(MCP_SERVER_NAME, buildJsonObject {
                        put("type", "stdio")
                        put("command", "node")
                        put("args", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(bridgeScript()))))
                        put("env", buildJsonObject {
                            put("PERMISSION_BRIDGE_PORT", port.toString())
                            put("PERMISSION_BRIDGE_SESSION", sessionId)
                            put("PERMISSION_BRIDGE_TOKEN", token)
                        })
                    })
                })
            }
            fileAccess.writeFile(mcpConfigPath(), config.toString(), overwrite = true)
        }.onFailure { e ->
            FileLogger.e(TAG, "写桥接资产失败", e)
            throw RuntimeException("初始化权限桥资产失败: ${e.message}", e)
        }
    }
}

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

function send(obj) { process.stdout.write(JSON.stringify(obj) + '\n'); }

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
    });
  } else {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
  }
}
"""
