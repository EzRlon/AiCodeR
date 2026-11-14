package com.aicode.feature.changelog.domain.model

import com.aicode.feature.annotation.domain.model.AnnotationStatus

/**
 * 改动类型：对齐 Conventional Commits 的 type，便于从提交记录直接映射。
 * 未知 token 归一为 [OTHER]，不丢数据也不误归类。
 */
enum class ChangeKind(val token: String, val labelZh: String) {
    FEAT("feat", "新功能"),
    FIX("fix", "修复"),
    REFACTOR("refactor", "重构"),
    DOCS("docs", "文档"),
    PERF("perf", "性能"),
    TEST("test", "测试"),
    CHORE("chore", "杂项"),
    OTHER("other", "其他");

    companion object {
        fun fromToken(token: String?): ChangeKind {
            val t = token?.trim()?.lowercase() ?: return OTHER
            return entries.firstOrNull { it.token == t } ?: OTHER
        }
    }
}

/**
 * 一条项目改动账目（领域模型）。状态机复用 [AnnotationStatus]，与标注 / 修复账本三方同构——
 * 「哪些改好了、哪些没做」用状态一眼可辨，不必新造概念。
 */
data class ChangeEntry(
    val id: String,
    val workspacePath: String,
    val title: String,
    val kind: ChangeKind,
    val status: AnnotationStatus,
    /** 大白话摘要（用户视图）；空则退回 [title]。 */
    val humanSummary: String = "",
    /** 结构化细节（Agent 视图）：为什么改 + 改前改后。 */
    val agentDetail: String = "",
    /** 验证证据：跑过的命令 / 测试名。「done 需真跑过的证据」，merge 不算。 */
    val evidence: String = "",
    val filesJson: String = "[]",
    val sessionId: String? = null,
    val sourceAnnotationId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val verifiedAt: Long? = null
) {
    /** 用户视图要显示的那行字：优先大白话，没有就用标题。 */
    val displaySummary: String get() = humanSummary.ifBlank { title }

    /** 是否已有验证证据（"改完了"是否真的验过）。 */
    val hasEvidence: Boolean get() = evidence.isNotBlank()

    val isVerified: Boolean get() = status == AnnotationStatus.CLOSED
}
