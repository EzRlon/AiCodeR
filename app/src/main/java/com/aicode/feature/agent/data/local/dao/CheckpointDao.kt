package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity

@Dao
interface CheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity)

    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity>

    // 撤销必须限定会话：不同会话的 checkpoint 可能挂着同一个 messageId 之外的历史，
    // 全局按 messageId 查会定位到别会话的 checkpoint 并还原它名下的文件。
    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId AND userMessageId = :messageId LIMIT 1")
    suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity?

    @Query("SELECT * FROM session_checkpoints WHERE id = :checkpointId LIMIT 1")
    suspend fun getCheckpointById(checkpointId: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId")
    suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity>

    /** 按快照时间倒序取本会话全部文件快照，供「最近编辑文件」查询去重后取最新。 */
    @Query(
        "SELECT s.* FROM checkpoint_file_snapshots s " +
            "INNER JOIN session_checkpoints c ON s.checkpointId = c.id " +
            "WHERE c.sessionId = :sessionId ORDER BY s.createdAt DESC"
    )
    suspend fun getRecentSnapshotsForSession(sessionId: String): List<CheckpointFileSnapshotEntity>

    @Query("SELECT COUNT(*) FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId AND filePath = :filePath")
    suspend fun countSnapshot(checkpointId: String, filePath: String): Int

    @Query("DELETE FROM session_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpointsForSession(sessionId: String)

    @Query("DELETE FROM checkpoint_file_snapshots WHERE checkpointId IN (SELECT id FROM session_checkpoints WHERE sessionId = :sessionId)")
    suspend fun deleteFileSnapshotsForSession(sessionId: String)

    @Query("DELETE FROM session_checkpoints WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long)

    @Query("DELETE FROM checkpoint_file_snapshots")
    suspend fun deleteAllFileSnapshots()

    @Query("DELETE FROM session_checkpoints")
    suspend fun deleteAllCheckpoints()
}
