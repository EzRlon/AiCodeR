package com.aicode.testutil

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/**
 * 布局坐标断言工具（ComposeTestRule 扩展形态）。
 *
 * 与同包 [ComposeLayoutAssertions.kt] 的关系：那份是顶层函数 + px 容差的早期版本；
 * 这份统一为 `ComposeTestRule` 扩展——Dp 容差在函数内经 `density` 换算，调用方不必传 rule，
 * 直接 `composeRule.assertSameRow(listOf(nodeA, nodeB))` 即可。两者签名错开（receiver 不同），
 * Kotlin 重载共存，调用时按 receiver/参数自然分派，不会歧义。
 *
 * 覆盖的三类真实事故（与旧工具同源）：
 * - [assertSameRow] —— 底栏按钮换行成两排
 * - [assertNoOverlap] —— 浮层两段文字重叠
 * - [assertAllWithinBounds] —— 控件被挤出容器可视区
 *
 * 坐标一律取语义树布局坐标（`SemanticsNode.positionInRoot` / `size`），不碰像素，
 * Robolectric 与真机字体渲染差异不影响断言。
 */

/** 节点在根坐标系下的外接矩形（`positionInRoot` + `size`）。 */
private fun SemanticsNodeInteraction.boundingBoxInRoot(): Rect {
    val node = fetchSemanticsNode()
    val pos = node.positionInRoot
    return Rect(pos.x, pos.y, pos.x + node.size.width, pos.y + node.size.height)
}

/** 节点的短描述：优先 testTag，其次文本内容，兜底坐标。失败信息用它指认「是谁」。 */
private fun SemanticsNodeInteraction.nodeLabel(): String {
    val node = fetchSemanticsNode()
    node.config.getOrNull(SemanticsProperties.TestTag)?.let { return "testTag=$it" }
    val text = node.config.getOrNull(SemanticsProperties.Text)
    if (!text.isNullOrEmpty()) return "text=\"${text.joinToString(separator = "|") { it.text }}\""
    val pos = node.positionInRoot
    return "无标签节点@(x=${pos.x}, y=${pos.y})"
}

/**
 * 断言所有节点的垂直中心 y 相差不超过 [toleranceDp]，即「在同一行」。
 *
 * 用垂直中心（`positionInRoot().y + size.height / 2`）而非 top 比较，兼容一行内
 * 不同高度的控件混排（如 48dp IconButton 与 20dp 图标）。验证底栏按钮、工具条
 * 图标这类本该水平排齐的元素有没有因为挤压换行成两排时用。
 *
 * 失败信息列出每个节点的标识与中心 y 值，并在掉行的节点后标 `← 偏离`，
 * 一眼看出谁掉了行。例如：
 * ```
 * 各节点垂直中心 y：
 *   [0] testTag=btn_home → y=120.0
 *   [1] testTag=btn_more → y=132.5 ← 偏离（超出容差 4.0dp≈10.7px）
 * ```
 *
 * 节点由调用方传入 `onNodeWithTag(...)` 等查询结果；少于 2 个节点时直接失败。
 */
fun ComposeTestRule.assertSameRow(
    interactions: List<SemanticsNodeInteraction>,
    toleranceDp: Float = 4f,
) {
    assertTrue(
        "assertSameRow 至少需要 2 个节点，实际 ${interactions.size} 个：" +
            interactions.joinToString(prefix = "[", postfix = "]") { it.nodeLabel() },
        interactions.size >= 2,
    )
    val tolerancePx = with(density) { toleranceDp.dp.toPx() }
    val centers = interactions.map { interaction ->
        val node = interaction.fetchSemanticsNode()
        node.positionInRoot.y + node.size.height / 2f
    }
    val first = centers.first()
    val details = interactions.indices.joinToString(separator = "\n") { index ->
        val deviation = centers[index] - first
        val marker = if (kotlin.math.abs(deviation) > tolerancePx) "  ← 偏离" else ""
        "  [$index] ${interactions[index].nodeLabel()} → y=${centers[index]}$marker"
    }
    assertTrue(
        "节点未排在同一行：最大中心差 ${centers.max() - centers.min()}px 超过容差 " +
            "$toleranceDp dp≈$tolerancePx px——元素可能换行成两排。\n各节点垂直中心 y：\n$details",
        centers.all { kotlin.math.abs(it - first) <= tolerancePx },
    )
}

