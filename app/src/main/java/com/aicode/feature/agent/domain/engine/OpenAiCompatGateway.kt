package com.aicode.feature.agent.domain.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地 OpenAI→Anthropic 协议转换网关：让只有 OpenAI 兼容端点的厂商（未收录进
 * EngineVendorCompat 的自定义反代等）也能驱动 Claude Code 引擎。claude 以
 * ANTHROPIC_BASE_URL 指向本网关，网关把 /v1/messages 请求翻译成 OpenAI
 * chat/completions 转发给上游，响应（含 SSE 流式）反向翻译回 Anthropic 协议。
 * 上游 Key 只存在 App 侧注册表，不经过 claude 的环境变量。
 */
object OpenAiCompatGateway {
    private const val TAG = "EngineOpenAiCompat"

    /** 上游读超时：大模型长输出可能数分钟不发字节，放宽到 10 分钟。 */
    private const val UPSTREAM_READ_TIMEOUT_MS = 10 * 60_000
    private const val CONNECT_TIMEOUT_MS = 30_000

    private data class Upstream(val baseUrl: String, val apiKey: String, val fullUrl: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false
    private val upstreams = ConcurrentHashMap<String, Upstream>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 惰性启动监听（幂等），返回回环端口。 */
    suspend fun ensureStarted(): Int = startMutex.withLock {
        serverSocket?.let { return it.localPort }
        closed = false
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        com.aicode.core.util.FileLogger.i(TAG, "OpenAI 兼容转换网关监听 127.0.0.1:${socket.localPort}")
        socket.localPort
    }

    fun close() {
        closed = true
        scope.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** 注册某供应商的上游（幂等覆盖）；[fullUrl]=baseUrl 是完整 endpoint（含路径，直接 POST）。 */
    fun registerUpstream(key: String, baseUrl: String, apiKey: String, fullUrl: Boolean) {
        upstreams[key] = Upstream(baseUrl, apiKey, fullUrl)
    }

    /** claude 用的 ANTHROPIC_BASE_URL。 */
    fun converterBaseUrl(key: String, port: Int): String = "http://127.0.0.1:$port/cc-conv/$key"

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val conn = runCatching { socket.accept() }.getOrNull() ?: break
            scope.launch { runCatching { handleConnection(conn) }.onFailure {
                com.aicode.core.util.FileLogger.w(TAG, "转换请求处理失败: ${it.message}")
                runCatching { conn.close() }
            } }
        }
    }

    private fun handleConnection(conn: Socket) {
        conn.use { s ->
            s.soTimeout = UPSTREAM_READ_TIMEOUT_MS
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val buf = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            // 注册表键取路径段 /cc-conv/<key>/v1/messages
            val key = path.removePrefix("/cc-conv/").substringBefore('/')
            val upstream = upstreams[key]
            if (upstream == null) {
                respondError(s, 404, "unknown converter key")
                return
            }
            val anthropicBody = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (anthropicBody == null) {
                respondError(s, 400, "invalid JSON body")
                return
            }
            val stream = anthropicBody["stream"]?.jsonPrimitive?.contentOrNull == "true"
            val upstreamBody = buildOpenAiRequest(anthropicBody).toString()
            if (stream) proxyStream(s, upstream, upstreamBody) else proxyOnce(s, upstream, upstreamBody)
        }
    }

    // ── 上游调用 ────────────────────────────────────────────────

