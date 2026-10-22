package com.aicode.feature.agent.domain.tool.team

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.team.TeamRepository
import com.aicode.feature.agent.domain.team.TeamDefinitionScope
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 召唤专家团：把用户选定的专家团绑到当前会话，与聊天页顶栏「召唤专家团」走同一条
 * 绑定路径（chat_sessions.teamName 落库；下一轮起 SystemPromptProvider 注入团长提示词）。
 *
 * `team` 缺省时不执行绑定，只返回当前可用团队清单与适用场景，供模型选定后二次调用。
 */
class SummonTeamTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val teamRepository: TeamRepository
) : AbstractContextualTool() {

    override val name = "summon_team"
    override val description = "把专家团绑到当前会话（全流程托管）。识别到项目级诉求" +
        "（新项目、跨模块大功能、从零到能用）时直接调用，无需让用户去顶栏手动召唤；" +
        "不传 team 则返回可用团队清单与适用场景，供你选定后再次调用。绑定后下一轮对话起生效。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "team" to ToolParameter(
            name = "team",
            type = ParameterType.STRING,
            description = "要召唤的专家团名（从可用清单中选）。缺省时只列出可用团队与适用场景，不执行绑定",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        if (context.agentDefinition != null) {
            return ToolResult.Error("子代理会话不能召唤专家团，请由主会话执行", "SUB_SESSION")
        }
        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话 ID，无法召唤专家团", "NO_SESSION")
        chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        val requested = args["team"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val teams = teamRepository.listAll()
        if (requested.isEmpty()) {
            return ToolResult.Success(JsonPrimitive(buildTeamListing(teams)))
        }

        val team = teams.firstOrNull { it.definition.name.equals(requested, ignoreCase = true) }?.definition
            ?: return ToolResult.Error(
                "专家团「$requested」不存在或未安装。可用团队：\n" + buildTeamListing(teams),
                "TEAM_NOT_FOUND"
            )

        val current = chatSessionDao.getById(sessionId)?.teamName
        if (current.equals(team.name, ignoreCase = true)) {
            return ToolResult.Success(JsonPrimitive(
                "专家团「${team.name}」已在当前会话生效，无需重复召唤。成员：${team.members.joinToString("、")}。"
            ))
        }

        chatSessionDao.updateTeamName(sessionId, team.name)
        return ToolResult.Success(JsonPrimitive(
            "已召唤专家团「${team.name}」并绑定当前会话。成员：${team.members.joinToString("、")}。" +
                "从下一轮对话起团长提示词与成员名单注入系统提示词；请告知用户团队已就位并按团长职责推进。"
        ))
    }

    private fun buildTeamListing(teams: List<com.aicode.feature.agent.domain.team.TeamDefinitionEntry>): String {
        if (teams.isEmpty()) {
            return "当前没有可用专家团。可在「设置 → 在线商店」安装内置专家团，" +
                "或在 ~/.aicode/teams（全局）/ .aicode/teams（项目级）添加定义。"
        }
        return "当前可用专家团：\n" + teams.joinToString("\n") { entry ->
            val scope = if (entry.scope == TeamDefinitionScope.PROJECT) "项目级" else "全局"
            val desc = entry.definition.description.ifBlank { "（无描述）" }
            "- ${entry.definition.name}（$scope）: $desc"
        } + "\n选定后再次调用本工具并传 team 参数完成召唤。"
    }
}
