package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
    private val toolPermissionManager: ToolPermissionManager
) {
    private companion object {
        const val TAG = "EnginePermissionBridge"

        /** 等脚本发来请求行的读超时：claude 的 canUseTool 调用即连即发，5 分钟足够宽。 */
        const val CONNECT_READ_TIMEOUT_MS = 5 * 60_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** 一次性鉴权 token：随 env 下发给容器内脚本，请求行必须带回（防本机其它应用连端口伪造弹窗）。 */
    /** 鉴权 token 集合：随 env 下发给容器内脚本，请求行必须带回；支持并发多 CLI 会话（qa FULL P1-1）。 */
    private val expectedTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false

    /** 惰性启动监听（幂等），返回回环端口。[token] 为本实例的请求鉴权令牌。 */
    suspend fun ensureStarted(token: String): Int = startMutex.withLock {
        expectedTokens.add(token)
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
    }

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
            // 鉴权：token 由启动方经 env 下发给脚本，脚本原样带回；不匹配即丢弃（防伪造弹窗）
            if (request["token"]?.jsonPrimitive?.content !in expectedTokens) {
                FileLogger.w(TAG, "权限桥请求 token 不匹配，丢弃")
                return
            }
            val sessionId = request["session"]?.jsonPrimitive?.content.orEmpty()
            val toolName = request["tool_name"]?.jsonPrimitive?.content ?: return
            val toolInput = request["input"] as? JsonObject ?: JsonObject(emptyMap())

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

    private fun compactPreview(input: JsonObject): String {
        val text = input.toString()
        return if (text.length <= 300) text else text.take(300) + "…"
    }
}
