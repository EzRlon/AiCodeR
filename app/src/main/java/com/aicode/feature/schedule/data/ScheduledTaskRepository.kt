package com.aicode.feature.schedule.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.core.util.FileLogger
import com.aicode.feature.schedule.domain.ScheduledTask
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.scheduleDataStore by preferencesDataStore(
    name = "scheduled_tasks",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 定时任务的持久化：整份任务列表以 JSON 存在 DataStore 里。
 *
 * 任务数量少、读写整份比拆 key 简单，故用单个字符串键。字段增删靠 [Json] 的
 * `ignoreUnknownKeys` 容忍旧数据，缺失字段回落到默认值，未知枚举值由 `coerceInputValues` 兜底。
 *
 * 三个开关都要在：整份列表是一个字符串，任一条目解码失败就会让全部任务消失，
 * 并在下一次写回时被永久清掉。
 */
@Singleton
class ScheduledTaskRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ScheduledTaskRepository"
        val TASKS_KEY = stringPreferencesKey("scheduled_tasks_json")
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    val tasksFlow: Flow<List<ScheduledTask>> = context.scheduleDataStore.data.map { prefs ->
        prefs[TASKS_KEY]?.let { raw ->
            runCatching { json.decodeFromString<List<ScheduledTask>>(raw) }
                .onFailure { FileLogger.w(TAG, "解析定时任务失败，按空列表继续", it) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** 按 id 取一条任务；不存在返回 null。 */
    suspend fun get(id: String): ScheduledTask? = tasksFlow.first().firstOrNull { it.id == id }

    /** 新增或覆盖一条任务（按 id 匹配）；已有任务的执行结果字段会被保留。 */
    suspend fun upsert(task: ScheduledTask) {
        context.scheduleDataStore.edit { prefs ->
            val current = decode(prefs[TASKS_KEY])
            val existing = current.firstOrNull { it.id == task.id }
            // UI 持有的快照可能落后于 Worker 刚写入的结果（如切开关时正好跑完一次），
            // 整份写回会把 lastRun* 回退成旧值，故按 id 保留现有执行结果。
            val merged = if (existing != null) {
                task.copy(
                    lastRunAt = existing.lastRunAt,
                    lastExitCode = existing.lastExitCode,
                    lastOutput = existing.lastOutput
                )
            } else {
                task
            }
            val next = current.filterNot { it.id == task.id } + merged
            prefs[TASKS_KEY] = json.encodeToString(next)
        }
    }

    suspend fun delete(id: String) {
        context.scheduleDataStore.edit { prefs ->
            val next = decode(prefs[TASKS_KEY]).filterNot { it.id == id }
            prefs[TASKS_KEY] = json.encodeToString(next)
        }
    }

    /**
     * 批量导入（备份还原用）：按 id 覆盖现有任务，备份里没有的任务保留。
     * 与 [upsert] 不同，这里忠实还原备份快照（含上次执行结果），不做执行结果的本地保留。
     */
    suspend fun upsertAll(tasks: List<ScheduledTask>) {
        if (tasks.isEmpty()) return
        context.scheduleDataStore.edit { prefs ->
            val current = decode(prefs[TASKS_KEY])
            val incoming = tasks.associateBy { it.id }
            val merged = current.map { existing -> incoming[existing.id] ?: existing } +
                tasks.filterNot { task -> current.any { it.id == task.id } }
            prefs[TASKS_KEY] = json.encodeToString(merged)
        }
    }

    /** 写回一次执行结果（保留任务其余字段）。 */
    suspend fun updateLastRun(id: String, exitCode: Int?, output: String) {
        context.scheduleDataStore.edit { prefs ->
            val current = decode(prefs[TASKS_KEY])
            val next = current.map { task ->
                if (task.id == id) {
                    task.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastExitCode = exitCode,
                        lastOutput = output
                    )
                } else {
                    task
                }
            }
            prefs[TASKS_KEY] = json.encodeToString(next)
        }
    }

    /** 仅更新启用开关（Worker 内部自动停用 ONCE/过期任务用，避免整份写回回退执行结果）。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.scheduleDataStore.edit { prefs ->
            val current = decode(prefs[TASKS_KEY])
            val next = current.map { task ->
                if (task.id == id) task.copy(enabled = enabled) else task
            }
            prefs[TASKS_KEY] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<ScheduledTask> =
        raw?.let {
            runCatching { json.decodeFromString<List<ScheduledTask>>(it) }
                .onFailure { e -> FileLogger.w(TAG, "解析定时任务失败，按空列表继续", e) }
                .getOrDefault(emptyList())
        } ?: emptyList()
}
