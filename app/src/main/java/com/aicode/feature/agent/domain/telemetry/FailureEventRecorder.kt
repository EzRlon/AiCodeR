package com.aicode.feature.agent.domain.telemetry

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.FailureEventDao
import com.aicode.feature.agent.data.local.entity.FailureEventEntity
import com.aicode.feature.agent.data.local.entity.FailureEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行时失败事件埋点（1.7）：三个最小事件——意图纠正、任务失败、编译失败。
 *
 * 与离线评测集（1.3）分工：评测集是「明显变差」的回归护栏，本埋点给的是线上长期指标
 * （用户纠正率、任务失败率），二者互补不重叠。
 *
 * 纪律：埋点绝不影响主流程——异步写、异常吞掉只记日志；[detail] 截断存储，只用于聚类不用于回放。
 */
@Singleton
class FailureEventRecorder @Inject constructor(
    private val dao: FailureEventDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 记录一次失败事件；同一会话同类型事件在 [DEDUP_WINDOW_MS] 内只记一次（防工具批量报错刷屏）。 */
    fun record(
        kind: String,
        sessionId: String? = null,
        source: String? = null,
        detail: String? = null
    ) {
        if (kind !in FailureEventKind.ALL) return
        scope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val recent = if (sessionId != null) dao.getBySession(sessionId, DEDUP_SCAN_LIMIT) else emptyList()
                val duplicated = recent.any {
                    it.kind == kind && it.source == source && now - it.createdAt < DEDUP_WINDOW_MS
                }
                if (duplicated) return@launch
                dao.insert(
                    FailureEventEntity(
                        sessionId = sessionId,
                        kind = kind,
                        source = source?.take(MAX_SOURCE_CHARS),
                        detail = detail?.take(MAX_DETAIL_CHARS),
                        createdAt = now
                    )
                )
            }.onFailure { e ->
                FileLogger.w(TAG, "记录失败事件失败: $kind", e)
            }
        }
    }

    /** 清掉 [keepDays] 天前的事件（随 App 启动或低频维护调用）。 */
    fun prune(keepDays: Int = KEEP_DAYS) {
        scope.launch {
            runCatching {
                dao.deleteBefore(System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000)
            }.onFailure { e -> FileLogger.w(TAG, "清理失败事件失败", e) }
        }
    }

    private companion object {
        const val TAG = "FailureEventRecorder"
        const val DEDUP_WINDOW_MS = 60_000L
        const val DEDUP_SCAN_LIMIT = 20
        const val MAX_SOURCE_CHARS = 120
        const val MAX_DETAIL_CHARS = 400
        const val KEEP_DAYS = 90
    }
}
