package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 授权记录（审计）：每一条「需要用户决策」或「被系统自动拒绝」的工具授权事件。
 * 自动放行不记录（白名单/档位直放量大且无审计价值）；表内保留最近 200 条，由
 * [com.aicode.feature.agent.domain.permission.PermissionAuditRecorder] 在写入后裁剪。
 */
@Entity(tableName = "permission_audit", indices = [Index(value = ["timestamp"])])
data class PermissionAuditEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    /** 发起该调用的会话 id；空串表示未知（记录点击时不可跳转会话）。 */
    val sessionId: String,
    val toolName: String,
    /** 中文动作摘要（取自授权弹窗的标题摘要，如「AI 请求执行命令」）。 */
    val summary: String,
    /** 决定：[com.aicode.feature.agent.domain.permission.PermissionAuditDecision] 的 key。 */
    val decision: String,
    /** 来源：[com.aicode.feature.agent.domain.permission.PermissionAuditSource] 的 key。 */
    val source: String,
    /** 备注：系统拒绝的原因等；无则 null。 */
    val reason: String? = null
)
