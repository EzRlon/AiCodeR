package com.aicode.feature.memorygraph.domain.model

/** 记忆图谱节点。 */
data class GraphNode(
    val id: String,
    val workspacePath: String,
    val name: String,
    val type: String,
    val summary: String = "",
    val sourceRefId: String? = null,
    /** 作用域：GLOBAL=全局记忆（跨项目共享）；PROJECT=本项目。 */
    val scope: String = "PROJECT",
    /** 生效时间；0 表示未知。 */
    val validFrom: Long = 0L,
    /** 失效时间：非空=已被新结论取代。 */
    val invalidAt: Long? = null,
    /** 结论出处会话 id（供「跳到来源会话」）。 */
    val sourceSessionId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** 当前是否有效（未被取代）。 */
    val isValid: Boolean get() = invalidAt == null
}

/** 记忆图谱有向边。 */
data class GraphEdge(
    val id: String,
    val workspacePath: String,
    val fromNodeId: String,
    val toNodeId: String,
    val relation: String,
    val validAt: Long = 0L,
    val invalidAt: Long? = null
)

/** 检索结果：命中节点 + 排序理由（关键词直接命中 / 图谱游走扩散）。 */
data class GraphSearchHit(
    val node: GraphNode,
    /** KEYWORD=直接命中；WALK=经边扩散 1 跳。 */
    val via: String
)
