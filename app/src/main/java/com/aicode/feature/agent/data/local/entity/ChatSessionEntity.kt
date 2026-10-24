package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["workspacePath"])]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = AgentMode.BUILD.name,
    /** 进入 PLAN 前的模式（如 AUTO）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: String? = null,
    /** 会话级持续目标文本；null 表示未设定目标。 */
    val goal: String? = null,
    /** goal 生命周期状态；[goal] 为 null 时无意义。 */
    val goalStatus: String? = null,
    val goalTurnCount: Int = 0,
    val goalStartInputTokens: Int = 0,
    val goalStartOutputTokens: Int = 0,
    val reasoningEffort: String = ReasoningEffort.MEDIUM.name,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    /** 会话级召唤的专家团名（对应 teams/<name>.md）；null 表示未使用专家团。 */
    val teamName: String? = null,
    /** 回收站：null = 正常会话；非 null = 入回收站的时间戳（超过 30 天后彻底清除）。 */
    val deletedAt: Long? = null,
    /** 本会话的执行引擎：null = 本体内置工作流；"claude_code" = CLI 桥接（引擎切换）。 */
    val engine: String? = null
) {
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
        mode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.BUILD),
        modeBeforePlan = modeBeforePlan?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() },
        goal = goal,
        goalStatus = goalStatus?.let { runCatching { GoalStatus.valueOf(it) }.getOrNull() },
        goalTurnCount = goalTurnCount,
        goalStartInputTokens = goalStartInputTokens,
        goalStartOutputTokens = goalStartOutputTokens,
        reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort) }.getOrDefault(ReasoningEffort.MEDIUM),
        providerId = providerId,
        model = model,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        parentId = parentId,
        subagentType = subagentType,
        teamName = teamName,
        deletedAt = deletedAt,
        engine = engine
    )

    companion object {
        fun fromDomain(session: ChatSession): ChatSessionEntity = ChatSessionEntity(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            workspacePath = session.workspacePath,
            mode = session.mode.name,
            modeBeforePlan = session.modeBeforePlan?.name,
            goal = session.goal,
            goalStatus = session.goalStatus?.name,
            goalTurnCount = session.goalTurnCount,
            goalStartInputTokens = session.goalStartInputTokens,
            goalStartOutputTokens = session.goalStartOutputTokens,
            reasoningEffort = session.reasoningEffort.name,
            providerId = session.providerId,
            model = session.model,
            totalInputTokens = session.totalInputTokens,
            totalOutputTokens = session.totalOutputTokens,
            lastInputTokens = session.lastInputTokens,
            isPinned = session.isPinned,
            parentId = session.parentId,
            subagentType = session.subagentType,
            teamName = session.teamName,
            deletedAt = session.deletedAt,
            engine = session.engine
        )
    }
}
