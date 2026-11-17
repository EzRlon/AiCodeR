package com.aicode.core.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/** 命中的界面组件：屏幕像素边界 + 可读标签（文本/内容说明）。 */
data class ComponentHit(val bounds: Rect, val label: String)

/**
 * 在活动窗口的 Compose 语义树里按屏幕坐标命中「真实组件」。
 *
 * 语义树是 Compose 的组件模型：节点带边界与语义（文本、内容说明、测试标签），
 * 自根向下找到「最深且包含点击点」的节点，再向最近的带语义祖先回退——
 * 即用户视角的「点到了哪个组件」。仅覆盖主窗口；弹层窗口内组件的精确命中不在本版本范围。
 *
 * 注：承载语义树的 AndroidComposeView 在当前 Compose 版本为 internal 类，无法静态引用；
 * 此处按类名识别 + 一次 getter 调用取 [SemanticsOwner]（其余全部走公开语义 API）。
 */
object ComposeSemanticsHitTester {

    /** 承载 Compose 内容的 View 类名（Compose 内部类，按名识别避免静态引用）。 */
    private const val COMPOSE_VIEW_CLASS = "androidx.compose.ui.platform.AndroidComposeView"

    fun hitTest(activity: Activity, x: Float, y: Float): ComponentHit? {
        val decor = activity.window?.decorView ?: return null
        var best: ComponentHit? = null
        walkComposeViews(decor) { composeView, owner ->
            val loc = IntArray(2)
            composeView.getLocationOnScreen(loc)
            val node = deepestHit(owner.unmergedRootSemanticsNode, x - loc[0], y - loc[1])
                ?: return@walkComposeViews
            val labelled = nearestLabelled(node)
            val b = labelled.boundsInRoot
            val bounds = Rect(b.left + loc[0], b.top + loc[1], b.right + loc[0], b.bottom + loc[1])
            val current = best
            if (current == null || area(bounds) < area(current.bounds)) {
                best = ComponentHit(bounds, labelOf(labelled))
            }
        }
        return best
    }

    private fun area(rect: Rect): Float = (rect.right - rect.left) * (rect.bottom - rect.top)

    /** 遍历视图树，对「承载语义树的 Compose View」回调其语义根。 */
    private fun walkComposeViews(view: View, onFound: (View, SemanticsOwner) -> Unit) {
        val owner = semanticsOwnerOf(view)
        if (owner != null) onFound(view, owner)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkComposeViews(view.getChildAt(i), onFound)
            }
        }
    }

    /** 取该 View 的 Compose 语义根；非 Compose 承载 View 返回 null。 */
    private fun semanticsOwnerOf(view: View): SemanticsOwner? {
        if (view.javaClass.name != COMPOSE_VIEW_CLASS) return null
        return runCatching {
            view.javaClass.getMethod("getSemanticsOwner").invoke(view) as? SemanticsOwner
        }.getOrNull()
    }

    /** 从 [node] 向下找「最深、且边界包含点击点」的节点；不含点返回 null。 */
    private fun deepestHit(node: SemanticsNode, x: Float, y: Float): SemanticsNode? {
        val r = node.boundsInRoot
        if (x < r.left || x > r.right || y < r.top || y > r.bottom) return null
        var result = node
        node.children.forEach { child ->
            deepestHit(child, x, y)?.let { result = it }
        }
        return result
    }

    /** 从命中节点向祖先回退，取最近的带语义节点（如 Text 的祖先按钮）。 */
    private fun nearestLabelled(node: SemanticsNode): SemanticsNode {
        var current: SemanticsNode? = node
        while (current != null) {
            if (labelOf(current).isNotBlank()) return current
            current = current.parent
        }
        return node
    }

    private fun labelOf(node: SemanticsNode): String {
        val config = node.config
        val text = config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text.trim() }
            ?.trim()
            .orEmpty()
        if (text.isNotBlank()) return text
        val description = config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            ?.trim()
            .orEmpty()
        if (description.isNotBlank()) return description
        return config.getOrNull(SemanticsProperties.TestTag).orEmpty()
    }
}
