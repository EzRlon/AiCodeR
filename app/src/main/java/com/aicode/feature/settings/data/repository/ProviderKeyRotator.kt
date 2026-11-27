package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderKey
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 Key 切换的结果，供调用方改写凭据并提示「已切换到第 N 个 Key」。 */
data class KeySwitchResult(
    val newKey: String,
    /** 1 起的新 Key 序号。 */
    val newIndex: Int,
    val total: Int,
    /** 新 Key 的专属 Base URL 覆盖；空 = 跟随供应商全局 baseUrl。 */
    val newBaseUrlOverride: String = ""
)

/** 失败的归因，决定用哪套冷却策略。 */
enum class KeyFailureKind {
    /** 429 / quota 类：按惩罚序列放大冷却，可用响应头精修。 */
    RATE_LIMIT,

    /** 401/403：凭据失效，固定冷却并标记，等待用户更换。 */
    AUTH,

    /** 网络断流/超时/连接中断：短冷却后即可再用（不按限流惩罚）。 */
    NETWORK
}

/** 选 Key 的结果。全冷却时不再返回 null，而是带着「最早何时恢复」交给上层决定等多久。 */
sealed interface KeyPick {
    data class Available(
        val key: String,
        val index: Int,
        val total: Int,
        /** 该 Key 的专属 Base URL 覆盖；空 = 跟随供应商全局 baseUrl。 */
        val baseUrlOverride: String = ""
    ) : KeyPick

    /**
     * 所有 Key 都在冷却。上层可按 [earliestRecoveryAtMs] 等待（有上限），
     * 或直接给用户一个带恢复时间的提示——不要静默失败。
     */
    data class AllCooling(
        val earliestRecoveryAtMs: Long,
        val total: Int
    ) : KeyPick
}

/**
 * 多 Key 轮换器 v2：决定某个 provider 在某条会话上该用哪个 API Key，并在 Key 不可用时切到下一个。
 *
 * 与 v1 的差异：
 * 1. 冷却状态与用量统计交给 [KeyLedger]，**跨进程持久化**——重启后不会失忆回头去撞正在锁定的 Key；
 * 2. 冷却时长自适应（429 逐级放大到 24h），并优先采用服务端 Retry-After / reset 头；
 * 3. 全部冷却时返回 [KeyPick.AllCooling] + 恢复时间，由上层等待或给出明确提示，而不是直接报错。
 *
 * **会话粘性**依然保留：服务端 prompt 缓存按 API Key 隔离，同一会话逐请求换 Key 会让缓存命中率
 * 归零、成本和首字延迟一起恶化。因此只在会话首次取 Key、或当前 Key 被判为不可用时才换。
 */
