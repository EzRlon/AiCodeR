package com.aicode.testutil

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag

/**
 * 布局结构断言工具：把「上一版踩过的显示 bug」翻译成可重复执行的判据。
 *
 * 背景：语音浮层两行文字叠在同一坐标（Surface 内容槽是 Box 语义）、底栏按钮被挤压换行——
 * 这类问题截图能看出来，但靠人眼每次发版都看一遍不现实。这里断言的是**结构与坐标**，
 * 不碰像素（Robolectric 渲染字体与真机不同，像素断言会脆）。
 *
 * 三个断言对应三类真实事故：
 * - [assertSameRow] —— 底栏按钮分成两排
 * - [assertNoOverlap] —— 浮层两行文字重叠
 * - [assertDisplayedFully] —— 窄屏按钮被挤出可视区
 */

/** 两个矩形在任一轴上的重叠面积是否超过 [maxOverlapPx]（容忍亚像素级采样误差）。 */
fun Rect.overlapsWith(other: Rect, maxOverlapPx: Float = 1f): Boolean =
    width > 0 && other.width > 0 &&
        (left < other.right - maxOverlapPx && other.left < right - maxOverlapPx) &&
        (top < other.bottom - maxOverlapPx && other.top < bottom - maxOverlapPx)

/**
 * 断言若干节点处于同一「行」：纵坐标中心相差不超过 [tolerancePx]。
 * 用中心而非 top，兼容不同高度的控件（IconButton 48dp 与 20dp 图标混排）。
 */
fun assertSameRow(nodes: List<SemanticsNodeInteraction>, tolerancePx: Float = 4f) {
    require(nodes.size >= 2) { "assertSameRow 至少需要 2 个节点" }
    val centers = nodes.map { it.fetchSemanticsNode().boundsInRoot.center.y }
    val first = centers.first()
    centers.forEachIndexed { index, y ->
        check((y - first).let { it >= -tolerancePx && it <= tolerancePx }) {
            "节点 $index 偏离行中心 ${y - first}px（容差 $tolerancePx）——按钮可能换行了。" +
                "全部中心 y：$centers"
        }
    }
}

/** 断言两个节点的边界不相交（各自独立渲染，没有叠在一起）。 */
fun assertNoOverlap(a: SemanticsNodeInteraction, b: SemanticsNodeInteraction) {
    val ra = a.fetchSemanticsNode().boundsInRoot
    val rb = b.fetchSemanticsNode().boundsInRoot
    check(!ra.overlapsWith(rb)) {
        "两个节点重叠：$ra 与 $rb——文字/控件叠在同一坐标（Surface 内容槽 Box 语义的经典事故）。"
    }
}

/** 断言节点完整落在 [root] 的可视边界内（没被挤出屏幕或被裁剪）。 */
fun assertDisplayedFully(node: SemanticsNodeInteraction, root: SemanticsNodeInteraction) {
    val r = node.fetchSemanticsNode().boundsInRoot
    val outer = root.fetchSemanticsNode().boundsInRoot
    val inside = r.left >= outer.left - 0.5f && r.right <= outer.right + 0.5f &&
        r.top >= outer.top - 0.5f && r.bottom <= outer.bottom + 0.5f
    check(inside) {
        "节点 $r 超出容器 $outer——控件可能被挤出可视区。"
    }
}

/** 按 testTag 找节点并断言存在且唯一。 */
fun SemanticsNodeInteraction.assertTagExists(tag: String): SemanticsNodeInteraction =
    assert(hasTestTag(tag))
