package com.aicode.feature.agent.domain.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 从 400 文案里学习「哪些档位被拒」（黑名单语义）：两种真实错误格式各有用例，钳制/自愈/过期单独覆盖。 */
class ReasoningLearningTest {

    @After
    fun tearDown() {
        ReasoningLearning.init(null)
        ReasoningLearning.clear()
    }

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
    fun observe_deniesLevelsOutsideWhitelistAndIsScopedPerProvider() {
        // 白名单 low/high ⇒ 被拒的是除此之外的全部档位（黑名单：不发明，只排除）。
        ReasoningLearning.observe("p1", "gpt-5.6-sol", IllegalStateException("valid levels: low, high"))
        val denied = ReasoningLearning.rejectedLevels("p1", "gpt-5.6-sol")
        assertEquals(setOf("none", "minimal", "medium", "xhigh", "max"), denied)
        // 换渠道（providerId 不同）不能串台：同一模型在不同中转站档位支持可能不同。
        assertTrue(ReasoningLearning.rejectedLevels("p2", "gpt-5.6-sol").isEmpty())
        assertTrue(ReasoningLearning.rejectedLevels("p1", "gpt-6-sol").isEmpty())
    }

    @Test
    fun observe_unparsableError_learnsNothing() {
        ReasoningLearning.observe("p1", "gpt-5.6-sol", IllegalStateException("429 rate limited"))
        assertTrue(ReasoningLearning.rejectedLevels("p1", "gpt-5.6-sol").isEmpty())
    }

    @Test
    fun observe_ignoresBlankKeys() {
        ReasoningLearning.observe("", "gpt-5.6-sol", IllegalStateException("valid levels: low"))
        ReasoningLearning.observe("p1", "", IllegalStateException("valid levels: low"))
        assertTrue(ReasoningLearning.rejectedLevels("", "gpt-5.6-sol").isEmpty())
        assertTrue(ReasoningLearning.rejectedLevels("p1", "").isEmpty())
    }

    @Test
    fun observe_rejectsNonLevelWhitelist() {
        // tool_choice 之类错误的白名单过滤后只剩 none，不是档位白名单，不得入库。
        ReasoningLearning.observe(
            "p1", "gpt-5.6-sol",
            IllegalStateException("Invalid value for tool_choice. Supported values are: 'auto', 'none', 'required'")
        )
        assertTrue(ReasoningLearning.rejectedLevels("p1", "gpt-5.6-sol").isEmpty())
    }

    @Test
    fun clear_removesLearnedEntries() {
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low"))
        ReasoningLearning.observe("p2", "m", IllegalStateException("valid levels: high"))
        ReasoningLearning.clear(providerId = "p1")
        assertTrue(ReasoningLearning.rejectedLevels("p1", "m").isEmpty())
        assertEquals(setOf("none", "minimal", "low", "medium", "xhigh", "max"), ReasoningLearning.rejectedLevels("p2", "m"))
        ReasoningLearning.clear()
        assertTrue(ReasoningLearning.rejectedLevels("p2", "m").isEmpty())
    }

    @Test
    fun tighten_downgradesDeniedLevel() {
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low, high"))
        // high 可用、max 被拒 ⇒ max 降到最近可用档 high。
        assertEquals("high", ReasoningLearning.tighten("max", "p1", "m"))
        assertEquals("high", ReasoningLearning.tighten("high", "p1", "m"))
    }

    @Test
    fun tighten_passthroughWhenNothingLearnedOrEffortAllowed() {
        assertTrue(ReasoningLearning.rejectedLevels("p1", "m").isEmpty())
        assertEquals("medium", ReasoningLearning.tighten("medium", "p1", "m"))
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low, high"))
        assertEquals("low", ReasoningLearning.tighten("low", "p1", "m"))
    }

    @Test
    fun markUsed_selfHealsDeniedLevel() {
        // 自愈：某档位调用成功即推翻历史「被拒」记录，不依赖 TTL 过期。
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low, high"))
        assertTrue("max" in ReasoningLearning.rejectedLevels("p1", "m"))
        ReasoningLearning.markUsed("p1", "m", "max")
        assertTrue("max" !in ReasoningLearning.rejectedLevels("p1", "m"))
        assertEquals("max", ReasoningLearning.tighten("max", "p1", "m"))
    }

    @Test
    fun rejectedLevels_expiresAfterTtl() {
        val t0 = 1_000_000_000_000L
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low"), now = t0)
        assertTrue(ReasoningLearning.rejectedLevels("p1", "m", now = t0).isNotEmpty())
        // 超过 TTL：陈旧证据自动失效，不再收窄档位。
        assertTrue(ReasoningLearning.rejectedLevels("p1", "m", now = t0 + ReasoningLearning.TTL_MILLIS).isEmpty())
        assertEquals("high", ReasoningLearning.tighten("high", "p1", "m", now = t0 + ReasoningLearning.TTL_MILLIS))
    }

    @Test
    fun init_loadsPersistedEntriesAndWritesThrough() {
        val fake = FakeStore()
        ReasoningLearning.init(fake)
        ReasoningLearning.observe("p1", "m", IllegalStateException("valid levels: low, high"))
        // 学习结果已落盘。
        assertTrue(fake.snapshot.containsKey("p1:m"))

        // 模拟重启：清内存后从同一后端读回。
        ReasoningLearning.init(null)
        ReasoningLearning.clear()
        ReasoningLearning.init(fake)
        assertEquals(setOf("none", "minimal", "medium", "xhigh", "max"), ReasoningLearning.rejectedLevels("p1", "m"))
    }

    @Test
    fun clamp_downToNearestAllowedLevel() {
        assertEquals("medium", ReasoningLearning.clamp("high", setOf("low", "medium")))
        assertEquals("low", ReasoningLearning.clamp("minimal", setOf("low", "medium")))
    }

    @Test
    fun clamp_upToLowestWhenNothingLower() {
        assertEquals("low", ReasoningLearning.clamp("none", setOf("low", "medium")))
    }

    @Test
    fun clamp_unknownEffortOrEmptyAllowed_passthrough() {
        assertEquals("medium", ReasoningLearning.clamp("medium", setOf("low", "medium")))
        assertEquals("weird", ReasoningLearning.clamp("weird", setOf("low")))
        assertEquals("high", ReasoningLearning.clamp("high", emptySet()))
    }

    /** 内存版落盘后端，用于验证 read/write 契约。 */
    private class FakeStore : ReasoningLearningStore {
        var snapshot: Map<String, Map<String, Long>> = emptyMap()
        override fun read(): Map<String, Map<String, Long>> = snapshot
        override fun write(snapshot: Map<String, Map<String, Long>>) {
            this.snapshot = snapshot
        }
    }
}
