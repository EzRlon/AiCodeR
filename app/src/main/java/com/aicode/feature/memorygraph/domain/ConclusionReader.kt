package com.aicode.feature.memorygraph.domain

import com.aicode.feature.memorygraph.domain.model.GraphNode

/**
 * 记忆图谱的读取侧契约：只暴露 agent 主链路（系统提示词构建、上下文压缩）需要的那两个能力，
 * 不泄漏 Room/DAO。
 *
 * 为什么需要它：`SystemPromptProvider` 与 `ContextCompactor` 原先直接注入
 * `memorygraph.data.GraphRepository`（用全限定名直连、无接口），等于让 agent 的领域层依赖
 * 另一个模块的数据层——agent 核心链路从此绑死数据库，无法脱离 Room 单测，图谱重构也会直接
 * 波及主链路。实现由 data 层提供，装配仍走 Hilt。
 */
interface ConclusionReader {
    /** 当前有效的结论类节点（按时间倒序），供会话开始注入。 */
    suspend fun validConclusions(workspacePath: String, limit: Int = DEFAULT_INJECT_LIMIT): List<GraphNode>

    /** 当前有效的、属于指定团队（label 带 [团队:名字] 前缀）的结论节点（按时间倒序）。 */
    suspend fun validTeamConclusions(workspacePath: String, teamName: String, limit: Int = DEFAULT_TEAM_INJECT_LIMIT): List<GraphNode>

    /** 从压缩摘要里抽取结论并落库（幂等，按名称去重）；[teamName] 非空时结论带团队标签。 */
    suspend fun extractConclusionsFromSummary(workspacePath: String, sessionId: String, summary: String, teamName: String? = null)

    companion object {
        /** 会话开始注入的结论条数上限：太多会挤占上下文，且旧结论参考价值递减。 */
        const val DEFAULT_INJECT_LIMIT = 8

        /** 团长指令注入的团队结论条数上限：团队结论更聚焦，少量即可。 */
        const val DEFAULT_TEAM_INJECT_LIMIT = 5
    }
}
