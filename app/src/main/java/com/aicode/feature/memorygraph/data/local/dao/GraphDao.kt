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

    /**
     * 工作区节点 = 本项目节点 + 全局节点（workspacePath 为空串，跨项目共享一份）。
     * 全局记忆不再在每个工作区重复建节点（迁移 73）。
     */
    @Query("SELECT * FROM graph_nodes WHERE workspacePath = :workspacePath OR workspacePath = '' ORDER BY updatedAt DESC")
    fun nodesByWorkspace(workspacePath: String): Flow<List<GraphNodeEntity>>

    /** 工作区边 = 本项目边 + 全局节点之间的边。 */
    @Query("SELECT * FROM graph_edges WHERE workspacePath = :workspacePath OR workspacePath = ''")
    fun edgesByWorkspace(workspacePath: String): Flow<List<GraphEdgeEntity>>

    @Query("SELECT * FROM graph_nodes WHERE workspacePath = :workspacePath AND type = :type AND sourceRefId = :refId LIMIT 1")
    suspend fun findBySourceRef(workspacePath: String, type: String, refId: String): GraphNodeEntity?

    @Query("SELECT * FROM graph_nodes WHERE id = :id LIMIT 1")
    suspend fun nodeById(id: String): GraphNodeEntity?

    /** 关键词命中（名称/摘要子串，大小写不敏感）；[includeInvalid]=1 时含已失效节点。 */
    @Query(
        "SELECT * FROM graph_nodes WHERE (workspacePath = :workspacePath OR workspacePath = '') AND " +
            "(name LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%') AND " +
            "(:includeInvalid = 1 OR invalidAt IS NULL) " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun searchNodes(workspacePath: String, query: String, includeInvalid: Int, limit: Int): List<GraphNodeEntity>

    /** 当前有效的结论类节点（MEMORY/CONCLUSION/CUSTOM），按时间倒序——会话开始注入用。 */
    @Query(
        "SELECT * FROM graph_nodes WHERE (workspacePath = :workspacePath OR workspacePath = '') " +
            "AND type IN ('MEMORY', 'CONCLUSION', 'CUSTOM') AND invalidAt IS NULL " +
            "ORDER BY validFrom DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun validConclusions(workspacePath: String, limit: Int): List<GraphNodeEntity>

    /** 工作区内发生过 memory 工具调用的（会话, 参数）对——图谱同步用它替代逐会话全量读消息（optimizer P0）。 */
    @Query(
        "SELECT m.sessionId AS sessionId, m.toolArgs AS toolArgs FROM agent_messages m " +
            "JOIN chat_sessions s ON m.sessionId = s.id " +
            "WHERE s.workspacePath = :workspacePath AND m.toolName = 'memory'"
    )
    suspend fun memoryToolCallsByWorkspace(workspacePath: String): List<MemoryToolCallRow>

    /** 与某节点相连的全部边。 */
    @Query("SELECT * FROM graph_edges WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun edgesTouching(nodeId: String): List<GraphEdgeEntity>

    /** 标记节点失效（事实不覆盖，只标失效）。 */
    @Query("UPDATE graph_nodes SET invalidAt = :invalidAt, updatedAt = :invalidAt WHERE id = :id AND invalidAt IS NULL")
    suspend fun invalidateNode(id: String, invalidAt: Long)

    @Query("DELETE FROM graph_nodes WHERE id = :id")
    suspend fun deleteNodeById(id: String)

    /** 删节点连带清边（外键未启用手动级联）。 */
    @Query("DELETE FROM graph_edges WHERE fromNodeId = :id OR toNodeId = :id")
    suspend fun deleteEdgesTouching(id: String)

    @Query("DELETE FROM graph_edges WHERE workspacePath = :workspacePath")
    suspend fun deleteEdgesOfWorkspace(workspacePath: String)

    /** 全量导出（备份用）。 */
    @Query("SELECT * FROM graph_nodes")
    suspend fun allNodes(): List<GraphNodeEntity>

    /** 全量导出（备份用）。 */
    @Query("SELECT * FROM graph_edges")
    suspend fun allEdges(): List<GraphEdgeEntity>
}

/** memory 工具调用投影（GraphDao.memoryToolCallsByWorkspace 用）。 */
data class MemoryToolCallRow(
    val sessionId: String,
    val toolArgs: String?
)
