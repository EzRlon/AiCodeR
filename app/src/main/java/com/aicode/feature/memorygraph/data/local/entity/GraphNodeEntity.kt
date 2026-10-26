package com.aicode.feature.memorygraph.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 图谱节点类型。 */
object GraphNodeType {
    const val SESSION = "SESSION"
    const val MEMORY = "MEMORY"
    const val CUSTOM = "CUSTOM"
    /** 从会话结论抽取出来的结论（压缩摘要的「关键决定」「教训与坑」）。 */
    const val CONCLUSION = "CONCLUSION"
}

/** 节点作用域：全局记忆跨工作区共享一份，项目级按工作区隔离。 */
object GraphNodeScope {
    const val GLOBAL = "GLOBAL"
    const val PROJECT = "PROJECT"
}

/**
 * 记忆图谱节点（v2 从零建层，v1 泳道是会话聚合视图、无节点表）。
 * 多记忆空间：[workspacePath] 对齐 chat_sessions.workspacePath 既有约定，实现按项目隔离；
 * 全局记忆用空串表示跨项目共享（迁移 73 起）。
 *
 * 时间轴（迁移 73）：[invalidAt] 非空表示该结论已被新结论取代（事实不覆盖、只标失效），
 * 检索默认过滤失效项、UI 灰显可回溯。内容真源仍是 memory md 文件——节点只存结构与关系。
 */
@Entity(
    tableName = "graph_nodes",
    indices = [
        Index(value = ["workspacePath"]),
        // 幂等同步锚点：同一空间同一来源（会话 id / 记忆名）只允许一个节点
        Index(value = ["workspacePath", "type", "sourceRefId"], unique = true),
        Index(value = ["invalidAt"])
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
    /** 作用域：GLOBAL=全局记忆（workspacePath 空串，跨项目共享）；PROJECT=本项目。 */
    val scope: String = GraphNodeScope.PROJECT,
    /** 生效时间（结论得出时间）；0 表示未知，按 createdAt 处理。 */
    val validFrom: Long = 0L,
    /** 失效时间：非空=已被取代；null=当前有效。 */
    val invalidAt: Long? = null,
    /** 结论出处会话 id（MEMORY/CUSTOM 节点回指来源，供「跳到来源会话」）。 */
    val sourceSessionId: String? = null,
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
                scope = node.scope,
                validFrom = node.validFrom,
                invalidAt = node.invalidAt,
                sourceSessionId = node.sourceSessionId,
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
            scope = scope,
            validFrom = validFrom,
            invalidAt = invalidAt,
            sourceSessionId = sourceSessionId,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
}
