package com.aicode.feature.agent.domain.provider

import android.content.Context
import androidx.core.content.edit

/**
 * [ReasoningLearning] 的落盘后端。把「哪些档位被服务端拒过」持久化，避免每次冷启动都重新踩同一个 400。
 *
 * 数据量极小（每个 `providerId:model` 至多 7 个档位），整份快照读写的开销可忽略，
 * 故不引入增量写接口。
 */
interface ReasoningLearningStore {

    /** 读回全部记录：key = "providerId:model"，value = 档位 apiValue -> 被拒时间戳（epoch 毫秒）。 */
    fun read(): Map<String, Map<String, Long>>

    /** 整体覆盖写入当前快照。 */
    fun write(snapshot: Map<String, Map<String, Long>>)
}

/** 基于应用私有 SharedPreferences 的实现；档位拼成 `level:timestamp` 逗号串存储。 */
class SharedPrefsReasoningLearningStore(context: Context) : ReasoningLearningStore {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): Map<String, Map<String, Long>> {
        val result = mutableMapOf<String, Map<String, Long>>()
        preferences.all.forEach { (entryKey, raw) ->
            val levels = (raw as? String)?.let(::decodeLevels) ?: return@forEach
            if (levels.isNotEmpty()) result[entryKey] = levels
        }
        return result
    }

    override fun write(snapshot: Map<String, Map<String, Long>>) {
        val encoded = snapshot.mapValues { (_, levels) -> encodeLevels(levels) }
        preferences.edit {
            clear()
            encoded.forEach { (entryKey, value) -> putString(entryKey, value) }
        }
    }

    private fun encodeLevels(levels: Map<String, Long>): String =
        levels.entries.joinToString(",") { "${it.key}:${it.value}" }

    private fun decodeLevels(raw: String): Map<String, Long> =
        raw.split(',')
            .mapNotNull { token ->
                val parts = token.split(':')
                if (parts.size != 2) return@mapNotNull null
                val timestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
                parts[0].takeIf { it.isNotBlank() }?.let { it to timestamp }
            }
            .toMap()

    private companion object {
        const val PREFS_NAME = "reasoning_learning"
    }
}
