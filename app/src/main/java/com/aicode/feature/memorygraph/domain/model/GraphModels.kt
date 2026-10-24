package com.aicode.feature.memorygraph.domain.model

/** 记忆图谱节点。 */
data class GraphNode(
    val id: String,
    val workspacePath: String,
    val name: String,
    val type: String,
    val summary: String = "",
    val sourceRefId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** 记忆图谱有向边。 */
data class GraphEdge(
    val id: String,
    val workspacePath: String,
    val fromNodeId: String,
    val toNodeId: String,
    val relation: String
)

/** 检索结果：命中节点 + 排序理由（关键词直接命中 / 图谱游走扩散）。 */
data class GraphSearchHit(
    val node: GraphNode,
    /** KEYWORD=直接命中；WALK=经边扩散 1 跳。 */
    val via: String
)
