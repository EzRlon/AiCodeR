package com.aicode.feature.agent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.handoffStateDataStore by preferencesDataStore(name = "handoff_state_prefs")

/**
 * 交接暂停态的落盘存储。
 *
 * 为什么要落盘：暂停态原本只存在于 ViewModel 的内存 map 里，进程被杀（后台回收、手动划掉）后
 * 原本因上下文超硬阈值而暂停的会话会恢复成可发送，`enqueueAgentRequest` 的拦截随之失效——
 * 用户回到 App 一发消息就直接顶到上下文上限。同理「已选继续」的抑制集合与「暂不」计数丢失后，
 * 要么反复弹提醒，要么该抑制的不再抑制。
 *
 * 存的是三份小表（sessionId → 值），体积可忽略；读写都只在交接评估与用户点击路径上发生，不在热路径。
 */
@Singleton
class HandoffStateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HandoffStateRepository"
        val STATE_KEY = stringPreferencesKey("handoff_state_json")
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    /** 落盘快照：会话 id → 触发暂停时的上下文占用百分比 / 已点暂不次数。 */
    @Serializable
    data class State(
        val paused: Map<String, Int> = emptyMap(),
        val continued: List<String> = emptyList(),
        val dismissedCounts: Map<String, Int> = emptyMap(),
        val goalEmptyTurns: Map<String, Int> = emptyMap()
    )

    /** 读取一次全部状态；解析失败按空状态继续（暂停态丢失只会让会话恢复可发送，不应阻断启动）。 */
    suspend fun load(): State = runCatching {
        val raw = context.handoffStateDataStore.data.first()[STATE_KEY] ?: return@runCatching State()
        json.decodeFromString<State>(raw)
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取交接状态失败，按空状态继续", e)
        State()
    }

    suspend fun save(state: State) {
        runCatching {
            context.handoffStateDataStore.edit { prefs ->
                prefs[STATE_KEY] = json.encodeToString(state)
            }
        }.onFailure { e ->
            FileLogger.w(TAG, "保存交接状态失败", e)
        }
    }
}
