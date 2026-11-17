package com.aicode.feature.agent.domain.tool.knowledge

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.io.File
import javax.inject.Inject

/**
 * 知识检索工具 `kb_search`：按关键词搜项目知识库（.aicode/notes）与世界书（.aicode/lorebook）的 md 全文。
 *
 * 只注入索引时模型不会主动翻原文——给它一个检索入口，开工前查约定/坑、遇到未知概念先搜再问，
 * 知识库才真正参与任务编排。只读、自动放行。
 */
class KnowledgeSearchTool @Inject constructor() : AbstractContextualTool() {

    private companion object {
        const val TAG = "KnowledgeSearchTool"
        const val NOTES_DIR = "/.aicode/notes"
        const val LOREBOOK_DIR = "/.aicode/lorebook"
        const val MAX_FILES_SCANNED = 60
        const val MAX_FILES_HIT = 8
        const val MAX_LINES_PER_FILE = 3
        const val MAX_LINE_CHARS = 160
    }

    override val name = "kb_search"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val description =
        "按关键词检索项目知识库（.aicode/notes）与世界书（.aicode/lorebook）的全文。" +
            "开工前先搜一次任务相关关键词（约定/踩坑/接口），遇到不确定的项目概念先搜再问，不要凭记忆猜。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            required = true,
            description = "检索关键词（对文件内容做包含匹配，不区分大小写）"
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            required = false,
            description = "检索范围：all（默认）/ notes（仅知识库）/ lorebook（仅世界书）"
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：query", "MISSING_QUERY")
        if (query.isEmpty()) return ToolResult.Error("参数无效：query 不能为空", "INVALID_ARGS")
        val scope = (args["scope"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "all"
        if (scope !in setOf("all", "notes", "lorebook")) {
            return ToolResult.Error("参数无效：scope 只支持 all / notes / lorebook", "INVALID_ARGS")
        }
        val projectRoot = context.projectRoot.trimEnd('/')
        if (projectRoot.isBlank()) return ToolResult.Error("缺少工作区，无法检索知识库", "NO_WORKSPACE")
        return runCatching {
            val dirs = buildList {
                if (scope != "lorebook") add("知识库" to File(projectRoot + NOTES_DIR))
                if (scope != "notes") add("世界书" to File(projectRoot + LOREBOOK_DIR))
            }
            var scanned = 0
            val hitList = mutableListOf<JsonElement>()
            for ((label, dir) in dirs) {
                val files = dir.takeIf { it.isDirectory }
                    ?.listFiles { f -> f.isFile && f.extension.equals("md", true) }
                    ?.sortedBy { it.name }
                    ?.take(MAX_FILES_SCANNED)
                    ?: continue
                for (file in files) {
                    scanned++
                    if (hitList.size >= MAX_FILES_HIT) break
                    val lines = runCatching { file.readLines() }.getOrElse { continue }
                    val matched = lines.asSequence()
                        .filter { it.contains(query, ignoreCase = true) }
                        .take(MAX_LINES_PER_FILE)
                        .map { it.trim().take(MAX_LINE_CHARS) }
                        .toList()
                    if (matched.isEmpty()) continue
                    hitList += buildJsonObject {
                        put("source", label)
                        put("file", file.name.removeSuffix(".md"))
                        put("path", file.path)
                        put("lines", buildJsonArray { matched.forEach { add(JsonPrimitive(it)) } })
                    }
                }
            }
            if (hitList.size >= MAX_FILES_HIT) {
                FileLogger.i(TAG, "kb_search 命中文件数达到上限 $MAX_FILES_HIT（query=$query），已截断")
            }
            ToolResult.Success(
                buildJsonObject {
                    put("query", query)
                    put("scannedFiles", scanned)
                    put("hitFiles", hitList.size)
                    put("hits", buildJsonArray { hitList.forEach { add(it) } })
                }
            )
        }.getOrElse { e ->
            FileLogger.e(TAG, "知识检索失败: ${e.message}", e)
            ToolResult.Error("知识检索失败：${e.message}", "KB_SEARCH_FAILED")
        }
    }
}
