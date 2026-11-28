package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningEffort
import java.util.concurrent.ConcurrentHashMap

/**
 * 从服务端 400 文案里学习「这个模型的哪些档位会被拒」，用于后续请求自动收紧档位。
 *
 * **为什么记黑名单而不是白名单**：白名单是「只能用这些档位」的结论——一条被截断或异常的
 * 400 文案就能把可用档位一次砍到只剩一档；黑名单是「除了这些档位」的结论，最坏只是多避开
 * 几档，不会凭空发明服务端不认的档位，失败模式有上界。
 *
 * **三重兜底，保证一次错误学习不可能长期生效**：
 * 1. 元数据优先——各 adapter 里 models.dev 的 `reasoning_options` 是权威，学习结果只用于在
 *    其允许范围内进一步收紧，永不反过来覆盖元数据；
 * 2. 过期——每条被拒记录带时间戳，超过 [TTL_MILLIS] 即视为陈旧证据自动失效；
 * 3. 可清除——[clear] 支持按渠道 / 模型清理。
 *
 * 默认纯内存（重启即清，不产生任何持久副作用）；仅在 [init] 注入落盘后端后才跨会话保留。
 * 实测两种错误都会把白名单原文吐出来：
 * - `level "minimal" not supported, valid levels: low, medium, high, xhigh, max`
 * - `Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. Supported values are: 'low', 'medium', …`
 *
 * 记录按 `providerId:model` 隔离（同一模型换渠道档位支持可能不同），不自动重发被拒的请求，
 * 避免重复计费。
 */
object ReasoningLearning {

    /**
     * 被拒记录的存活时长。取较短值：服务端抽风、中转站临时改档位造成的假拒绝能快速自愈；
     * 真实不支持的档位最多每 [TTL_MILLIS] 重新踩一次 400，代价可接受。
     */
    const val TTL_MILLIS: Long = 2 * 60 * 60 * 1000L

    /** key = "providerId:model"，value = 档位 apiValue -> 被拒时间戳（epoch 毫秒）。 */
    private val rejected = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /** 落盘后端；null 表示纯内存（默认）。 */
    @Volatile
    private var store: ReasoningLearningStore? = null

    /**
     * 注入落盘后端并把已有记录读回内存（已过期的条目在取用时会被剔除）；重复调用安全。
     * 传 null 恢复纯内存。
     */
    fun init(store: ReasoningLearningStore?) {
        this.store = store
        if (store == null) return
        val loaded = runCatching { store.read() }.getOrNull() ?: return
        loaded.forEach { (entryKey, levels) ->
            if (levels.isNotEmpty()) rejected[entryKey] = ConcurrentHashMap(levels)
        }
    }

    /**
     * 记录一次失败：把该模型「白名单之外」的档位标记为被拒（带时间戳）；解析不出白名单则什么都不做
     * （避免把无关错误当成收窄依据）。
     */
    fun observe(
        providerId: String,
        model: String,
        error: Throwable?,
        now: Long = System.currentTimeMillis()
    ) {
        val message = error?.message ?: return
        val supported = parseSupportedLevels(message) ?: return
        if (providerId.isBlank() || model.isBlank()) return
        val supportedSet = supported.toSet()
        val denied = ReasoningEffort.entries.map { it.apiValue }.filter { it !in supportedSet }
        if (denied.isEmpty()) return
        val entry = rejected.getOrPut(key(providerId, model)) { ConcurrentHashMap() }
        denied.forEach { entry[it] = now }
        persist()
    }