    private fun openUpstream(upstream: Upstream, body: String): HttpURLConnection {
        val url = if (upstream.fullUrl) upstream.baseUrl
        else upstream.baseUrl.trimEnd('/') + "/chat/completions"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = UPSTREAM_READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${upstream.apiKey}")
        conn.setRequestProperty("Accept", if (body.contains("\"stream\":true")) "text/event-stream" else "application/json")
        conn.getOutputStream().use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /** 非流式：一次性翻译。 */
    private fun proxyOnce(conn: Socket, upstream: Upstream, anthropicToOpenAiBody: String) {
        val upstreamConn = openUpstream(upstream, anthropicToOpenAiBody)
        val code = upstreamConn.responseCode
        val upstreamBody = (if (code in 200..299) upstreamConn.inputStream else upstreamConn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            respondError(conn, code, extractUpstreamError(upstreamBody))
            return
        }
        val openAi = runCatching { json.parseToJsonElement(upstreamBody).jsonObject }.getOrNull()
        if (openAi == null) {
            respondError(conn, 502, "upstream returned non-JSON body")
            return
        }
        val anthropic = mapNonStreamResponse(openAi)
        respond(conn, "application/json", anthropic.toString())
    }

    /** 流式：读上游 SSE 逐块翻译成 Anthropic 事件流（close 定界，写完即关）。 */
    private fun proxyStream(conn: Socket, upstream: Upstream, anthropicToOpenAiBody: String) {
        val upstreamConn = openUpstream(upstream, anthropicToOpenAiBody)
        val code = upstreamConn.responseCode
        if (code !in 200..299) {
            val err = upstreamConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            respondError(conn, code, extractUpstreamError(err))
            return
        }
        conn.getOutputStream().bufferedWriter().use { w ->
            w.write("HTTP/1.1 200 OK\r\n")
            w.write("Content-Type: text/event-stream\r\n")
            w.write("Cache-Control: no-cache\r\n")
            w.write("Connection: close\r\n\r\n")
            w.flush()
            val state = SseState()
            upstreamConn.inputStream.bufferedReader().forEachLine { line ->
                translateSseLine(state, line)?.let { events ->
                    events.forEach { w.write(it); w.write("\n") }
                    w.flush()
                }
            }
            state.finish()?.forEach { w.write(it); w.write("\n") }
            w.flush()
        }
    }

    private fun extractUpstreamError(body: String): String =
        runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.let { (it as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: it.toString() }
        }.getOrNull() ?: body.take(300)

    private fun respond(conn: Socket, contentType: String, body: String) {
        conn.getOutputStream().bufferedWriter().use { w ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            w.write("HTTP/1.1 200 OK\r\n")
            w.write("Content-Type: $contentType\r\n")
            w.write("Content-Length: ${bytes.size}\r\n")
            w.write("Connection: close\r\n\r\n")
            w.write(body)
            w.flush()
        }
    }

    private fun respondError(conn: Socket, code: Int, message: String) {
        val error = buildJsonObject {
            put("type", "error")
            put("error", buildJsonObject {
                put("type", if (code == 401 || code == 403) "authentication_error" else "api_error")
                put("message", message)
            })
        }.toString()
        conn.getOutputStream().bufferedWriter().use { w ->
            w.write("HTTP/1.1 $code ${if (code == 404) "Not Found" else "Bad Gateway"}\r\n")
            w.write("Content-Type: application/json\r\n")
            w.write("Content-Length: ${error.toByteArray(Charsets.UTF_8).size}\r\n")
            w.write("Connection: close\r\n\r\n")
            w.write(error)
            w.flush()
        }
    }

    // ── 协议翻译（纯函数，可单测）──────────────────────────────

    /** Anthropic /v1/messages 请求体 → OpenAI chat/completions 请求体。 */
    internal fun buildOpenAiRequest(a: JsonObject): JsonObject = buildJsonObject {
        put("model", a["model"]?.jsonPrimitive?.contentOrNull ?: "")
        // max_tokens 是数值，原样透传；转字符串会被严格 OpenAI 端点按类型错误拒绝
        a["max_tokens"]?.let { put("max_tokens", it) }
        a["temperature"]?.let { put("temperature", it) }
        a["top_p"]?.let { put("top_p", it) }
        a["stream"]?.let { put("stream", it) }
        a["stop_sequences"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { put("stop", it) }

        val messages = buildJsonArray {
            a["system"]?.let { sys ->
                val text = when (sys) {
                    is JsonPrimitive -> sys.content
                    is JsonArray -> sys.jsonArray.mapNotNull { b ->
                        (b as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.content == "text" }
                            ?.get("text")?.jsonPrimitive?.content
                    }.joinToString("\n")
                    else -> null
                }
                if (!text.isNullOrBlank()) add(buildJsonObject {
                    put("role", "system")
                    put("content", text)
                })
            }
            a["messages"]?.jsonArray?.forEach { m ->
                val msg = m as? JsonObject ?: return@forEach
                val role = msg["role"]?.jsonPrimitive?.content ?: return@forEach
                when (val content = msg["content"]) {
                    is JsonPrimitive -> add(buildJsonObject {
                        put("role", role)
                        put("content", content.content)
                    })
                    is JsonArray -> {
                        if (role == "assistant") addAssistantBlocks(content)
                        else addUserBlocks(content)
                    }
                    else -> {}
                }
            }
        }
        put("messages", messages)

        a["tools"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let { tools ->
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    val tool = t as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool["name"]?.jsonPrimitive?.content ?: "")
                            tool["description"]?.let { put("description", it) }
                            put("parameters", tool["input_schema"] ?: buildJsonObject { })
                        })
                    })
                }
            })
        }
        a["tool_choice"]?.let { choice ->
            when (choice) {
                is JsonPrimitive -> if (choice.content == "any") put("tool_choice", "required")
                is JsonObject -> if (choice["type"]?.jsonPrimitive?.content == "tool") {
                    put("tool_choice", buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", choice["name"]?.jsonPrimitive?.content ?: "")
                        })
                    })
                }
                else -> {}
            }
        }
    }

    /** assistant 块：text→content，tool_use→tool_calls（arguments 序列化为 JSON 字符串）。 */
    private fun kotlinx.serialization.json.JsonArrayBuilder.addAssistantBlocks(blocks: JsonArray) {
        val text = StringBuilder()
        val toolCalls = mutableListOf<JsonObject>()
        blocks.forEach { b ->
            val block = b as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> text.append(block["text"]?.jsonPrimitive?.content ?: "")
                "tool_use" -> toolCalls += buildJsonObject {
                    put("id", block["id"]?.jsonPrimitive?.content ?: "")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", block["name"]?.jsonPrimitive?.content ?: "")
                        put("arguments", block["input"]?.toString() ?: "{}")
                    })
                }
            }
        }
        add(buildJsonObject {
            put("role", "assistant")
            if (text.isNotEmpty()) put("content", text.toString())
            if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
        })
    }

    /** user 块：tool_result→role=tool 消息（须紧跟 assistant tool_calls），其余 text 合并成 user 消息。 */
    private fun kotlinx.serialization.json.JsonArrayBuilder.addUserBlocks(blocks: JsonArray) {
        val text = StringBuilder()
        blocks.forEach { b ->
            val block = b as? JsonObject ?: return@forEach
            when (block["type"]?.jsonPrimitive?.content) {
                "tool_result" -> {
                    val content = when (val c = block["content"]) {
                        is JsonPrimitive -> c.content
                        is JsonArray -> c.jsonArray.mapNotNull { r ->
                            (r as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.content == "text" }
                                ?.get("text")?.jsonPrimitive?.content
                        }.joinToString("\n")
                        else -> ""
                    }
                    val isError = block["is_error"]?.jsonPrimitive?.contentOrNull == "true"
                    add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", block["tool_use_id"]?.jsonPrimitive?.content ?: "")
                        put("content", if (isError) "[tool error] $content" else content)
                    })
                }
                "text" -> text.append(block["text"]?.jsonPrimitive?.content ?: "")
                "image" -> text.append("[image omitted by converter]")
                else -> {}
            }
        }
        if (text.isNotEmpty()) add(buildJsonObject {
            put("role", "user")
            put("content", text.toString())
        })
    }

    /** OpenAI 非流式响应 → Anthropic message。 */
    internal fun mapNonStreamResponse(o: JsonObject): JsonObject {
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = buildJsonArray {
            message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                add(buildJsonObject { put("type", "text"); put("text", it) })
            }
            (message?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                val call = tc as? JsonObject ?: return@forEach
                val fn = call["function"] as? JsonObject
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", call["id"]?.jsonPrimitive?.content ?: "toolu_${UUID.randomUUID()}")
                    put("name", fn?.get("name")?.jsonPrimitive?.content ?: "")
                    put("input", runCatching {
                        json.parseToJsonElement(fn?.get("arguments")?.jsonPrimitive?.content ?: "{}").jsonObject
                    }.getOrDefault(JsonObject(emptyMap())))
                })
            }
        }
        val usage = o["usage"] as? JsonObject
        return buildJsonObject {
            put("id", o["id"]?.jsonPrimitive?.content ?: "msg_${UUID.randomUUID()}")
            put("type", "message")
            put("role", "assistant")
            put("model", o["model"]?.jsonPrimitive?.content ?: "")
            put("content", content)
            put("stop_reason", mapStopReason(choice?.get("finish_reason")?.jsonPrimitive?.content))
            put("stop_sequence", kotlinx.serialization.json.JsonNull)
            put("usage", buildJsonObject {
                put("input_tokens", usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                put("output_tokens", usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            })
        }
    }

    private fun mapStopReason(openAi: String?): String = when (openAi) {
        "tool_calls", "function_call" -> "tool_use"
        "length" -> "max_tokens"
        "content_filter" -> "refusal"
        else -> "end_turn"
    }

    /** 流式翻译状态机：追踪当前打开的 content block、OpenAI 工具调用 index 与已见元数据。
     *  OpenAI 并行工具调用用 index 区分，Anthropic 侧对应多个顺序 tool_use 块。 */
    internal class SseState {
        var blockIndex = -1
        var openType: String? = null // "text" | "tool_use"
        var openToolIndex: Int? = null
        val toolMeta = HashMap<Int, Pair<String, String?>>() // openai index -> (id, name?)
        var model = ""
        var inputTokens = 0
        var outputTokens = 0
        var stopReason: String? = null
        var started = false
        var finished = false

        private fun event(name: String, data: JsonObject): List<String> =
            listOf("event: $name", "data: $data", "")

        /** 处理一行上游 SSE（data: {...} / data: [DONE]），返回要写出的行（可能多事件），无输出返回 null。 */
        fun translate(openAiData: JsonObject): List<String>? {
            val out = mutableListOf<String>()
            val choice = (openAiData["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            if (!started) {
                started = true
                model = openAiData["model"]?.jsonPrimitive?.content ?: model
                inputTokens = (openAiData["usage"] as? JsonObject)?.get("prompt_tokens")
                    ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                out += event("message_start", buildJsonObject {
                    put("type", "message_start")
                    put("message", buildJsonObject {
                        put("id", "msg_${UUID.randomUUID()}")
                        put("type", "message")
                        put("role", "assistant")
                        put("model", model)
                        put("content", JsonArray(emptyList()))
                        put("stop_reason", kotlinx.serialization.json.JsonNull)
                        put("stop_sequence", kotlinx.serialization.json.JsonNull)
                        put("usage", buildJsonObject {
                            put("input_tokens", inputTokens)
                            put("output_tokens", 0)
                        })
                    })
                })
            }
            (openAiData["usage"] as? JsonObject)?.let { u ->
                u["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull()?.let { if (it > 0) inputTokens = it }
                u["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull()?.let { if (it > 0) outputTokens = it }
            }
            val delta = choice?.get("delta") as? JsonObject
            val text = delta?.get("content")?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrEmpty()) {
                if (openType != "text") closeBlock(out)
                if (openType != "text") {
                    blockIndex++
                    openType = "text"
                    out += event("content_block_start", buildJsonObject {
                        put("type", "content_block_start")
                        put("index", blockIndex)
                        put("content_block", buildJsonObject { put("type", "text"); put("text", "") })
                    })
                }
                out += event("content_block_delta", buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", blockIndex)
                    put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) })
                })
            }
            (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                val call = tc as? JsonObject ?: return@forEach
                val fn = call["function"] as? JsonObject
                val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                val frag = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
                val index = call["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val id = call["id"]?.jsonPrimitive?.contentOrNull
                if (id != null || name != null) {
                    val prev = toolMeta[index]
                    toolMeta[index] = (id ?: prev?.first ?: "") to (name ?: prev?.second)
                }
                if (openType != "tool_use" || openToolIndex != index) {
                    // 新工具调用（含并行 index 切换）：关闭已开块，开新 tool_use 块
                    closeBlock(out)
                    val meta = toolMeta[index]
                    blockIndex++
                    openType = "tool_use"
                    openToolIndex = index
                    out += event("content_block_start", buildJsonObject {
                        put("type", "content_block_start")
                        put("index", blockIndex)
                        put("content_block", buildJsonObject {
                            put("type", "tool_use")
                            put("id", meta?.first ?: "toolu_${UUID.randomUUID()}")
                            put("name", meta?.second ?: "")
                            put("input", JsonObject(emptyMap()))
                        })
                    })
                }
                if (openType == "tool_use" && !frag.isNullOrEmpty()) {
                    out += event("content_block_delta", buildJsonObject {
                        put("type", "content_block_delta")
                        put("index", blockIndex)
                        put("delta", buildJsonObject {
                            put("type", "input_json_delta")
                            put("partial_json", frag)
                        })
                    })
                }
            }
            choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason = mapStopReason(it) }
            return out.ifEmpty { null }
        }

        fun closeBlock(out: MutableList<String>) {
            if (openType != null) {
                out += event("content_block_stop", buildJsonObject {
                    put("type", "content_block_stop")
                    put("index", blockIndex)
                })
                openType = null
                openToolIndex = null
            }
        }

        /** 上游结束（[DONE]/流关闭）后补齐收尾事件。 */
        fun finish(): List<String> {
            if (finished) return emptyList()
            finished = true
            val out = mutableListOf<String>()
            closeBlock(out)
            if (!started) return emptyList()
            out += event("message_delta", buildJsonObject {
                put("type", "message_delta")
                put("delta", buildJsonObject {
                    put("stop_reason", stopReason ?: "end_turn")
                    put("stop_sequence", kotlinx.serialization.json.JsonNull)
                })
                put("usage", buildJsonObject { put("output_tokens", outputTokens) })
            })
            out += event("message_stop", buildJsonObject { put("type", "message_stop") })
            return out
        }
    }

    /** 一行上游 SSE → 0..n 个 Anthropic 事件行。 */
    internal fun translateSseLine(state: SseState, line: String): List<String>? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return state.finish()
        val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return state.translate(chunk)
    }
}
