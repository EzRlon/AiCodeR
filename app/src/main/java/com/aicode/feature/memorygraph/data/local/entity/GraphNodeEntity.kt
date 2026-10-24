package com.aicode.feature.memorygraph.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 图谱节点类型。 */
object GraphNodeType {
    const val SESSION = "SESSION"
    const val MEMORY = "MEMORY"
    const val CUSTOM = "CUSTOM"
}

/**
 * 记忆图谱节点（v2 从零建层，v1 泳道是会话聚合视图、无节点表）。
 * 多记忆空间：[workspacePath] 对齐 chat_sessions.workspacePath 既有约定，实现按项目隔离。
 */
@Entity(
    tableName = "graph_nodes",
    indices = [
        Index(value = ["workspacePath"]),
        // 幂等同步锚点：同一空间同一来源（会话 id / 记忆名）只允许一个节点
        Index(value = ["workspacePath", "type", "sourceRefId"], unique = true)
    ]
)
data class GraphNodeEntity(
    @PrimaryKey val id: String,
    val workspacePath: String,
    val name: String,
    val type: String,
    val summary: String = "",
    /** 节点来源引用：SESSION=会话 id；MEMORY=记忆名；CUSTOM=null。 */
    val sourceRefId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        fun fromDomain(node: com.aicode.feature.memorygraph.domain.model.GraphNode): GraphNodeEntity =
            GraphNodeEntity(
                id = node.id,
                workspacePath = node.workspacePath,
                name = node.name,
                type = node.type,
                summary = node.summary,
                sourceRefId = node.sourceRefId,
                createdAt = node.createdAt,
                updatedAt = node.updatedAt
            )
    }

    fun toDomain(): com.aicode.feature.memorygraph.domain.model.GraphNode =
        com.aicode.feature.memorygraph.domain.model.GraphNode(
            id = id,
            workspacePath = workspacePath,
            name = name,
            type = type,
            summary = summary,
            sourceRefId = sourceRefId,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
}
