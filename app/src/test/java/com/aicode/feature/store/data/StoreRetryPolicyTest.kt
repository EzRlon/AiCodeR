package com.aicode.feature.store.data

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreRetryPolicyTest {

    // ---- Retry-After 解析 ----

    @Test
    fun `parseRetryAfter_seconds`() {
        assertEquals(3_000L, StoreRetryPolicy.parseRetryAfterMillis("3"))
    }

    @Test
    fun `parseRetryAfter_zero_seconds`() {
        assertEquals(0L, StoreRetryPolicy.parseRetryAfterMillis("0"))
    }

    @Test
    fun `parseRetryAfter_http_date_counts_down_from_now`() {
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(now + 5_000L)

        val delay = StoreRetryPolicy.parseRetryAfterMillis(date, now)!!

        assertTrue("delay=$delay should be near 5s", delay in 4_000L..6_000L)
    }

    @Test
    fun `parseRetryAfter_past_date_clamps_to_zero`() {
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(now - 5_000L)

        assertEquals(0L, StoreRetryPolicy.parseRetryAfterMillis(date, now))
    }

    @Test
    fun `parseRetryAfter_missing_or_invalid_is_null`() {
        assertNull(StoreRetryPolicy.parseRetryAfterMillis(null))
        assertNull(StoreRetryPolicy.parseRetryAfterMillis(""))
        assertNull(StoreRetryPolicy.parseRetryAfterMillis("  "))
        assertNull(StoreRetryPolicy.parseRetryAfterMillis("soon"))
    }

    // ---- 重试决策 ----

    @Test
    fun `http_429_with_retry_after_waits_server_delay`() {
        val error = HttpStatusException(code = 429, retryAfterMillis = 3_000)

        assertEquals(3_000L, StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
    }

    @Test
    fun `http_429_without_retry_after_fails_fast`() {
        val error = HttpStatusException(code = 429, retryAfterMillis = null)

        assertNull(StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
    }

    @Test
    fun `http_429_retry_after_beyond_cap_fails_fast`() {
        val error = HttpStatusException(code = 429, retryAfterMillis = 60_000)

        assertNull(StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
    }

    @Test
    fun `http_500_uses_exponential_backoff_then_gives_up`() {
        val error = HttpStatusException(code = 500, retryAfterMillis = null)

        assertEquals(500L, StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
        assertEquals(1_000L, StoreRetryPolicy.nextDelayMillis(error, attempt = 2))
        assertNull(StoreRetryPolicy.nextDelayMillis(error, attempt = 3))
    }

    @Test
    fun `http_503_prefers_retry_after_header`() {
        val error = HttpStatusException(code = 503, retryAfterMillis = 2_000)

        assertEquals(2_000L, StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
    }

    @Test
    fun `http_4xx_does_not_retry`() {
        val error = HttpStatusException(code = 404, retryAfterMillis = null)

        assertNull(StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
    }

    @Test
    fun `io_exception_retries_with_backoff`() {
        val error = IOException("connection reset")

        assertEquals(500L, StoreRetryPolicy.nextDelayMillis(error, attempt = 1))
        assertEquals(1_000L, StoreRetryPolicy.nextDelayMillis(error, attempt = 2))
        assertNull(StoreRetryPolicy.nextDelayMillis(error, attempt = 3))
    }

    @Test
    fun `unrelated_exception_does_not_retry`() {
        assertNull(StoreRetryPolicy.nextDelayMillis(IllegalStateException("boom"), attempt = 1))
    }
}
