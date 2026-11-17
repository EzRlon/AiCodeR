package com.aicode.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「Robolectric + Compose UI 测试」基建验证：在 JVM（Robolectric 载体）上渲染真实 Compose 树。
 *
 * 依赖链：testImplementation(ui-test-junit4) 提供 createComposeRule 与语义匹配器，
 * debugImplementation(ui-test-manifest) 在测试 manifest 里注册空壳 ComponentActivity 供
 * ActivityScenario 拉起，Robolectric 提供框架实现。三者齐备即可不碰设备/模拟器跑 Compose 单测。
 *
 * 两条测试各自验证什么：
 * 1. `compose 渲染冒烟`：最小渲染链路是否通——createComposeRule 能否在 Robolectric 上完成
 *    setContent → 首帧渲染 → 语义树文本查询（onNodeWithText + assertExists）。
 *    这是基建的地基：连一个 Text 都渲染不出来，其余 Compose 单测无从谈起。
 * 2. `AppSwitch 渲染并可切换`：项目自定义组件（无 Hilt 依赖）可独立渲染，且「渲染 + 语义 + 交互」
 *    三层都通——AppSwitch 内部 Modifier.toggleable 写入的 ToggleableState 语义能从语义配置读到，
 *    performClick 能触发 onCheckedChange 回调改写外部状态。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeInfraTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `compose 渲染冒烟`() {
        composeRule.setContent {
            Column {
                Text("基建冒烟")
            }
        }
        composeRule.onNodeWithText("基建冒烟").assertExists()
    }

    @Test
    fun `AppSwitch 渲染并可切换`() {
        // 测试方法体内没有 remember 作用域，直接用顶层局部 var by mutableStateOf 承接回调
        var checked by mutableStateOf(false)

        composeRule.setContent {
            Column(modifier = Modifier.testTag("app_switch_host")) {
                AppSwitch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag("app_switch")
                )
            }
        }

        // 语义层：toggleable 写入的 ToggleableState 能从语义配置读到（不依赖 testTag 也能验证）
        val node = composeRule.onNodeWithTag("app_switch")
        val toggleState = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        assertNotNull("AppSwitch 应带 ToggleableState 语义（toggleable 修饰符）", toggleState)

        // 交互层：点击触发 onCheckedChange，等渲染空闲后读回外部状态
        node.performClick()
        composeRule.runOnIdle { }
        assertTrue("点击后 checked 应被置为 true", checked)
    }
}
