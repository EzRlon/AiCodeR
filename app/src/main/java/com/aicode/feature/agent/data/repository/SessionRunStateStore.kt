package com.aicode.feature.agent.data.repository

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 跨进程的「回合运行态」：进程被杀时来不及执行 finally 的 RUNNING 记录，会在下次启动
 * 被识别为 INTERRUPTED，聊天页据此显示「上次回合被中断，可继续」。
 *
 * 不新增 Room 迁移：状态量很小，用 filesDir 下的 JSON 文件 + 原子替换即可。
 */
@Singleton
class SessionRunStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SessionRunStateStore"
        private const val FILE_NAME = "session-run-state.json"
        private const val RUNNING = "RUNNING"
        private const val INTERRUPTED = "INTERRUPTED"
    }

    @Serializable
    data class RunRecord(
        val state: String,
        val startedAt: Long,
        val preview: String
    )

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun markRunning(sessionId: String, preview: String) = update { map ->
        map + (sessionId to RunRecord(RUNNING, System.currentTimeMillis(), preview.take(80)))
    }

    /** 正常完成/失败/用户主动停止都走这里；进程被杀时不会执行，从而保留 RUNNING 供下次识别。 */
    suspend fun markIdle(sessionId: String) = update { map -> map - sessionId }

    /** 冷启动：把所有 RUNNING 转成 INTERRUPTED 并返回，供 UI 显示继续入口。 */
    suspend fun recoverInterrupted(): Map<String, RunRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val current = read()
                val interrupted = current.filterValues { it.state == RUNNING }
                    .mapValues { it.value.copy(state = INTERRUPTED) }
                if (interrupted.isNotEmpty()) {
                    write(current + interrupted)
                }
                interrupted
            }.onFailure { FileLogger.w(TAG, "恢复中断回合失败", it) }
                .getOrDefault(emptyMap())
        }
    }

    private suspend fun update(transform: (Map<String, RunRecord>) -> Map<String, RunRecord>) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { write(transform(read())) }
                    .onFailure { FileLogger.w(TAG, "写入回合运行态失败", it) }
            }
        }

    private fun read(): Map<String, RunRecord> = runCatching {
        if (!file.exists() || file.length() == 0L) emptyMap()
        else json.decodeFromString<Map<String, RunRecord>>(file.readText())
    }.getOrDefault(emptyMap())

    private fun write(map: Map<String, RunRecord>) {
        if (map.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(map))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
