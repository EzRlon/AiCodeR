package com.aicode.feature.fixledger.domain.tool

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.fixledger.data.FixLedgerRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `fixledger` 工具：AI 修复工作流的账本回写通道（标注中心「真融合」第一批）。
 *
 * AI 修完某条标注对应的问题后，调用本工具把修复账本与标注账本两侧状态同步推进
 * （仅可前进到 FIXING/REVIEW；CLOSED 由用户复验后手动——复验是用户验收，不交给 AI）。
 * 之前两侧纯手动推进，「AI 修完 → 账本推进」断链（调研确认工具注册表内无任何账本回写通道）。
 */
class FixLedgerTool @Inject constructor(
    private val repository: FixLedgerRepository
) : AgentTool() {

    override val name = "fixledger"

    override val description =
        "回写修复账本状态：修完某条标注对应的问题后调用，把该标注的修复账本与标注账本一起推进到「修复中」或「待复验」。只能前进，不能关闭（关闭由用户复验后手动）。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "annotationId" to ToolParameter(
            name = "annotationId",
            type = ParameterType.STRING,
            description = "来源标注 id（汇总发给 AI 的文本里每条标注都带 id）",
            required = true
        ),
        "target" to ToolParameter(
            name = "target",
            type = ParameterType.STRING,
            description = "目标状态：FIXING（开始修复）或 REVIEW（已修完待复验）",
            required = true,
            enum = listOf("FIXING", "REVIEW")
        )
    )

    // 账本状态簿记，风险低；不设 ASK 弹窗，保持修复工作流顺畅
    override val capabilities: Set<ToolCapability> = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val annotationId = (args["annotationId"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (annotationId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：annotationId 不能为空", "INVALID_ARGS")
        }
        val targetName = (args["target"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
        val target = when (targetName) {
            "FIXING" -> AnnotationStatus.FIXING
            "REVIEW" -> AnnotationStatus.REVIEW
            else -> return ToolResult.Error("参数无效：target 只能是 FIXING 或 REVIEW", "INVALID_ARGS")
        }
        val applied = repository.advanceLinked(annotationId, target)
            ?: return ToolResult.Error(
                "无法推进（标注不存在，或状态不能后退/重复推进）",
                "NOT_ADVANCEABLE"
            )
        return ToolResult.Success(
            buildJsonObject {
                put("annotationId", annotationId)
                put("status", applied)
            }
        )
    }
}
