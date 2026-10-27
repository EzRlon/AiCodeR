package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningEffort
import java.util.concurrent.ConcurrentHashMap

/**
 * 从服务端 400 文案里学习「这个模型到底支持哪些档位」（apiValue 白名单）。
 *
 * 实测两种错误都会把白名单原文吐出来：
 * - `level "minimal" not supported, valid levels: low, medium, high, xhigh, max`
 * - `Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. Supported values are: 'low', 'medium', …`
 *
 * 学习结果按 `providerId:model` 缓存（同一模型换渠道白名单可能不同），后续请求直接用学到的白名单
 * 钳制档位，不再重复踩同一个 400；不自动重发被拒的请求，避免重复计费。
 */
object ReasoningLearning {

    private val learned = ConcurrentHashMap<String, Set<String>>()

    /** 取某个渠道下该模型学到的档位白名单（没学过返回 null）。 */
    fun recall(providerId: String, model: String): Set<String>? {
        if (providerId.isBlank() || model.isBlank()) return null
        return learned[key(providerId, model)]
    }

    /** 记录一次失败：能解析出白名单就覆盖，解析不出则什么都不做（避免把无关错误当成收窄依据）。 */
    fun observe(providerId: String, model: String, error: Throwable?) {
        val message = error?.message ?: return
        val levels = parseSupportedLevels(message) ?: return
        if (providerId.isBlank() || model.isBlank()) return
        learned[key(providerId, model)] = levels.toSet()
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
            .mapNotNull { ReasoningEffort.fromApiValue(it)?.apiValue }
            .distinct()
        // 防 400 文案误学：tool_choice 之类的错误也会写 "supported values are: 'auto', 'none', 'required'"，
        // 过滤后只剩 none，这不是档位白名单，学进去会把后续真实档位全部钳没。
        return levels.takeIf { list -> list.any { it != ReasoningEffort.NONE.apiValue } }
    }

    /** 把请求档位钳到白名单内：向下取最近档，没有更低的取最低档；白名单不含该值也解析不了时原样返回。 */
    fun clamp(effort: String, learned: Set<String>): String {
        if (effort in learned) return effort
        val requested = ReasoningEffort.fromApiValue(effort) ?: return effort
        val known = learned.mapNotNull { ReasoningEffort.fromApiValue(it) }
        val lower = known.filter { it.ordinal < requested.ordinal }.maxByOrNull { it.ordinal }
        val picked = lower ?: known.minByOrNull { it.ordinal } ?: return effort
        return picked.apiValue
    }

    /** 测试与「切换提供商后重试」用：清掉某渠道或全部学习结果。 */
    fun clear(providerId: String? = null, model: String? = null) {
        if (providerId.isNullOrBlank()) {
            learned.clear()
            return
        }
        if (model.isNullOrBlank()) {
            learned.keys.removeIf { it.startsWith("$providerId:") }
        } else {
            learned.remove(key(providerId, model))
        }
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    /** `valid levels: low, medium, high` */
    private val VALID_LEVELS = Regex("""valid levels?:\s*([a-zA-Z_,\s]+)""")

    /** `Supported values are: 'low', 'medium', 'high'` */
    private val SUPPORTED_VALUES = Regex("""[Ss]upported values are:\s*([a-zA-Z_,'\s"]+)""")
}