/**
 * 断言所有节点两两的边界矩形不相交（重叠面积为 0）。
 *
 * 相交判断用严格不等式 `left < other.right && right > other.left && top < other.bottom &&
 * bottom > other.top`：两个矩形恰好边对边贴合（公共边）时不满足严格小于，天然不算重叠，
 * 不会把「相邻」误报成「相叠」。验证浮层两段文字、卡片内控件互相叠压这类事故时用。
 *
 * 失败信息给出相交的那对节点（标识 + 各自矩形）以及相交区域，例如：
 * ```
 * 节点 [0] testTag=line_a 与 [1] testTag=line_b 边界重叠：
 *   相交区域 Rect(10.0, 20.0, 90.0, 40.0)，宽 80.0 x 高 20.0
 *   [0] Rect(10.0, 20.0, 90.0, 50.0)（testTag=line_a）
 *   [1] Rect(0.0, 20.0, 100.0, 40.0)（testTag=line_b）
 * ```
 *
 * 少于 2 个节点时无对可比，直接失败提示（避免静默通过的假绿）。
 */
fun ComposeTestRule.assertNoOverlap(interactions: List<SemanticsNodeInteraction>) {
    assertTrue(
        "assertNoOverlap 至少需要 2 个节点（两两比较），实际 ${interactions.size} 个",
        interactions.size >= 2,
    )
    val rects = interactions.map { it.boundingBoxInRoot() }
    val labels = interactions.map { it.nodeLabel() }
    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val a = rects[i]
            val b = rects[j]
            val overlaps = a.left < b.right && a.right > b.left &&
                a.top < b.bottom && a.bottom > b.top
            if (overlaps) {
                val ixLeft = maxOf(a.left, b.left)
                val ixTop = maxOf(a.top, b.top)
                val ixRight = minOf(a.right, b.right)
                val ixBottom = minOf(a.bottom, b.bottom)
                val intersection = Rect(ixLeft, ixTop, ixRight, ixBottom)
                assertTrue(
                    "节点 [$i] 与 [$j] 边界重叠（浮层两段文字叠同一坐标的经典事故）：\n" +
                        "  相交区域 $intersection，宽 ${intersection.width} x 高 ${intersection.height}\n" +
                        "  [$i] $a（${labels[i]}）\n" +
                        "  [$j] $b（${labels[j]}）",
                    false,
                )
            }
        }
    }
}

/**
 * 断言所有节点完整落在 [container] 的边界内：水平方向全在内，垂直方向同样断言。
 *
 * 验证窄屏上按钮被挤出容器可视区、内容被裁剪一半这类事故时用。越界允许 0.5px
 * 浮点容差，避免节点与容器恰好对齐时因亚像素取整误报「越界」。
 *
 * 垂直方向默认与水平一起断言，没有放宽：本项目已知的越界事故（按钮挤出屏幕、
 * 表单行被裁）两个方向都出现过，而「内容故意溢出容器再被裁剪」的场景目前都是
 * 显式 clip 的特效而非业务布局；真出现此类合法溢出时，把该处的 verticalOk 判断
 * 删掉并注释原因，不要全局放宽。
 *
 * 失败信息分别按水平/垂直越界给出节点矩形与容器矩形，例如：
 * ```
 * 节点 [2] testTag=btn_send 水平越界：节点右边缘 812.0 超出容器右边缘 800.0
 *   节点 Rect(760.0, 640.0, 812.0, 688.0)
 *   容器 Rect(0.0, 600.0, 800.0, 690.0)
 * ```
 */
fun ComposeTestRule.assertAllWithinBounds(
    container: SemanticsNodeInteraction,
    interactions: List<SemanticsNodeInteraction>,
) {
    assertTrue(
        "assertAllWithinBounds 至少需要 1 个待检节点，实际 ${interactions.size} 个",
        interactions.isNotEmpty(),
    )
    val containerRect = container.boundingBoxInRoot()
    // 亚像素容差：语义树坐标为浮点，容器与节点恰好对齐时可能差零点几 px
    val epsilon = 0.5f
    interactions.forEachIndexed { index, interaction ->
        val rect = interaction.boundingBoxInRoot()
        val label = interaction.nodeLabel()
        val horizontalOk = rect.left >= containerRect.left - epsilon &&
            rect.right <= containerRect.right + epsilon
        assertTrue(
            "节点 [$index] $label 水平越界：容器 $containerRect，节点 $rect " +
                "（左缘 ${rect.left} vs 容器 ${containerRect.left}，右缘 ${rect.right} vs 容器 ${containerRect.right}）",
            horizontalOk,
        )
        val verticalOk = rect.top >= containerRect.top - epsilon &&
            rect.bottom <= containerRect.bottom + epsilon
        assertTrue(
            "节点 [$index] $label 垂直越界：容器 $containerRect，节点 $rect " +
                "（上缘 ${rect.top} vs 容器 ${containerRect.top}，下缘 ${rect.bottom} vs 容器 ${containerRect.bottom}）",
            verticalOk,
        )
    }
}
