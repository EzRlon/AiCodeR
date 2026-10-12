package com.aicode.feature.annotation.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.annotation.domain.model.AnnotationType

@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val status: String,
    val imagePath: String,
    val marksJson: String = "",
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): AnnotationItem = AnnotationItem(
        id = id,
        type = try { AnnotationType.valueOf(type) } catch (_: Exception) { AnnotationType.BUG },
        status = try { AnnotationStatus.valueOf(status) } catch (_: Exception) { AnnotationStatus.PENDING },
        imagePath = imagePath,
        marksJson = marksJson,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(item: AnnotationItem): AnnotationEntity = AnnotationEntity(
            id = item.id,
            type = item.type.name,
            status = item.status.name,
            imagePath = item.imagePath,
            marksJson = item.marksJson,
            note = item.note,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }
}
