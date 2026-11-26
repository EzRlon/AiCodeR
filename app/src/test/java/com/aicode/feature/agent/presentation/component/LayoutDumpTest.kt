package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 导出底栏/浮层的**布局树**（每个节点的文字、坐标、尺寸），落成文本文件供人眼查看。
 *
 * 为什么不是截图：Robolectric 的图形原生运行时（ShadowNativeRenderNode）在
 * Linux aarch64 上没有实现，captureToImage 直接抛 UnsupportedOperationException。
 * 布局树虽然看不到配色，但元素位置、是否换行、是否重叠这些结构信息是齐全的，
 * 恰好是判断布局问题需要的。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = android.app.Application::class)
class LayoutDumpTest {

    @get:Rule
    val compose = createComposeRule()

    // 落模块构建目录（Gradle 测试工作目录即 app/）：本地与 CI（/home/runner）都能写。
    // 曾硬编码 /root/workspace/aicoder，CI runner 无 /root 权限，mkdirs 失败后 writeText 抛 FileNotFoundException。
    private val outDir = File("build/layout-dumps").apply { mkdirs() }

    @Test
    fun `导出底栏布局-构建模式`() = dump("input-bar-build") { renderBar(AgentMode.BUILD) }

    @Test
    fun `导出底栏布局-计划模式`() = dump("input-bar-plan") { renderBar(AgentMode.PLAN) }

    @Test
    fun `导出底栏布局-忙态`() =
        dump("input-bar-busy") { renderBar(AgentMode.BUILD, isBusy = true, value = "正在处理你的请求") }

    @Test
    fun `导出语音浮层-长按态`() = dump("voice-hold") {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldStage(
                    processing = false,
                    dragTarget = VoiceDragTarget.SEND,
                    partialText = ""
                )
            }
        }
    }

    @Test
    fun `导出语音浮层-识别中`() = dump("voice-processing") {
        compose.setContent {
            AIEditorTheme(darkTheme = false) {
                VoiceHoldStage(
                    processing = true,
                    dragTarget = VoiceDragTarget.SEND,
                    partialText = ""
                )
            }
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private fun dump(name: String, render: () -> Unit) {
        render()
        compose.waitForIdle()

        val sb = StringBuilder()
        sb.appendLine("=".repeat(70))
        sb.appendLine("布局: $name")
        sb.appendLine("=".repeat(70))
        sb.appendLine()

        val matcher = SemanticsMatcher("describe") { node ->
            val hasText = !node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
            val hasDesc = !node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()
            hasText || hasDesc
        }
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))

        sb.appendLine("带文字或图标描述的节点（按 y 从上到下排序）：")
        sb.appendLine()
        nodes.forEach { node ->
            val r = node.boundsInRoot
            val label = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("｜")
                ?: ""
            sb.appendLine(
                "  y=%6.1f..%-6.1f  x=%6.1f..%-6.1f  内容=%s".format(r.top, r.bottom, r.left, r.right, label)
            )
        }

        // 按 y 聚类，直接回答「分几排」
        val rows = mutableListOf<MutableList<Float>>()
        nodes.map { it.boundsInRoot.center.y }.sorted().forEach { y ->
            val last = rows.lastOrNull()
            if (last != null && y - last.last() <= 20f) last.add(y) else rows.add(mutableListOf(y))
        }
        sb.appendLine()
        sb.appendLine("按中心 y 聚类 → 共 ${rows.size} 排：")
        rows.forEachIndexed { i, row ->
            sb.appendLine("  第 ${i + 1} 排: ${row.size} 个元素, 中心 y=${row.average().toInt()}")
        }

        val file = File(outDir, "$name.txt")
        file.writeText(sb.toString())
        println("布局树已导出: ${file.absolutePath}")
    }

    private fun renderBar(
        mode: AgentMode,
        isBusy: Boolean = false,
        value: String = ""
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
}
