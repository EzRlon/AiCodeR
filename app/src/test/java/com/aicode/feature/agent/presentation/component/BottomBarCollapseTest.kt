package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BottomBarCollapseState] 的迁移规则：谁能让底栏展开、谁的展开会被用户的手动收起挡住。
 *
 * 这套规则的易错点集中在「粘性」——用户手动收起后，通知类事件不该把它弹回来，但阻塞类事件必须。
 * 单测直接钉住这张优先级表，改规则时不会悄悄破坏其中一条。
 */
class BottomBarCollapseTest {

    private val expanded = BottomBarCollapseState()
    private val manuallyCollapsed = BottomBarCollapseState(collapsed = true, manualCollapsedThisTurn = true)

    @Test
    fun `用户收起后进入折叠且记下手动标记`() {
        val next = expanded.reduce(BottomBarCollapseEvent.USER_COLLAPSED)

        assertTrue(next.collapsed)
        assertTrue(next.manualCollapsedThisTurn)
    }

    @Test
    fun `用户展开会清掉本轮手动标记`() {
        val next = manuallyCollapsed.reduce(BottomBarCollapseEvent.USER_EXPANDED)

        assertFalse(next.collapsed)
        assertFalse(next.manualCollapsedThisTurn)
    }

    @Test
    fun `阻塞事件无视手动收起并清掉粘性`() {
        val next = manuallyCollapsed.reduce(BottomBarCollapseEvent.BLOCKING)

        assertFalse("授权/提问必须先展开，否则用户无法继续", next.collapsed)
        assertFalse(next.manualCollapsedThisTurn)
    }

    @Test
    fun `需介入事件同样无视手动收起`() {
        val next = manuallyCollapsed.reduce(BottomBarCollapseEvent.NEED_INTERVENTION)

        assertFalse(next.collapsed)
        assertFalse(next.manualCollapsedThisTurn)
    }

    @Test
    fun `完成事件尊重本轮手动收起`() {
        val next = manuallyCollapsed.reduce(BottomBarCollapseEvent.COMPLETED)

        assertTrue("用户说过不看，就不该被完成提示反复弹回来", next.collapsed)
        assertTrue(next.manualCollapsedThisTurn)
    }

    @Test
    fun `交付事件同样尊重本轮手动收起`() {
        val next = manuallyCollapsed.reduce(BottomBarCollapseEvent.DELIVERY)

        assertTrue(next.collapsed)
    }

    @Test
    fun `未手动收起时完成事件会展开`() {
        val next = expanded.reduce(BottomBarCollapseEvent.COMPLETED)

        assertFalse(next.collapsed)
        assertFalse(next.manualCollapsedThisTurn)
    }

    @Test
    fun `用户发新消息会展开并清除粘性`() {
        val next = manuallyCollapsed.reduce(BottomBarCollapseEvent.USER_SENT)

        assertFalse(next.collapsed)
        assertFalse(next.manualCollapsedThisTurn)
    }

    @Test
    fun `手动收起后再次手动收起保持幂等`() {
        val twice = manuallyCollapsed.reduce(BottomBarCollapseEvent.USER_COLLAPSED)

        assertEquals(manuallyCollapsed, twice)
    }

    @Test
    fun `Saver 能还原折叠态`() {
        val restored = BottomBarCollapseState.Saver.restore(listOf(true, true))

        assertEquals(BottomBarCollapseState(collapsed = true, manualCollapsedThisTurn = true), restored)
    }

    @Test
    fun `Saver 还原展开态`() {
        val restored = BottomBarCollapseState.Saver.restore(listOf(false, false))

        assertEquals(BottomBarCollapseState(), restored)
    }

    @Test
    fun `只有忙且折叠时才渲染任务轨道`() {
        assertTrue(manuallyCollapsed.showsRail(isBusy = true))
        assertFalse("空闲时没有任务可看，必须给回输入框", manuallyCollapsed.showsRail(isBusy = false))
        assertFalse(expanded.showsRail(isBusy = true))
    }
}
