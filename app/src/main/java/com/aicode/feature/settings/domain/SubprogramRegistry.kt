package com.aicode.feature.settings.domain

import com.aicode.core.util.shellQuote
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子程序注册表：母程序生成的每个子程序（内嵌 MCP 调试通道）的名称/包名/端口/token 清单。
 * 存储：当前工作区 `.aicode/subprograms.json`（随项目走，可 git 追踪）；
 * 探活：容器内 curl（容器与宿主 App 共享网络栈，localhost 互通）。
 */
@Singleton
class SubprogramRegistry @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val commandEngine: CommandEngine
) {

    data class Subprogram(
        val name: String,
        val packageName: String,
        val port: Int,
        val token: String
    )

    fun file(): File = File(File(workspaceRepository.currentPath(), ".aicode"), "subprograms.json")

    fun load(): List<Subprogram> = runCatching {
        val f = file()
        if (!f.isFile) return emptyList()
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("subprograms") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Subprogram(
                name = o.optString("name"),
                packageName = o.optString("packageName"),
                port = o.optInt("port"),
                token = o.optString("token")
            ).takeIf { it.name.isNotBlank() && it.port > 0 && it.token.isNotBlank() }
        }
    }.getOrElse { emptyList() }

    fun save(list: List<Subprogram>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(
                    JSONObject().put("name", s.name).put("packageName", s.packageName)
                        .put("port", s.port).put("token", s.token)
                )
            }
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("subprograms", arr).toString(2))
        }
    }

    /** 探活：POST initialize（JSON-RPC），响应含 result 即在线。
     *  token 与 body 经全库唯一转义实现 shellQuote 后拼入，防止单引号逃逸注入命令。 */
    suspend fun check(sub: Subprogram): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put("protocolVersion", "2026-07-28")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "aicoder").put("version", "1.0"))
            )
            .toString()
        val cmd = "curl -s -m 6 -X POST http://127.0.0.1:${sub.port}/mcp " +
            "-H 'Content-Type: application/json' " +
            "-H ${shellQuote("Authorization: Bearer ${sub.token}")} " +
            "-d ${shellQuote(body)}"
        val result = commandEngine.runCommandSyncWithExit(cmd, timeoutMs = 15_000L)
        result.exitCode == 0 && result.output.contains("\"result\"")
    }
}
