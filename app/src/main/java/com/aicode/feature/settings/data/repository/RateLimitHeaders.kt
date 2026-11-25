package com.aicode.feature.settings.data.repository

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 供应商限速响应头解析结果。
 *
 * 不同厂商给的信息差异极大：OpenAI 给 `x-ratelimit-remaining-requests` / `x-ratelimit-reset-requests`，
 * Anthropic 给 `anthropic-ratelimit-requests-remaining` / `...-reset`（ISO 8601 时间戳），
 * 而 NVIDIA NIM 的 429 常常**一个头都不给**——所以这里所有字段都是可空的，拿不到就返回 null，
 * 由 [KeyLedger] 的自适应惩罚序列兜底，绝不臆造恢复时间。
 */
data class RateLimitInfo(
    /** 服务端要求的最短等待时长（毫秒），来自 Retry-After / retry-after-ms。 */
    val retryAfterMs: Long? = null,
    /** 当前窗口剩余请求数；0 表示额度已耗尽。 */
    val remainingRequests: Int? = null,
    /** 当前窗口重置的剩余时长（毫秒），由 reset 头换算而来。 */
    val resetAfterMs: Long? = null,
    /** 当前窗口剩余 token 数（部分厂商提供）。 */
    val remainingTokens: Int? = null
) {
    val isEmpty: Boolean
        get() = retryAfterMs == null && remainingRequests == null &&
            resetAfterMs == null && remainingTokens == null

    /** 额度已耗尽且知道何时恢复：可以提前把 Key 打入冷却，避免明知会 429 还去撞。 */
    val exhaustedWithReset: Boolean
        get() = remainingRequests != null && remainingRequests <= 0 && resetAfterMs != null
}

object RateLimitHeaders {

    /** 单次解析最多扫这么多头，防御异常多的响应头。 */
    private const val MAX_HEADERS = 200

    private const val MAX_WAIT_MS = 24 * 60 * 60 * 1000L

    /** 常见厂商的「剩余请求数」头名，按优先级排列。 */
    private val REMAINING_HEADERS = listOf(
        "x-ratelimit-remaining-requests",
        "anthropic-ratelimit-requests-remaining",
        "ratelimit-remaining",
        "x-ratelimit-remaining"
    )

    private val RESET_HEADERS = listOf(
        "x-ratelimit-reset-requests",
        "anthropic-ratelimit-requests-reset",
        "ratelimit-reset",
        "x-ratelimit-reset"
    )

    private val REMAINING_TOKEN_HEADERS = listOf(
        "x-ratelimit-remaining-tokens",
        "anthropic-ratelimit-tokens-remaining"
    )

    /**
     * 从响应头解析限速信息。[headers] 为 (name, value) 序列，实现方通常传 OkHttp 的
     * `response.headers` 展开结果，避免本类依赖具体 HTTP 客户端。
     */
    fun parse(headers: List<Pair<String, String>>): RateLimitInfo {
        if (headers.isEmpty()) return RateLimitInfo()
        val map = HashMap<String, String>(headers.size.coerceAtMost(MAX_HEADERS))
        for ((rawName, rawValue) in headers.take(MAX_HEADERS)) {
            val name = rawName.lowercase(Locale.US)
            val value = rawValue.trim()
            if (value.isNotEmpty() && name !in map) map[name] = value
        }

        val retryAfterMs = map["retry-after-ms"]?.let(::parseMillis)
            ?: map["retry-after"]?.let(::parseRetryAfter)
        val remaining = REMAINING_HEADERS.firstNotNullOfOrNull { map[it]?.toIntOrNull() }
        val resetAfterMs = RESET_HEADERS.firstNotNullOfOrNull { name ->
            map[name]?.let(::parseResetDuration)
        }
        val remainingTokens = REMAINING_TOKEN_HEADERS.firstNotNullOfOrNull { map[it]?.toIntOrNull() }

        return RateLimitInfo(
            retryAfterMs = retryAfterMs?.coerceIn(0L, MAX_WAIT_MS),
            remainingRequests = remaining,
            resetAfterMs = resetAfterMs?.coerceIn(0L, MAX_WAIT_MS),
            remainingTokens = remainingTokens
        )
    }

    private fun parseMillis(raw: String): Long? = raw.toLongOrNull()?.takeIf { it >= 0 }

