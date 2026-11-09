package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import com.aicode.core.theme.AIEditorTheme
import com.aicode.testutil.assertNoOverlap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 语音浮层的布局回归。
 *
 * 目标事故（历史 bug）：浮层里「状态文字」与「手势提示」叠在同一坐标——
 * Surface 的内容槽是 Box 语义，两个子项并列会重合。修法是显式套 Column，
 * 这条用例就是防止改回去。
 *
 * 断言的是**坐标不相交**（assertNoOverlap），不是文案内容——浮层文案随状态切换，
 * 且 Robolectric 默认 locale 与 App 语言未必一致，按文字断言会假红。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class VoiceOverlayLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** 长按态：状态行与手势提示行分行，不重叠。 */
    @Test
    fun `长按态两行文字不重叠`() {
        val dragTarget = mutableStateOf(VoiceDragTarget.SEND)
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldOverlay(
                    processing = false,
                    tapMode = false,
                    dragTarget = dragTarget
                )
            }
        }
        assertTextRowsDoNotOverlap()
    }

    /** 识别中：只有状态行，不应出现手势提示（状态自己已经说明了）。 */
    @Test
    fun `识别中不重复提示手势`() {
        val dragTarget = mutableStateOf(VoiceDragTarget.SEND)
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldOverlay(
                    processing = true,
                    tapMode = false,
                    dragTarget = dragTarget
                )
            }
        }
        // 识别中只有一行文案：文本节点数为 1
        assertTrue(
            "识别中应只渲染状态行，不叠加手势提示",
            compose.textNodeCount() == 1
        )
    }

    /** 滑中取消分支：提示文案切换，仍只有一行。 */
    @Test
    fun `滑中分支只显示一行状态`() {
        val dragTarget = mutableStateOf(VoiceDragTarget.CANCEL)
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldOverlay(
                    processing = false,
                    tapMode = false,
                    dragTarget = dragTarget
                )
            }
        }
        assertTrue(
            "滑中后状态文字已说明结果，不应再重复提示手势",
            compose.textNodeCount() == 1
        )
    }

    /** 带关闭按钮时，关闭按钮可点。 */
    @Test
    fun `关闭按钮可点击`() {
        var clicked = false
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldOverlay(
                    processing = false,
                    tapMode = true,
                    dragTarget = null,
                    onCancel = { clicked = true }
                )
            }
        }
        compose.onNode(hasClickActionNode()).performClick()
        assertTrue("点击关闭应触发 onCancel", clicked)
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 浮层内的文本节点两两不重叠。 */
    private fun assertTextRowsDoNotOverlap() {
        val textNodes = compose.onAllNodes(
            SemanticsMatcher("is text node") { node ->
                !node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
            }
        ).fetchSemanticsNodes()
        assertTrue("长按态应有两行文案（状态 + 手势提示），实际 ${textNodes.size} 行", textNodes.size >= 2)
        // 逐对比较边界：任两行相交就是「叠在同一坐标」事故复现
        val bounds = textNodes.map { it.boundsInRoot }
        for (i in bounds.indices) {
            for (j in i + 1 until bounds.size) {
                assertNoOverlapBounds(bounds[i], bounds[j])
            }
        }
        compose.onRoot().assertExists()
    }

    private fun assertNoOverlapBounds(
        a: androidx.compose.ui.geometry.Rect,
        b: androidx.compose.ui.geometry.Rect
    ) {
        val overlaps = a.left < b.right && b.left < a.right &&
            a.top < b.bottom && b.top < a.bottom
        assertTrue("浮层内两行文字重叠：$a 与 $b", !overlaps)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.textNodeCount(): Int {
        val matcher = SemanticsMatcher("is text node") { node ->
            !node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
        }
        return onAllNodes(matcher).fetchSemanticsNodes().size
    }

    private fun hasClickActionNode(): SemanticsMatcher = hasClickAction()
}
