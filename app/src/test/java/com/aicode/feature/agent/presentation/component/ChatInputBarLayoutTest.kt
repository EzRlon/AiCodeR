package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.core.theme.AIEditorTheme
import com.aicode.testutil.assertNoOverlap
import com.aicode.testutil.assertSameRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Tool
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 底栏按钮的布局回归。
 *
 * 目标事故（用户反馈）：底栏按钮分成两排、按钮被挤出可视区。
 * 断言坐标关系（同一 Row 里的按钮中心 y 是否一致），不打像素。
 *
 * 定位方式：布局用例用 contentDescription（这些按钮本身就有无障碍描述，
 * 为测试往产品代码加 testTag 属于本末倒置）；涉及文案的断言刻意避开——
 * Robolectric 默认 locale 与 App 当前语言不一致，按「发送」这类界面文字断言会假红。
 *
 * 已知边界：这里渲染的是底栏用到的真实按钮组件（SendButton / UploadIconButton），
 * 没有渲染整个 ChatInputBar——它要 40+ 个参数与 Onboarding 状态，
 * 为坐标断言拉起整页不划算；整页用例需要 Hilt 测试基建，另行安排。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class ChatInputBarLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** 底栏右侧：工具键与附件键在同一排。 */
    @Test
    fun `工具键与附件键同排`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    UploadIconButton(
                        enabled = true,
                        icon = FeatherIcons.Tool,
                        contentDescription = "tools",
                        onClick = {}
                    )
                    UploadIconButton(
                        enabled = true,
                        icon = FeatherIcons.Plus,
                        contentDescription = "attach",
                        onClick = {}
                    )
                }
            }
        }
        assertSameRow(
            listOf(
                compose.onNodeWithContentDescription("tools"),
                compose.onNodeWithContentDescription("attach")
            )
        )
    }

    /** 发送键「可发送」状态：渲染出带无障碍描述的状态图标。 */
    @Test
    fun `发送键可发送状态渲染出图标`() = assertSendIconRendered(busy = false)

    /** 发送键「忙」状态（显示停止方块）：同样渲染出图标。 */
    @Test
    fun `发送键忙状态渲染出图标`() = assertSendIconRendered(busy = true)

    /**
     * 断言发送键在给定状态下渲染出了状态图标。
     *
     * 刻意不按文案定位：Robolectric 默认 locale 是 en-US，而 App 语言由用户决定，
     * 按「发送」这种界面文字断言会在换语言时假红。这里只要求「有节点带无障碍描述」，
     * 与具体语言无关——两种状态各是「向上箭头」和「停止方块」，都带描述。
     */
    private fun assertSendIconRendered(busy: Boolean) {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                SendButton(
                    canSend = true,
                    hasContent = true,
                    isBusy = busy,
                    tokenProgress = 0f,
                    tokenEstimated = false,
                    onSend = {},
                    onStop = {},
                    onVoiceHoldStart = {},
                    onVoiceHoldFinish = {},
                    onVoiceHoldCancel = {},
                    dragTarget = null
                )
            }
        }
        val hasAnyDescription = SemanticsMatcher("has content description") { node ->
            val list = node.config.getOrNull(SemanticsProperties.ContentDescription)
            list != null && list.isNotEmpty()
        }
        val described = compose.onAllNodes(hasAnyDescription).fetchSemanticsNodes()
        assertTrue(
            "isBusy=$busy 时发送键应渲染出带描述的状态图标，实际命中 ${described.size} 个节点",
            described.isNotEmpty()
        )
    }

    /** 上下排的按钮不能叠在一起（多排布局的经典事故）。 */
    @Test
    fun `上下排按钮不重叠`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    UploadIconButton(
                        enabled = true,
                        icon = FeatherIcons.Tool,
                        contentDescription = "top",
                        onClick = {}
                    )
                    UploadIconButton(
                        enabled = true,
                        icon = FeatherIcons.Plus,
                        contentDescription = "bottom",
                        onClick = {}
                    )
                }
            }
        }
        assertNoOverlap(
            compose.onNodeWithContentDescription("top"),
            compose.onNodeWithContentDescription("bottom")
        )
    }
}
