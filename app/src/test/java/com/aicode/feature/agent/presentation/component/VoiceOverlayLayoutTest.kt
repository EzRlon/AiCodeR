package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.core.theme.AIEditorTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 长按语音全屏舞台（VoiceHoldStage）的布局回归。
 *
 * 目标事故（历史 bug）：旧浮层里「状态文字」与「手势提示」叠在同一坐标——
 * Surface 的内容槽是 Box 语义，两个子项并列会重合。新舞台用 Column 排布
 * 状态行与识别文字气泡，这条用例防止回退。
 *
 * 断言的是文本节点数量与坐标不相交，不按文案内容断言——Robolectric 默认 locale
 * 与 App 语言未必一致，按文字断言会假红。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class VoiceOverlayLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** 长按初始态：只有一行状态文字（扇形标签是 nativeCanvas 画的，不产生语义文本节点）。 */
    @Test
    fun `长按态只有一行状态文字`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldStage(processing = false, dragTarget = VoiceDragTarget.SEND, partialText = "")
            }
        }
        assertTrue("初始态应只有状态行，实际 ${textNodeCount()} 行", textNodeCount() == 1)
    }

    /** 识别中间结果：气泡与状态行同时存在，且两行不重叠。 */
    @Test
    fun `识别气泡与状态行不重叠`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldStage(processing = false, dragTarget = VoiceDragTarget.SEND, partialText = "你好世界")
            }
        }
        assertTrue("应有两行文案（气泡 + 状态），实际 ${textNodeCount()} 行", textNodeCount() >= 2)
        assertTextRowsDoNotOverlap()
    }

    /** 识别中：状态行切换为「正在识别…」，仍只有一行。 */
    @Test
    fun `识别中只有一行状态`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldStage(processing = true, dragTarget = VoiceDragTarget.SEND, partialText = "")
            }
        }
        assertTrue("识别中应只有状态行，实际 ${textNodeCount()} 行", textNodeCount() == 1)
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 浮层内的文本节点两两不重叠。 */
    private fun assertTextRowsDoNotOverlap() {
        val bounds = textNodes().map { it.boundsInRoot }
        for (i in bounds.indices) {
            for (j in i + 1 until bounds.size) {
                assertNoOverlapBounds(bounds[i], bounds[j])
            }
        }
    }

    private fun textNodes() = compose.onAllNodes(textNodeMatcher).fetchSemanticsNodes()

    private fun textNodeCount(): Int = textNodes().size

    private fun assertNoOverlapBounds(
        a: androidx.compose.ui.geometry.Rect,
        b: androidx.compose.ui.geometry.Rect
    ) {
        val overlaps = a.left < b.right && b.left < a.right &&
            a.top < b.bottom && b.top < a.bottom
        assertTrue("浮层内两行文字重叠：$a 与 $b", !overlaps)
    }

    private val textNodeMatcher = SemanticsMatcher("is text node") { node ->
        !node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
    }
}
