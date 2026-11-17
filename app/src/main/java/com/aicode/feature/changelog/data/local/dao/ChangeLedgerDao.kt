package com.aicode.feature.changelog.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.changelog.data.local.entity.ChangeLedgerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChangeLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChangeLedgerEntity)

    /** 某工作区的全部账目（新的在前）。 */
    @Query("SELECT * FROM change_ledger WHERE workspacePath = :workspacePath ORDER BY updatedAt DESC")
    fun observeByWorkspace(workspacePath: String): Flow<List<ChangeLedgerEntity>>

    @Query("SELECT * FROM change_ledger WHERE workspacePath = :workspacePath ORDER BY updatedAt DESC")
    suspend fun getByWorkspaceOnce(workspacePath: String): List<ChangeLedgerEntity>

    @Query("SELECT * FROM change_ledger WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChangeLedgerEntity?

    @Query("SELECT * FROM change_ledger WHERE sourceAnnotationId = :annotationId LIMIT 1")
    suspend fun getBySourceAnnotationId(annotationId: String): ChangeLedgerEntity?

    @Query("DELETE FROM change_ledger WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE change_ledger SET status = :status, updatedAt = :updatedAt, verifiedAt = :verifiedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long, verifiedAt: Long?)

    @Query("SELECT COUNT(*) FROM change_ledger WHERE workspacePath = :workspacePath")
    suspend fun countByWorkspace(workspacePath: String): Int
}
