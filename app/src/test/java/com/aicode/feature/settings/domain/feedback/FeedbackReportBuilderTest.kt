package com.aicode.feature.settings.domain.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * 反馈报告的拼装与预填链接。
 *
 * 这里钉住的是「用户点提交后到底会看到什么」：标题前缀、正文分节、标签、
 * 以及最容易被忽略的链接长度——中文经 URL 编码会膨胀约 9 倍，不裁剪就会超过浏览器上限。
 */
class FeedbackReportBuilderTest {

    private fun draft(
        kind: FeedbackKind = FeedbackKind.SUGGESTION,
        title: String = "希望支持导出",
        description: String = "想在设置里一键导出全部配置。",
        reproduction: String = "1. 打开设置\n2. 找不到导出",
        diagnostics: String = "App 版本: 2.3.0\nAndroid: 15 (API 35)",
        logExcerpt: String = "2026-11-15 INFO 启动完成"
    ) = FeedbackDraft(kind, title, description, reproduction, diagnostics, logExcerpt)

    @Test
    fun `标题按类型加前缀`() {
        assertEquals("[建议] 希望支持导出", FeedbackReportBuilder.buildTitle(draft(kind = FeedbackKind.SUGGESTION)))
        assertEquals("[Bug] 希望支持导出", FeedbackReportBuilder.buildTitle(draft(kind = FeedbackKind.BUG)))
        assertEquals("[反馈] 希望支持导出", FeedbackReportBuilder.buildTitle(draft(kind = FeedbackKind.OTHER)))
    }

    @Test
    fun `标题为空时退回描述首行`() {
        val title = FeedbackReportBuilder.buildTitle(
            draft(title = "   ", description = "第一行概括\n第二行细节")
        )

        assertEquals("[建议] 第一行概括", title)
    }

    @Test
    fun `缺陷正文包含复现步骤与环境信息`() {
        val body = FeedbackReportBuilder.composeBody(draft(kind = FeedbackKind.BUG))

        assertTrue(body.contains("## 反馈类型"))
        assertTrue(body.contains("缺陷"))
        assertTrue(body.contains("## 复现步骤"))
        assertTrue(body.contains("## 环境信息"))
        assertTrue(body.contains("## 日志片段（已脱敏）"))
        assertTrue(body.contains("```text"))
    }

    @Test
    fun `建议正文不带复现步骤`() {
        val body = FeedbackReportBuilder.composeBody(draft(kind = FeedbackKind.SUGGESTION))

        assertFalse(body.contains("## 复现步骤"))
    }

    @Test
    fun `日志为空时不生成日志段落`() {
        val body = FeedbackReportBuilder.composeBody(draft(logExcerpt = "   "))

        assertFalse(body.contains("## 日志片段"))
    }

    @Test
    fun `标签与类型对应`() {
        assertEquals("enhancement", FeedbackReportBuilder.label(FeedbackKind.SUGGESTION))
        assertEquals("bug", FeedbackReportBuilder.label(FeedbackKind.BUG))
        assertNull(FeedbackReportBuilder.label(FeedbackKind.OTHER))
    }

    @Test
    fun `链接指向仓库的预填 Issue 页`() {
        val report = FeedbackReportBuilder.build(draft(kind = FeedbackKind.BUG))

        assertTrue(report.issueUrl.startsWith("${FeedbackReportBuilder.REPO_URL}/issues/new?"))
        assertTrue(report.issueUrl.contains("template=${FeedbackReportBuilder.TEMPLATE_FILE}"))
        assertTrue(report.issueUrl.contains("labels=bug"))
        // 中文编码后不应残留原文，也不能用 + 表示空格（部分浏览器会原样显示）
        assertFalse(report.issueUrl.contains("希望支持导出"))
        assertFalse(report.issueUrl.contains("+"))
    }

    @Test
    fun `链接正文可解码回 Markdown`() {
        val report = FeedbackReportBuilder.build(draft())
        val encodedBody = report.issueUrl.substringAfter("&body=").substringBefore("&labels=")
        val decoded = URLDecoder.decode(encodedBody, "UTF-8")

        assertTrue(decoded.contains("## 描述"))
        assertTrue(decoded.contains("想在设置里一键导出全部配置。"))
    }

    @Test
    fun `超长日志会被裁出链接但完整正文保留`() {
        val hugeLog = (1..2000).joinToString("\n") { "2026-11-15 12:00:00 INFO 第 $it 行日志内容" }
        val report = FeedbackReportBuilder.build(draft(logExcerpt = hugeLog))
        val encodedBody = report.issueUrl.substringAfter("&body=").substringBefore("&labels=")
        val decoded = URLDecoder.decode(encodedBody, "UTF-8")

        assertTrue("链接长度应受控", report.issueUrl.length <= FeedbackReportBuilder.MAX_URL_CHARS)
        assertTrue("裁剪后的正文要说明原因", decoded.contains("已裁剪"))
        assertTrue("完整正文仍应带日志", report.fullBody.contains("第 2000 行日志内容"))
    }

    @Test
    fun `无标签的反馈类型不带 labels 参数`() {
        val report = FeedbackReportBuilder.build(draft(kind = FeedbackKind.OTHER))

        assertFalse(report.issueUrl.contains("labels="))
    }
}
