package com.aicode.feature.fixledger.data

import com.aicode.feature.annotation.data.AnnotationRepository
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.fixledger.data.local.dao.FixLedgerDao
import com.aicode.feature.fixledger.data.local.entity.FixLedgerEntity
import com.aicode.feature.fixledger.domain.model.FixLedgerItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 修复账本仓库：纯 Room 存储，无文件资产；结构镜像 AnnotationRepository（少一层文件落盘）。 */
@Singleton
class FixLedgerRepository @Inject constructor(
    private val dao: FixLedgerDao,
    private val annotationRepository: AnnotationRepository
) {
    val items: Flow<List<FixLedgerItem>> = dao.getAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(
        title: String,
        note: String = "",
        sourceAnnotationId: String? = null,
        status: AnnotationStatus = AnnotationStatus.PENDING
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsert(
            FixLedgerEntity(
                id = id,
                title = title,
                status = status.name,
                note = note,
                sourceAnnotationId = sourceAnnotationId,
                createdAt = now,
                updatedAt = now
            )
        )
        return id
    }

    /** 该标注是否已转入过修复账本（防重复转入）。 */
    suspend fun hasEntryForAnnotation(annotationId: String): Boolean =
        dao.getBySourceAnnotationId(annotationId) != null

    suspend fun updateStatus(id: String, status: AnnotationStatus) {
        dao.updateStatus(id, status.name, System.currentTimeMillis())
    }

    /**
     * AI 回写通道（真融合第一批）：按来源标注推进修复账本与标注账本两侧状态。
     * 仅允许前进且目标限定 FIXING/REVIEW——CLOSED 由用户复验后手动（复验是用户验收，不交给 AI）。
     * 无账本条目时从标注自动补建（qa P1-edge：汇总文案无条件教 AI 回写，用户没手动转入也不断链）。
     * @return 推进后的状态名；标注不存在/非法目标返回 null。
     */
    suspend fun advanceLinked(annotationId: String, target: AnnotationStatus): String? {
        if (target != AnnotationStatus.FIXING && target != AnnotationStatus.REVIEW) return null
        val annotation = annotationRepository.getById(annotationId) ?: return null
        val entry = dao.getBySourceAnnotationId(annotationId)
        val current = entry?.let { e ->
            runCatching { AnnotationStatus.valueOf(e.status) }.getOrDefault(AnnotationStatus.PENDING)
        } ?: annotation.status
        if (target.ordinal <= current.ordinal) return null
        if (entry == null) {
            // 自动补建：以标注当前状态为起点建条目，再推进到目标（qa FULL P1-2：补建后原实现漏推进）
            val newId = add(
                title = annotation.note.ifBlank { annotation.type.name },
                note = "",
                sourceAnnotationId = annotationId,
                status = current
            )
            if (target != current) {
                dao.updateStatus(newId, target.name, System.currentTimeMillis())
            }
        } else {
            dao.updateStatus(entry.id, target.name, System.currentTimeMillis())
        }
        // 标注侧只在落后时前进，避免两侧失衡时倒拉（qa P2）
        if (annotation.status.ordinal < target.ordinal) {
            runCatching { annotationRepository.updateStatus(annotationId, target) }
        }
        return target.name
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}
