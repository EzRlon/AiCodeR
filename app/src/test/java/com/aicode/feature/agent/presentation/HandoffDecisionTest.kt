package com.aicode.feature.agent.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 交接阈值判定纯函数：50% 软提醒每会话最多两次；75% 自动交接优先且不受「暂不」次数影响；
 * 提醒开关与自动交接开关相互独立。
 */
class HandoffDecisionTest {

    private fun decide(
        percent: Int,
        promptPercent: Int = 50,
        reminderEnabled: Boolean = true,
        autoEnabled: Boolean = true,
        autoPercent: Int = 75,
        dismissed: Int = 0
    ) = decideHandoffAction(percent, promptPercent, reminderEnabled, autoEnabled, autoPercent, dismissed)

    @Test
    fun belowPromptThreshold_noAction() {
        assertEquals(HandoffAction.NONE, decide(49))
    }

    @Test
    fun atPromptThreshold_prompts() {
        assertEquals(HandoffAction.PROMPT, decide(50))
    }

    @Test
    fun dismissedOnce_promptsAgain() {
        assertEquals(HandoffAction.PROMPT, decide(60, dismissed = 1))
    }

    @Test
    fun dismissedTwice_noMoreReminders() {
        assertEquals(HandoffAction.NONE, decide(60, dismissed = 2))
    }

    @Test
    fun atAutoThreshold_autoHandoff() {
        assertEquals(HandoffAction.AUTO_HANDOFF, decide(75))
    }

    @Test
    fun autoHandoffIgnoresDismissCount() {
        assertEquals(HandoffAction.AUTO_HANDOFF, decide(75, dismissed = 2))
    }

    @Test
    fun autoDisabled_fallsBackToPrompt() {
        assertEquals(HandoffAction.PROMPT, decide(80, autoEnabled = false))
    }

    @Test
    fun reminderDisabled_noPrompt() {
        assertEquals(HandoffAction.NONE, decide(60, reminderEnabled = false))
    }

    @Test
    fun reminderDisabled_stillAutoHandsOff() {
        assertEquals(HandoffAction.AUTO_HANDOFF, decide(80, reminderEnabled = false))
    }

    @Test
    fun bothDisabled_noAction() {
        assertEquals(HandoffAction.NONE, decide(90, reminderEnabled = false, autoEnabled = false))
    }

    @Test
    fun autoThresholdBelowPromptThreshold_autoWins() {
        assertEquals(HandoffAction.AUTO_HANDOFF, decide(45, promptPercent = 50, autoPercent = 40))
    }
}
