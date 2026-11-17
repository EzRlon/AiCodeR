package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.PermissionAuditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionAuditDao {

    @Insert
    suspend fun insert(entity: PermissionAuditEntity)

    /** 裁剪：只保留最近 [keep] 条（按 timestamp 倒序）。 */
    @Query(
        "DELETE FROM permission_audit WHERE id NOT IN " +
            "(SELECT id FROM permission_audit ORDER BY timestamp DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)

    /** 授权记录页观察流：最近 [limit] 条，时间倒序。 */
    @Query("SELECT * FROM permission_audit ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<PermissionAuditEntity>>

    @Query("DELETE FROM permission_audit")
    suspend fun clearAll()
}
