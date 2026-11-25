package com.aicode.feature.settings.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 子程序崩溃上报的母程序侧落库：按包名累计崩溃次数与最近一次详情。
 *
 * 存储在母程序私有目录（filesDir/subprogram-crashes.json）而非工作区注册表——
 * 广播接收发生在 App 进程，跨工作区统一按包名聚合，避免容器/宿主路径映射问题。
 */
object SubprogramCrashStore {

    data class Stats(
        val count: Int,
        val lastAtMs: Long,
        val lastType: String,
        val lastMessage: String,
    )

    private const val FILE_NAME = "subprogram-crashes.json"
    private const val MAX_MESSAGE = 500
    private val lock = Any()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 记录一次崩溃；包名空串直接忽略（防御畸形广播）。 */
    fun record(
        context: Context,
        packageName: String,
        crashType: String,
        crashMessage: String,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val all = readMap(file(context)).toMutableMap()
            val old = all[packageName]
            all[packageName] = Stats(
                count = (old?.count ?: 0) + 1,
                lastAtMs = atMs,
                lastType = crashType.take(200),
                lastMessage = crashMessage.take(MAX_MESSAGE),
            )
            writeMap(file(context), all)
        }
    }

    fun load(context: Context): Map<String, Stats> = synchronized(lock) {
        readMap(file(context))
    }

    private fun readMap(file: File): Map<String, Stats> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val out = mutableMapOf<String, Stats>()
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
                out[key] = Stats(
                    count = o.optInt("count", 0),
                    lastAtMs = o.optLong("lastAtMs", 0L),
                    lastType = o.optString("lastType"),
                    lastMessage = o.optString("lastMessage"),
                )
            }
            out
        }.getOrElse { emptyMap() }
    }

    private fun writeMap(file: File, all: Map<String, Stats>) {
        runCatching {
            val root = JSONObject()
            for ((pkg, s) in all) {
                root.put(
                    pkg,
                    JSONObject()
                        .put("count", s.count)
                        .put("lastAtMs", s.lastAtMs)
                        .put("lastType", s.lastType)
                        .put("lastMessage", s.lastMessage)
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }
}
