package com.aicode.feature.memorygraph.data

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.memorygraph.data.local.dao.GraphDao
import com.aicode.feature.memorygraph.data.local.entity.GraphEdgeEntity
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeEntity
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeScope
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeType
import com.aicode.feature.memorygraph.domain.ConclusionParser
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
 * 记忆图谱仓库：节点/边存储 + 双源同步 + 混合检索 + 时间轴。
 *
 * 双源：①工作区会话（chat_sessions，节点=SESSION）；②memory 工具的记忆条目
 * （全局 + 项目级 md，节点=MEMORY）。边=会话内发生过 memory 工具调用即连「记录于」。
 *
 * 内容真源是 memory md 文件：节点只存「名称/摘要/出处/关系/时间」，不复制正文——
 * AI 从系统提示词读到的是同一份 md，避免两处存储互相打架。
 *
 * 时间轴（迁移 73）：结论被取代时只标 [GraphNodeEntity.invalidAt]，不删除、不覆盖，
 * 检索默认过滤失效项（AI 不会把过期结论当现状），UI 灰显可回溯历史。
 */
@Singleton
class GraphRepository @Inject constructor(
    private val graphDao: GraphDao,
    private val chatSessionDao: ChatSessionDao,
    private val memoryRepository: MemoryRepository
) : com.aicode.feature.memorygraph.domain.ConclusionReader {

    fun observeGraph(workspacePath: String): Flow<Pair<List<GraphNode>, List<GraphEdge>>> =
        combine(
            graphDao.nodesByWorkspace(workspacePath),
            graphDao.edgesByWorkspace(workspacePath)
        ) { nodes, edges ->
            nodes.map { it.toDomain() } to edges.map { e ->
                GraphEdge(e.id, e.workspacePath, e.fromNodeId, e.toNodeId, e.relation, e.validAt, e.invalidAt)
            }
        }

    /**
     * 幂等同步工作区图谱：SESSION 节点对齐会话（含回收站外的全部根会话）、MEMORY 节点对齐
     * 记忆条目、边按会话内的 memory 工具调用连接。可反复调用（upsert 幂等）。
     *
     * 全局记忆只存一份（workspacePath 空串、scope=GLOBAL），项目级记忆按工作区隔离——
     * 否则同一条全局记忆会在每个工作区各生成一个重复节点。
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
                    scope = GraphNodeScope.PROJECT,
                    validFrom = session.createdAt,
                    invalidAt = existing?.invalidAt,
                    sourceSessionId = session.id,
                    createdAt = existing?.createdAt ?: session.createdAt,
                    updatedAt = session.updatedAt
                )
            )
        }

        // 2) 记忆节点（全局 + 项目级，listMemories 已做项目级优先去重）
        val memories = runCatching { memoryRepository.listMemories(workspacePath) }.getOrDefault(emptyList())
        val memoryNodeIds = mutableMapOf<String, String>()
        memories.forEach { memory ->
            val isGlobal = memory.scope == MemoryScope.GLOBAL
            // 全局记忆落在空 workspacePath 下（跨项目共享一份），项目记忆落在本项目下
            val nodeWorkspace = if (isGlobal) "" else workspacePath
            val existing = graphDao.findBySourceRef(nodeWorkspace, GraphNodeType.MEMORY, memory.name)
            val id = existing?.id ?: UUID.randomUUID().toString()
            memoryNodeIds[memory.name] = id
            graphDao.upsertNode(
                GraphNodeEntity(
                    id = id,
                    workspacePath = nodeWorkspace,
                    name = memory.name,
                    type = GraphNodeType.MEMORY,
                    summary = memory.description,
                    sourceRefId = memory.name,
                    scope = if (isGlobal) GraphNodeScope.GLOBAL else GraphNodeScope.PROJECT,
                    validFrom = existing?.validFrom?.takeIf { it > 0 } ?: now,
                    invalidAt = existing?.invalidAt,
                    sourceSessionId = existing?.sourceSessionId,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
        }

        // 3) 边：会话内发生过 memory 工具调用 → 该记忆「记录于」该会话。
        // 单条 SQL 聚合（optimizer P0：替代逐会话全量读消息），只认 JSON 键值模式防常见词误连边。
        if (memoryNodeIds.isNotEmpty() && sessionNodeIds.isNotEmpty()) {
            val memoryNamePatterns = HashMap<String, Regex>()
            val nodeWorkspaceCache = HashMap<String, String>()
            val memCalls = runCatching { graphDao.memoryToolCallsByWorkspace(workspacePath) }
                .onFailure { FileLogger.w(TAG, "读 memory 工具调用失败（图谱跳过连边）: ${it.message}") }
                .getOrDefault(emptyList())
            memCalls.forEach { row ->
                val sessionNodeId = sessionNodeIds[row.sessionId] ?: return@forEach
                val args = row.toolArgs ?: return@forEach
                memoryNodeIds.forEach { (name, memoryNodeId) ->
                    // 正则预编译提到双层循环外：原先每条调用×每个记忆都重新编译一次，是纯浪费。
                    val pattern = memoryNamePatterns.getOrPut(name) {
                        Regex("\"name\"\\s*:\\s*\"" + Regex.escape(name) + "\"")
                    }
                    if (pattern.containsMatchIn(args)) {
                        // 记忆节点的所属空间：全局记忆在空 workspacePath，项目记忆在本工作区。
                        // nodeById 提到循环外按 id 缓存：同一记忆在多条调用里命中时只查一次库。
                        val nodeWorkspace = nodeWorkspaceCache.getOrPut(memoryNodeId) {
                            graphDao.nodeById(memoryNodeId)?.workspacePath ?: workspacePath
                        }
                        graphDao.upsertEdge(
                            GraphEdgeEntity(
                                id = edgeId(nodeWorkspace, memoryNodeId, sessionNodeId, RELATION_RECORDED_IN),
                                workspacePath = nodeWorkspace,
                                fromNodeId = memoryNodeId,
                                toNodeId = sessionNodeId,
                                relation = RELATION_RECORDED_IN,
                                validAt = now,
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
     * 混合检索：关键词直接命中 → 沿边 1 跳扩散（图谱游走）→ updatedAt 时序兜底排序。
     * 默认只看当前有效的节点（[includeInvalid]=false），AI 查询不会拿到过期结论；
     * 查询过短（<2 字符）退化为按时间返回最近节点。
     */
    suspend fun search(
        workspacePath: String,
        query: String,
        limit: Int = 30,
        includeInvalid: Boolean = false
    ): List<GraphSearchHit> {
        val trimmed = query.trim()
        val hits = LinkedHashMap<String, GraphSearchHit>()
        if (trimmed.length >= 2) {
            graphDao.searchNodes(workspacePath, trimmed, if (includeInvalid) 1 else 0, limit).forEach { node ->
                hits[node.id] = GraphSearchHit(node.toDomain(), VIA_KEYWORD)
            }
        }
        // 图谱游走：命中节点的 1 跳邻居也纳入（去重）
        hits.values.toList().forEach { hit ->
            graphDao.edgesTouching(hit.node.id).forEach { edge ->
                if (edge.invalidAt != null) return@forEach
                val peerId = if (edge.fromNodeId == hit.node.id) edge.toNodeId else edge.fromNodeId
                if (peerId !in hits) {
                    graphDao.nodeById(peerId)?.let { peer ->
                        if (includeInvalid || peer.invalidAt == null) {
                            hits[peerId] = GraphSearchHit(peer.toDomain(), VIA_WALK)
                        }
                    }
                }
            }
        }
        return hits.values
            .sortedByDescending { it.node.updatedAt }
            .take(limit)
    }

    /**
     * 当前有效结论（会话开始注入用）：只看未失效的 MEMORY/CUSTOM 节点，
     * 按结论时间倒序取前 [limit] 条。返回「名称 + 摘要 + 出处会话」，不含正文（省 token）。
     */
    /** [com.aicode.feature.memorygraph.domain.ConclusionReader] 实现：默认值声明在接口上。 */
    override suspend fun validConclusions(workspacePath: String, limit: Int): List<GraphNode> =
        runCatching {
            graphDao.validConclusions(workspacePath, CONCLUSION_TYPES, limit).map { it.toDomain() }
        }
            .onFailure { FileLogger.w(TAG, "读有效结论失败: ${it.message}") }
            .getOrDefault(emptyList())

    /**
     * 标记结论失效（被新结论取代）：不删除、不覆盖，只写 invalidAt。
     * 这样 AI 默认查不到旧结论，人仍能在页面上回溯「以前是怎么定的、什么时候改的」。
     */
    suspend fun invalidateNode(id: String, at: Long = System.currentTimeMillis()) {
        graphDao.invalidateNode(id, at)
    }

    /**
     * 记录一条结论节点（抽取路径）：类型 CUSTOM（无 md 来源）、带类型标签与出处会话。
     * 幂等：同一会话同一结论名只建一个节点；已存在则只刷新摘要与时间，不重复建。
     */
    suspend fun recordConclusion(
        workspacePath: String,
        name: String,
        summary: String,
        label: String,
        sourceSessionId: String
    ): String {
        val now = System.currentTimeMillis()
        val existing = graphDao.findBySourceRef(workspacePath, GraphNodeType.CONCLUSION, name)
        val id = existing?.id ?: UUID.randomUUID().toString()
        graphDao.upsertNode(
            GraphNodeEntity(
                id = id,
                workspacePath = workspacePath,
                name = name,
                type = GraphNodeType.CONCLUSION,
                summary = summary,
                sourceRefId = name,
                scope = GraphNodeScope.PROJECT,
                validFrom = existing?.validFrom?.takeIf { it > 0 } ?: now,
                invalidAt = existing?.invalidAt,
                sourceSessionId = sourceSessionId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
        // 连边：结论「出自」来源会话，便于页面一键跳回去看证据
        graphDao.findBySourceRef(workspacePath, GraphNodeType.SESSION, sourceSessionId)?.let { sessionNode ->
            graphDao.upsertEdge(
                GraphEdgeEntity(
                    id = edgeId(workspacePath, id, sessionNode.id, RELATION_DERIVED_FROM),
                    workspacePath = workspacePath,
                    fromNodeId = id,
                    toNodeId = sessionNode.id,
                    relation = RELATION_DERIVED_FROM,
                    validAt = now,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        FileLogger.i(TAG, "记录结论节点「$name」[$label] 来自会话 $sourceSessionId")
        return id
    }

    /**
     * 从压缩摘要抽取结论写入图谱（零额外模型调用：摘要已在压缩时生成，这里只解析文本）。
     * 失败静默——抽取是附加价值，绝不能让压缩本身失败。
     */
    override suspend fun extractConclusionsFromSummary(workspacePath: String, sessionId: String, summary: String) {
        if (workspacePath.isBlank() || summary.isBlank()) return
        val conclusions = ConclusionParser.parse(summary)
        if (conclusions.isEmpty()) return
        conclusions.forEach { conclusion ->
            runCatching {
                recordConclusion(
                    workspacePath = workspacePath,
                    name = conclusion.name,
                    summary = conclusion.text,
                    label = conclusion.label,
                    sourceSessionId = sessionId
                )
            }.onFailure { FileLogger.w(TAG, "写结论节点失败「${conclusion.name}」: ${it.message}") }
        }
        FileLogger.i(TAG, "压缩摘要抽取结论：${conclusions.size} 条")
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
                scope = GraphNodeScope.PROJECT,
                validFrom = now,
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
                validAt = now,
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
        const val RELATION_RELATED = "相关"
        const val RELATION_SUPERSEDED = "被取代"
        const val RELATION_DERIVED_FROM = "出自"
        const val VIA_KEYWORD = "KEYWORD"
        const val VIA_WALK = "WALK"

        /** 结论类节点类型：单一来源是 [GraphNodeType]，避免 SQL 里再硬编码一份。 */
        private val CONCLUSION_TYPES = listOf(GraphNodeType.MEMORY, GraphNodeType.CONCLUSION, GraphNodeType.CUSTOM)
    }
}
