package com.aicode.feature.changelog.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.changelog.domain.model.ChangeEntry
import com.aicode.feature.changelog.domain.model.ChangeKind

/**
 * 一条项目改动账目：跨会话记录「改了什么、为什么、怎么验的、现在什么状态」。
 *
 * 与 `fix_ledger`（只记来自标注的修复）互为补充：本表覆盖 feat/fix/refactor/docs 等全部改动，
 * 且带 [workspacePath] 按项目隔离——长期项目里反馈累积多了，靠它回答「哪些改好了、哪些没做」。
 */
@Entity(
    tableName = "change_ledger",
    indices = [Index(value = ["workspacePath"]), Index(value = ["updatedAt"])]
)
data class ChangeLedgerEntity(
    @PrimaryKey val id: String,
    /** 所属工作区；账本按项目隔离，空串表示未绑定工作区。 */
    val workspacePath: String,
    /** 一行标题（用户视图的骨架）。 */
    val title: String,
    /** 改动类型：feat/fix/refactor/docs/chore/perf/test，对齐 Conventional Commits。 */
    val kind: String,
    /** 状态机（复用标注账本）：PENDING→SENT→FIXING→REVIEW→CLOSED。 */
    val status: String,
    /** 大白话摘要（用户视图）；空则退回 [title]。 */
    val humanSummary: String = "",
    /** 结构化细节（Agent 视图）：为什么改 + 改前改后。 */
    val agentDetail: String = "",
    /** 验证证据：跑过的命令 / 测试名。「done 需真跑过的证据」，merge 不算。 */
    val evidence: String = "",
    /** 涉改文件（JSON 数组文本，便于展示与回链）。 */
    val filesJson: String = "[]",
    /** 来源会话 id（可跳回现场）。 */
    val sessionId: String? = null,
    /** 来源标注 id（由标注中心转入时回填，用于防重复转入）。 */
    val sourceAnnotationId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** 复验通过（进入 CLOSED）的时间；未复验为 null。 */
    val verifiedAt: Long? = null
) {
    fun toDomain(): ChangeEntry = ChangeEntry(
        id = id,
        workspacePath = workspacePath,
        title = title,
        kind = ChangeKind.fromToken(kind),
        status = runCatching { AnnotationStatus.valueOf(status) }.getOrDefault(AnnotationStatus.PENDING),
        humanSummary = humanSummary,
        agentDetail = agentDetail,
        evidence = evidence,
        filesJson = filesJson,
        sessionId = sessionId,
        sourceAnnotationId = sourceAnnotationId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        verifiedAt = verifiedAt
    )

    companion object {
        fun fromDomain(item: ChangeEntry): ChangeLedgerEntity = ChangeLedgerEntity(
            id = item.id,
            workspacePath = item.workspacePath,
            title = item.title,
            kind = item.kind.token,
            status = item.status.name,
            humanSummary = item.humanSummary,
            agentDetail = item.agentDetail,
            evidence = item.evidence,
            filesJson = item.filesJson,
            sessionId = item.sessionId,
            sourceAnnotationId = item.sourceAnnotationId,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            verifiedAt = item.verifiedAt
        )
    }
}
