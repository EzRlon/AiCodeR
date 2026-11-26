package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 供应商限速画像，决定无服务端信号时的冷却起步策略。
 * - CONSERVATIVE：NVIDIA 等惩罚延长型/未知中转站——冷却从用户配置基数起逐级放大，宁可多等；
 * - AGGRESSIVE：OpenAI/Anthropic/Gemini/DeepSeek 官方域——分钟窗口型，从 30s 起步靠恢复样本收敛。
 */
enum class CooldownProfile { CONSERVATIVE, AGGRESSIVE }

/** 单个 Key 在 UI 上要展示的运行时画像（只读快照，不含 Key 明文）。 */
data class KeyRuntimeSnapshot(
    val keyHash: String,
    val coolingDown: Boolean,
    val coolUntilMs: Long,
    val penaltyLevel: Int,
    val authFailed: Boolean,
    val recentRpm: Int,
    val dayRequests: Int,
    val dayTokensIn: Long,
    val dayTokensOut: Long,
    val day429: Int,
    val totalRequests: Long,
    val totalSuccess: Long,
    val total429: Long,
    val lastSuccessAtMs: Long
)

/**
 * 每个 API Key 的运行时账本：冷却/惩罚状态 + 用量统计，并**持久化到文件**。
 *
 * 为什么必须落盘：NVIDIA NIM 这类供应商在被 429 后，冷却期内继续请求会**指数延长锁定**，
 * 而冷却可能长达数小时甚至 24 小时（日配额型）。如果冷却只存在内存里，一次进程重启就
 * 全部失忆、立刻回头去撞正在锁定的 Key——这正是「多个账号仍然反复限速」的根因之一。
 *
 * 状态机（无服务端 Retry-After 时的核心）：
 * - 429：惩罚等级 +1，冷却时长按 [COOLDOWN_MULTIPLIERS] 逐级放大（5m → 15m → 1h → 4h → 12h → 24h，
 *   以用户配置的「冷却分钟数」为基数），封顶 24 小时；
 * - 有 Retry-After / reset 头时以服务端给的时长为准（权威值优先于猜测）；
 * - 成功且距上次 429 超过 [DECAY_INTERVAL_MS]：惩罚等级 -1，缓慢回归正常，避免一次限速永久重罚；
 * - 401/403 归因为凭据失效：冷却 [AUTH_COOLDOWN_MS] 并标记，供 UI 显著提示。
 */
