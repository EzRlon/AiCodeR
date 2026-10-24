package com.aicode.feature.memorygraph.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆图谱有向边：from → to，[relation] 描述关系（如「记录于」「引用」）。
 * 同一空间内 (from, to, relation) 唯一，抽取同步可幂等重放。
 */
@Entity(
    tableName = "graph_edges",
    indices = [
        Index(value = ["workspacePath"]),
        Index(value = ["fromNodeId"]),
        Index(value = ["toNodeId"]),
        Index(value = ["workspacePath", "fromNodeId", "toNodeId", "relation"], unique = true)
    ]
)
data class GraphEdgeEntity(
    @PrimaryKey val id: String,
    val workspacePath: String,
    val fromNodeId: String,
    val toNodeId: String,
    val relation: String,
    val createdAt: Long,
    val updatedAt: Long
)
