package com.aicode.feature.fixledger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.fixledger.domain.model.FixLedgerItem

@Entity(tableName = "fix_ledger")
data class FixLedgerEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    val note: String = "",
    val sourceAnnotationId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): FixLedgerItem = FixLedgerItem(
        id = id,
        title = title,
        status = try { AnnotationStatus.valueOf(status) } catch (_: Exception) { AnnotationStatus.PENDING },
        note = note,
        sourceAnnotationId = sourceAnnotationId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(item: FixLedgerItem): FixLedgerEntity = FixLedgerEntity(
            id = item.id,
            title = item.title,
            status = item.status.name,
            note = item.note,
            sourceAnnotationId = item.sourceAnnotationId,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }
}
