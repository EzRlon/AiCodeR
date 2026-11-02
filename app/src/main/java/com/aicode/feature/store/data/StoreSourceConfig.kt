package com.aicode.feature.store.data

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个商店数据源。
 *
 * [trusted] 决定该源的条目能否「一键安装」：第三方源默认 false，安装前必须走详情页确认，
 * 因为无法保证其内容安全。内置源由 App 维护，标记为 true。
 */
@Serializable
data class StoreSource(
    /** 唯一标识，用于去重与配置定位。 */
    val id: String,
    /** 展示名，出现在条目卡片与来源标签上。 */
    val name: String,
    /** 接口基地址，如 `https://claudemarket.ai`。 */
    val baseUrl: String,
    /** 优先级：同 id 条目冲突时数值大者胜出。 */
    val priority: Int = 0,
    /** 是否可信；不可信源安装前强制二次确认。 */
    val trusted: Boolean = false,
    /** 是否启用。 */
    val enabled: Boolean = true,
    /** 是否为内置源；内置源不可删除。 */
    val builtIn: Boolean = false,
)

/**
 * 商店多源配置。
 *
 * 默认只含两个内置源（claudemarket 技能、官方 MCP Registry）。用户可追加自定义源，
 * 配置存 filesDir，随 App 数据一起保留。
 */
@Singleton
class StoreSourceConfig @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val file get() = File(context.filesDir, FILE_NAME)

    private val _sources = MutableStateFlow(DEFAULT_SOURCES)
    val sources: StateFlow<List<StoreSource>> = _sources.asStateFlow()

    /** 首次读取磁盘配置；失败时保留内置默认，不影响商店可用性。 */
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock
            runCatching {
                val saved = json.decodeFromString<List<StoreSource>>(file.readText())
                // 内置源以代码为准：用户无法篡改其 baseUrl 与可信标记
                val merged = DEFAULT_SOURCES.map { def ->
                    saved.firstOrNull { it.id == def.id }?.let { def.copy(enabled = it.enabled) } ?: def
                } + saved.filter { s -> DEFAULT_SOURCES.none { it.id == s.id } }
                _sources.value = merged
            }.onFailure { FileLogger.w(TAG, "读取商店源配置失败: ${it.message}") }
        }
    }

    /** 追加自定义源；id 重复时覆盖同 id 的非内置源。 */
    suspend fun add(source: StoreSource) = update { current ->
        val cleaned = source.copy(builtIn = false)
        current.filterNot { it.id == cleaned.id } + cleaned
    }

    /** 删除自定义源；内置源不可删。 */
    suspend fun remove(id: String) = update { current ->
        current.filterNot { it.id == id && !it.builtIn }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = update { current ->
        current.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private suspend fun update(transform: (List<StoreSource>) -> List<StoreSource>) {
        mutex.withLock {
            val next = transform(_sources.value)
            _sources.value = next
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(json.encodeToString(next)) }
                    .onFailure { FileLogger.w(TAG, "保存商店源配置失败: ${it.message}") }
            }
        }
    }

    /** 按优先级排序的启用源；同 id 冲突时高优先级在前，调用方取首个即可。 */
    fun enabledSources(): List<StoreSource> =
        _sources.value.filter { it.enabled }.sortedByDescending { it.priority }

    private companion object {
        const val TAG = "StoreSourceConfig"
        const val FILE_NAME = "store_sources.json"

        val DEFAULT_SOURCES = listOf(
            StoreSource(
                id = "claudemarket",
                name = "ClaudeMarket",
                baseUrl = "https://claudemarket.ai",
                priority = 100,
                trusted = true,
                builtIn = true,
            ),
            StoreSource(
                id = "mcp-registry",
                name = "MCP Registry",
                baseUrl = "https://registry.modelcontextprotocol.io",
                priority = 90,
                trusted = true,
                builtIn = true,
            ),
        )
    }
}
