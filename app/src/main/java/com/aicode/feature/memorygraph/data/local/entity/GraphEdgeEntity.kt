package com.aicode.feature.memorygraph.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆图谱有向边：from → to，[relation] 描述关系（如「记录于」「相关」「被取代」）。
 * 同一空间内 (from, to, relation) 唯一，抽取同步可幂等重放。
 *
 * 时间轴（迁移 73）：[invalidAt] 非空表示该关系已失效（如旧结论被新结论取代），
 * 检索默认只看有效边；历史关系保留可回溯。
 */
@Entity(
    tableName = "graph_edges",
    indices = [
        Index(value = ["workspacePath"]),
        Index(value = ["fromNodeId"]),
        Index(value = ["toNodeId"]),
        Index(value = ["workspacePath", "fromNodeId", "toNodeId", "relation"], unique = true),
        Index(value = ["invalidAt"])
    ]
)
data class GraphEdgeEntity(
    @PrimaryKey val id: String,
    val workspacePath: String,
    val fromNodeId: String,
    val toNodeId: String,
    val relation: String,
    /** 关系生效时间；0 表示未知。 */
    val validAt: Long = 0L,
    /** 失效时间：非空=该关系已被取代。 */
    val invalidAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)
