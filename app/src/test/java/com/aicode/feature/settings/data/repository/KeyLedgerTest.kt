package com.aicode.feature.settings.data.repository

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class KeyLedgerTest {

    /** 每个测试独立临时目录：账本落盘互不串扰（生产路径只用 context.filesDir）。 */
    private fun newLedger(dir: File = Files.createTempDirectory("keyledger-test").toFile()): Pair<KeyLedger, File> {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        return KeyLedger(context) to dir
    }

    /** 账本落盘是异步单飞写：轮询等 ledger 目录下文件出现，超时返回 null。 */

    private fun sha256Hex(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun ledgerFileName(dir: File, providerId: String): File {
        val hash = sha256Hex(providerId).take(16)
        return File(File(dir, "keyledger"), "ledger_$hash.json")
    }

    /** 距冷却截止的剩余毫秒（测试与生产各取一次时钟，留 5 秒容差）。 */
    private fun remainingMs(ledger: KeyLedger, providerId: String, key: String): Long =
        ledger.coolUntilMs(providerId, key) - System.currentTimeMillis()

    // ---------- 惩罚序列（CONSERVATIVE） ----------

    /** 无服务端信号：首次 429 冷却 = 用户配置基数（等级 0 → ×1）。 */
    @Test
    fun reportRateLimit_first429_cooldownEqualsBase() {
        val (ledger, _) = newLedger()

        ledger.reportRateLimit("p1", "key-a", baseCooldownMs = 60_000L)

        assertTrue(ledger.isCoolingDown("p1", "key-a"))
        val remaining = remainingMs(ledger, "p1", "key-a")
        assertTrue("期望 ≈60s，实际 ${remaining}ms", remaining in 55_000..60_000)
    }

    /** 连续 429 按序列放大：×1 → ×3 → ×12，惩罚等级逐级上升。 */
    @Test
    fun reportRateLimit_consecutive429_escalates() {
        val (ledger, _) = newLedger()
        val base = 60_000L
        val expected = longArrayOf(60_000L, 180_000L, 720_000L)

        expected.forEachIndexed { index, duration ->
            ledger.reportRateLimit("p1", "key-a", baseCooldownMs = base)
            val remaining = remainingMs(ledger, "p1", "key-a")
            assertTrue("第 ${index + 1} 次 429 期望 ≈${duration}ms，实际 ${remaining}ms",
                remaining in duration - 8_000..duration)
            assertEquals(index + 1, ledger.snapshot("p1", "key-a").penaltyLevel)
        }
    }

    /** 冷却封顶 24 小时：10min 基数 ×288 = 48h，落库被压到 24h。 */
    @Test
    fun reportRateLimit_capsAt24h() {
        val (ledger, _) = newLedger()
        repeat(6) { ledger.reportRateLimit("p1", "key-a", baseCooldownMs = 600_000L) }

        val remaining = remainingMs(ledger, "p1", "key-a")
        val day = 24 * 60 * 60 * 1000L
        assertTrue("期望封顶 24h，实际 ${remaining}ms", remaining in day - 10_000..day)
        assertEquals(6, ledger.snapshot("p1", "key-a").day429)
    }

    /** 惩罚等级封顶后不再上升：第 7 次 429 的冷却仍等于第 6 次（基数 1min → 4.8h）。 */
    @Test
    fun reportRateLimit_levelCappedAtLastIndex() {
        val (ledger, _) = newLedger()
        repeat(7) { ledger.reportRateLimit("p1", "key-a", baseCooldownMs = 60_000L) }

        val remaining = remainingMs(ledger, "p1", "key-a")
        val capped = 288 * 60_000L
        assertTrue("期望等级封顶后仍为 4.8h，实际 ${remaining}ms",
            remaining in capped - 8_000..capped)
        assertEquals(5, ledger.snapshot("p1", "key-a").penaltyLevel)
    }

    // ---------- 服务端信号优先与画像 ----------

    /** 服务端明确给出 Retry-After 时以它为准，不用自适应序列。 */
    @Test
    fun reportRateLimit_serverRetryAfterWins() {
        val (ledger, _) = newLedger()

        ledger.reportRateLimit(
            "p1", "key-a",
            rateLimit = RateLimitInfo(retryAfterMs = 45_000L),
            baseCooldownMs = 600_000L
        )

        val remaining = remainingMs(ledger, "p1", "key-a")
        assertTrue("期望 ≈45s（服务端权威值），实际 ${remaining}ms", remaining in 40_000..45_000)
    }

    /** AGGRESSIVE 画像（分钟窗口型官方域）：无样本时 30s 起步，与用户基数无关。 */
    @Test
    fun reportRateLimit_aggressiveProfile_30sFloor() {
        val (ledger, _) = newLedger()

        ledger.reportRateLimit(
            "p1", "key-a", baseCooldownMs = 600_000L, profile = CooldownProfile.AGGRESSIVE
        )

        val remaining = remainingMs(ledger, "p1", "key-a")
        assertTrue("期望 ≈30s 起步，实际 ${remaining}ms", remaining in 25_000..30_000)
    }

    // ---------- 鉴权失败 ----------

    /** 401/403 固定冷却 1 小时并标记 authFailed；成功后标记清除。 */
    @Test
    fun reportAuthFailure_fixedOneHour_clearedOnSuccess() {
        val (ledger, _) = newLedger()

        ledger.reportAuthFailure("p1", "key-a", baseCooldownMs = 300_000L)

        assertTrue(ledger.snapshot("p1", "key-a").authFailed)
        val remaining = remainingMs(ledger, "p1", "key-a")
        val hour = 60 * 60_000L
        assertTrue("期望 ≈1h（AUTH 固定冷却），实际 ${remaining}ms", remaining in hour - 10_000..hour)

        ledger.reportSuccess("p1", "key-a")
        assertFalse(ledger.snapshot("p1", "key-a").authFailed)
    }

    // ---------- 统计与请求计数 ----------

    /** 请求终结恰好计一次：reportSuccess 自带计数；token 用量入日账。 */
    @Test
    fun reportSuccess_recordsRequestAndTokens() {
        val (ledger, _) = newLedger()

        ledger.reportSuccess("p1", "key-a", tokensIn = 100, tokensOut = 50)
        val snapshot = ledger.snapshot("p1", "key-a")

        assertEquals(1L, snapshot.totalRequests)
        assertEquals(1L, snapshot.totalSuccess)
        assertEquals(100L, snapshot.dayTokensIn)
        assertEquals(50L, snapshot.dayTokensOut)
        assertTrue(snapshot.lastSuccessAtMs > 0)
    }

    /** 用户关闭冷却（基数 0）：不冷却，但 429 统计照记。 */
    @Test
    fun reportRateLimit_zeroBase_noCooldownButCounts() {
        val (ledger, _) = newLedger()

        ledger.reportRateLimit("p1", "key-a", baseCooldownMs = 0L)

        assertFalse(ledger.isCoolingDown("p1", "key-a"))
        val snapshot = ledger.snapshot("p1", "key-a")
        assertEquals(1L, snapshot.total429)
        assertEquals(1, snapshot.day429)
    }

    // ---------- 恢复时间 ----------

    /** 多 Key 各自冷却时，earliestRecoveryMs 取最早恢复者；全健康返回 0。 */
    @Test
    fun earliestRecoveryMs_picksEarliest() {
        val (ledger, _) = newLedger()

        assertEquals(0L, ledger.earliestRecoveryMs("p1", listOf("key-a", "key-b")))

        ledger.reportRateLimit("p1", "key-a", baseCooldownMs = 600_000L)
        ledger.reportRateLimit("p1", "key-b", baseCooldownMs = 60_000L)

        val earliest = ledger.earliestRecoveryMs("p1", listOf("key-a", "key-b")) - System.currentTimeMillis()
        assertTrue("期望 ≈1min（key-b 先恢复），实际 ${earliest}ms", earliest in 30_000..120_000)
    }

    // ---------- 持久化 ----------

    /** 冷却状态跨实例存活：写侧异步落盘后，新实例按同一目录读回冷却与统计。 */
    @Test
    fun persistence_roundtripAcrossInstances() {
        val dir = Files.createTempDirectory("keyledger-test").toFile()
        val (writer, _) = newLedger(dir)

        writer.reportRateLimit("persist-p1", "key-x", baseCooldownMs = 600_000L)
        writer.reportSuccess("persist-p1", "key-x", tokensIn = 10, tokensOut = 5)

        // 等待文件存在≠两次异步单飞写全部完成：轮询新实例快照，
        // 直到本用例断言的字段同时达到期望值；超时抛错并带上最后一次读到的值。
        val deadline = System.currentTimeMillis() + 10_000L
        var last: KeyRuntimeSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            val (probe, _) = newLedger(dir)
            last = probe.snapshot("persist-p1", "key-x")
            if (last.total429 == 1L && last.totalSuccess == 1L) break
            Thread.sleep(100)
        }
        assertTrue(
            "两次写未在超时内全部落盘（最后快照 total429=${last?.total429}, totalSuccess=${last?.totalSuccess}）",
            last != null && last.total429 == 1L && last.totalSuccess == 1L
        )

        val (reader, _) = newLedger(dir)
        assertTrue("新实例应读到冷却状态", reader.isCoolingDown("persist-p1", "key-x"))
        val snapshot = reader.snapshot("persist-p1", "key-x")
        assertEquals(1L, snapshot.total429)
        assertEquals(1L, snapshot.totalSuccess)
        assertEquals(10L, snapshot.dayTokensIn)
    }

    /** 上次选中 Key 的哈希跨实例存活：recordSelected 只落哈希不落明文，新实例可读回；与 keys 数组互不截断。 */
    @Test
    fun persistence_lastSelected_roundtripAcrossInstances() {
        val dir = Files.createTempDirectory("keyledger-test").toFile()
        val (writer, _) = newLedger(dir)

        assertNull(writer.lastSelectedHash("persist-p1"))
        writer.recordSelected("persist-p1", "key-x")
        // 同时写一条 Key 状态，验证 keys 数组存在时顶层 last 字段仍能读出
        writer.reportRateLimit("persist-p1", "key-y", baseCooldownMs = 600_000L)

        val expected = sha256Hex("key-x").take(12)
        val deadline = System.currentTimeMillis() + 10_000L
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            val (probe, _) = newLedger(dir)
            last = probe.lastSelectedHash("persist-p1")
            if (last == expected && probe.isCoolingDown("persist-p1", "key-y")) break
            Thread.sleep(100)
        }
        assertEquals("lastSelected 未在超时内落盘（最后读到 $last）", expected, last)

        val (reader, _) = newLedger(dir)
        assertEquals(expected, reader.lastSelectedHash("persist-p1"))
        assertTrue("同一账本内的冷却状态仍可读回", reader.isCoolingDown("persist-p1", "key-y"))
    }

    /** 账本文件损坏：不崩溃、按全新账本重建，坏文件保留为 .bak 供诊断。 */
    @Test
    fun persistence_corruptedFile_rebuiltAndBackedUp() {
        val dir = Files.createTempDirectory("keyledger-test").toFile()
        val file = ledgerFileName(dir, "corrupt-p1")
        file.parentFile.mkdirs()
        file.writeText("this is not json {")

        val (ledger, _) = newLedger(dir)

        assertFalse(ledger.isCoolingDown("corrupt-p1", "key-a"))
        assertTrue("坏文件应保留为 .bak", File(dir, "keyledger/ledger_${file.name.removePrefix("ledger_").removeSuffix(".json")}.json.bak").exists() ||
            File(dir, "keyledger/" + file.name + ".bak").exists())
        assertEquals(0L, ledger.snapshot("corrupt-p1", "key-a").total429)
    }
}
