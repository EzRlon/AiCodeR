package com.aicode.feature.agent.domain.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 从 400 文案里学习档位白名单：两种真实错误格式各有用例，钳制逻辑单独覆盖。 */
class ReasoningLearningTest {

    @After
    fun tearDown() = ReasoningLearning.clear()

    @Test
    fun parse_validLevelsFormat() {
        val message = """{"error":{"message":"level \"minimal\" not supported, valid levels: low, medium, high, xhigh, max"}}"""
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            ReasoningLearning.parseSupportedLevels(message)
        )
    }

    @Test
    fun parse_supportedValuesFormat() {
        val message = "Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. " +
            "Supported values are: 'low', 'medium', 'high', 'xhigh', 'max'"
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            ReasoningLearning.parseSupportedLevels(message)
        )
    }

    @Test
    fun parse_geminiStyleFormat() {
        assertEquals(
            listOf("minimal", "high"),
            ReasoningLearning.parseSupportedLevels("""level "low" not supported, valid levels: minimal, high""")
        )
    }

    @Test
    fun parse_unrelatedMessage_returnsNull() {
        assertNull(ReasoningLearning.parseSupportedLevels("401 invalid api key"))
        assertNull(ReasoningLearning.parseSupportedLevels(""))
        assertNull(ReasoningLearning.parseSupportedLevels(null))
    }

    @Test
    fun observe_storesWhitelistAndIsScopedPerProvider() {
        ReasoningLearning.observe("p1", "gpt-5.6-sol", IllegalStateException("valid levels: low, high"))
        assertEquals(setOf("low", "high"), ReasoningLearning.recall("p1", "gpt-5.6-sol"))
        // 换渠道（providerId 不同）不能串台：同一模型在不同中转站白名单可能不同。
        assertNull(ReasoningLearning.recall("p2", "gpt-5.6-sol"))
        assertNull(ReasoningLearning.recall("p1", "gpt-6-sol"))
    }

    @Test
    fun observe_unparsableError_learnsNothing() {
        ReasoningLearning.observe("p1", "gpt-5.6-sol", IllegalStateException("429 rate limited"))
        assertNull(ReasoningLearning.recall("p1", "gpt-5.6-sol"))
    }

    @Test
    fun observe_ignoresBlankKeys() {
        ReasoningLearning.observe("", "gpt-5.6-sol", IllegalStateException("valid levels: low"))
        ReasoningLearning.observe("p1", "", IllegalStateException("valid levels: low"))
        assertNull(ReasoningLearning.recall("", "gpt-5.6-sol"))
        assertNull(ReasoningLearning.recall("p1", ""))
    }

    @Test
    fun observe_rejectsNonLevelWhitelist() {
        // tool_choice 之类错误的白名单过滤后只剩 none，不是档位白名单，不得入库。
        ReasoningLearning.observe(
            "p1", "gpt-5.6-sol",
            IllegalStateException("Invalid value for tool_choice. Supported values are: 'auto', 'none', 'required'")
        )
        assertNull(ReasoningLearning.recall("p1", "gpt-5.6-sol"))
    }

    @Test
    fun clear_removesLearnedEntries() {
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low"))
        ReasoningLearning.observe("p2", "m", IllegalStateException("valid levels: high"))
        ReasoningLearning.clear(providerId = "p1")
        assertNull(ReasoningLearning.recall("p1", "m"))
        assertEquals(setOf("high"), ReasoningLearning.recall("p2", "m"))
        ReasoningLearning.clear()
        assertNull(ReasoningLearning.recall("p2", "m"))
    }

    @Test
    fun clamp_downToNearestLearnedLevel() {
        assertEquals("medium", ReasoningLearning.clamp("high", setOf("low", "medium")))
        assertEquals("low", ReasoningLearning.clamp("minimal", setOf("low", "medium")))
    }

    @Test
    fun clamp_upToLowestWhenNothingLower() {
        assertEquals("low", ReasoningLearning.clamp("none", setOf("low", "medium")))
    }

    @Test
    fun clamp_unknownEffortOrEmptyLearned_passthrough() {
        assertEquals("medium", ReasoningLearning.clamp("medium", setOf("low", "medium")))
        assertEquals("weird", ReasoningLearning.clamp("weird", setOf("low")))
        assertEquals("high", ReasoningLearning.clamp("high", emptySet()))
    }
}
