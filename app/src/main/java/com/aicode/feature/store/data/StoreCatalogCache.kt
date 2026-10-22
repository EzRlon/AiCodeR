package com.aicode.feature.store.data

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
 * 在线商店目录的本地持久缓存（stale-while-revalidate）：进店先秒出上次目录，再后台刷新替换。
 * 存 filesDir（持久）而非 cacheDir——目录缓存被清会导致每次进店都白屏等网络，违背缓存初衷。
 * payload 存 DTO 序列化结果（CmSkillsResponse / McpRegistryResponse / 在线专家团列表）。
 */
@Singleton
class StoreCatalogCache @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** 缓存条目：payload 为源 DTO 的 JSON 序列化结果；savedAt 仅供排查。 */
    @Serializable
    private data class Entry(val savedAt: Long, val payload: String)

    @Serializable
    private data class OnlineTeamsPayload(val teams: List<TeamStoreItem>)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun readSkills(): CmSkillsResponse? = read(SKILL_FILE) { decode(it) }
    suspend fun saveSkills(resp: CmSkillsResponse) = write(SKILL_FILE, json.encodeToString(resp))

    suspend fun readMcp(): McpRegistryResponse? = read(MCP_FILE) { decode(it) }
    suspend fun saveMcp(resp: McpRegistryResponse) = write(MCP_FILE, json.encodeToString(resp))

    suspend fun readOnlineTeams(): List<TeamStoreItem>? =
        read(TEAMS_FILE) { decode<OnlineTeamsPayload>(it)?.teams }

    suspend fun saveOnlineTeams(teams: List<TeamStoreItem>) =
        write(TEAMS_FILE, json.encodeToString(OnlineTeamsPayload(teams)))

    /** 读缓存并在线程内完成 transform（DTO 解析不落主线程）。 */
    private suspend fun <T> read(name: String, transform: (String) -> T?): T? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val f = file(name)
            if (!f.isFile) return@withContext null
            runCatching {
                json.decodeFromString<Entry>(f.readText(Charsets.UTF_8))
                    .payload.takeIf { it.isNotBlank() }?.let(transform)
            }.getOrElse { e ->
                FileLogger.w(TAG, "读取目录缓存失败: $name", e)
                null
            }
        }
    }

    /** 临时文件＋rename 保证原子性：进程死亡不残留截断 JSON。 */
    private suspend fun write(name: String, payload: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val target = file(name)
                val tmp = File(context.filesDir, "$name.tmp")
                tmp.writeText(
                    json.encodeToString(Entry(System.currentTimeMillis(), payload)),
                    Charsets.UTF_8
                )
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    target.writeText(
                        json.encodeToString(Entry(System.currentTimeMillis(), payload)),
                        Charsets.UTF_8
                    )
                }
            }.onFailure { e -> FileLogger.w(TAG, "写入目录缓存失败: $name", e) }
        }
    }

    private inline fun <reified T> decode(payload: String): T? = runCatching {
        json.decodeFromString<T>(payload)
    }.getOrElse { e ->
        FileLogger.w(TAG, "目录缓存解析失败，按无缓存处理", e)
        null
    }

    private fun file(name: String) = File(context.filesDir, name)

    private companion object {
        const val TAG = "StoreCatalogCache"
        const val SKILL_FILE = "store-catalog-skill.json"
        const val MCP_FILE = "store-catalog-mcp.json"
        const val TEAMS_FILE = "store-catalog-teams.json"
    }
}
