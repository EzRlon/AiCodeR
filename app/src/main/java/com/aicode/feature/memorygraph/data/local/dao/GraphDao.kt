package com.aicode.feature.memorygraph.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.memorygraph.data.local.entity.GraphEdgeEntity
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GraphDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(entity: GraphNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdge(entity: GraphEdgeEntity)

    @Query("SELECT * FROM graph_nodes WHERE workspacePath = :workspacePath ORDER BY updatedAt DESC")
    fun nodesByWorkspace(workspacePath: String): Flow<List<GraphNodeEntity>>

    @Query("SELECT * FROM graph_edges WHERE workspacePath = :workspacePath")
    fun edgesByWorkspace(workspacePath: String): Flow<List<GraphEdgeEntity>>

    @Query("SELECT * FROM graph_nodes WHERE workspacePath = :workspacePath AND type = :type AND sourceRefId = :refId LIMIT 1")
    suspend fun findBySourceRef(workspacePath: String, type: String, refId: String): GraphNodeEntity?

    @Query("SELECT * FROM graph_nodes WHERE id = :id LIMIT 1")
    suspend fun nodeById(id: String): GraphNodeEntity?

    /** 关键词命中（名称/摘要子串，大小写不敏感）。 */
    @Query(
        "SELECT * FROM graph_nodes WHERE workspacePath = :workspacePath AND " +
            "(name LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun searchNodes(workspacePath: String, query: String, limit: Int): List<GraphNodeEntity>

    /** 工作区内发生过 memory 工具调用的（会话, 参数）对——图谱同步用它替代逐会话全量读消息（optimizer P0）。 */
    @Query(
        "SELECT m.sessionId AS sessionId, m.toolArgs AS toolArgs FROM agent_messages m " +
            "JOIN chat_sessions s ON m.sessionId = s.id " +
            "WHERE s.workspacePath = :workspacePath AND m.toolName = 'memory'"
    )
    suspend fun memoryToolCallsByWorkspace(workspacePath: String): List<MemoryToolCallRow>

    /** 与某节点相连的全部边。 */
    @Query("SELECT * FROM graph_edges WHERE workspacePath = :workspacePath AND (fromNodeId = :nodeId OR toNodeId = :nodeId)")
    suspend fun edgesTouching(workspacePath: String, nodeId: String): List<GraphEdgeEntity>

    @Query("DELETE FROM graph_nodes WHERE id = :id")
    suspend fun deleteNodeById(id: String)

    /** 删节点连带清边（外键未启用手动级联）。 */
    @Query("DELETE FROM graph_edges WHERE fromNodeId = :id OR toNodeId = :id")
    suspend fun deleteEdgesTouching(id: String)

    @Query("DELETE FROM graph_edges WHERE workspacePath = :workspacePath")
    suspend fun deleteEdgesOfWorkspace(workspacePath: String)
}

/** memory 工具调用投影（GraphDao.memoryToolCallsByWorkspace 用）。 */
data class MemoryToolCallRow(
    val sessionId: String,
    val toolArgs: String?
)
