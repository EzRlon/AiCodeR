package com.aicode.feature.changelog.domain.tool

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.changelog.data.ChangeLedgerRepository
import com.aicode.feature.changelog.domain.model.ChangeKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `changelog` 工具：AI 把本轮改动写进项目改动账本。
 *
 * 存在的理由：长期项目里反馈与改动累积多了，跨会话就记不清「哪些改好了、哪些没做」。
 * 让 AI 在**完成改动的同一轮**落笔（改了什么/为什么/怎么验的），账本由 AI 自己维护，
 * 不依赖用户手工补记。状态只允许推进到 FIXING/REVIEW——CLOSED（已验）是用户验收，
 * 与修复账本同一条纪律，不交给 AI 自我签收。
 */
class ChangeLedgerTool @Inject constructor(
    private val repository: ChangeLedgerRepository
) : AgentTool() {

    override val name = "changelog"

    override val description =
        "把本轮改动记入项目改动账本（跨会话可查「哪些改好了、哪些没做」）。完成一项改动后调用：" +
            "title 一行说清改了什么，kind 选类型，humanSummary 用大白话，agentDetail 写为什么改与改前改后，" +
            "evidence 填怎么验证的（跑过的命令或测试名——没验证就留空，不要编），files 填改动文件。" +
            "另传 status=REVIEW 表示已修完待复验（默认 FIXING）。不要在同一改动上重复记录。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "一行标题，说清改了什么（如「思考档位审计落库」）",
            required = true
        ),
        "kind" to ToolParameter(
            name = "kind",
            type = ParameterType.STRING,
            description = "改动类型",
            required = false,
            enum = listOf("feat", "fix", "refactor", "docs", "perf", "test", "chore")
        ),
        "humanSummary" to ToolParameter(
            name = "humanSummary",
            type = ParameterType.STRING,
            description = "给用户看的大白话摘要（一句话，避免术语）",
            required = false
        ),
        "agentDetail" to ToolParameter(
            name = "agentDetail",
            type = ParameterType.STRING,
            description = "给后续 AI 看的结构化细节：为什么改 + 改前改后",
            required = false
        ),
        "evidence" to ToolParameter(
            name = "evidence",
            type = ParameterType.STRING,
            description = "验证证据：跑过的命令 / 测试名。**没验证就留空**，不要编造",
            required = false
        ),
        "files" to ToolParameter(
            name = "files",
            type = ParameterType.STRING,
            description = "涉改文件，逗号分隔（如 app/src/.../Foo.kt, app/src/.../Bar.kt）",
            required = false
        ),
        "status" to ToolParameter(
            name = "status",
            type = ParameterType.STRING,
            description = "FIXING（默认，正在改）或 REVIEW（已修完待复验）",
            required = false,
            enum = listOf("FIXING", "REVIEW")
        )
    )

    // 账本状态簿记，风险低；不设 ASK 弹窗，保持工作流顺畅（同 fixledger）
    override val capabilities: Set<ToolCapability> = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val title = str(args, "title")
        if (title.isNullOrBlank()) {
            return ToolResult.Error("参数无效：title 不能为空", "INVALID_ARGS")
        }
        val kind = ChangeKind.fromToken(str(args, "kind"))
        val status = when (str(args, "status")?.uppercase()) {
            "REVIEW" -> AnnotationStatus.REVIEW
            "FIXING", null -> AnnotationStatus.FIXING
            else -> return ToolResult.Error("参数无效：status 只能是 FIXING 或 REVIEW", "INVALID_ARGS")
        }
        val filesJson = str(args, "files")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { list -> "[" + list.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]" }
            ?: "[]"

        val id = repository.add(
            title = title,
            kind = kind,
            status = status,
            humanSummary = str(args, "humanSummary").orEmpty(),
            agentDetail = str(args, "agentDetail").orEmpty(),
            evidence = str(args, "evidence").orEmpty(),
            filesJson = filesJson
        )
        return ToolResult.Success(
            buildJsonObject {
                put("id", id)
                put("status", status.name)
                put("kind", kind.token)
            }
        )
    }

    private fun str(args: Map<String, JsonElement>, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