    /** 该渠道 / 模型当前被证伪的档位（已剔除过期条目）。 */
    fun rejectedLevels(
        providerId: String,
        model: String,
        now: Long = System.currentTimeMillis()
    ): Set<String> {
        if (providerId.isBlank() || model.isBlank()) return emptySet()
        val entryKey = key(providerId, model)
        val entry = rejected[entryKey] ?: return emptySet()
        val expired = entry.filterValues { now - it >= TTL_MILLIS }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { entry.remove(it) }
            if (entry.isEmpty()) rejected.remove(entryKey)
            persist()
        }
        return entry.keys.toSet()
    }

    /**
     * 自愈：某档位**调用成功**即证明它可用，把它从被拒集合移除。
     * 这是「错误学习不会长期生效」的关键——服务端抽风或中转站临时改档位造成的假拒绝，
     * 在模型下次证明能用时立刻消失，无需等 [TTL_MILLIS] 过期。
     */
    fun markUsed(
        providerId: String,
        model: String,
        effort: String?,
        now: Long = System.currentTimeMillis()
    ) {
        val level = ReasoningEffort.fromApiValue(effort) ?: return
        if (providerId.isBlank() || model.isBlank()) return
        val entryKey = key(providerId, model)
        val entry = rejected[entryKey] ?: return
        if (entry.remove(level.apiValue) != null) {
            if (entry.isEmpty()) rejected.remove(entryKey)
            persist()
        }
    }

    /** 按被拒记录收紧请求档位：请求档位被证伪时降到最近可用档，否则原样返回。 */
    fun tighten(
        effort: String,
        providerId: String,
        model: String,
        now: Long = System.currentTimeMillis()
    ): String {
        val denied = rejectedLevels(providerId, model, now)
        if (denied.isEmpty() || effort !in denied) return effort
        val allowed = ReasoningEffort.entries.map { it.apiValue }.filter { it !in denied }.toSet()
        return clamp(effort, allowed)
    }

    /**
     * 从错误文案里解析受支持的档位（apiValue 列表，保持出现顺序）；
     * 返回 null 表示文案里没有可用的白名单。
     */
    fun parseSupportedLevels(message: String?): List<String>? {
        if (message.isNullOrBlank()) return null
        val raw = VALID_LEVELS.findAll(message).map { it.groupValues[1] }.firstOrNull()
            ?: SUPPORTED_VALUES.findAll(message).map { it.groupValues[1] }.firstOrNull()
            ?: return null
        val levels = raw.split(',', ';', '|')
            .map { it.trim().trim('\'', '"', '.', '`') }
            .mapNotNull { ReasoningEffort.fromApiValue(it) }
            .distinct()
            .sortedBy { it.ordinal }
            .map { it.apiValue }
        // 防 400 文案误学：tool_choice 之类的错误也会写 "supported values are: 'auto', 'none', 'required'"，
        // 过滤后只剩 none，这不是档位白名单，学进去会把后续真实档位全部钳没。
        return levels.takeIf { list -> list.any { it != ReasoningEffort.NONE.apiValue } }
    }

    /** 纯函数：在 [allowed] 集合内把请求档位钳到最近档（向下优先，没有更低的取最低档）。 */
    fun clamp(effort: String, allowed: Set<String>): String {
        if (effort in allowed) return effort
        val requested = ReasoningEffort.fromApiValue(effort) ?: return effort
        val known = allowed.mapNotNull { ReasoningEffort.fromApiValue(it) }
        val lower = known.filter { it.ordinal < requested.ordinal }.maxByOrNull { it.ordinal }
        val picked = lower ?: known.minByOrNull { it.ordinal } ?: return effort
        return picked.apiValue
    }

    /** 测试与「切换提供商后重试」用：清掉某渠道或全部学习结果。 */
    fun clear(providerId: String? = null, model: String? = null) {
        if (providerId.isNullOrBlank()) {
            rejected.clear()
        } else if (model.isNullOrBlank()) {
            rejected.keys.removeIf { it.startsWith("$providerId:") }
        } else {
            rejected.remove(key(providerId, model))
        }
        persist()
    }

    /** 把内存快照写回落盘后端（未注入后端时为 no-op）。 */
    private fun persist() {
        val target = store ?: return
        val snapshot = rejected.mapValues { (_, levels) -> levels.toMap() }
        runCatching { target.write(snapshot) }
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    /** `valid levels: low, medium, high` */
    private val VALID_LEVELS = Regex("""valid levels?:\s*([a-zA-Z_,\s]+)""")

    /** `Supported values are: 'low', 'medium', 'high'` */
    private val SUPPORTED_VALUES = Regex("""[Ss]upported values are:\s*([a-zA-Z_,'\s"]+)""")
}