    /**
     * Retry-After 支持两种格式：秒数（`120`）与 HTTP-date（`Wed, 21 Oct 2015 07:28:00 GMT`）。
     */
    private fun parseRetryAfter(raw: String): Long? {
        raw.toLongOrNull()?.let { return it * 1000L }
        val date = parseHttpDate(raw) ?: return null
        return (date.time - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * reset 头可能是：
     * - 时长期望格式：`1s`、`500ms`、`6m0s`、`1h2m3s`（OpenAI 风格）
     * - ISO 8601 时间戳：`2026-11-25T10:00:00Z`、`2026-11-25T10:00:00.123+08:00`（Anthropic 风格）
     * - 纯秒数
     */
    private fun parseResetDuration(raw: String): Long? {
        parseDuration(raw)?.let { return it }
        parseIsoInstant(raw)?.let { return (it - System.currentTimeMillis()).coerceAtLeast(0L) }
        parseHttpDate(raw)?.let { return (it.time - System.currentTimeMillis()).coerceAtLeast(0L) }
        return raw.toLongOrNull()?.let { it * 1000L }
    }

    /** 解析 `1h2m3s` / `1s` / `500ms` 这类时长串；无法识别返回 null。错误体解析也复用。 */
    internal fun parseDuration(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toLongOrNull()?.times(1000L)

        var totalMs = 0L
        var index = 0
        var matchedAny = false
        while (index < text.length) {
            val numberStart = index
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            if (index == numberStart) return null
            val number = text.substring(numberStart, index).toDoubleOrNull() ?: return null

            val unitStart = index
            while (index < text.length && text[index].isLetter()) index++
            if (index == unitStart) return null
            val unit = text.substring(unitStart, index).lowercase(Locale.US)
            val multiplier = when (unit) {
                "ms" -> 1.0
                "s" -> 1_000.0
                "m" -> 60_000.0
                "h" -> 3_600_000.0
                "d" -> 86_400_000.0
                else -> return null
            }
            totalMs += (number * multiplier).toLong()
            matchedAny = true
        }
        return if (matchedAny) totalMs else null
    }

    internal fun parseIsoInstant(raw: String): Long? {
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val position = ParsePosition(0)
            val date = format.parse(raw, position) ?: continue
            if (position.index == raw.length) return date.time
        }
        return null
    }

    private fun parseHttpDate(raw: String): Date? {
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            val position = ParsePosition(0)
            val date = format.parse(raw, position) ?: continue
            if (position.index == raw.length) return date
        }
        return null
    }
}

/**
 * 429/配额错误体的通用解析：大量供应商（Gemini、DeepSeek、one-api/new-api 系中转站等）
 * 把恢复信息放在 JSON 错误体或错误消息文本里，而不是响应头。
 *
 * 解析顺序：JSON 字段探测 → Gemini RetryInfo 明细 → 纯文本正则。拿不到就返回空值，
 * 由 KeyLedger 的自适应策略兜底——宁可不确定，不臆造恢复时间。
 */
object RateLimitBody {

    /** 错误体一般不大；超长只取头部足够覆盖 JSON 关键字段。 */
    private const val MAX_BODY = 16 * 1024

    /** 单位换算表：识别到的单位 → 毫秒。 */
    private val UNIT_MS = mapOf(
        "ms" to 1L, "毫秒" to 1L,
        "s" to 1_000L, "sec" to 1_000L, "secs" to 1_000L, "second" to 1_000L, "seconds" to 1_000L, "秒" to 1_000L,
        "m" to 60_000L, "min" to 60_000L, "mins" to 60_000L, "minute" to 60_000L, "minutes" to 60_000L,
        "分钟" to 60_000L, "分" to 60_000L,
        "h" to 3_600_000L, "hour" to 3_600_000L, "hours" to 3_600_000L, "小时" to 3_600_000L,
        "d" to 86_400_000L, "day" to 86_400_000L, "days" to 86_400_000L, "天" to 86_400_000L
    )

    /** JSON 里可能携带恢复时长的字段名（小写匹配，数字值默认秒、带 ms 的字段按毫秒）。 */
    private val JSON_DURATION_KEYS = listOf(
        "retry-after", "retry_after", "retryafter", "retry-after-ms", "retry_after_ms",
        "reset-after", "reset_after", "resets-in", "resetsin", "retrydelay", "retry_delay",
        "quota-reset", "quota_reset", "quotareset", "ratelimit-reset", "reset_at", "resetat"
    )

    private val JSON_REMAINING_KEYS = listOf(
        "remaining", "remaining_requests", "remainingrequests", "ratelimit-remaining", "remainingquota"
    )

