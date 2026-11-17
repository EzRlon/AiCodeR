package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * **整条底栏**的布局回归——针对用户反馈的「底栏按钮分成两排」。
 *
 * 与 ChatInputBarLayoutTest 的区别：那边只渲染底栏里的个别按钮组件，这边渲染完整的
 * ChatInputBar（控制行 + 输入区 + 底部按钮行），能抓「整行放不下导致换行」这类问题。
 *
 * 不需要 Hilt：WorkspaceViewModel 是唯一可空依赖，传 null 即可；其余 21 个参数都是
 * 普通值/回调。这也是这个组件可测性好的证明——换成依赖一屏 ViewModel 的页面就得另建
 * Hilt 测试基建了。
 *
 * 断言用「带无障碍描述的节点是否同排」而非文案：Robolectric 默认 locale 与 App 语言
 * 未必一致，按文字定位会假红。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class ChatInputBarFullLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** 渲染完整底栏。参数用最小合法值，只关心布局不关心行为。 */
    private fun renderInputBar(
        value: String = "",
        isBusy: Boolean = false,
        mode: AgentMode = AgentMode.BUILD
    ) {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ChatInputBar(
                        value = value,
                        onValueChange = {},
                        onSend = {},
                        onStop = {},
                        isBusy = isBusy,
                        workspaceViewModel = null,
                        hasRunningSessions = { false },
                        activeProvider = null,
                        providers = emptyList(),
                        modelMetadata = emptyMap(),
                        onSelectModel = { _, _ -> },
                        currentMode = mode,
                        onToggleMode = {},
                        reasoningEffort = ReasoningEffort.MEDIUM,
                        onReasoningEffortChange = {},
                        pendingAttachments = emptyList(),
                        onRemoveAttachment = {},
                        canUploadFiles = true,
                        canUploadImages = true,
                        onUploadFile = {},
                        onUploadImage = {},
                        onTakePhoto = {}
                    )
                }
            }
        }
    }

    /**
     * 底栏渲染完成后，所有图标按钮应落在**同一条水平带**上（不能分两排）。
     *
     * 判据：取全部带 contentDescription 的节点（都是图标按钮），按中心 y 分组；
     * 若出现两个相距超过一个按钮高度的组，说明布局换行了。
     */
    @Test
    fun `底部按钮不分两排`() {
        renderInputBar()
        val rows = describedNodesGroupedByRow()
        assertTrue(
            "底部按钮应排在同一行，实际分成 ${rows.size} 行；各行中心 y = " +
                "${rows.map { it.first }}，每行节点数 = ${rows.map { it.second }}",
            rows.size <= 2 // 控制行 + 底行本就两排，故 2 是上限
        )
    }

    /** 忙态下不应改变布局结构（停止键同样是底行的一个图标）。 */
    @Test
    fun `忙态底部按钮同样不分两排`() {
        renderInputBar(isBusy = true)
        val rows = describedNodesGroupedByRow()
        assertTrue(
            "忙态下按钮行数异常：${rows.map { it.first }}",
            rows.size <= 2
        )
    }

    /**
     * 三种模式下**底部按钮行**都是一行，不被模式说明条挤成两排。
     *
     * 说明条本身是设计的一部分：非 BUILD 模式默认展开（ChatInputBar:630 的
     * rememberSaveable 初值为 currentMode != BUILD），所以整条底栏有 2~3 个视觉带
     * 属正常。要验的是「同一组按钮彼此同排」，故按 y 聚类后只看**最大簇的节点数**，
     * 而不是限制总行数。
     */
    @Test
    fun `构建模式按钮同排`() = assertButtonsShareOneRow(AgentMode.BUILD)

    @Test
    fun `计划模式按钮同排`() = assertButtonsShareOneRow(AgentMode.PLAN)

    @Test
    fun `自动模式按钮同排`() = assertButtonsShareOneRow(AgentMode.AUTO)

    private fun assertButtonsShareOneRow(mode: AgentMode) {
        renderInputBar(mode = mode)
        val rows = describedNodesGroupedByRow()
        val largest = rows.maxOfOrNull { it.second } ?: 0
        val total = rows.sumOf { it.second }
        assertTrue(
            "$mode 模式下 ${total} 个按钮分散在 ${rows.size} 个高度（各簇节点数 " +
                "${rows.map { it.second }}），最大簇只占 $largest 个——按钮没有聚在同一排",
            largest >= 3
        )
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private fun describedNodesGroupedByRow(): List<Pair<Float, Int>> {
        val matcher = SemanticsMatcher("has content description") { node ->
            !node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
        }
        val centers = compose.onAllNodes(matcher).fetchSemanticsNodes()
            .map { it.boundsInRoot.center.y }
            .sorted()
        if (centers.isEmpty()) return emptyList()
        // 同一行的节点中心 y 相差很小；按 8dp（=16px @2x）容差聚类
        val groups = mutableListOf<MutableList<Float>>()
        centers.forEach { y ->
            val last = groups.lastOrNull()
            if (last != null && y - last.last() <= 16f) last.add(y) else groups.add(mutableListOf(y))
        }
        return groups.map { g -> g.average().toFloat() to g.size }
    }
}
