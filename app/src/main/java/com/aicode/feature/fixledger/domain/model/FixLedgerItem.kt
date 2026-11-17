package com.aicode.feature.fixledger.domain.model

import com.aicode.feature.annotation.domain.model.AnnotationStatus

/**
 * 一条修复账目：把「用户反馈 → 修复 → 复验」记成可追溯条目。
 * 状态机与标注账本同构（待确认→已发AI→修复中→待复验→关闭），直接复用 [AnnotationStatus]。
 */
data class FixLedgerItem(
    val id: String,
    /** 问题摘要（必填，一行）。 */
    val title: String,
    val status: AnnotationStatus,
    /** 备注 / 复验结论（可空）。 */
    val note: String,
    /** 来源标注 id（从标注中心转入时回填，用于防重复转入）。 */
    val sourceAnnotationId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
