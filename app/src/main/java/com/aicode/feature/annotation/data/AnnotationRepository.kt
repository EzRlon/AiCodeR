package com.aicode.feature.annotation.data

import android.graphics.Bitmap
import com.aicode.core.util.FileLogger
import com.aicode.core.util.ScreenCapture
import com.aicode.feature.annotation.data.local.dao.AnnotationDao
import com.aicode.feature.annotation.data.local.entity.AnnotationEntity
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationMarksCodec
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.annotation.domain.model.AnnotationType
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 截图标注账本仓库：截图 PNG 落盘到工作区 `~/workspace/.aicode/annotations/`（随工作区、
 * 可被 AI 直接读取），元数据与状态机落 Room。经 [FileAccessProvider] 读写，
 * 本地/远程模式跟随当前执行环境。
 */
@Singleton
class AnnotationRepository @Inject constructor(
    private val annotationDao: AnnotationDao,
    private val fileAccess: FileAccessProvider
) {
    private companion object {
        const val TAG = "AnnotationRepository"
        val ANNOTATIONS_DIR = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/annotations"
    }

    val items: Flow<List<AnnotationItem>> = annotationDao.getAll().map { list -> list.map { it.toDomain() } }

    /**
     * 保存一条标注：PNG 落盘 + 元数据落库。返回落库条目；截图或写盘失败返回 null。
     */
    suspend fun saveCapture(
        bitmap: Bitmap,
        type: AnnotationType,
        marks: List<AnnotationMark>,
        note: String
    ): AnnotationItem? = withContext(Dispatchers.IO) {
        val png = ScreenCapture.toPngBytes(bitmap) ?: return@withContext null
        val id = UUID.randomUUID().toString()
        val path = "$ANNOTATIONS_DIR/$id.png"
        try {
            fileAccess.writeBytes(path, png)
            val now = System.currentTimeMillis()
            val item = AnnotationItem(
                id = id,
                type = type,
                status = AnnotationStatus.PENDING,
                imagePath = path,
                marksJson = AnnotationMarksCodec.encode(marks),
                note = note.trim(),
                createdAt = now,
                updatedAt = now
            )
            annotationDao.upsert(AnnotationEntity.fromDomain(item))
            item
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存标注失败", e)
            null
        }
    }

    suspend fun updateStatus(id: String, status: AnnotationStatus) {
        annotationDao.updateStatus(id, status.name, System.currentTimeMillis())
    }

    /** 读取单个标注（fixledger 回写 guard 用）。 */
    suspend fun getById(id: String): AnnotationItem? = annotationDao.getById(id)?.toDomain()

    suspend fun delete(item: AnnotationItem) {
        annotationDao.deleteById(item.id)
        runCatching { fileAccess.delete(item.imagePath) }
    }

    /** 读取截图字节（汇总注入为消息附件用）。 */
    suspend fun readImageBytes(item: AnnotationItem): ByteArray? =
        withContext(Dispatchers.IO) { runCatching { fileAccess.readBytes(item.imagePath) }.getOrNull() }
}
