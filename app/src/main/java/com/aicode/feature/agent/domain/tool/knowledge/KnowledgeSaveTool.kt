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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 知识沉淀工具 `kb_save`：把任务中的新结论/决策/踩坑追加到项目知识库（.aicode/notes）。
 *
 * 与知识检索（kb_search）构成闭环：检索进来、沉淀回去，知识库才随使用持续增值。
 * 防污染约束：同主题追加日期小节而非覆盖；标题清洗非法字符；正文长度封顶。
 * 只写 .aicode/notes 项目资产，自动放行。
 */
class KnowledgeSaveTool @Inject constructor() : AbstractContextualTool() {

    private companion object {
        const val TAG = "KnowledgeSaveTool"
        const val NOTES_DIR = "/.aicode/notes"
        const val MAX_TITLE_CHARS = 60
        const val MAX_CONTENT_CHARS = 4000
    }

    override val name = "kb_save"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override val description =
        "把本次任务沉淀的新结论写入项目知识库（.aicode/notes）：定案、踩坑根因、接口约定等会反复用到的结论。" +
            "同主题已有笔记时追加日期小节，不覆盖旧内容；一次性任务细节不沉淀。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            required = true,
            description = "笔记主题（作为文件名，如「登录模块缓存约定」；已有同主题笔记则追加小节）"
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            required = true,
            description = "Markdown 正文：结论本身与关键上下文，不写过程叙述"
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val title = (args["title"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：title", "MISSING_TITLE")
        val content = (args["content"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：content", "MISSING_CONTENT")
        if (title.isEmpty() || content.isEmpty()) {
            return ToolResult.Error("参数无效：title 与 content 不能为空", "INVALID_ARGS")
        }
        val projectRoot = context.projectRoot.trimEnd('/')
        if (projectRoot.isBlank()) return ToolResult.Error("缺少工作区，无法写入知识库", "NO_WORKSPACE")
        return runCatching {
            val dir = File(projectRoot + NOTES_DIR).apply { mkdirs() }
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(MAX_TITLE_CHARS).trim()
            if (safeTitle.isEmpty()) return@runCatching ToolResult.Error("参数无效：title 清洗后为空", "INVALID_ARGS")
            val file = File(dir, "$safeTitle.md")
            val body = content.take(MAX_CONTENT_CHARS)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val action = if (file.exists()) {
                file.appendText("\n\n## $stamp\n\n$body\n")
                "appended"
            } else {
                file.writeText("# $safeTitle\n\n$body\n")
                "created"
            }
            FileLogger.i(TAG, "kb_save $action: ${file.name}")
            ToolResult.Success(
                buildJsonObject {
                    put("action", action)
                    put("file", file.path)
                    put("truncated", content.length > MAX_CONTENT_CHARS)
                }
            )
        }.getOrElse { e ->
            FileLogger.e(TAG, "知识沉淀失败: ${e.message}", e)
            ToolResult.Error("知识沉淀失败：${e.message}", "KB_SAVE_FAILED")
        }
    }
}
