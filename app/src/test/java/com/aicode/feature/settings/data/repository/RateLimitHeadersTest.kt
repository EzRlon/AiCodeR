package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RateLimitHeadersTest {

    // ---------- 响应头解析 ----------

    /** Retry-After 秒数形式：120 → 120_000ms。 */
    @Test
    fun parse_retryAfterSeconds() {
        val info = RateLimitHeaders.parse(listOf("Retry-After" to "120"))

        assertEquals(120_000L, info.retryAfterMs)
        assertTrue(info.isEmpty.not())
    }

    /** Retry-After 毫秒专用头优先于秒数头。 */
    @Test
    fun parse_retryAfterMsPreferred() {
        val info = RateLimitHeaders.parse(
            listOf("Retry-After" to "120", "Retry-After-Ms" to "500")
        )

        assertEquals(500L, info.retryAfterMs)
    }

    /** Retry-After HTTP-date 形式：未来 1 小时 → 介于 0 与 2 小时之间（不卡精确值，防时钟抖动）。 */
    @Test
    fun parse_retryAfterHttpDate() {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        val oneHourLater = format.format(java.util.Date(System.currentTimeMillis() + 3_600_000L))

        val info = RateLimitHeaders.parse(listOf("Retry-After" to oneHourLater))

        assertTrue(info.retryAfterMs in 1..(2 * 3_600_000L))
    }

    /** 超长 Retry-After 封顶 24 小时（防御异常服务端给出天文数字）。 */
    @Test
    fun parse_retryAfterCappedAt24h() {
        val info = RateLimitHeaders.parse(listOf("Retry-After" to "100000000"))

        assertEquals(24 * 60 * 60 * 1000L, info.retryAfterMs)
    }

    /** OpenAI 风格剩余/reset 头：剩余为 0 且有 reset → exhaustedWithReset 可提前冷却。 */
    @Test
    fun parse_openAiHeaders_exhaustedWithReset() {
        val info = RateLimitHeaders.parse(
            listOf(
                "x-ratelimit-remaining-requests" to "0",
                "x-ratelimit-reset-requests" to "6m0s"
            )
        )

        assertEquals(0, info.remainingRequests)
        assertEquals(360_000L, info.resetAfterMs)
        assertTrue(info.exhaustedWithReset)
    }

    /** reset 头 Anthropic ISO 8601 形式：未来时间戳 → 换算为剩余时长。 */
    @Test
    fun parse_anthropicResetIsoInstant() {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val future = format.format(java.util.Date(System.currentTimeMillis() + 1_800_000L))

        val info = RateLimitHeaders.parse(
            listOf("anthropic-ratelimit-requests-reset" to future)
        )

        assertTrue(info.resetAfterMs in 1..(3_600_000L))
    }

    /** 头名大小写不敏感，且同名额取第一个非空值。 */
    @Test
    fun parse_headerNamesCaseInsensitive() {
        val info = RateLimitHeaders.parse(
            listOf("X-RateLimit-Remaining-Tokens" to "1024")
        )

        assertEquals(1024, info.remainingTokens)
    }

    /** 无任何限速头：返回全空信息。 */
    @Test
    fun parse_noHeaders_returnsEmpty() {
        val info = RateLimitHeaders.parse(emptyList())

        assertNull(info.retryAfterMs)
        assertNull(info.remainingRequests)
        assertNull(info.resetAfterMs)
        assertNull(info.remainingTokens)
        assertTrue(info.isEmpty)
    }

    // ---------- 时长串解析（parseDuration，错误体解析也复用） ----------

    /** OpenAI 组合时长 `1h2m3s` → 3_723_000ms。 */
    @Test
    fun parseDuration_compound() {
        assertEquals(3_723_000L, RateLimitHeaders.parseDuration("1h2m3s"))
    }

    /** 毫秒单位 `500ms` → 500ms。 */
    @Test
    fun parseDuration_milliseconds() {
        assertEquals(500L, RateLimitHeaders.parseDuration("500ms"))
    }

    /** 纯数字按秒解释：90 → 90_000ms。 */
    @Test
    fun parseDuration_pureDigitsAreSeconds() {
        assertEquals(90_000L, RateLimitHeaders.parseDuration("90"))
    }

    /** 小数数量 `1.5h` → 5_400_000ms。 */
    @Test
    fun parseDuration_fractional() {
        assertEquals(5_400_000L, RateLimitHeaders.parseDuration("1.5h"))
    }

    /** 不可识别输入返回 null（单位不存在 / 非数字 / 空串）。 */
    @Test
    fun parseDuration_invalidReturnsNull() {
        assertNull(RateLimitHeaders.parseDuration("abc"))
        assertNull(RateLimitHeaders.parseDuration("1x"))
        assertNull(RateLimitHeaders.parseDuration(""))
        assertNull(RateLimitHeaders.parseDuration("h"))
    }

    // ---------- 错误体解析（RateLimitBody） ----------

    /** JSON 数字字段 retry_after：数字值默认按秒。 */
    @Test
    fun bodyParse_jsonRetryAfterSeconds() {
        val info = RateLimitBody.parse("""{"error":{"retry_after":30}}""")

        assertEquals(30_000L, info.retryAfterMs)
    }

    /** JSON 字段名含 ms 按毫秒解释。 */
    @Test
    fun bodyParse_jsonRetryAfterMs() {
        val info = RateLimitBody.parse("""{"retry_after_ms":1500}""")

        assertEquals(1500L, info.retryAfterMs)
    }

    /** error 对象下探一层：message 里的恢复文本也能被提取。 */
    @Test
    fun bodyParse_nestedErrorMessageText() {
        val info = RateLimitBody.parse(
            """{"error":{"message":"Rate limit exceeded. Please try again in 45s.","type":"quota"}}"""
        )

        assertEquals(45_000L, info.retryAfterMs)
    }

    /** Gemini 风格：details[].@type 含 RetryInfo 且带 retryDelay。 */
    @Test
    fun bodyParse_geminiRetryInfo() {
        val info = RateLimitBody.parse(
            """{"error":{"code":429,"message":"Quota exceeded","details":[""" +
                """{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"42s"}]}}"""
        )

        assertEquals(42_000L, info.retryAfterMs)
    }

    /** remaining 字段：剩余请求数提取。 */
    @Test
    fun bodyParse_remainingRequests() {
        val info = RateLimitBody.parse("""{"remaining_requests":0}""")

        assertEquals(0, info.remainingRequests)
    }

    /** 纯文本英文提示：`try again in 30s`。 */
    @Test
    fun bodyParse_plainTextEnglish() {
        val info = RateLimitBody.parse("Too many requests. Please try again in 30 seconds.")

        assertEquals(30_000L, info.retryAfterMs)
    }

    /** 纯文本中文提示：`30秒后重试`（后置模式匹配）。 */
    @Test
    fun bodyParse_plainTextChinese() {
        val info = RateLimitBody.parse("请求过于频繁，请在30秒后重试。")

        assertEquals(30_000L, info.retryAfterMs)
    }

    /** 「每分钟限制 N 次」这类描述不得被误当恢复时间（上下文守卫）。 */
    @Test
    fun bodyParse_rateDescriptionNotMisread() {
        val info = RateLimitBody.parse("您已达到每分钟限制 5 次的额度上限。")

        assertTrue(info.isEmpty)
    }

    /** 非 JSON 且无恢复文本：返回空信息，交由自适应冷却兜底。 */
    @Test
    fun bodyParse_plainHtml_returnsEmpty() {
        val info = RateLimitBody.parse("<html><body>502 Bad Gateway</body></html>")

        assertTrue(info.isEmpty)
    }

    /** null / 空白体直接返回空信息。 */
    @Test
    fun bodyParse_nullOrBlank_returnsEmpty() {
        assertTrue(RateLimitBody.parse(null).isEmpty)
        assertTrue(RateLimitBody.parse("  ").isEmpty)
    }
}
