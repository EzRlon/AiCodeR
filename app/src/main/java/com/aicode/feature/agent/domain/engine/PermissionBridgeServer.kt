package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.annotation.data.AnnotationRepository
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.fixledger.data.FixLedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLI 权限桥的 App 侧端点。
 *
 * 容器内的 Claude Code 以 `--permission-prompt-tool` 挂上我们随包安装的 MCP 小服务
 * （claude-permission-bridge.mjs）；该服务把每次权限请求经 **本机 TCP 回环口**（PRoot 与
 * 宿主共享网络栈，与 VNC/llama-server 同先例）打到本类，这里翻译成 [PendingToolPermission]
 * 走 [ToolPermissionManager] 挂起等待用户裁决（聊天弹窗 / 通知栏 / 悬浮球自动全通），
 * 再把决定回传给 MCP 服务 → claude 收到 allow/deny。
 *
 * 线上协议（一行一 JSON，由脚本发起、本类应答）：
 * 请求 `{"session":"<sessionId>","tool_name":"Bash","input":{...}}`
 * 响应 `{"behavior":"allow","updatedInput":{...}}` 或 `{"behavior":"deny","message":"..."}`。
 */
@Singleton
class PermissionBridgeServer @Inject constructor(
    private val toolPermissionManager: ToolPermissionManager,
    private val fixLedgerRepository: FixLedgerRepository,
    private val annotationRepository: com.aicode.feature.annotation.data.AnnotationRepository,
    private val recallTool: com.aicode.feature.agent.domain.tool.recall.RecallTool,
    private val graphSearchTool: com.aicode.feature.memorygraph.domain.tool.GraphSearchTool,
    private val fileAccess: com.aicode.feature.workspace.domain.FileAccessProvider,
    private val deliveryRegistry: CliDeliveryRegistry
) {
    private companion object {
        const val TAG = "EnginePermissionBridge"

        /** 等脚本发来请求行的读超时：claude 的 canUseTool 调用即连即发，5 分钟足够宽。 */
        const val CONNECT_READ_TIMEOUT_MS = 5 * 60_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** 鉴权 token 集合：随 env 下发给容器内脚本，请求行必须带回；支持并发多 CLI 会话（qa FULL P1-1）。
     *
     * 不用 sessionId 当 token：sessionId 会出现在聊天界面、`session-<id>.log` 文件名与容器内 env，
     * 本机任意进程拿到它就能带 token 连回环口发 `kind=data`，无弹窗读全部历史会话原文。
     * token 与会话 id 的对应关系另存，用于数据通道的「仅当前活跃会话」校验。 */
    private val expectedTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** token → 会话 id：数据通道据此判断请求是否来自某个仍在跑的会话。 */
    private val tokenSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false

    /** 惰性启动监听（幂等），返回回环端口。[token] 为本实例的请求鉴权令牌（随机串，与会话 id 无关）。 */
    suspend fun ensureStarted(token: String, sessionId: String): Int = startMutex.withLock {
        expectedTokens.add(token)
        tokenSessions[token] = sessionId
        serverSocket?.let { return it.localPort }
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        FileLogger.i(TAG, "权限桥监听 127.0.0.1:${socket.localPort}")
        socket.localPort
    }

    /** 回合结束后移除该会话的 token（并发会话互不干扰）。 */
    fun removeToken(token: String) {
        expectedTokens.remove(token)
        tokenSessions.remove(token)
    }

    /** 生成本次回合的随机鉴权令牌（与会话 id 解耦，避免从 UI/日志/环境变量泄漏后被人直接复用）。 */
    fun newToken(): String = UUID.randomUUID().toString()

    fun close() {
        closed = true
        scope.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val conn = runCatching { socket.accept() }.getOrNull() ?: break
            scope.launch { runCatching { handleConnection(conn) } }
        }
    }

    private suspend fun handleConnection(conn: Socket) {
        try {
            conn.soTimeout = CONNECT_READ_TIMEOUT_MS
            val reader = conn.getInputStream().bufferedReader()
            val line = reader.readLine() ?: return
            val request = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return
            // 鉴权：token 由启动方经 env 下发给脚本，脚本原样带回；不匹配即丢弃（防伪造弹窗）。
            // 回写 deny：否则 mjs 侧会空等满 5 分钟超时（发版 optimizer P2-3）
            if (request["token"]?.jsonPrimitive?.content !in expectedTokens) {
                FileLogger.w(TAG, "权限桥请求 token 不匹配，丢弃")
                runCatching {
                    conn.getOutputStream().bufferedWriter().use { w ->
                        w.write("{\"behavior\":\"deny\",\"message\":\"token mismatch\"}")
                        w.write("\n")
                        w.flush()
                    }
                }
                return
            }
            val sessionId = request["session"]?.jsonPrimitive?.content.orEmpty()
            val kind = request["kind"]?.jsonPrimitive?.contentOrNull
            val toolName = request["tool_name"]?.jsonPrimitive?.content ?: return
            val toolInput = request["input"] as? JsonObject ?: JsonObject(emptyMap())

            // 数据通道（aicode_bridge 的 ledger_* / recall_search 工具）：只读查询与状态推进，不经权限弹窗。
            // 因此必须确认请求确实来自一个仍在跑的会话（token 与会话 id 绑定，removeToken 后即失效），
            // 否则任意拿到 token 的本机进程都能无弹窗读历史原文。
            if (kind == "data") {
                val presentedToken = request["token"]?.jsonPrimitive?.contentOrNull
                val boundSession = presentedToken?.let { tokenSessions[it] }
                if (boundSession == null || boundSession != sessionId) {
                    FileLogger.w(TAG, "数据通道请求的会话与 token 不匹配，丢弃: tool=$toolName")
                    val denied = errData("会话已结束或 token 无效")
                    conn.getOutputStream().bufferedWriter().use { w ->
                        w.write(denied.toString())
                        w.write("\n")
                        w.flush()
                    }
                    return
                }
                val dataResponse = handleDataRequest(toolName, toolInput, sessionId)
                conn.getOutputStream().bufferedWriter().use { w ->
                    w.write(dataResponse.toString())
                    w.write("\n")
                    w.flush()
                }
                FileLogger.i(TAG, "CLI 数据请求: tool=$toolName")
                return
            }

            FileLogger.i(TAG, "CLI 权限请求: tool=$toolName session=$sessionId")
            val outcome = toolPermissionManager.awaitApproval(
                PendingToolPermission(
                    id = UUID.randomUUID().toString(),
                    toolName = toolName,
                    title = "Claude Code · $toolName",
                    summary = "CLI 引擎请求使用 $toolName",
                    details = toolInput.toString(),
                    // CLI 工具调用不在本体验权策略引擎管辖内，不做「始终允许」记忆（UI 自动禁用）。
                    argsPreview = compactPreview(toolInput),
                    sessionId = sessionId
                )
            )
            val allow = outcome is com.aicode.feature.agent.domain.tool.ToolApprovalOutcome.Decided &&
                outcome.choice != PermissionChoice.REJECT
            val response = if (allow) {
                buildJsonObject {
                    put("behavior", "allow")
                    put("updatedInput", toolInput)
                }
            } else {
                buildJsonObject {
                    put("behavior", "deny")
                    put("message", "用户在授权中心拒绝了本次 $toolName 调用")
                }
            }
            conn.getOutputStream().bufferedWriter().use { w ->
                w.write(response.toString())
                w.write("\n")
                w.flush()
            }
            FileLogger.i(TAG, "CLI 权限裁决: tool=$toolName allow=$allow")
        } finally {
            runCatching { conn.close() }
        }
    }

    /**
     * `send_file`：把工作区文件登记为本次会话的交付物，等桥在解析工具结果时取走挂成文件卡片。
     *
     * 与内置 `SendFileTool` 保持同一套语义：**原子**——任一文件无效则整体失败，不登记任何附件。
     * 否则用户会看到半份交付，且模型收到的"成功"与实际不符。
     */
    private fun handleSendFile(input: JsonObject, sessionId: String): JsonObject {
        val paths = CliDeliveryPayload.extractPaths(input)
        if (paths.isEmpty()) return errData("paths 不能为空")

        val payload = CliDeliveryPayload.build(paths, fileAccess)
        if (!payload.ok) {
            return errData(
                "本次未交付任何文件（全部路径必须有效）：\n" + payload.errors.joinToString("\n")
            )
        }

        deliveryRegistry.record(sessionId, payload.attachments)
        FileLogger.i(TAG, "CLI 交付登记: session=$sessionId files=${payload.attachments.size}")
        return okData(
            "已交付 ${payload.attachments.size} 个文件到聊天区：\n" +
                payload.attachments.joinToString("\n") { it.fileName }
        )
    }

    private fun compactPreview(input: JsonObject): String {
        val text = input.toString()
        return if (text.length <= 300) text else text.take(300) + "…"
    }

    // ── 数据通道（aicode_bridge 的 ledger_* 工具）─────────────────────

    /** 账本类数据请求：只读合流列表与状态推进（与本体 fixledger 工具同语义，只能前进，关闭由用户手动）。 */
    private suspend fun handleDataRequest(tool: String, input: JsonObject, sessionId: String): JsonObject {
        return when (tool) {
            "send_file" -> handleSendFile(input, sessionId)
            "ledger_list" -> {
                val annotations = annotationRepository.items.first()
                val fixes = fixLedgerRepository.items.first()
                okData(renderLedgerText(annotations, fixes))
            }
            "ledger_advance" -> {
                val annotationId = input["annotationId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val target = input["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (annotationId.isBlank()) return errData("缺少 annotationId")
                val status = runCatching { AnnotationStatus.valueOf(target) }.getOrNull()
                    ?: return errData("target 非法，可选 FIXING / REVIEW")
                if (status == AnnotationStatus.PENDING || status == AnnotationStatus.CLOSED) {
                    return errData("只能推进到 FIXING / REVIEW（待确认与关闭由用户手动）")
                }
                // advanceLinked 在「标注不存在 / 不能后退 / 重复推进」时返回 null——必须如实报错，不能报假成功
                val message = try {
                    fixLedgerRepository.advanceLinked(annotationId, status)
                } catch (e: Exception) {
                    return errData("推进失败：" + (e.message ?: e.javaClass.simpleName))
                }
                if (message == null) {
                    return errData("无法推进：标注不存在，或状态不能后退/重复推进（当前可能已在目标状态之后）")
                }
                okData(message)
            }
            "recall_search" -> {
                // 跨会话档案检索：复用本体 RecallTool（FTS4 + CJK LIKE 降级），scope=session 用当前桥接会话 id
                val context = com.aicode.feature.agent.domain.model.AgentContext(
                    currentFile = null,
                    selectedCode = null,
                    // as? JsonPrimitive 而非 jsonPrimitive：模型可能传非字符串，抛异常会吞掉响应导致 mjs 侧等满超时（qa P2-2）
                    projectRoot = (input["projectRoot"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty(),
                    language = null,
                    sessionId = sessionId.ifBlank { null }
                )
                when (val result = recallTool.executeWithContext(input, context)) {
                    is com.aicode.feature.agent.domain.tool.ToolResult.Success -> {
                        // recall 的成功结果是纯文本 JsonPrimitive；非原始值兑底 JSON 字符串
                        val text = (result.data as? kotlinx.serialization.json.JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?: result.data.toString()
                        okData(text)
                    }
                    is com.aicode.feature.agent.domain.tool.ToolResult.Error -> errData(result.message)
                    is com.aicode.feature.agent.domain.tool.ToolResult.Partial -> okData(result.message)
                }
            }
            "graph_search" -> {
                // 图谱结论检索：复用本体 GraphSearchTool（只看当前有效结论，可含已失效）
                val context = com.aicode.feature.agent.domain.model.AgentContext(
                    currentFile = null,
                    selectedCode = null,
                    projectRoot = (input["projectRoot"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty(),
                    language = null,
                    sessionId = sessionId.ifBlank { null }
                )
                when (val result = graphSearchTool.executeWithContext(input, context)) {
                    is com.aicode.feature.agent.domain.tool.ToolResult.Success -> {
                        val text = (result.data as? kotlinx.serialization.json.JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?: result.data.toString()
                        okData(text)
                    }
                    is com.aicode.feature.agent.domain.tool.ToolResult.Error -> errData(result.message)
                    is com.aicode.feature.agent.domain.tool.ToolResult.Partial -> okData(result.message)
                }
            }
            else -> errData("未知数据工具: $tool")
        }
    }

    private fun renderLedgerText(annotations: List<com.aicode.feature.annotation.domain.model.AnnotationItem>, fixes: List<com.aicode.feature.fixledger.domain.model.FixLedgerItem>): String {
        val sb = StringBuilder()
        sb.appendLine("标注账本（" + annotations.size + " 条）：")
        annotations.forEach { a ->
            sb.appendLine("- id=" + a.id + " [" + a.status.name + "] " + a.type.name + " | " + a.note.ifBlank { "（未填说明）" } + " | 图：" + a.imagePath)
        }
        sb.appendLine("修复账本（" + fixes.size + " 条）：")
        fixes.forEach { f ->
            sb.appendLine("- id=" + f.id + " [" + f.status.name + "] " + f.title + " | " + f.note.ifBlank { "（无备注）" } + " | 源标注：" + (f.sourceAnnotationId ?: "-"))
        }
        return sb.toString()
    }

    private fun okData(result: String): JsonObject = buildJsonObject {
        put("ok", true)
        put("result", result)
    }

    private fun errData(message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("message", message)
    }
}
