package com.aicoder.app.mcp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子程序内嵌 MCP server（MCP 2026-07-28 Streamable HTTP 简化模式）：
 * 单端点 POST /mcp，JSON-RPC 2.0，简单响应模式（无 SSE/会话，合规且实现极轻）。
 *
 * 安全三件套（规范强制，不可省略）：
 * 1. 仅绑定 127.0.0.1（不监听 0.0.0.0）——只有本机其他应用可连；
 * 2. Bearer token 鉴权（母程序生成本子程序时分配共享 secret）；
 * 3. 拒绝带 Origin 头的跨源浏览器请求（防网页探测内网端口）。
 *
 * 由母程序通过 MCP tools/call 调用，工具集：ping/get_status/get_recent_logs/run_selftest/get_info。
 * 集成：build.gradle 加 implementation("org.nanohttpd:nanohttpd:2.3.1")；
 * manifest 加 <uses-permission android:name="android.permission.INTERNET"/> 与 FOREGROUND_SERVICE；
 * 由 SubprogramMcpService 前台服务承载启动。
 */
class SubprogramMcpServer(
    private val port: Int,
    private val token: String,
    private val appInfo: Info
) : NanoHTTPD("127.0.0.1", port) {

    /** 子程序基本信息（宿主 App 注入）。 */
    data class Info(val packageName: String, val versionName: String, val displayName: String)

    override fun serve(session: IHTTPSession): Response {
        // 安全：拒绝浏览器跨源探测
        if (session.headers["origin"]?.isNotBlank() == true && session.headers["origin"] != "null") {
            return json(403, error("forbidden", "cross-origin rejected"))
        }
        // 安全：Bearer token 鉴权
        val auth = session.headers["authorization"].orEmpty()
        if (auth != "Bearer $token") {
            return json(401, error("unauthorized", "invalid token"))
        }
        if (session.method != Method.POST) {
            return json(405, error("method_not_allowed", "POST only"))
        }
        val body = session.inputStream.readBytes().toString(Charsets.UTF_8)
        val req = runCatching { JSONObject(body) }.getOrElse {
            return json(400, error("parse_error", "invalid json"))
        }
        val id = req.opt("id")
        val method = req.optString("method")
        val result: JSONObject = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", "2026-07-28")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "subprogram-${appInfo.packageName}").put("version", appInfo.versionName))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", toolsDef())
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                dispatchTool(name, args)
            }
            else -> return json(404, error("method_not_found", "unknown method: $method"))
        }
        return json(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
    }

    private fun toolsDef(): JSONArray = JSONArray().put(
        JSONObject().put("name", "ping").put("description", "存活检查")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
    ).put(
        JSONObject().put("name", "get_status").put("description", "进程状态：内存/运行时长/主线程存活")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
    ).put(
        JSONObject().put("name", "get_recent_logs").put("description", "最近应用日志尾部")
            .put("inputSchema", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("lines", JSONObject().put("type", "integer"))))
    ).put(
        JSONObject().put("name", "run_selftest").put("description", "执行子程序内置自检项并返回结果")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
    ).put(
        JSONObject().put("name", "get_info").put("description", "包名/版本/显示名")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
    )

    private fun dispatchTool(name: String, args: JSONObject): JSONObject {
        val content = { text: String ->
            JSONArray().put(JSONObject().put("type", "text").put("text", text))
        }
        val ok = { text: String ->
            JSONObject().put("content", content(text)).put("isError", false)
        }
        return try {
            when (name) {
                "ping" -> ok("pong @ ${System.currentTimeMillis()}")
                "get_info" -> ok(JSONObject().put("packageName", appInfo.packageName)
                    .put("versionName", appInfo.versionName).put("displayName", appInfo.displayName).toString())
                "get_status" -> ok(getStatus())
                "get_recent_logs" -> ok(getRecentLogs(args.optInt("lines", 50)))
                "run_selftest" -> ok(runSelftest())
                else -> JSONObject().put("content", content("unknown tool: $name")).put("isError", true)
            }
        } catch (t: Throwable) {
            Log.e("SubprogramMcp", "tool $name failed", t)
            JSONObject().put("content", content("error: ${t.message}")).put("isError", true)
        }
    }

    private fun getStatus(): String {
        val mem = android.app.ActivityManager.MemoryInfo().also {
            (appContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }
        return JSONObject()
            .put("availMemMB", mem.availMem / 1_000_000)
            .put("lowMemory", mem.lowMemory)
            .put("uptimeMs", android.os.SystemClock.uptimeMillis())
            .put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date()))
            .toString()
    }

    private fun getRecentLogs(lines: Int): String = runCatching {
        val dir = appContext().getExternalFilesDir("logs") ?: appContext().filesDir
        val latest = dir.listFiles()?.maxByOrNull { it.lastModified() }
        latest?.readText()?.lineSequence()?.takeLast(lines)?.joinToString("\n") ?: "（无日志文件）"
    }.getOrDefault("（读取失败）")

    /**
     * 内置自检项：宿主 App 在此追加自己的关键功能检查（网络可达、关键服务存活等）。
     * 返回 JSON：[{name, pass, detail}]。
     */
    private fun runSelftest(): String {
        val items = JSONArray()
        fun check(name: String, pass: Boolean, detail: String = "") {
            items.put(JSONObject().put("name", name).put("pass", pass).put("detail", detail))
        }
        check("进程存活", true)
        val mem = android.app.ActivityManager.MemoryInfo().also {
            (appContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }
        check("内存充足", !mem.lowMemory, "可用 ${mem.availMem / 1_000_000}MB")
        // TODO: 宿主 App 在此追加业务自检项
        return JSONObject().put("items", items)
            .put("passed", items.fold(0) { acc, i -> acc + if (i.optBoolean("pass")) 1 else 0 })
            .put("total", items.length())
            .toString()
    }

    private fun appContext(): android.content.Context = AppContextHolder.get()

    private fun json(status: Int, obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.lookup(status) ?: Response.Status.OK, "application/json", obj.toString())

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("error", JSONObject().put("code", -32000).put("message", "$code: $message"))
}
