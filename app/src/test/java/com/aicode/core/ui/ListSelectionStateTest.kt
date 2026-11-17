package com.aicode.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选状态机：批量操作直接作用在真实数据上（删文件 / 改配置），
 * 所以「退出清空」「失效项剔除」「全选替换」这几条语义必须准确。
 */
class ListSelectionStateTest {

    @Test
    fun initiallyInactiveAndEmpty() {
        val state = ListSelectionState()

        assertFalse(state.isActive)
        assertEquals(0, state.selectedCount)
        assertTrue(state.selectedKeys.isEmpty())
    }

    @Test
    fun toggleAddsThenRemoves() {
        val state = ListSelectionState()

        state.toggle("a")
        state.toggle("b")
        assertEquals(listOf("a", "b"), state.selectedKeys)

        state.toggle("a")
        assertEquals(listOf("b"), state.selectedKeys)
    }

    /** 退出多选必须清空选中集合，否则下次进入会带着上一轮的勾选。 */
    @Test
    fun exitClearsSelection() {
        val state = ListSelectionState()
        state.enter()
        state.toggle("a")

        state.exit()

        assertFalse(state.isActive)
        assertEquals(0, state.selectedCount)
    }

    /** 数据刷新后剔除已消失的 key，避免批量操作打到已删除条目。 */
    @Test
    fun retainOnlyDropsVanishedKeys() {
        val state = ListSelectionState()
        state.replaceAll(listOf("a", "b", "c"))

        state.retainOnly(setOf("b", "c", "d"))

        assertEquals(listOf("b", "c"), state.selectedKeys)
    }

    /** 全选/取消全选是整体替换：重复调用不累积、不重复。 */
    @Test
    fun replaceAllIsIdempotent() {
        val state = ListSelectionState()

        state.replaceAll(listOf("a", "b"))
        state.replaceAll(listOf("a", "b"))

        assertEquals(listOf("a", "b"), state.selectedKeys)

        state.replaceAll(emptyList())
        assertEquals(0, state.selectedCount)
    }

    /** selectedKeys 是快照：批量回调遍历时不受后续 toggle 影响。 */
    @Test
    fun selectedKeysIsSnapshot() {
        val state = ListSelectionState()
        state.toggle("a")

        val snapshot = state.selectedKeys
        state.toggle("b")

        assertEquals(listOf("a"), snapshot)
        assertEquals(listOf("a", "b"), state.selectedKeys)
    }
}
