package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.FailureEventEntity

@Dao
interface FailureEventDao {
    @Insert
    suspend fun insert(event: FailureEventEntity)

    /** 按类型统计时间窗内的次数（运行时指标：用户纠正率、任务失败率的分母/分子）。 */
    @Query("SELECT COUNT(*) FROM failure_events WHERE kind = :kind AND createdAt >= :since")
    suspend fun countSince(kind: String, since: Long): Int

    /** 时间窗内全部事件总数。 */
    @Query("SELECT COUNT(*) FROM failure_events WHERE createdAt >= :since")
    suspend fun countAllSince(since: Long): Int

    /** 某会话的事件明细（排查具体一轮为什么被纠正/失败）。 */
    @Query("SELECT * FROM failure_events WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 50): List<FailureEventEntity>

    /** 只保留最近 [keepDays] 天的事件，控制表体积（与调用记录同级的低频清理）。 */
    @Query("DELETE FROM failure_events WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long): Int
}