@Singleton
class KeyLedger @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 内部可变状态；所有读写都在 [lock] 下进行。 */
    private class KeyState {
        var coolUntilMs = 0L
        var penaltyLevel = 0
        var last429AtMs = 0L
        var authFailed = false
        var lastErrorKind: String? = null
        var dayStamp = ""
        var dayRequests = 0
        var dayTokensIn = 0L
        var dayTokensOut = 0L
        var dayErrors = 0
        var day429 = 0
        var totalRequests = 0L
        var totalSuccess = 0L
        var total429 = 0L
        var lastSuccessAtMs = 0L
        var lastRequestAtMs = 0L
        var last429Sampled = false

        /**
         * 真实恢复样本（毫秒）：429 后首次成功距上次 429 的时长，取最近若干个的最小值
         * 作为「该 Key 实际恢复窗口」的估计。冷却等待会拉大样本，因此只保留最小值方向的信息。
         */
        val recoverySamples = ArrayDeque<Long>()

        /** 最近 60 秒的请求时刻，用于实测 RPM。不落盘（跨进程无意义）。 */
        val window = ArrayDeque<Long>()
    }

    private val lock = Any()

    /** 「providerId \u0000 keyHash」→ 状态。 */
    private val states = HashMap<String, KeyState>()

    private val loadedProviders = HashSet<String>()
    private val dirtyProviders = HashSet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    // ---------------------------------------------------------------- 查询

    /** 该 Key 是否处于冷却中（冷却到期的会被顺带清理）。 */
    fun isCoolingDown(providerId: String, key: String): Boolean = synchronized(lock) {
        val state = stateOf(providerId, key)
        normalizeCooldown(state, now())
        state.coolUntilMs > now()
    }

    /** 冷却截止时间戳；未冷却返回 0。 */
    fun coolUntilMs(providerId: String, key: String): Long = synchronized(lock) {
        val state = stateOf(providerId, key)
        normalizeCooldown(state, now())
        state.coolUntilMs
    }

    /** 这组 Key 里最早恢复的时间戳；全都健康则返回 0。 */
    fun earliestRecoveryMs(providerId: String, keys: List<String>): Long = synchronized(lock) {
        val now = now()
        var earliest = Long.MAX_VALUE
        for (key in keys) {
            val state = stateOf(providerId, key)
            normalizeCooldown(state, now)
            if (state.coolUntilMs > now && state.coolUntilMs < earliest) earliest = state.coolUntilMs
        }
        if (earliest == Long.MAX_VALUE) 0L else earliest
    }

    fun snapshot(providerId: String, key: String): KeyRuntimeSnapshot = synchronized(lock) {
        val state = stateOf(providerId, key)
        snapshotOf(state, hashOf(key), now())
    }

    fun snapshots(providerId: String, keys: List<String>): List<KeyRuntimeSnapshot> = synchronized(lock) {
        val now = now()
        keys.map { snapshotOf(stateOf(providerId, it), hashOf(it), now) }
    }

    // ---------------------------------------------------------------- 上报

    /** 每次真正发出请求前调用：计入总量、日量并推入 60 秒窗口。 */
    fun reportRequest(providerId: String, key: String) {
        synchronized(lock) {
            val now = now()
            val state = stateOf(providerId, key)
            rollDay(state, now)
            state.totalRequests++
            state.dayRequests++
            state.lastRequestAtMs = now
            trimWindow(state, now)
            state.window.addLast(now)
            markDirty(providerId)
        }
    }

    /** 请求成功：记录 token 用量、清除凭据失效标记、按健康时长衰减惩罚等级，并采样恢复时长。 */
    fun reportSuccess(
        providerId: String,
        key: String,
        tokensIn: Long = 0L,
        tokensOut: Long = 0L,
        rateLimit: RateLimitInfo? = null
    ) {
        synchronized(lock) {
            val now = now()
            val state = stateOf(providerId, key)
            noteRequest(state, now)
            state.totalSuccess++
            if (state.last429AtMs > 0 && !state.last429Sampled) {
                val sample = now - state.last429AtMs
                if (sample in 1..MAX_COOLDOWN_MS) {
                    state.recoverySamples.addLast(sample)
                    while (state.recoverySamples.size > RECOVERY_SAMPLE_KEEP) {
                        state.recoverySamples.removeFirst()
                    }
                }
                state.last429Sampled = true
            }
            state.lastSuccessAtMs = now
            state.dayTokensIn += tokensIn.coerceAtLeast(0)
            state.dayTokensOut += tokensOut.coerceAtLeast(0)
            if (state.authFailed) {
                state.authFailed = false
                state.lastErrorKind = null
            }
            // 健康使用满 DECAY_INTERVAL_MS 后逐级降罚，避免一次限速导致永不回来的高惩罚
            if (state.penaltyLevel > 0 && state.last429AtMs > 0 &&
                now - state.last429AtMs >= DECAY_INTERVAL_MS
            ) {
                state.penaltyLevel--
                state.last429AtMs = now
            }
            // 成功响应也可能带「剩余额度」头：剩余为 0 时提前冷却，避免明知会 429 还去撞
            if (rateLimit != null && rateLimit.exhaustedWithReset) {
                val reset = rateLimit.resetAfterMs ?: 0L
                if (reset > 0) {
                    state.coolUntilMs = maxOf(state.coolUntilMs, now + reset.coerceAtMost(MAX_COOLDOWN_MS))
                    FileLogger.i(TAG, "额度预判：$providerId ${key.masked()} 剩余请求为 0，冷却 ${reset / 1000}s")
                }
            }
            markDirty(providerId)
        }
    }

    /**
     * 限流（429 / quota 类）上报：惩罚等级升级并按序列放大冷却时长。
     * [baseCooldownMs] 为用户配置的冷却基数，<= 0 表示用户关闭了冷却，此时只记统计不冷却。
     */
    fun reportRateLimit(
        providerId: String,
        key: String,
        rateLimit: RateLimitInfo? = null,
        baseCooldownMs: Long,
        profile: CooldownProfile = CooldownProfile.CONSERVATIVE
    ) {
        synchronized(lock) {
            val now = now()
            val state = stateOf(providerId, key)
            noteRequest(state, now)
            state.dayErrors++
            state.day429++
            state.total429++
            state.last429AtMs = now
            state.last429Sampled = false
            state.lastErrorKind = "RATE_LIMIT"

            if (baseCooldownMs > 0) {
                val levelBefore = state.penaltyLevel
                // 决策链：服务端明确给出时长时以它为准（权威值），否则按画像选自适应策略
                val duration = rateLimit?.retryAfterMs
                    ?: rateLimit?.resetAfterMs?.takeIf { rateLimit.remainingRequests != null && rateLimit.remainingRequests <= 0 }
                    ?: when (profile) {
                        CooldownProfile.CONSERVATIVE -> cooldownFor(baseCooldownMs, levelBefore)
                        CooldownProfile.AGGRESSIVE -> aggressiveCooldown(state, levelBefore)
                    }
                state.coolUntilMs = maxOf(state.coolUntilMs, now + duration.coerceAtMost(MAX_COOLDOWN_MS))
                state.penaltyLevel = (levelBefore + 1).coerceAtMost(COOLDOWN_MULTIPLIERS.lastIndex)
                FileLogger.i(
                    TAG,
                    "限流冷却 provider=$providerId key=${key.masked()} profile=$profile " +
                        "${duration / 1000}s 等级=${state.penaltyLevel}"
                )
            }
            markDirty(providerId)
        }
    }

    /**
     * 分钟窗口型供应商的冷却：从 30s 起步，优先用历史恢复样本（×1.3 安全系数）收敛到真实窗口；
     * 反复 429 时抬下限（10m/30m），既避免冷却中撞回（惩罚延长）又不至于白等太久。
     */
    private fun aggressiveCooldown(state: KeyState, level: Int): Long {
        val floor = when {
            level >= 4 -> 30 * 60_000L
            level >= 2 -> 10 * 60_000L
            else -> 30_000L
        }
        val estimate = state.recoverySamples.minOrNull()
            ?.let { (it * 13 / 10).coerceAtLeast(30_000L) }
        return maxOf(estimate ?: floor, floor).coerceAtMost(2 * 60 * 60_000L)
    }

    /**
     * 网络断流/超时/连接中断：只做短冷却（30s），不累加限流惩罚等级、不标记失效——
     * 这类失败多半与 Key 无关，过度惩罚会把健康 Key 拉黑。
     */
    fun reportNetworkFailure(providerId: String, key: String) {
        synchronized(lock) {
            val now = now()
            val state = stateOf(providerId, key)
            noteRequest(state, now)
            state.lastErrorKind = "NETWORK"
            state.coolUntilMs = maxOf(state.coolUntilMs, now + NETWORK_COOLDOWN_MS)
            markDirty(providerId)
        }
    }

    /** 鉴权失败（401/403）：凭据本身有问题，固定冷却并标记，等待用户处理。 */
    fun reportAuthFailure(providerId: String, key: String, baseCooldownMs: Long) {
        synchronized(lock) {
            val now = now()
            val state = stateOf(providerId, key)
            noteRequest(state, now)
            state.dayErrors++
            state.authFailed = true
            state.lastErrorKind = "AUTH"
            if (baseCooldownMs > 0) {
                state.coolUntilMs = maxOf(
                    state.coolUntilMs,
                    now + AUTH_COOLDOWN_MS.coerceAtMost(MAX_COOLDOWN_MS)
                )
            }
            FileLogger.i(TAG, "凭据失效 provider=$providerId key=${key.masked()}，暂停使用")
            markDirty(providerId)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun cooldownFor(baseMs: Long, level: Int): Long {
        val multiplier = COOLDOWN_MULTIPLIERS[level.coerceIn(0, COOLDOWN_MULTIPLIERS.lastIndex)]
        return (baseMs * multiplier).coerceAtMost(MAX_COOLDOWN_MS)
    }

    private fun normalizeCooldown(state: KeyState, now: Long) {
        if (state.coolUntilMs in 1..now) {
            state.coolUntilMs = 0L
        }
    }

    /**
     * 每次请求终结（成功/限流/鉴权失败）都恰好计一次：适配器只在上报成功或失败时回调，
     * 发起时不单独计数也能保证请求数准确。
     */
    private fun noteRequest(state: KeyState, now: Long) {
        rollDay(state, now)
        state.totalRequests++
        state.dayRequests++
        state.lastRequestAtMs = now
        trimWindow(state, now)
        state.window.addLast(now)
    }

    private fun rollDay(state: KeyState, now: Long) {
        val stamp = dayFormat.format(Date(now))
        if (state.dayStamp != stamp) {
            state.dayStamp = stamp
            state.dayRequests = 0
            state.dayTokensIn = 0L
            state.dayTokensOut = 0L
            state.dayErrors = 0
            state.day429 = 0
        }
    }

    private fun trimWindow(state: KeyState, now: Long) {
        val floor = now - RPM_WINDOW_MS
        while (true) {
            val first = state.window.peekFirst() ?: return
            if (first > floor) return
            state.window.removeFirst()
        }
    }

    private fun snapshotOf(state: KeyState, keyHash: String, now: Long): KeyRuntimeSnapshot {
        normalizeCooldown(state, now)
        trimWindow(state, now)
        rollDayIfNeeded(state, now)
        return KeyRuntimeSnapshot(
            keyHash = keyHash,
            coolingDown = state.coolUntilMs > now,
            coolUntilMs = state.coolUntilMs,
            penaltyLevel = state.penaltyLevel,
            authFailed = state.authFailed,
            recentRpm = state.window.size,
            dayRequests = state.dayRequests,
            dayTokensIn = state.dayTokensIn,
            dayTokensOut = state.dayTokensOut,
            day429 = state.day429,
            totalRequests = state.totalRequests,
            totalSuccess = state.totalSuccess,
            total429 = state.total429,
            lastSuccessAtMs = state.lastSuccessAtMs
        )
    }

    /** 只做日期翻转的轻量版本（避免快照读时把统计清零后又回写不一致）。 */
    private fun rollDayIfNeeded(state: KeyState, now: Long) {
        if (state.dayStamp.isNotEmpty() && state.dayStamp != dayFormat.format(Date(now))) {
            rollDay(state, now)
        }
    }

    /** 取某 provider 的某个 Key 状态，必要时先把该 provider 的账本从磁盘恢复。 */
    private fun stateOf(providerId: String, key: String): KeyState {
        ensureLoaded(providerId)
        return states.getOrPut(stateKey(providerId, key)) { KeyState() }
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000${hashOf(key)}"

    private fun markDirty(providerId: String) {
        dirtyProviders.add(providerId)
        scope.launch {
            saveMutex.withLock {
                val payloads = synchronized(lock) {
                    val ids = dirtyProviders.toList()
                    dirtyProviders.clear()
                    ids.mapNotNull { id -> buildJson(id)?.let { fileFor(id) to it } }
                }
                for ((file, json) in payloads) {
                    runCatching {
                        // 临时文件 + rename 原子替换：避免半写导致账本损坏（统计清零、冷却重置）
                        val tmp = File(file.parentFile, file.name + ".tmp")
                        tmp.writeText(json)
                        if (!tmp.renameTo(file)) {
                            file.writeText(json)
                            tmp.delete()
                        }
                    }.onFailure { FileLogger.w(TAG, "账本写入失败 ${file.name}: ${it.message}") }
                }
            }
        }
    }

    private fun buildJson(providerId: String): String? {
        val prefix = "$providerId\u0000"
        val entries = states.entries.filter { it.key.startsWith(prefix) }
        if (entries.isEmpty()) return null
        val array = JSONArray()
        for ((compositeKey, state) in entries) {
            val keyHash = compositeKey.substring(prefix.length)
            array.put(
                JSONObject().apply {
                    put("h", keyHash)
                    put("cool", state.coolUntilMs)
                    put("level", state.penaltyLevel)
                    put("last429", state.last429AtMs)
                    put("auth", state.authFailed)
                    put("err", state.lastErrorKind ?: JSONObject.NULL)
                    put("day", state.dayStamp)
                    put("dayReq", state.dayRequests)
                    put("dayIn", state.dayTokensIn)
                    put("dayOut", state.dayTokensOut)
                    put("dayErr", state.dayErrors)
                    put("day429", state.day429)
                    put("req", state.totalRequests)
                    put("ok", state.totalSuccess)
                    put("r429", state.total429)
                    put("lastOk", state.lastSuccessAtMs)
                    put("sampled", state.last429Sampled)
                    if (state.recoverySamples.isNotEmpty()) {
                        put("samples", JSONArray(state.recoverySamples.toList()))
                    }
                }
            )
        }
        return JSONObject().apply {
            put("provider", providerId)
            put("version", FILE_VERSION)
            put("keys", array)
        }.toString()
    }

    private fun ensureLoaded(providerId: String) {
        if (providerId in loadedProviders) return
        loadedProviders.add(providerId)
        val file = fileFor(providerId)
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("keys") ?: return
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val keyHash = item.optString("h")
                if (keyHash.isEmpty()) continue
                val state = states.getOrPut("$providerId\u0000$keyHash") { KeyState() }
                state.coolUntilMs = item.optLong("cool", 0L)
                state.penaltyLevel = item.optInt("level", 0).coerceIn(0, COOLDOWN_MULTIPLIERS.lastIndex)
                state.last429AtMs = item.optLong("last429", 0L)
                state.authFailed = item.optBoolean("auth", false)
                state.lastErrorKind = item.optString("err").takeIf { it.isNotEmpty() && it != "null" }
                state.dayStamp = item.optString("day")
                state.dayRequests = item.optInt("dayReq", 0)
                state.dayTokensIn = item.optLong("dayIn", 0L)
                state.dayTokensOut = item.optLong("dayOut", 0L)
                state.dayErrors = item.optInt("dayErr", 0)
                state.day429 = item.optInt("day429", 0)
                state.totalRequests = item.optLong("req", 0L)
                state.totalSuccess = item.optLong("ok", 0L)
                state.total429 = item.optLong("r429", 0L)
                state.lastSuccessAtMs = item.optLong("lastOk", 0L)
                state.last429Sampled = item.optBoolean("sampled", false)
                item.optJSONArray("samples")?.let { samples ->
                    for (sampleIndex in 0 until samples.length()) {
                        samples.optLong(sampleIndex, 0L).takeIf { it > 0 }?.let { state.recoverySamples.addLast(it) }
                    }
                    while (state.recoverySamples.size > RECOVERY_SAMPLE_KEEP) {
                        state.recoverySamples.removeFirst()
                    }
                }
            }
        }.onFailure {
            // 文件损坏不影响可用性：备份坏文件供诊断，按全新账本重建
            runCatching { file.renameTo(File(file.parentFile, file.name + ".bak")) }
            FileLogger.w(TAG, "账本读取失败，已重建（坏文件保留为 .bak）${file.name}: ${it.message}")
        }
    }

    private fun fileFor(providerId: String): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "ledger_${sha256(providerId).take(16)}.json")
    }

    private fun hashOf(key: String): String = sha256(key).take(12)

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun now(): Long = System.currentTimeMillis()

    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "KeyLedger"
        const val DIR_NAME = "keyledger"
        const val FILE_VERSION = 1
        const val RPM_WINDOW_MS = 60_000L
        const val AUTH_COOLDOWN_MS = 60 * 60_000L
        const val NETWORK_COOLDOWN_MS = 30_000L
        const val DECAY_INTERVAL_MS = 30 * 60_000L
        const val MAX_COOLDOWN_MS = 24 * 60 * 60_000L

        /** 冷却放大序列（以用户配置的冷却分钟数为基数）：5m/15m/1h/4h/12h/24h。 */
        val COOLDOWN_MULTIPLIERS = intArrayOf(1, 3, 12, 48, 144, 288)

        /** 恢复样本保留个数：取最小值方向收敛，多个足够。 */
        const val RECOVERY_SAMPLE_KEEP = 5
    }
}