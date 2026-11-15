package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.orchestration.OrchestrationPolicy
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.team.TeamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外部 CLI 引擎的主 Agent 编排提示：三桥共用同一份内容，避免各写一套又逐渐漂移。
 *
 * - 始终注入「编排守则」（分层干活、任务板、结构化上报、证据验收、权限升级）；
 * - 会话绑定了专家团时追加团长指令、成员清单、生命周期与门禁——让外部引擎下的
 *   「召唤专家团」不再只是 UI 状态，而是真的按团长角色运转（成员已通过 --agents
 *   / OpenCode agent 段映射成引擎侧 agent）。
 *
 * 只服务外部引擎；内置引擎的提示词链路保持原样（用户要求：内置作为故障兜底，先不动）。
 */
@Singleton
class CliOrchestrationPrompt @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val teamRepository: TeamRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository
) {
    private companion object {
        const val TAG = "CliOrchestrationPrompt"
    }

    /**
     * 返回本回合要追加到系统提示的编排块；取不到会话时返回 null（不阻断回合）。
     * 团队与子代理目录扫描是文件 IO，统一放到 IO 调度器，避免卡住对话主线程。
     */
    suspend fun build(sessionId: String, engine: String): String? = withContext(Dispatchers.IO) {
        val session = runCatching { sessionUseCase.getSessionById(sessionId) }
            .getOrNull() ?: return@withContext null
        val teamName = session.teamName?.takeIf { it.isNotBlank() }
        if (teamName == null) return@withContext OrchestrationPolicy.BASE
        val team = runCatching { teamRepository.resolve(teamName) }
            .onFailure { FileLogger.w(TAG, "解析专家团失败，仅注入通用编排守则: $teamName", it) }
            .getOrNull() ?: return@withContext OrchestrationPolicy.BASE

        // 一次扫盘建索引：成员逐个 findIncludingDisabled 每次都全量读 agents 目录（团队 5 人 = 5 次扫描，
        // 每回合发生）。名称匹配规则与仓库内部一致（定义名或文件名，忽略大小写）。
        val allDefinitions = runCatching { agentDefinitionRepository.listAll() }
            .onFailure { FileLogger.w(TAG, "枚举子代理定义失败，成员描述与可派发标注按空处理", it) }
            .getOrDefault(emptyList())
        val members = team.members.map { member ->
            member to allDefinitions.firstOrNull {
                AgentDefinitionRepository.matchesDefinitionName(it.definition, member)
            }?.definition?.description.orEmpty()
        }
        // Codex 没有自定义成员派发能力：只给出成员分工参考，不诱导它调用不存在的 Task。
        if (engine == AgentEngines.CODEX) {
            return@withContext OrchestrationPolicy.BASE + "\n\n" + OrchestrationPolicy.teamSection(
                teamName = team.name,
                teamPrompt = team.prompt,
                members = members,
                lifecycle = team.lifecycle,
                gates = team.gates,
                dispatchable = false
            )
        }
        // Claude / OpenCode：按各自实际映射规则算出哪些成员真的能派发，未映射的明确标注。
        val mapped = runCatching {
            val disabled = agentDefinitionRepository.disabledNames()
            val defs = allDefinitions
                .filterNot { it.definition.name.lowercase() in disabled }
                .map { it.definition }
            when (engine) {
                AgentEngines.OPENCODE -> CliAgentMapping.partitionForOpenCode(defs).mapped
                else -> CliAgentMapping.partition(defs).mapped
            }.map { it.name.lowercase() }.toSet()
        }.onFailure { FileLogger.w(TAG, "枚举可映射子代理失败，成员按未映射标注", it) }.getOrDefault(emptySet())
        val unmapped = team.members.filter { it.lowercase() !in mapped }.toSet()
        OrchestrationPolicy.BASE + "\n\n" + OrchestrationPolicy.teamSection(
            teamName = team.name,
            teamPrompt = team.prompt,
            members = members,
            lifecycle = team.lifecycle,
            gates = team.gates,
            dispatchable = true,
            unmapped = unmapped
        )
    }
}
