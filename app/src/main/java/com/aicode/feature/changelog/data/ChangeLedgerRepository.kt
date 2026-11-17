package com.aicode.feature.changelog.data

import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.changelog.data.local.dao.ChangeLedgerDao
import com.aicode.feature.changelog.data.local.entity.ChangeLedgerEntity
import com.aicode.feature.changelog.domain.model.ChangeEntry
import com.aicode.feature.changelog.domain.model.ChangeKind
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 改动账本仓库：Room 存储，按工作区隔离。
 *
 * 与 [com.aicode.feature.fixledger.data.FixLedgerRepository] 同构（少一层标注联动），
 * 但覆盖面更宽：feat/fix/refactor/docs 等全部改动都记，服务「长期项目里哪些改好了、
 * 哪些没做」这个诉求。工作区路径统一取自 [WorkspaceRepository]，保证账本跟随项目走。
 */
@Singleton
class ChangeLedgerRepository @Inject constructor(
    private val dao: ChangeLedgerDao,
    private val workspaceRepository: WorkspaceRepository
) {
    private fun currentWorkspace(): String = runCatching { workspaceRepository.currentPath() }.getOrDefault("")

    /** 当前工作区的账本流（新的在前）。 */
    fun observeCurrent(): Flow<List<ChangeEntry>> = observe(currentWorkspace())

    fun observe(workspacePath: String): Flow<List<ChangeEntry>> =
        dao.observeByWorkspace(workspacePath).map { rows -> rows.map { it.toDomain() } }

    suspend fun listCurrent(): List<ChangeEntry> =
        dao.getByWorkspaceOnce(currentWorkspace()).map { it.toDomain() }

    /**
     * 记一条改动。[status] 默认 [AnnotationStatus.FIXING]（AI 落笔时通常已动手），
     * 由调用方按语义传更准确的值。
     */
    suspend fun add(
        title: String,
        kind: ChangeKind = ChangeKind.OTHER,
        status: AnnotationStatus = AnnotationStatus.FIXING,
        humanSummary: String = "",
        agentDetail: String = "",
        evidence: String = "",
        filesJson: String = "[]",
        sessionId: String? = null,
        sourceAnnotationId: String? = null,
        workspacePath: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsert(
            ChangeLedgerEntity(
                id = id,
                workspacePath = workspacePath ?: currentWorkspace(),
                title = title,
                kind = kind.token,
                status = status.name,
                humanSummary = humanSummary,
                agentDetail = agentDetail,
                evidence = evidence,
                filesJson = filesJson,
                sessionId = sessionId,
                sourceAnnotationId = sourceAnnotationId,
                createdAt = now,
                updatedAt = now,
                verifiedAt = if (status == AnnotationStatus.CLOSED) now else null
            )
        )
        return id
    }

    /** 更新状态；进入 CLOSED 时记下复验时间（复验是用户验收，由用户触发）。 */
    suspend fun updateStatus(id: String, status: AnnotationStatus) {
        val now = System.currentTimeMillis()
        dao.updateStatus(id, status.name, now, if (status == AnnotationStatus.CLOSED) now else null)
    }

    /** AI 回写通道：按来源标注推进账目状态（仅前进到 FIXING/REVIEW，CLOSED 由用户复验后手动）。 */
    suspend fun advanceByAnnotation(annotationId: String, target: AnnotationStatus): String? {
        if (target != AnnotationStatus.FIXING && target != AnnotationStatus.REVIEW) return null
        val entry = dao.getBySourceAnnotationId(annotationId) ?: return null
        val current = runCatching { AnnotationStatus.valueOf(entry.status) }.getOrDefault(AnnotationStatus.PENDING)
        if (target.ordinal <= current.ordinal) return null
        dao.updateStatus(entry.id, target.name, System.currentTimeMillis(), null)
        return target.name
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun countCurrent(): Int = dao.countByWorkspace(currentWorkspace())
}
