package com.aicode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageNormalizerTest {

    @Test
    fun `Anthropic 的 input 不含缓存，需要相加`() {
        val u = UsageNormalizer.anthropic(
            input = 1_322,
            cacheRead = 303_104,
            cacheCreation = 0,
            output = 1_440
        )
        assertEquals(304_426, u.inputTokens)
        assertEquals(303_104, u.cachedInputTokens)
        assertEquals(1_322, u.freshInputTokens)
        assertEquals(1_440, u.outputTokens)
    }

    @Test
    fun `Anthropic 同时有缓存写入时一并计入总量`() {
        val u = UsageNormalizer.anthropic(100, 900, 50, 10)
        assertEquals(1_050, u.inputTokens)
        assertEquals(900, u.cachedInputTokens)
        assertEquals(50, u.cacheCreationTokens)
        assertEquals(100, u.freshInputTokens)
    }

    @Test
    fun `OpenAI 的 prompt_tokens 已含缓存，不能重复加`() {
        val u = UsageNormalizer.openAi(prompt = 10_000, cached = 8_000, output = 500)
        assertEquals(10_000, u.inputTokens)
        assertEquals(8_000, u.cachedInputTokens)
        assertEquals(2_000, u.freshInputTokens)
    }

    @Test
    fun `Gemini 的 promptTokenCount 已含缓存`() {
        val u = UsageNormalizer.gemini(prompt = 5_000, cached = 4_000, output = 100)
        assertEquals(5_000, u.inputTokens)
        assertEquals(1_000, u.freshInputTokens)
    }

    @Test
    fun `归一化后缓存必须是总量的子集`() {
        // 上游异常：OpenAI 兼容端点把 cached 报得比 prompt 还大
        val u = UsageNormalizer.openAi(prompt = 100, cached = 500, output = 1)
        assertEquals(100, u.inputTokens)
        assertEquals(100, u.cachedInputTokens)
        assertEquals(0, u.freshInputTokens)
    }

    @Test
    fun `负数与缺失字段按 0 处理`() {
        val u = UsageNormalizer.anthropic(input = -5, cacheRead = null, cacheCreation = -1, output = -9)
        assertEquals(0, u.inputTokens)
        assertEquals(0, u.cachedInputTokens)
        assertEquals(0, u.cacheCreationTokens)
        assertEquals(0, u.outputTokens)
    }

    @Test
    fun `Anthropic 总量溢出时饱和而不是回绕`() {
        val u = UsageNormalizer.anthropic(
            input = Int.MAX_VALUE,
            cacheRead = Int.MAX_VALUE,
            cacheCreation = 0,
            output = 1
        )
        assertEquals(Int.MAX_VALUE, u.inputTokens)
        assertTrue(u.inputTokens > 0)
    }

    @Test
    fun `任意归一化结果都满足子集关系`() {
        val cases = listOf(
            UsageNormalizer.anthropic(123, 456, 78, 90),
            UsageNormalizer.openAi(1_000, 999, 10),
            UsageNormalizer.gemini(10, 0, 1)
        )
        cases.forEach { u ->
            assertTrue(u.cachedInputTokens >= 0)
            assertTrue(u.cacheCreationTokens >= 0)
            assertTrue(u.cachedInputTokens + u.cacheCreationTokens <= u.inputTokens)
            assertTrue(u.freshInputTokens >= 0)
            assertEquals(
                u.inputTokens,
                u.freshInputTokens + u.cachedInputTokens + u.cacheCreationTokens
            )
        }
    }
}
