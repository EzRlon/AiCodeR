package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    // 会话可见性一律排除回收站（deletedAt IS NULL）：软删只改标记行，查询不过滤就等于没删。
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND deletedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL AND deletedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getRootSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND deletedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    /** 含回收站的工作区会话（整个工作区被删除时用，确保软删会话的消息也被清掉）。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnceIncludingTrashed(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL AND deletedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getRootSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    /**
     * 工作区下可复用的空会话（未发过任何消息的根会话，取最近更新的一个）。
     * 下沉到 SQL：原先取全部根会话再逐条查消息，每次新建会话都要多轮 DB 查询。
     */
    @Query(
        "SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL AND deletedAt IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM agent_messages WHERE sessionId = chat_sessions.id) " +
            "ORDER BY isPinned DESC, updatedAt DESC LIMIT 1"
    )
    suspend fun getEmptyRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 最近更新的根会话（忽略置顶，仅按 updatedAt 降序），供启动时「打开最近会话」。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 指定父会话的全部子会话（子代理），按最近更新降序。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun getSubSessionsByParent(parentId: String): Flow<List<ChatSessionEntity>>

    /** 指定父会话的全部子会话（子代理），一次性查询。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun getSubSessionsByParentOnce(parentId: String): List<ChatSessionEntity>

    /** 含回收站的子会话：恢复/彻底删除/软删整组时要能看到已被软删的子会话。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId")
    suspend fun getSubSessionsByParentOnceIncludingTrashed(parentId: String): List<ChatSessionEntity>

    /** 回收站：该工作区已软删的根会话（不含子代理会话——软删/恢复/彻底删都按整组生效，逐条列出会误导）。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND deletedAt IS NOT NULL AND parentId IS NULL ORDER BY deletedAt DESC")
    fun getTrashedSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    /** 回收站（一次性读，供清空与清理）。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getTrashedSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    /** 回收站是否存在条目（侧栏入口按需显示，只数根会话，与列表口径一致）。 */
    @Query("SELECT COUNT(*) FROM chat_sessions WHERE workspacePath = :workspacePath AND deletedAt IS NOT NULL AND parentId IS NULL")
    fun countTrashedByWorkspace(workspacePath: String): Flow<Int>

    /** 软删（入回收站）：调用方负责把同一组的会话 id（含子代理会话）一并传入。 */
    @Query("UPDATE chat_sessions SET deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<String>, deletedAt: Long)

    /** 从回收站恢复。 */
    @Query("UPDATE chat_sessions SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreFromTrash(ids: List<String>)

    /** 已逾期（deletedAt <= threshold）的回收站会话，供彻底清除。 */
    @Query("SELECT * FROM chat_sessions WHERE deletedAt IS NOT NULL AND deletedAt <= :threshold")
    suspend fun getExpiredTrashed(threshold: Long): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 分页读取（keyset：按 updatedAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAt > :lastUpdatedAt OR (updatedAt = :lastUpdatedAt AND id > :lastId) ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAt: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    /** 单个会话的实时流（根会话与子会话通用）。 */
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ChatSessionEntity?>

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)

    /** 彻底删除（回收站清除 / 逾期清理），按 id 批量。 */
    @Query("DELETE FROM chat_sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM chat_sessions WHERE workspacePath = :workspacePath")
    suspend fun deleteByWorkspace(workspacePath: String)

    @Query("UPDATE chat_sessions SET providerId = :providerId, model = :model, lastInputTokens = 0 WHERE id = :id")
    suspend fun updateProviderModel(id: String, providerId: String?, model: String?)

    @Query("UPDATE chat_sessions SET reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoningEffort(id: String, effort: String)

    @Query("UPDATE chat_sessions SET teamName = :teamName WHERE id = :id")
    suspend fun updateTeamName(id: String, teamName: String?)

    @Query("UPDATE chat_sessions SET engine = :engine, providerId = NULL, model = NULL WHERE id = :id")
    suspend fun updateEngine(id: String, engine: String?)
    /** 设定/覆盖 goal：同时重置轮次计数与 token 快照。goal 为 null 表示清除。 */
    @Query("UPDATE chat_sessions SET goal = :goal, goalStatus = :status, goalTurnCount = 0, goalStartInputTokens = :startInput, goalStartOutputTokens = :startOutput WHERE id = :id")
    suspend fun updateGoal(id: String, goal: String?, status: String?, startInput: Int, startOutput: Int)

    @Query("UPDATE chat_sessions SET goalStatus = :status WHERE id = :id")
    suspend fun updateGoalStatus(id: String, status: String?)

    @Query("UPDATE chat_sessions SET goalTurnCount = goalTurnCount + 1 WHERE id = :id")
    suspend fun incrementGoalTurn(id: String)

    @Query("UPDATE chat_sessions SET totalInputTokens = totalInputTokens + :inputTokens, totalOutputTokens = totalOutputTokens + :outputTokens, totalCachedInputTokens = totalCachedInputTokens + :cachedInputTokens WHERE id = :id")
    suspend fun addTokenUsage(id: String, inputTokens: Int, outputTokens: Int, cachedInputTokens: Int)

    @Query("UPDATE chat_sessions SET lastInputTokens = :lastInputTokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: String, lastInputTokens: Int)
}
