package com.aicode.feature.agent.domain.provider

/**
 * 归一化后的单次调用用量。
 *
 * [inputTokens] 是**本次调用处理的全部输入**：未命中缓存的新增输入 + 缓存命中 + 缓存写入。
 * [cachedInputTokens] / [cacheCreationTokens] 是它的**子集**，用于展示与计价，**不能再次相加**。
 *
 * 这套口径与计费口径一致（`ModelCostCalculator` 用 `input - cached - creation` 算未缓存部分），
 * 也是上下文窗口判断需要的真实上下文大小。
 */
data class NormalizedUsage(
    val inputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val outputTokens: Int = 0
) {
    /** 未命中缓存的新增输入（展示用，恒 ≥ 0）。 */
    val freshInputTokens: Int
        get() = (inputTokens - cachedInputTokens - cacheCreationTokens).coerceAtLeast(0)
}

/**
 * 各 provider 的 usage → 统一口径。
 *
 * **只在适配器出口调用一次**：聚合层（会话总账、记忆图谱、成本估算）只做加法，
 * 任何地方都不要再 `+ cachedInputTokens`，否则会重复计数。
 *
 * provider 语义差异（必须按协议处理，不能按字段名猜）：
 * - Anthropic / Claude CLI：`input_tokens` 只含未命中缓存的部分，需要加 `cache_read`、`cache_creation`；
 * - OpenAI Chat Completions / Responses：`prompt_tokens` / `input_tokens` **已包含** cached 部分；
 * - Gemini：`promptTokenCount` 同样已包含 `cachedContentTokenCount`。
 */
object UsageNormalizer {

    /** Anthropic Messages / Claude Code CLI stream-json。 */
    fun anthropic(
        input: Int,
        cacheRead: Int?,
        cacheCreation: Int?,
        output: Int
    ): NormalizedUsage = normalize(
        input = input,
        cached = cacheRead,
        creation = cacheCreation,
        output = output,
        inputIncludesCache = false
    )

    /** OpenAI Chat Completions（`usage.prompt_tokens`）。 */
    fun openAi(
        prompt: Int,
        cached: Int?,
        output: Int,
        cacheCreation: Int? = null
    ): NormalizedUsage = normalize(
        input = prompt,
        cached = cached,
        creation = cacheCreation,
        output = output,
        inputIncludesCache = true
    )

    /** Gemini generateContent（`usageMetadata.promptTokenCount`）。 */
    fun gemini(prompt: Int, cached: Int?, output: Int): NormalizedUsage =
        openAi(prompt = prompt, cached = cached, output = output)

    private fun normalize(
        input: Int,
        cached: Int?,
        creation: Int?,
        output: Int,
        inputIncludesCache: Boolean
    ): NormalizedUsage {
        val safeInput = input.coerceAtLeast(0)
        val safeCached = (cached ?: 0).coerceAtLeast(0)
        val safeCreation = (creation ?: 0).coerceAtLeast(0)
        val totalInput = if (inputIncludesCache) {
            safeInput
        } else {
            // 极端长会话可能越过 Int 上限：饱和相加，避免回绕成负数。
            saturatingAdd(saturatingAdd(safeInput, safeCached), safeCreation)
        }
        // 归一化后 cached / creation 必须是 total 的子集；上游偶有异常值，按 total 截断并保证互不重叠。
        val boundedCached = safeCached.coerceAtMost(totalInput)
        val boundedCreation = safeCreation.coerceAtMost(totalInput - boundedCached)
        return NormalizedUsage(
            inputTokens = totalInput,
            cachedInputTokens = boundedCached,
            cacheCreationTokens = boundedCreation,
            outputTokens = output.coerceAtLeast(0)
        )
    }

    private fun saturatingAdd(a: Int, b: Int): Int =
        (a.toLong() + b.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
