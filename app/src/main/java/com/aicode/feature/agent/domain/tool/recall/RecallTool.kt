package com.aicode.feature.agent.domain.tool.recall

import com.aicode.core.util.escapeLikePattern
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 跨压缩全文检索历史会话档案（FTS4）：上下文压缩只标 isCompacted 不删原文，
 * recall 按关键词取回被压缩归档的原始消息细节，避免「摘要失真后无从回看」。
 */
class RecallTool @Inject constructor(
    private val agentMessageDao: AgentMessageDao
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "RecallTool"
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 20
        const val SNIPPET_LIMIT = 500

        /** CJK 表意字符（含扩展 A）：命中则查询走 LIKE 降级路径。 */
        val CJK_REGEX = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]")
    }

    override val name = "recall"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "全文检索历史会话档案，含已被上下文压缩归档的消息原文。摘要细节不足、需要回看某个" +
            "被压缩掉的原始记录（报错原文/决定理由/数据）时用它；范围可选本会话/本工作区/全部会话。" +
            "优先用英文原词/文件名/错误码检索；中文关键词自动降级为模糊匹配。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "检索关键词，多个词用空白分隔（全部命中才算匹配）。",
            required = true
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "检索范围：session=仅本会话；workspace=当前工作区全部会话；all=全部会话。默认 all。",
            enum = listOf("session", "workspace", "all"),
            required = false
        ),
        "limit" to ToolParameter(
            name = "limit",
            type = ParameterType.INTEGER,
            description = "最多返回条数（1~20，默认 8，按时间倒序）。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.Error("缺少必需参数：query", "MISSING_QUERY")
        }
        val match = buildMatch(query)
            ?: return ToolResult.Error("query 中没有可检索的词", "EMPTY_QUERY")
        val scope = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "all"
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
        // FTS4 simple tokenizer 不切中文（连续汉字是单个整体 token，子串检索不命中）：
        // 含 CJK 的查询降级 LIKE 模糊匹配；纯 ASCII 走 FTS（词尾 * 前缀匹配英文子词）。
        val useLike = CJK_REGEX.containsMatchIn(query)

        return try {
            val matches = when (scope) {
                "session" -> {
                    val sessionId = context.sessionId
                        ?: return ToolResult.Error(
                            "当前无会话，scope=session 不可用，请改用 all 或 workspace",
                            "NO_SESSION"
                        )
                    if (useLike) agentMessageDao.recallLikeInSession(escapeLikePattern(query), sessionId, limit)
                    else agentMessageDao.recallSearchInSession(match, sessionId, limit)
                }
                "workspace" -> {
                    val root = context.projectRoot.takeIf { it.isNotBlank() }
                        ?: return ToolResult.Error(
                            "当前未选择工作区，scope=workspace 不可用，请改用 all",
                            "NO_WORKSPACE"
                        )
                    if (useLike) agentMessageDao.recallLikeInWorkspace(escapeLikePattern(query), root, limit)
                    else agentMessageDao.recallSearchInWorkspace(match, root, limit)
                }
                else -> if (useLike) agentMessageDao.recallLikeAll(escapeLikePattern(query), limit)
                else agentMessageDao.recallSearch(match, limit)
            }
            if (matches.isEmpty()) {
                return ToolResult.Success(
                    JsonPrimitive("档案中未命中「$query」。试试更短的关键词（完整单词），或换 scope。")
                )
            }
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val lines = matches.joinToString("\n\n") { m ->
                val snippet = m.content.replace(Regex("\\s+"), " ").let {
                    if (it.length > SNIPPET_LIMIT) it.take(SNIPPET_LIMIT) + "…" else it
                }
                "[${time.format(Date(m.timestamp))}] 会话「${m.sessionTitle}」 ${m.role}：$snippet"
            }
            ToolResult.Success(
                JsonPrimitive("档案命中 ${matches.size} 条（scope=$scope，时间倒序）：\n\n$lines")
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "recall 执行失败: ${e.message}", e)
            ToolResult.Error("检索失败：${e.message}")
        }
    }

    /** 空白分词、去引号、每词短语后缀 *（FTS4 前缀匹配，命中英文子词），避免把用户输入当 FTS 语法解析出错。 */
    private fun buildMatch(query: String): String? =
        query.split(Regex("\\s+"))
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ") { "\"$it\"*" }
}
