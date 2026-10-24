package com.aicode.feature.fixledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.fixledger.data.local.entity.FixLedgerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FixLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FixLedgerEntity)

    @Query("SELECT * FROM fix_ledger ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<FixLedgerEntity>>

    @Query("SELECT * FROM fix_ledger WHERE sourceAnnotationId = :annotationId LIMIT 1")
    suspend fun getBySourceAnnotationId(annotationId: String): FixLedgerEntity?

    @Query("DELETE FROM fix_ledger WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE fix_ledger SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)
}
