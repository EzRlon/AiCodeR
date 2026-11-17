package com.aicode.feature.agent.domain.permission

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.PermissionAuditDao
import com.aicode.feature.agent.data.local.entity.PermissionAuditEntity
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 授权记录的决定类型。 */
enum class PermissionAuditDecision(val key: String) {
    ALLOW_ONCE("ALLOW_ONCE"),
    ALLOW_ALWAYS("ALLOW_ALWAYS"),
    DENY_USER("DENY_USER"),
    DENY_SYSTEM("DENY_SYSTEM"),

    /** 守夜模式自动放行（删除分级通过 / 非破坏性不可判定命令 / 计划审查自动通过等）。 */
    ALLOW_WATCH("ALLOW_WATCH"),

    /** 守夜兜底超时：无人应答，自动拒绝。 */
    WATCH_TIMEOUT("WATCH_TIMEOUT");

    companion object {
        fun fromKey(key: String?): PermissionAuditDecision? = entries.firstOrNull { it.key == key }
    }
}

/** 授权记录的事件来源。 */
enum class PermissionAuditSource(val key: String) {
    POPUP("POPUP"),
    NOTIFICATION("NOTIFICATION"),
    FLOAT_BALL("FLOAT_BALL"),
    SYSTEM("SYSTEM");

    companion object {
        fun fromKey(key: String?): PermissionAuditSource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 授权记录（审计）写入器：统一收口「用户决策」（弹窗/通知栏的批准与拒绝）与
 * 「系统自动拒绝」两类事件。自动放行不记录（白名单/档位直放量大、无审计价值）。
 *
 * 写入为 fire-and-forget（不阻塞调用方），每次写入后裁剪到最近 [KEEP_LIMIT] 条。
 */
@Singleton
class PermissionAuditRecorder @Inject constructor(
    private val dao: PermissionAuditDao
) {
    private companion object {
        const val TAG = "PermissionAudit"
        const val KEEP_LIMIT = 200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 用户决策（应用内弹窗或通知栏的批准/拒绝）。 */
    fun recordUserDecision(
        request: PendingToolPermission,
        choice: PermissionChoice,
        source: PermissionAuditSource
    ) {
        val decision = when (choice) {
            PermissionChoice.REJECT -> PermissionAuditDecision.DENY_USER
            PermissionChoice.ONCE -> PermissionAuditDecision.ALLOW_ONCE
            PermissionChoice.ALWAYS -> PermissionAuditDecision.ALLOW_ALWAYS
        }
        write(
            sessionId = request.sessionId,
            toolName = request.toolName,
            summary = request.summary,
            decision = decision,
            source = source,
            reason = null
        )
    }

    /** 系统自动拒绝（引擎裁决 DENY：档位「禁止」/ deny 规则 / 内置安全防护）。 */
    fun recordSystemDeny(sessionId: String?, toolName: String, summary: String, reason: String?) {
        write(
            sessionId = sessionId.orEmpty(),
            toolName = toolName,
            summary = summary,
            decision = PermissionAuditDecision.DENY_SYSTEM,
            source = PermissionAuditSource.SYSTEM,
            reason = reason
        )
    }

    /** 守夜兜底超时：无人值守无人应答，自动拒绝（通知由通知中心订阅超时事件流发出）。 */
    fun recordWatchTimeout(request: PendingToolPermission) {
        write(
            sessionId = request.sessionId,
            toolName = request.toolName,
            summary = request.summary,
            decision = PermissionAuditDecision.WATCH_TIMEOUT,
            source = PermissionAuditSource.SYSTEM,
            reason = "守夜模式：弹窗超时无人应答，已自动拒绝并继续"
        )
    }

    /** 守夜自动放行（删除分级通过 / 非破坏性不可判定命令 / 计划审查自动通过等）。 */
    fun recordWatchAllow(sessionId: String?, toolName: String, summary: String, reason: String) {
        write(
            sessionId = sessionId.orEmpty(),
            toolName = toolName,
            summary = summary,
            decision = PermissionAuditDecision.ALLOW_WATCH,
            source = PermissionAuditSource.SYSTEM,
            reason = reason
        )
    }

    /** 授权记录页数据流（最近 [limit] 条，时间倒序）。 */
    fun observeRecent(limit: Int = KEEP_LIMIT) = dao.recentFlow(limit)

    private fun write(
        sessionId: String,
        toolName: String,
        summary: String,
        decision: PermissionAuditDecision,
        source: PermissionAuditSource,
        reason: String?
    ) {
        scope.launch {
            runCatching {
                dao.insert(
                    PermissionAuditEntity(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        sessionId = sessionId,
                        toolName = toolName,
                        summary = summary,
                        decision = decision.key,
                        source = source.key,
                        reason = reason
                    )
                )
                dao.trim(KEEP_LIMIT)
            }.onFailure { FileLogger.w(TAG, "授权审计写入失败", it) }
        }
    }
}
