package com.aicode.feature.store.data

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 商店请求收到非 2xx 响应。
 *
 * [retryAfterMillis] 为服务端 `Retry-After` 指定的等待时长（无该头部时为 null）。
 * 继承普通 Exception 而非 IOException：状态码错误是否重试由 [StoreRetryPolicy] 按语义判断，
 * 不能落进「IOException 一律重试」的兜底。
 */
class HttpStatusException(
    val code: Int,
    val retryAfterMillis: Long?,
    /**
     * 服务端返回的是「人机安全校验页」而不是业务数据（例如 Vercel Security Checkpoint
     * 用 429 + text/html 挡非浏览器客户端）。
     *
     * 这类失败和「网络不通」「请求太频繁」都不是一回事：重试不会通过、换网络也没用，
     * 必须给用户一句如实的说明，否则「请检查网络」会把人引到错误的方向上排查。
     */
    val challenge: Boolean = false
) : Exception("HTTP $code")

/**
 * 商店网络请求的重试策略。
 *
 * - 连接类 [IOException] / HTTP 5xx：按 500ms、1000ms 指数退避重试；
 * - HTTP 429：不再用短退避硬撞（重试只会加剧限流）。仅在服务端给出可接受的
 *   `Retry-After` 时按该时长重试；否则立即失败，由界面提示用户稍后再试；
 * - 其它 4xx（404 等）：立即失败。
 */
internal object StoreRetryPolicy {

    const val MAX_ATTEMPTS = 3

    private const val BASE_BACKOFF_MILLIS = 500L

    /** 限流时最多愿意等待的时长；超过就直接失败，避免商店页长时间转圈。 */
    const val MAX_RATE_LIMIT_WAIT_MILLIS = 10_000L

    /**
     * 计算下一次重试前的等待毫秒数；返回 null 表示该错误不应再重试。
     * [attempt] 为已失败的尝试次数（从 1 开始）。
     */
    fun nextDelayMillis(error: Throwable, attempt: Int): Long? {
        if (attempt >= MAX_ATTEMPTS) return null
        if (error is HttpStatusException) {
            if (error.code == 429) {
                // 没有 Retry-After 就立即失败：短退避重试只会让限流更严重。
                return error.retryAfterMillis
                    ?.takeIf { it in 0L..MAX_RATE_LIMIT_WAIT_MILLIS }
            }
            if (error.code < 500) return null
        } else if (error !is IOException) {
            return null
        }
        // 5xx / IOException：服务端若明确给了可接受的 Retry-After，优先按它等待。
        error.retryAfterOrNull()
            ?.takeIf { it in 0L..MAX_RATE_LIMIT_WAIT_MILLIS }
            ?.let { return it }
        return baseBackoffMillis(attempt)
    }

    private fun Throwable.retryAfterOrNull(): Long? =
        (this as? HttpStatusException)?.retryAfterMillis

    private fun baseBackoffMillis(attempt: Int) = BASE_BACKOFF_MILLIS shl (attempt - 1)

    /**
     * 解析 `Retry-After` 头：支持秒数与 HTTP 日期两种格式；无法解析返回 null。
     * 日期格式按 [nowMillis] 换算为剩余等待时长，已过期返回 0。
     */
    fun parseRetryAfterMillis(
        header: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return (it * 1000L).coerceAtLeast(0L) }
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val at = format.parse(raw)?.time ?: return null
            (at - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }
}