@Singleton
class ProviderKeyRotator @Inject constructor(
    private val ledger: KeyLedger
) {

    /** 由最近一次 [pickKey] 记录的 provider Key 配置，供失败上报时读取候选与冷却基数。 */
    private data class KeySetup(
        val keys: List<ProviderKey>,
        val strategy: KeyRotationStrategy,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId → Key 值 → 平滑加权轮询的当前权重（运行时内存态）。 */
    private val weightedState = mutableMapOf<String, MutableMap<String, Long>>()

    /** providerId → 最近一次选中的 Key，供余额查询等旁路请求取「当前活动 Key」。 */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → 该会话粘住的 Key。会话数量无上界，用访问序 LRU 兜住内存，
     * 被淘汰的老会话下次请求重新分配 Key 即可，没有正确性问题。
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * 取 [config] 在 [sessionId] 上该用的 Key。
     *
     * 返回 [KeyPick.AllCooling] 时调用方可以选择等待（有上限）或直接提示用户最早恢复时间，
     * 不要当作「没有 Key」处理。
     */
    fun pickKey(config: AIProviderConfig, sessionId: String?): KeyPick {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return KeyPick.AllCooling(0L, 0)
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val bound = bindKey?.let { sessionKeys[it] }
            if (bound != null && keys.any { it.value == bound } && !ledger.isCoolingDown(config.id, bound)) {
                val boundKey = keys.first { it.value == bound }
                return KeyPick.Available(bound, keys.indexOfFirst { it.value == bound } + 1, keys.size, boundKey.baseUrlOverride)
            }

            val chosen = pick(config.id, keys, config.keyRotationStrategy, exclude = emptySet())
            if (chosen == null) {
                val earliest = ledger.earliestRecoveryMs(config.id, keys.map { it.value })
                return KeyPick.AllCooling(earliest, keys.size)
            }
            if (bindKey != null) sessionKeys[bindKey] = chosen.value
            lastSelected[config.id] = chosen.value
            ledger.recordSelected(config.id, chosen.value)
            return KeyPick.Available(chosen.value, keys.indexOfFirst { it.value == chosen.value } + 1, keys.size, chosen.baseUrlOverride)
        }
    }

    /**
     * 兼容旧调用点：返回 Key 或 null（全冷却时）。
     * 新代码请优先用 [pickKey] 以拿到恢复时间。
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?): String? =
        (pickKey(config, sessionId) as? KeyPick.Available)?.key

    /** 该 provider 当前选中 Key 的专属 Base URL 覆盖；空 = 跟随全局。 */
    fun activeBaseUrlOverride(config: AIProviderConfig, sessionId: String?): String? =
        (pickKey(config, sessionId) as? KeyPick.Available)?.baseUrlOverride

    /**
     * 只读地取该 provider 当前活动的 Key：不建会话绑定、不推进轮询游标。
     * 供余额查询这类旁路请求使用——它们要反映「聊天正在用哪个 Key」，而不该干扰轮换节奏。
     */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && keys.any { it.value == last } && !ledger.isCoolingDown(config.id, last)) return last
            // 内存无记录（如刚重启）：回退到持久化的「上次选中 Key」，让旁路请求看到真实的活动 Key
            val persisted = ledger.lastSelectedHash(config.id)
                ?.let { hash -> keys.firstOrNull { ledger.keyHash(it.value) == hash } }
            if (persisted != null && !ledger.isCoolingDown(config.id, persisted.value)) return persisted.value
            return keys.firstOrNull { !ledger.isCoolingDown(config.id, it.value) }?.value ?: keys.first().value
        }
    }

    /** 这组 Key（可排除已试过的）最早何时恢复；全部健康返回 0。 */
    fun earliestRecovery(config: AIProviderConfig, exclude: Set<String> = emptySet()): Long {
        val keys = config.effectiveApiKeys.map { it.value }.filter { it !in exclude }
        if (keys.isEmpty()) return 0L
        return ledger.earliestRecoveryMs(config.id, keys)
    }

    /** 当前 provider 的每个 Key 的运行时画像，供设置页「运行状态」卡片展示。 */
    fun snapshots(config: AIProviderConfig): List<KeyRuntimeSnapshot> =
        ledger.snapshots(config.id, config.effectiveApiKeys.map { it.value })

    /** 按明文 Key 值取运行时画像（编辑页里用户可能尚未保存配置）。 */
    fun snapshots(providerId: String, keyValues: List<String>): List<KeyRuntimeSnapshot> =
        ledger.snapshots(providerId, keyValues)

    /**
     * 每次真正发出请求前调用，用于统计。
     */
    fun reportRequest(providerId: String, key: String) {
        ledger.reportRequest(providerId, key)
    }

    /** 请求成功：记录 token 用量与响应头信息。 */
    fun reportSuccess(
        providerId: String,
        key: String,
        tokensIn: Long = 0L,
        tokensOut: Long = 0L,
        rateLimit: RateLimitInfo? = null
    ) {
        ledger.reportSuccess(providerId, key, tokensIn, tokensOut, rateLimit)
    }

    /**
     * 上报一次可归因于 [key] 的失败：把 [key] 记入账本（冷却/统计），切到下一个可用 Key 并重绑会话。
     *
     * 返回 null 表示没有可切换的候选（通常是全部冷却）——此时调用方应通过 [pickKey] 拿到恢复时间
     * 后选择等待或给出明确提示，而不是直接报「全部失败」。
     *
     * [triedKeys] 为本次请求已试过的 Key（含 [key] 自身），避免同一次请求内重复试同一个 Key。
     */
    fun reportFailure(
        providerId: String,
        sessionId: String?,
        key: String,
        triedKeys: Set<String> = emptySet(),
        kind: KeyFailureKind = KeyFailureKind.RATE_LIMIT,
        rateLimit: RateLimitInfo? = null,
        profile: CooldownProfile = CooldownProfile.CONSERVATIVE
    ): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId]
            if (setup == null || setup.keys.none { it.value == key }) return null

            // 先把真实发生的失败记进账本：无论后面有没有候选，这个 Key 确实被限流/失效了
            when (kind) {
                KeyFailureKind.RATE_LIMIT -> ledger.reportRateLimit(providerId, key, rateLimit, setup.cooldownMillis, profile)
                KeyFailureKind.AUTH -> ledger.reportAuthFailure(providerId, key, setup.cooldownMillis)
                KeyFailureKind.NETWORK -> ledger.reportNetworkFailure(providerId, key)
            }

            val next = pick(providerId, setup.keys, setup.strategy, exclude = triedKeys + key) ?: return null
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next.value }
            lastSelected[providerId] = next.value
            ledger.recordSelected(providerId, next.value)
            val index = setup.keys.indexOfFirst { it.value == next.value } + 1
            FileLogger.i(
                TAG,
                "Key 切换 provider=$providerId ${key.masked()} → ${next.value.masked()} (第 $index/${setup.keys.size} 个，原因=$kind)"
            )
            return KeySwitchResult(
                newKey = next.value,
                newIndex = index,
                total = setup.keys.size,
                newBaseUrlOverride = next.baseUrlOverride
            )
        }
    }

    /**
     * 按策略挑一个可选 Key：排除 [exclude]（本次已试过）、处于冷却中的、权重 ≤ 0 的。
     *
     * ROUND_ROBIN 在健康候选内做负载均衡优化：惩罚等级低者优先、最近一分钟请求少者优先，
     * 避免总把流量压在同一个「看起来健康但其实快到限」的 Key 上。SEQUENTIAL 保持严格顺序语义。
     */
    private fun pick(
        providerId: String,
        keys: List<ProviderKey>,
        strategy: KeyRotationStrategy,
        exclude: Set<String>
    ): ProviderKey? {
        val participants = keys.filter {
            it.weight > 0 && it.value !in exclude && !ledger.isCoolingDown(providerId, it.value)
        }
        if (participants.isEmpty()) return null
        return when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> sequentialAfterLastSelected(providerId, keys, participants)
            KeyRotationStrategy.ROUND_ROBIN -> weightedRoundRobin(providerId, participants)
        }
    }

    /**
     * SEQUENTIAL 选 Key：内存里有本进程的最近选中记录时保持严格顺序语义——从第一个健康 Key
     * 开始，不因持久化记录而漂移；内存无记录但账本持久化了「上次选中 Key」（进程重启接续）时，
     * 从它的下一个位置环形找第一个健康参与者，避免重启后永远从头薅第一个 Key。环形扫描只在
     * 参与者（已滤冷却）内命中，刚限速的 Key 自动跳过；找不到持久化 Key 或候选时回退第一个。
     */
    private fun sequentialAfterLastSelected(
        providerId: String,
        keys: List<ProviderKey>,
        participants: List<ProviderKey>
    ): ProviderKey {
        if (lastSelected[providerId] != null) return participants.first()
        val lastHash = ledger.lastSelectedHash(providerId) ?: return participants.first()
        val lastIdx = keys.indexOfFirst { ledger.keyHash(it.value) == lastHash }
        if (lastIdx < 0) return participants.first()
        for (step in 1..keys.size) {
            val candidate = keys[(lastIdx + step) % keys.size]
            participants.firstOrNull { it.value == candidate.value }?.let { return it }
        }
        return participants.first()
    }

    /**
     * 平滑加权轮询（SWRR）：每轮给各候选加上自身权重、取当前权重最大者，再给选中者减去候选总权重。
     * 无需按权重展开成虚拟列表，权重可到百万级；分布均匀且不会一直落在同一个 Key 上。
     *
     * 同权重时的次序微调（惩罚等级/近一分钟请求量）通过给候选打一个极小的权重折扣实现，
     * 不会破坏 SWRR 的长期比例性质。
     */
    private fun weightedRoundRobin(providerId: String, participants: List<ProviderKey>): ProviderKey {
        val current = weightedState.getOrPut(providerId) { mutableMapOf() }
        val snapshots = ledger.snapshots(providerId, participants.map { it.value })
        val loadFactor = HashMap<String, Long>(participants.size)
        for ((index, candidate) in participants.withIndex()) {
            val snapshot = snapshots.getOrNull(index)
            // 惩罚等级与近一分钟请求量各扣一点权重，让更健康的 Key 略占优势（最多扣 20% 权重）
            val penalty = (snapshot?.penaltyLevel ?: 0) * 1L
            val load = (snapshot?.recentRpm ?: 0) / 10L
            val discount = (penalty + load).coerceAtMost(candidate.weight.toLong() * 2 / 10)
            loadFactor[candidate.value] = (candidate.weight.toLong() - discount).coerceAtLeast(1L)
        }

        var total = 0L
        var best = participants.first()
        var bestWeight = Long.MIN_VALUE
        for (candidate in participants) {
            val adjusted = loadFactor[candidate.value] ?: candidate.weight.toLong()
            val next = (current[candidate.value] ?: 0L) + adjusted
            current[candidate.value] = next
            total += adjusted
            if (next > bestWeight) {
                bestWeight = next
                best = candidate
            }
        }
        current[best.value] = (current[best.value] ?: 0L) - total
        return best
    }

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** 日志脱敏：只留末 4 位，避免把完整 Key 写进日志文件。 */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}