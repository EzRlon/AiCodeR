package com.aicode.feature.agent.domain.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地 OpenAI→Anthropic 协议转换网关：让只有 OpenAI 兼容端点的厂商（未收录进
 * EngineVendorCompat 的自定义端点等）也能驱动 Claude Code 引擎。claude 以
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

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false
    private val upstreams = ConcurrentHashMap<String, Upstream>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 惰性启动监听（幂等），返回回环端口。 */
    suspend fun ensureStarted(): Int = startMutex.withLock {
        serverSocket?.let { return it.localPort }
        closed = false
        // close() 会 cancel 掉旧 scope；协程作用域取消后不可复用，必须重建，
        // 否则 launch 静默不跑：端口能连上但请求永远无人处理（引擎"失踪"）
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    /** claude 厂商/ANTHROPIC 型直连用的透传路由（协议不转换，仅替换认证头并补齐残缺流尾）。 */
    fun passthruBaseUrl(key: String, port: Int): String = "http://127.0.0.1:$port/passthru/$key"

    /** 透传路由的注册表键（与转换路由键空间隔离）。 */
    fun passthruKey(providerId: String): String = "passthru_" + providerId.replace(Regex("[^\\w.-]"), "_")

    /** codex 用的 base_url（Responses→chat 转换路由，路由尾部已带版本段）。 */
    fun codexConverterBaseUrl(key: String, port: Int): String = "http://127.0.0.1:$port/codex-conv/$key/v1"

    /** codex 转换路由的注册表键（与 cc-conv 键空间隔离）。 */
    fun codexKey(providerId: String): String = "codex_" + providerId.replace(Regex("[^\\w.-]"), "_")

    /** 已解析的入站 HTTP 请求。body 按 Content-Length / chunked 以**字节**读取后转 UTF-8。 */
    internal data class IncomingRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /**
     * 从 socket 读取一个 HTTP/1.1 请求。
     *
     * 必须按字节读取请求体：Content-Length 是字节数，而 [java.io.Reader] 的 read 参数是
     * **字符数**；请求里一旦含中文/emoji（用户提示词或注入的中文系统提示），按字符读会
     * 少读若干个 char 后一直阻塞等更多数据，连接两端互等直到超时——这正是 Claude/Codex
     * 引擎「请求发出后毫无响应」的根因。此外兼容 Transfer-Encoding: chunked。
     */
    internal fun readHttpRequest(input: InputStream): IncomingRequest? {
        val headers = HashMap<String, String>()

        // 逐字节读一行（只用 ISO-8859-1 解码头部，保证与原始字节一一对应）。
        fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            return line.toString("ISO-8859-1")
        }

        val requestLine = readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val bodyBytes = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunkedBody(input)
        } else {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        }
        return IncomingRequest(parts[0], parts[1], headers, String(bodyBytes, Charsets.UTF_8))
    }

    /** 读 chunked 请求体（去 chunk 头与 CRLF，拼接实际数据；0 长度块结束）。 */
    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()

        fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            return line.toString("ISO-8859-1")
        }

        while (true) {
            val sizeLine = readLine()?.substringBefore(';')?.trim() ?: break
            val size = sizeLine.toIntOrNull(16) ?: break
            if (size == 0) {
                // 吃掉 trailer headers
                while (true) {
                    val trailer = readLine() ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n < 0) return out.toByteArray()
                read += n
            }
            out.write(buf, 0, read)
            readLine() // 块末尾 CRLF
        }
        return out.toByteArray()
    }

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
            val request = readHttpRequest(BufferedInputStream(s.getInputStream())) ?: return
            val path = request.path
            val headers = request.headers
            val body = request.body

            // /passthru/<key>/<上游路径>：claude 厂商端点透传（anthropic 协议原样转发 + 流尾补齐）
            if (path.startsWith("/passthru/")) {
                val rest = path.removePrefix("/passthru/")
                val pkey = rest.substringBefore('/')
                val upstream = upstreams[pkey]
                if (upstream == null) {
                    respondError(s, 404, "unknown passthru key")
                    return
                }
                val suffix = if (rest.contains('/')) rest.substringAfter('/') else ""
                if (suffix.isEmpty()) {
                    respondError(s, 404, "missing passthru path")
                    return
                }
                proxyPassthru(s, upstream, "/" + suffix, body, headers)
                return
            }
            // /codex-conv/<key>/v1/responses：codex 专用 Responses→chat 转换路由
            if (path.startsWith("/codex-conv/")) {
                val codexKey = path.removePrefix("/codex-conv/").substringBefore('/')
                val upstream = upstreams[codexKey]
                if (upstream == null) {
                    respondError(s, 404, "unknown codex converter key")
                    return
                }
                val responsesBody = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (responsesBody == null) {
                    respondError(s, 400, "invalid JSON body")
                    return
                }
                val chatBody = responsesToChatRequest(responsesBody).toString()
                val chatStream = responsesBody["stream"]?.jsonPrimitive?.booleanOrNull == true
                if (chatStream) proxyResponsesStream(s, upstream, chatBody)
                else proxyResponsesOnce(s, upstream, chatBody)
                return
            }
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
            // claude 的 /v1/messages 请求里 stream 是 JSON 布尔，不是字符串
            val stream = anthropicBody["stream"]?.jsonPrimitive?.booleanOrNull == true
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

    /** 透传：anthropic 兼容端点请求原样转发（仅替换认证头），SSE 流尾残缺时补齐。 */
    private fun proxyPassthru(conn: Socket, upstream: Upstream, suffix: String, body: String, reqHeaders: Map<String, String>) {
        val url = upstream.baseUrl.trimEnd('/') + suffix
        val upstreamConn = URL(url).openConnection() as HttpURLConnection
        upstreamConn.requestMethod = "POST"
        upstreamConn.connectTimeout = CONNECT_TIMEOUT_MS
        upstreamConn.readTimeout = UPSTREAM_READ_TIMEOUT_MS
        upstreamConn.doOutput = true
        upstreamConn.setRequestProperty("Content-Type", "application/json")
        upstreamConn.setRequestProperty("Accept", "text/event-stream, application/json")
        // apiKeyHelper 输出同时进 x-api-key 与 Bearer 两种头，第三方两种风格全覆盖
        upstreamConn.setRequestProperty("x-api-key", upstream.apiKey)
        upstreamConn.setRequestProperty("Authorization", "Bearer ${upstream.apiKey}")
        reqHeaders["anthropic-version"]?.let { upstreamConn.setRequestProperty("anthropic-version", it) }
        reqHeaders["anthropic-beta"]?.let { upstreamConn.setRequestProperty("anthropic-beta", it) }
        upstreamConn.getOutputStream().use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = upstreamConn.responseCode
        if (code !in 200..299) {
            val err = upstreamConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            respondError(conn, if (code >= 400) code else 502, extractUpstreamError(err))
            return
        }
        val contentType = upstreamConn.contentType ?: ""
        if (!contentType.contains("event-stream")) {
            val text = upstreamConn.inputStream.bufferedReader().use { it.readText() }
            respond(conn, contentType.ifBlank { "application/json" }, text)
            return
        }
        conn.getOutputStream().bufferedWriter().use { w ->
            w.write("HTTP/1.1 200 OK\r\n")
            w.write("Content-Type: text/event-stream\r\n")
            w.write("Cache-Control: no-cache\r\n")
            w.write("Connection: close\r\n\r\n")
            w.flush()
            val state = PassthruSseState()
            try {
                upstreamConn.inputStream.bufferedReader().forEachLine { line ->
                    w.write(line); w.write("\n")
                    state.observe(line)
                    w.flush()
                }
            } catch (e: java.io.IOException) {
                // 上游掐线：已出正文时照常补尾，claude 才不会把整段当中断撤回或挂死（cc streamfix 实测语义）
            }
            state.finish().forEach { (name, data) ->
                w.write("event: $name\n")
                w.write("data: $data\n\n")
            }
            w.flush()
        }
    }

    /** 透传流尾补齐状态机：跟踪 anthropic SSE 事件序列，上游结束时残缺则补尾。 */
    internal class PassthruSseState {
        private var messageStarted = false
        private var sawContent = false
        private var deltaSent = false
        private var stopSent = false
        private val openBlocks = LinkedHashSet<Int>()

        fun observe(line: String) {
            if (!line.startsWith("data:")) return
            val obj = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: return
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> messageStarted = true
                "content_block_start" -> openBlocks.add(obj["index"]?.jsonPrimitive?.intOrNull ?: 0)
                "content_block_stop" -> openBlocks.remove(obj["index"]?.jsonPrimitive?.intOrNull ?: 0)
                "content_block_delta" -> sawContent = true
                "message_delta" -> deltaSent = true
                "message_stop" -> stopSent = true
            }
        }

        fun finish(): List<Pair<String, String>> {
            if (!messageStarted || stopSent || !sawContent) return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            for (idx in openBlocks) {
                out += "content_block_stop" to buildJsonObject {
                    put("type", "content_block_stop"); put("index", idx)
                }.toString()
            }
            openBlocks.clear()
            if (!deltaSent) {
                out += "message_delta" to buildJsonObject {
                    put("type", "message_delta")
                    put("delta", buildJsonObject { put("stop_reason", "end_turn"); put("stop_sequence", JsonNull) })
                    put("usage", buildJsonObject { put("output_tokens", 1) })
                }.toString()
            }
            out += "message_stop" to buildJsonObject { put("type", "message_stop") }.toString()
            stopSent = true
            return out
        }
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
            try {
                upstreamConn.inputStream.bufferedReader().forEachLine { line ->
                    translateSseLine(state, line)?.let { events ->
                        events.forEach { w.write(it); w.write("\n") }
                        w.flush()
                    }
                }
            } catch (e: java.io.IOException) {
                // 上游掐线：已出正文且块闭合时照常补尾（见 SseState.finish），整段撤回比报错更糟
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

    // ── Responses→chat 转换（codex 路由专用）──────────────────

    /** Responses API 请求体 → chat/completions 请求体。 */
    internal fun responsesToChatRequest(responses: JsonObject): JsonObject = buildJsonObject {
        responses["model"]?.let { put("model", it) }
        put("messages", buildJsonArray {
            (responses["instructions"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", it)
                })
            }
            when (val input = responses["input"]) {
                is JsonPrimitive -> add(buildJsonObject {
                    put("role", "user")
                    put("content", input.content)
                })
                is JsonArray -> responsesItemsToMessages(input).forEach { add(it) }
                else -> {}
            }
        })
        responses["stream"]?.let { put("stream", it) }
        if (responses["stream"]?.jsonPrimitive?.booleanOrNull == true) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        (responses["max_output_tokens"] as? JsonPrimitive)?.let { put("max_tokens", it) }
        responses["temperature"]?.let { put("temperature", it) }
        responses["top_p"]?.let { put("top_p", it) }
        (responses["tools"] as? JsonArray)?.takeIf { !it.isEmpty() }?.let { tools ->
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    val o = t as? JsonObject ?: return@forEach
                    if (o["type"]?.jsonPrimitive?.contentOrNull == "function") {
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", o["name"]?.jsonPrimitive?.contentOrNull ?: "")
                                (o["description"] as? JsonNull) ?: o["description"]?.let { put("description", it) }
                                (o["parameters"] as? JsonNull) ?: o["parameters"]?.let { put("parameters", it) }
                            })
                        })
                    }
                }
            })
        }
        responses["tool_choice"]?.let { tc ->
            when (tc) {
                is JsonPrimitive -> put("tool_choice", tc)
                is JsonObject -> if (tc["type"]?.jsonPrimitive?.contentOrNull == "function") {
                    put("tool_choice", buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject { put("name", tc["name"]?.jsonPrimitive?.contentOrNull ?: "") })
                    })
                }
                else -> {}
            }
        }
        responses["parallel_tool_calls"]?.let { put("parallel_tool_calls", it) }
    }

    /** Responses input 数组项 → chat messages。 */
    private fun responsesItemsToMessages(items: JsonArray): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        items.forEach { el ->
            val item = el as? JsonObject ?: return@forEach
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    val rawRole = item["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                    // codex 用 developer 角色（Responses 语义），chat 兼容端点普遍只认 system/user/assistant/tool
                    val role = if (rawRole == "developer") "system" else rawRole
                    val text = when (val content = item["content"]) {
                        is JsonPrimitive -> content.content
                        is JsonArray -> content.joinToString("") { part ->
                            val p = part as? JsonObject ?: return@joinToString ""
                            val type = p["type"]?.jsonPrimitive?.contentOrNull
                            if (type == "input_text" || type == "output_text" || type == "text" || type == "refusal")
                                p["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            else ""
                        }
                        else -> ""
                    }
                    messages += buildJsonObject {
                        put("role", role)
                        put("content", text)
                    }
                }
                "function_call" -> {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val toolCall = buildJsonObject {
                        put("id", callId)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", item["name"]?.jsonPrimitive?.contentOrNull ?: "")
                            put("arguments", item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
                        })
                    }
                    val last = messages.lastOrNull()
                    if (last?.get("role")?.jsonPrimitive?.contentOrNull == "assistant" && last["tool_calls"] is JsonArray) {
                        messages[messages.size - 1] = buildJsonObject {
                            last.forEach { (k, v) -> if (k != "tool_calls") put(k, v) }
                            put("tool_calls", buildJsonArray {
                                (last["tool_calls"] as JsonArray).forEach { add(it) }
                                add(toolCall)
                            })
                        }
                    } else {
                        messages += buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonNull)
                            put("tool_calls", buildJsonArray { add(toolCall) })
                        }
                    }
                }
                "function_call_output" -> {
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", item["call_id"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("content", when (val out = item["output"]) {
                            is JsonPrimitive -> out.content
                            is JsonArray, is JsonObject -> out.toString()
                            else -> ""
                        })
                    }
                }
                // reasoning 等其余类型不映射进 chat 历史
            }
        }
        return messages
    }

    /** 非流式：转发 chat 请求并把响应翻译回 Responses 对象。 */
    private fun proxyResponsesOnce(conn: Socket, upstream: Upstream, chatBody: String) {
        val upstreamConn = openUpstream(upstream, chatBody)
        val code = upstreamConn.responseCode
        val upstreamResp = (if (code in 200..299) upstreamConn.inputStream else upstreamConn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            respondError(conn, code, extractUpstreamError(upstreamResp))
            return
        }
        val chat = runCatching { json.parseToJsonElement(upstreamResp).jsonObject }.getOrNull()
        if (chat == null) {
            respondError(conn, 502, "upstream returned non-JSON body")
            return
        }
        respond(conn, "application/json", chatResponseToResponse(chat).toString())
    }

    /** chat/completions 非流式响应 → Responses response 对象。 */
    internal fun chatResponseToResponse(chat: JsonObject): JsonObject {
        val choice = (chat["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val text = message?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
        return buildJsonObject {
            put("id", "resp_${UUID.randomUUID()}")
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("status", "completed")
            put("model", chat["model"]?.jsonPrimitive?.contentOrNull ?: "")
            put("output", buildJsonArray {
                if (text.isNotEmpty()) {
                    add(messageOutputItem(text, "completed"))
                }
                (message?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                    val o = tc as? JsonObject ?: return@forEach
                    val fn = o["function"] as? JsonObject
                    add(buildJsonObject {
                        put("type", "function_call")
                        put("id", "fc_${UUID.randomUUID().toString().replace("-", "").take(24)}")
                        put("call_id", o["id"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("name", fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "")
                        put("arguments", fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}")
                        put("status", "completed")
                    })
                }
            })
            put("output_text", text)
            put("usage", usageObject(chat["usage"] as? JsonObject))
        }
    }

    /** 流式：读上游 chat SSE 逐块翻译成 Responses 事件流（close 定界，写完即关）。 */
    private fun proxyResponsesStream(conn: Socket, upstream: Upstream, chatBody: String) {
        val upstreamConn = openUpstream(upstream, chatBody)
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
            val state = ResponsesSseState()
            try {
                upstreamConn.inputStream.bufferedReader().forEachLine { line ->
                    translateResponsesSseLine(state, line)?.forEach { (name, data) ->
                        w.write("event: $name\n")
                        w.write("data: $data\n\n")
                    }
                    w.flush()
                }
            } catch (e: java.io.IOException) {
                // 上游掐线：照常补 response.completed，codex 收到完整收尾才不反复重试
            }
            state.finish().forEach { (name, data) ->
                w.write("event: $name\n")
                w.write("data: $data\n\n")
            }
            w.flush()
        }
    }

    /** 一行上游 chat SSE → 0..n 个 (Responses 事件名, 事件体)。 */
    internal fun translateResponsesSseLine(state: ResponsesSseState, line: String): List<Pair<String, JsonObject>>? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return state.finish()
        val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return state.translate(chunk)
    }

    private fun messageOutputItem(fullText: String, status: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", "msg_${UUID.randomUUID().toString().replace("-", "").take(24)}")
        put("role", "assistant")
        put("status", status)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "output_text")
                put("text", fullText)
                put("annotations", JsonArray(emptyList()))
            })
        })
    }

    private fun usageObject(u: JsonObject?): JsonObject = buildJsonObject {
        put("input_tokens", u?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L)
        put("output_tokens", u?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L)
        put("total_tokens", u?.get("total_tokens")?.jsonPrimitive?.longOrNull ?: 0L)
    }

    /** Responses 流式翻译状态机：chat 增量块 → Responses 事件序列。 */
    internal class ResponsesSseState {
        private val responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").take(24)
        private val createdAt = System.currentTimeMillis() / 1000
        private var model = ""
        private var created = false
        private var messageOpened = false
        private val text = StringBuilder()
        private var nextOutputIndex = 0
        private val tools = LinkedHashMap<Int, ToolAccum>()
        private var usage: JsonObject? = null
        private var finished = false

        private class ToolAccum {
            var itemIndex = -1
            val itemId = "fc_" + UUID.randomUUID().toString().replace("-", "").take(24)
            var callId = ""
            var name = ""
            val args = StringBuilder()
        }

        fun translate(chunk: JsonObject): List<Pair<String, JsonObject>>? {
            val out = mutableListOf<Pair<String, JsonObject>>()
            if (!created) {
                created = true
                model = chunk["model"]?.jsonPrimitive?.contentOrNull ?: ""
                out += "response.created" to buildJsonObject {
                    put("type", "response.created")
                    put("response", responseEnvelope("in_progress"))
                }
            }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val delta = choice?.get("delta") as? JsonObject
            val textDelta = delta?.get("content")?.jsonPrimitive?.contentOrNull
            if (!textDelta.isNullOrEmpty()) {
                if (!messageOpened) {
                    messageOpened = true
                    val itemIndex = nextOutputIndex++
                    out += itemAdded(itemIndex, messageOutputItem("", "in_progress"))
                }
                text.append(textDelta)
                out += "response.output_text.delta" to buildJsonObject {
                    put("type", "response.output_text.delta")
                    put("item_id", "")
                    put("output_index", 0)
                    put("content_index", 0)
                    put("delta", textDelta)
                }
            }
            (delta?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                val o = tc as? JsonObject ?: return@forEach
                val index = o["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val fn = o["function"] as? JsonObject
                val acc = tools[index]
                if (acc == null) {
                    val fresh = ToolAccum().also { it.itemIndex = nextOutputIndex++ }
                    tools[index] = fresh
                    out += itemAdded(fresh.itemIndex, functionCallItem(fresh, "in_progress"))
                } else acc
                val target = tools.getValue(index)
                o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { target.callId = it }
                fn?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    if (target.name.isEmpty()) target.name = it
                }
                fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { frag ->
                    target.args.append(frag)
                    out += "response.function_call_arguments.delta" to buildJsonObject {
                        put("type", "response.function_call_arguments.delta")
                        put("item_id", target.itemId)
                        put("output_index", target.itemIndex)
                        put("delta", frag)
                    }
                }
            }
            (chunk["usage"] as? JsonObject)?.let { usage = it }
            return out.ifEmpty { null }
        }

        /** 上游结束（[DONE]/流关闭）后补齐收尾事件。 */
        fun finish(): List<Pair<String, JsonObject>> {
            if (finished) return emptyList()
            finished = true
            val out = mutableListOf<Pair<String, JsonObject>>()
            if (!created) {
                created = true
                out += "response.created" to buildJsonObject {
                    put("type", "response.created")
                    put("response", responseEnvelope("in_progress"))
                }
            }
            val output = buildJsonArray {
                if (messageOpened || text.isNotEmpty()) {
                    add(messageOutputItem(text.toString(), "completed"))
                }
                tools.values.sortedBy { it.itemIndex }.forEach { acc ->
                    add(functionCallItem(acc, "completed"))
                }
            }
            output.forEach { item ->
                out += "response.output_item.done" to buildJsonObject {
                    put("type", "response.output_item.done")
                    put("output_index", 0)
                    put("item", item)
                }
            }
            out += "response.completed" to buildJsonObject {
                put("type", "response.completed")
                put("response", buildJsonObject {
                    put("id", responseId)
                    put("object", "response")
                    put("created_at", createdAt)
                    put("status", "completed")
                    put("model", model)
                    put("output", output)
                    put("usage", usageObject(usage))
                })
            }
            return out
        }

        private fun itemAdded(index: Int, item: JsonObject) = "response.output_item.added" to buildJsonObject {
            put("type", "response.output_item.added")
            put("output_index", index)
            put("item", item)
        }

        private fun responseEnvelope(status: String): JsonObject = buildJsonObject {
            put("id", responseId)
            put("object", "response")
            put("created_at", createdAt)
            put("status", status)
            put("model", model)
            put("output", JsonArray(emptyList()))
            put("usage", usageObject(null))
        }

        private fun functionCallItem(acc: ToolAccum, status: String): JsonObject = buildJsonObject {
            put("type", "function_call")
            put("id", acc.itemId)
            put("call_id", acc.callId)
            put("name", acc.name)
            put("arguments", acc.args.toString().ifEmpty { "{}" })
            put("status", status)
        }
    }
}
