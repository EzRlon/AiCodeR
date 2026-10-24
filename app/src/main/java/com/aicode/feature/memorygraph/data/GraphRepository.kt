package com.aicode.feature.memorygraph.data

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.memorygraph.data.local.dao.GraphDao
import com.aicode.feature.memorygraph.data.local.entity.GraphEdgeEntity
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeEntity
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeType
import com.aicode.feature.memorygraph.domain.model.GraphEdge
import com.aicode.feature.memorygraph.domain.model.GraphNode
import com.aicode.feature.memorygraph.domain.model.GraphSearchHit
import com.aicode.core.util.FileLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆图谱 v2 仓库：节点/边存储 + 双源同步 + 三路混合检索。
 *
 * 双源（调研确认现版两套记忆互不相通）：①工作区会话（chat_sessions，节点=SESSION）；
 * ②memory 工具的记忆条目（~/.aicode 全局 + 项目级 md，节点=MEMORY）。边=会话内发生过
 * memory 工具调用即连「记录于」。检索=关键词直接命中 + 沿边 1 跳扩散 + updatedAt 时序，
 * 纯本地无 embedding（不上云、无分词库，关键词路为子串匹配——中文抽取质量受限已在交付说明标注）。
 */
@Singleton
class GraphRepository @Inject constructor(
    private val graphDao: GraphDao,
    private val chatSessionDao: ChatSessionDao,
    private val memoryRepository: MemoryRepository
) {

    fun observeGraph(workspacePath: String): Flow<Pair<List<GraphNode>, List<GraphEdge>>> =
        combine(
            graphDao.nodesByWorkspace(workspacePath),
            graphDao.edgesByWorkspace(workspacePath)
        ) { nodes, edges ->
            nodes.map { it.toDomain() } to edges.map { e ->
                GraphEdge(e.id, e.workspacePath, e.fromNodeId, e.toNodeId, e.relation)
            }
        }

    /**
     * 幂等同步工作区图谱：SESSION 节点对齐会话（含回收站外的全部根会话）、MEMORY 节点对齐
     * 记忆条目、边按会话内的 memory 工具调用连接。可反复调用（upsert 幂等）。
     */
    suspend fun syncWorkspaceGraph(workspacePath: String) {
        if (workspacePath.isBlank()) return
        val now = System.currentTimeMillis()

        // 1) 会话节点（节点 id 复用给步骤 3，避免重复 findBySourceRef——optimizer #1）
        val sessions = chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath)
        val sessionNodeIds = mutableMapOf<String, String>()
        sessions.forEach { session ->
            val existing = graphDao.findBySourceRef(workspacePath, GraphNodeType.SESSION, session.id)
            val id = existing?.id ?: UUID.randomUUID().toString()
            sessionNodeIds[session.id] = id
            graphDao.upsertNode(
                GraphNodeEntity(
                    id = id,
                    workspacePath = workspacePath,
                    name = session.title,
                    type = GraphNodeType.SESSION,
                    summary = "",
                    sourceRefId = session.id,
                    createdAt = existing?.createdAt ?: session.createdAt,
                    updatedAt = session.updatedAt
                )
            )
        }

        // 2) 记忆节点（全局 + 项目级，listMemories 已做项目级优先去重）
        val memories = runCatching { memoryRepository.listMemories(workspacePath) }.getOrDefault(emptyList())
        val memoryNodeIds = mutableMapOf<String, String>()
        memories.forEach { memory ->
            val existing = graphDao.findBySourceRef(workspacePath, GraphNodeType.MEMORY, memory.name)
            val id = existing?.id ?: UUID.randomUUID().toString()
            memoryNodeIds[memory.name] = id
            graphDao.upsertNode(
                GraphNodeEntity(
                    id = id,
                    workspacePath = workspacePath,
                    name = memory.name,
                    type = GraphNodeType.MEMORY,
                    summary = memory.description,
                    sourceRefId = memory.name,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
        }

        // 3) 边：会话内发生过 memory 工具调用 → 该记忆「记录于」该会话。
        // 单条 SQL 聚合（optimizer P0：替代逐会话全量读消息），只认 JSON 键值模式防常见词误连边。
        if (memoryNodeIds.isNotEmpty() && sessionNodeIds.isNotEmpty()) {
            val memCalls = runCatching { graphDao.memoryToolCallsByWorkspace(workspacePath) }
                .onFailure { FileLogger.w(TAG, "读 memory 工具调用失败（图谱跳过连边）: ${it.message}") }
                .getOrDefault(emptyList())
            memCalls.forEach { row ->
                val sessionNodeId = sessionNodeIds[row.sessionId] ?: return@forEach
                val args = row.toolArgs ?: return@forEach
                memoryNodeIds.forEach { (name, memoryNodeId) ->
                    val pattern = Regex("\"name\"\\s*:\\s*\"" + Regex.escape(name) + "\"")
                    if (pattern.containsMatchIn(args)) {
                        graphDao.upsertEdge(
                            GraphEdgeEntity(
                                id = edgeId(workspacePath, sessionNodeId, memoryNodeId, RELATION_RECORDED_IN),
                                workspacePath = workspacePath,
                                fromNodeId = memoryNodeId,
                                toNodeId = sessionNodeId,
                                relation = RELATION_RECORDED_IN,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 三路混合检索：关键词直接命中 → 沿边 1 跳扩散（图谱游走）→ updatedAt 时序兜底排序。
     * 查询过短（<2 字符）退化为按时间返回最近节点。
     */
    suspend fun search(workspacePath: String, query: String, limit: Int = 30): List<GraphSearchHit> {
        val trimmed = query.trim()
        val hits = LinkedHashMap<String, GraphSearchHit>()
        if (trimmed.length >= 2) {
            graphDao.searchNodes(workspacePath, trimmed, limit).forEach { node ->
                hits[node.id] = GraphSearchHit(node.toDomain(), VIA_KEYWORD)
            }
        }
        // 图谱游走：命中节点的 1 跳邻居也纳入（去重）
        hits.values.toList().forEach { hit ->
            graphDao.edgesTouching(workspacePath, hit.node.id).forEach { edge ->
                val peerId = if (edge.fromNodeId == hit.node.id) edge.toNodeId else edge.fromNodeId
                if (peerId !in hits) {
                    graphDao.nodeById(peerId)?.let { peer ->
                        hits[peerId] = GraphSearchHit(peer.toDomain(), VIA_WALK)
                    }
                }
            }
        }
        return hits.values
            .sortedByDescending { it.node.updatedAt }
            .take(limit)
    }

    /** 手动节点（类型 CUSTOM，无来源引用）。 */
    suspend fun addCustomNode(workspacePath: String, name: String, summary: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        graphDao.upsertNode(
            GraphNodeEntity(
                id = id,
                workspacePath = workspacePath,
                name = name,
                type = GraphNodeType.CUSTOM,
                summary = summary,
                sourceRefId = null,
                createdAt = now,
                updatedAt = now
            )
        )
        return id
    }

    /** 连边（手动）：from → to，relation 自定义。 */
    suspend fun addEdge(workspacePath: String, fromNodeId: String, toNodeId: String, relation: String) {
        val now = System.currentTimeMillis()
        graphDao.upsertEdge(
            GraphEdgeEntity(
                id = edgeId(workspacePath, fromNodeId, toNodeId, relation),
                workspacePath = workspacePath,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                relation = relation,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun deleteNode(id: String) {
        graphDao.deleteEdgesTouching(id)
        graphDao.deleteNodeById(id)
    }

    private fun edgeId(workspace: String, from: String, to: String, relation: String): String =
        "$workspace|$from|$to|$relation"

    companion object {
        private const val TAG = "GraphRepository"
        const val RELATION_RECORDED_IN = "记录于"
        const val VIA_KEYWORD = "KEYWORD"
        const val VIA_WALK = "WALK"
    }
}
