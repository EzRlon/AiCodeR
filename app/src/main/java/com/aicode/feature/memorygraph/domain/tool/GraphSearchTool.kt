package com.aicode.feature.memorygraph.domain.tool

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.memorygraph.data.GraphRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 记忆图谱检索：查「本项目已经定过什么、踩过什么坑、和哪些会话有关」。
 *
 * 与 `recall` 分工：recall 查历史消息原文细节（报错原文、某句对话）；本工具查已沉淀的结论
 * 与它们的关联，返回「结论 + 出处会话」的指针，需要原文再用 recall 取。
 */
class GraphSearchTool @Inject constructor(
    private val graphRepository: GraphRepository
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "GraphSearchTool"
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
    }

    override val name = "graph_search"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "检索本项目已沉淀的结论图谱（定案/坑/待续）及其关联会话。开工前想确认「这功能以前怎么定的」" +
            "「这个坑修没修过」「哪些会话讨论过它」时用它；查历史消息原文细节请改用 recall。" +
            "默认只返回当前有效的结论（已被新结论取代的旧结论不返回）。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "检索关键词（匹配结论名称与说明）；留空则按时间倒序列出最近的结论。",
            required = false
        ),
        "includeInvalid" to ToolParameter(
            name = "includeInvalid",
            type = ParameterType.BOOLEAN,
            description = "是否包含已被取代的旧结论（想看历史沿革时置 true）。默认 false。",
            required = false
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "最多返回条数（1~30，默认 10）。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val workspace = context.projectRoot.takeIf { it.isNotBlank() }
            ?: return ToolResult.Error("当前未选择工作区，无法检索项目图谱", "NO_WORKSPACE")
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val includeInvalid = args["includeInvalid"]?.jsonPrimitive?.booleanOrNull ?: false
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

        return try {
            val hits = if (query.length >= 2) {
                graphRepository.search(workspace, query, limit, includeInvalid)
            } else {
                graphRepository.validConclusions(workspace, limit)
                    .map { com.aicode.feature.memorygraph.domain.model.GraphSearchHit(it, GraphRepository.VIA_KEYWORD) }
            }
            if (hits.isEmpty()) {
                return ToolResult.Success(
                    JsonPrimitive(
                        if (query.isBlank()) "图谱中还没有结论。结论抽取默认开启：内置引擎在压缩上下文后、外部引擎每几个回合自动沉淀；确保「设置 → 记忆图谱」没有被关闭。"
                        else "未命中「$query」。试试更短的关键词，或留空 query 列出最近结论。"
                    )
                )
            }
            val time = SimpleDateFormat("MM-dd", Locale.getDefault())
            val lines = hits.joinToString("\n") { hit ->
                val node = hit.node
                val status = if (node.invalidAt != null) "（已过时）" else ""
                val when_ = if (node.validFrom > 0) time.format(Date(node.validFrom)) else ""
                val summary = node.summary.replace(Regex("\\s+"), " ").take(120)
                "- [$when_] ${node.name}$status：$summary"
            }
            val scopeNote = if (includeInvalid) "（含已过时）" else "（仅当前有效）"
            ToolResult.Success(JsonPrimitive("图谱命中 ${hits.size} 条$scopeNote：\n$lines"))
        } catch (e: Exception) {
            FileLogger.e(TAG, "graph_search 执行失败: ${e.message}", e)
            ToolResult.Error("检索失败：${e.message}")
        }
    }
}
