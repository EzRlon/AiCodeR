package com.aicode.feature.changelog.domain.model

import com.aicode.feature.annotation.domain.model.AnnotationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改动账本的领域契约：类型归一、用户视图取值、证据/复验判定。
 *
 * 这几条决定了账本页「哪些改好了、哪些没做」的判断是否可信——误判会让用户以为改完了。
 */
class ChangeEntryTest {

    private fun entry(
        title: String = "标题",
        humanSummary: String = "",
        evidence: String = "",
        status: AnnotationStatus = AnnotationStatus.FIXING
    ) = ChangeEntry(
        id = "id",
        workspacePath = "/ws",
        title = title,
        kind = ChangeKind.FEAT,
        status = status,
        humanSummary = humanSummary,
        evidence = evidence,
        createdAt = 0,
        updatedAt = 0
    )

    @Test
    fun `未知类型归一为 OTHER 不丢数据`() {
        assertEquals(ChangeKind.OTHER, ChangeKind.fromToken("something-weird"))
        assertEquals(ChangeKind.OTHER, ChangeKind.fromToken(null))
        assertEquals(ChangeKind.OTHER, ChangeKind.fromToken("  "))
    }

    @Test
    fun `类型解析忽略大小写与空白`() {
        assertEquals(ChangeKind.FEAT, ChangeKind.fromToken("FEAT"))
        assertEquals(ChangeKind.FIX, ChangeKind.fromToken(" fix "))
        assertEquals(ChangeKind.REFACTOR, ChangeKind.fromToken("Refactor"))
    }

    @Test
    fun `用户视图优先大白话没有则退回标题`() {
        assertEquals("改好了侧栏折叠", entry(humanSummary = "改好了侧栏折叠").displaySummary)
        assertEquals("原始标题", entry(title = "原始标题").displaySummary)
    }

    @Test
    fun `证据为空视为未验证`() {
        assertFalse(entry(evidence = "").hasEvidence)
        assertFalse(entry(evidence = "   ").hasEvidence)
        assertTrue(entry(evidence = "gradle test X").hasEvidence)
    }

    @Test
    fun `只有 CLOSED 算已验`() {
        assertFalse(entry(status = AnnotationStatus.REVIEW).isVerified)
        assertTrue(entry(status = AnnotationStatus.CLOSED).isVerified)
    }
}
