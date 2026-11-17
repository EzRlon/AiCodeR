package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 运行时失败事件（1.7 失败埋点）：与 [LlmCallRecordEntity] 互补。
 *
 * 那张表记「每次模型调用」的成本与延迟；本表只记「一次交互里的坏信号」，
 * 用于长期量化「更准」——用户纠正率、任务失败率、编译失败率，而不是靠离线评测集证明。
 *
 * @property kind 事件类型，见 [FailureEventKind]
 * @property source 触发来源：工具名（任务失败）/ 任务名（编译失败）/ 空（意图纠正）
 * @property detail 简短说明（错误码、首句摘要），截断存储，只用于聚类分析不用于回放
 */
@Entity(
    tableName = "failure_events",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["kind"])
    ]
)
data class FailureEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String? = null,
    val kind: String,
    val source: String? = null,
    val detail: String? = null,
    val createdAt: Long
)

/** 失败事件类型：三个最小事件集，新增类型必须同步埋点与看板口径。 */
object FailureEventKind {
    /** 意图纠正：用户重述/改口/重发上一条（AI 没听懂或做错了的信号）。 */
    const val INTENT_CORRECTION = "intent_correction"

    /** 任务失败：工具执行报错、权限阻塞、子代理失败。 */
    const val TASK_FAILURE = "task_failure"

    /** 编译失败：构建命令非零退出（用户或 AI 跑构建时）。 */
    const val BUILD_FAILURE = "build_failure"

    val ALL = setOf(INTENT_CORRECTION, TASK_FAILURE, BUILD_FAILURE)
}