    /** 文本提取：要求「retry / again / 后」类上下文，避免把「每分钟限制 N 次」这类描述误当恢复时间。 */
    private val TEXT_PATTERNS = listOf(
        Regex("(?i)(?:retry|try again|重试)[^0-9\\n]{0,40}?(\\d+)\\s*(ms|${UNIT_MS.keys.joinToString("|") { Regex.escape(it) }})"),
        Regex("(?i)(\\d+)\\s*(ms|${UNIT_MS.keys.joinToString("|") { Regex.escape(it) }})[^0-9\\n]{0,20}?(?:后|later|again|再)")
    )

    fun parse(body: String?): RateLimitInfo {
        if (body.isNullOrBlank()) return RateLimitInfo()
        val trimmed = body.take(MAX_BODY).trim()
        if (!trimmed.startsWith("{")) return parsePlainText(trimmed)
        return parseJson(trimmed) ?: parsePlainText(trimmed)
    }

    /** 扫描结果：恢复时长 / 剩余额度 / 可供文本提取的错误消息。 */
    private data class ScanResult(
        val retryAfterMs: Long? = null,
        val remainingRequests: Int? = null,
        val errorMessage: String? = null
    )

    /** 浅层递归找限速字段（error 对象下探一层，够覆盖常见结构且防深递归）。 */
    private fun parseJson(text: String): RateLimitInfo? = runCatching {
        val root = JSONObject(text)
        val (retryAfterMs, remaining, messageText) = scanObject(root, depth = 0)
        val fromText = messageText?.let { parsePlainText(it) }
        val info = RateLimitInfo(
            retryAfterMs = retryAfterMs ?: fromText?.retryAfterMs,
            remainingRequests = remaining ?: fromText?.remainingRequests
        )
        if (info.isEmpty) null else info
    }.getOrNull()

    private fun scanObject(obj: JSONObject, depth: Int): ScanResult {
        var retryAfterMs: Long? = null
        var remaining: Int? = null
        var messageText: String? = null

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next().lowercase(Locale.US)
            val value = obj.opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            when {
                (key in JSON_DURATION_KEYS || key == "retrydelay") && retryAfterMs == null -> {
                    valueToMs(value, key)?.let { retryAfterMs = it }
                }
                key in JSON_REMAINING_KEYS && remaining == null -> {
                    value.toString().toIntOrNull()?.let { remaining = it }
                }
                (key == "message" || key == "error_message") && messageText == null -> {
                    messageText = value.toString()
                }
                key == "error" && value is JSONObject && depth < 2 -> {
                    scanObject(value, depth + 1).let { inner ->
                        retryAfterMs = retryAfterMs ?: inner.retryAfterMs
                        remaining = remaining ?: inner.remainingRequests
                        messageText = messageText ?: inner.errorMessage
                    }
                }
                key == "details" && value is JSONArray && depth < 2 -> {
                    // Gemini 风格：details[].@type 含 RetryInfo 的条目带 retryDelay
                    for (index in 0 until value.length()) {
                        val detail = value.optJSONObject(index) ?: continue
                        val type = detail.optString("@type")
                        val retryDelay = detail.optString("retryDelay")
                        if (retryAfterMs == null && retryDelay.isNotEmpty() &&
                            (type.isEmpty() || type.contains("RetryInfo", ignoreCase = true))
                        ) {
                            RateLimitHeaders.parseDuration(retryDelay)?.let { retryAfterMs = it }
                        }
                    }
                }
            }
        }

        return ScanResult(retryAfterMs, remaining, messageText)
    }

    private fun valueToMs(value: Any, key: String): Long? {
        val perUnit = if (key.contains("ms")) 1L else 1_000L
        return when (value) {
            is Number -> (value.toLong() * perUnit).takeIf { it > 0 }
            is String -> when {
                value.isEmpty() -> null
                value.all { it.isDigit() } -> (value.toLong() * perUnit).takeIf { it > 0 }
                else -> RateLimitHeaders.parseDuration(value)
                    ?: RateLimitHeaders.parseIsoInstant(value)?.let { instant ->
                        (instant - System.currentTimeMillis()).takeIf { diff -> diff > 0 }
                    }
            }
            else -> null
        }
    }

    private fun parsePlainText(text: String): RateLimitInfo {
        for (pattern in TEXT_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val number = match.groupValues[1].toLongOrNull() ?: continue
            val unit = match.groupValues[2].lowercase(Locale.US)
            val ms = number * (UNIT_MS[unit] ?: 1_000L)
            if (ms > 0) return RateLimitInfo(retryAfterMs = ms.coerceAtMost(MAX_WAIT_MS))
        }
        return RateLimitInfo()
    }

    private const val MAX_WAIT_MS = 24 * 60 * 60 * 1000L
}