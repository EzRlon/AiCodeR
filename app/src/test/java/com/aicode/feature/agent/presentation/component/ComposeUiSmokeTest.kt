package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.core.theme.AIEditorTheme
import com.aicode.testutil.assertNoOverlap
import com.aicode.testutil.assertSameRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI 自动化冒烟：验证「Robolectric 能渲染本项目主题 + 布局断言工具可用」。
 *
 * 这组是基建验收——跑通即证明 UI 自动化这条路可行；后续关键页面用例都沿用这里的骨架。
 * 断言打结构与坐标，不打像素（Robolectric 字体渲染与真机不同）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class ComposeUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    // ── 1. 基建：主题 + 文本可渲染、可定位 ──────────────────────────

    @Test
    fun `主题渲染与节点定位`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Text("hello", modifier = Modifier.testTag("t1"))
            }
        }
        compose.onNodeWithTag("t1").fetchSemanticsNode() // 存在性：fetch 失败即抛异常
    }

    // ── 2. 断言工具：同一行 ────────────────────────────────────────

    @Test
    fun `同一行的两个节点断言通过`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("a", modifier = Modifier.testTag("a"))
                    Text("b", modifier = Modifier.testTag("b"))
                }
            }
        }
        assertSameRow(
            listOf(compose.onNodeWithTag("a"), compose.onNodeWithTag("b"))
        )
    }

    @Test
    fun `换行的两个节点断言失败`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("a", modifier = Modifier.testTag("a"))
                    Text("b", modifier = Modifier.testTag("b"))
                }
            }
        }
        try {
            assertSameRow(
                listOf(compose.onNodeWithTag("a"), compose.onNodeWithTag("b"))
            )
            org.junit.Assert.fail("Column 里的两个节点不应该被判为同一行")
        } catch (e: IllegalStateException) {
            // 预期：换行/异行必须被抓出来
            org.junit.Assert.assertTrue(e.message!!.contains("偏离行中心"))
        }
    }

    // ── 3. 断言工具：不重叠 ────────────────────────────────────────

    @Test
    fun `不重叠断言通过`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("第一行", modifier = Modifier.testTag("row1"))
                    Text("第二行", modifier = Modifier.testTag("row2"))
                }
            }
        }
        assertNoOverlap(compose.onNodeWithTag("row1"), compose.onNodeWithTag("row2"))
    }

    @Test
    fun `同坐标叠放断言失败`() {
        // 复刻上一版语音浮层事故：Surface 内容槽是 Box 语义，两个子项叠在同一坐标
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text("状态文字", modifier = Modifier.testTag("s1"))
                    Text("手势提示", modifier = Modifier.testTag("s2"))
                }
            }
        }
        try {
            assertNoOverlap(compose.onNodeWithTag("s1"), compose.onNodeWithTag("s2"))
            org.junit.Assert.fail("Box 里叠放的两个节点不应该通过不重叠断言")
        } catch (e: IllegalStateException) {
            org.junit.Assert.assertTrue(e.message!!.contains("重叠"))
        }
    }

    // ── 4. 真实组件冒烟：AppSwitch 可在主题内渲染并可定位 ──────────

    @Test
    fun `AppSwitch 可渲染可定位`() {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                com.aicode.core.ui.AppSwitch(
                    checked = true,
                    onCheckedChange = {},
                    modifier = Modifier.width(42.dp).height(24.dp).testTag("sw")
                )
            }
        }
        compose.onNodeWithTag("sw").fetchSemanticsNode() // 存在性：fetch 失败即抛异常
        // 存在即证明主题链（MaterialTheme + semanticColors）在 Robolectric 下初始化成功
        compose.onRoot()
    }
}
