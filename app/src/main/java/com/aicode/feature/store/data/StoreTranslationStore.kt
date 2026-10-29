package com.aicode.feature.store.data

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 商店条目的中文翻译（标题与描述的中英对照缓存条目）。 */
@Serializable
data class StoreTranslation(
    val nameZh: String = "",
    val descriptionZh: String = "",
    /** 翻译所依据源文的指纹（见 [storeTranslationSourceHash]）；源文变化时缓存失效重翻。 */
    val sourceHash: String = "",
    /** 翻译所依据的源文名称：安装后的本地条目（技能 / MCP / 专家团）按名称回查译文时用。 */
    val sourceName: String = ""
)

/** 翻译缓存的 key，如 "SKILL:pdf-toolkit" / "MCP:ac.inference.sh/mcp"。 */
fun StoreItem.storeTranslationKey(): String = "${kind.name}:$id"

/** 专家团条目与 [StoreItem] 共用同一翻译缓存键体系（EXPERT:文件名，与 ViewModel 映射一致）。 */
fun TeamStoreItem.storeTranslationKey(): String = "${StoreKind.EXPERT.name}:$fileName"

/** 需要翻译的源文指纹：名称与描述组成的稳定哈希。 */
fun StoreItem.storeTranslationSourceHash(): String =
    StoreTranslationStore.sourceHashOf(name, description)

/**
 * 商店条目翻译的本地缓存存储（存于 app 持久目录 filesDir，不随系统清理丢失）。
 * key 为「类型:条目id」复合键；只在界面语言为中文时写入与展示。
 *
 * 除商店列表外，安装后的本地条目（设置页技能 / MCP / 专家团卡片、会话的专家团选择弹窗）
 * 也按 [find] 取译文，故缓存同时保留一份内存快照供界面同步查询。
 */
@Singleton
class StoreTranslationStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** 磁盘内容的只读快照；[all] 读过之后才有内容，界面据此同步取译文。 */
    @Volatile
    private var snapshot: Map<String, StoreTranslation> = emptyMap()

    suspend fun all(): Map<String, StoreTranslation> = mutex.withLock {
        withContext(Dispatchers.IO) { read() }
    }

    /** [all] 已加载时的同步快照（可能在加载前为空）。 */
    fun cached(): Map<String, StoreTranslation> = snapshot

    suspend fun putAll(entries: Map<String, StoreTranslation>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            map.putAll(entries)
            write(map)
        }
    }

    /**
     * 把商店条目的译文迁到安装后的本地条目键（如 "SKILL:pdf-toolkit" → "SKILL:pdf-toolkit"、
     * "EXPERT:foo.md" → "EXPERT:online-foo.md"）。目标已有译文时不覆盖。
     */
    suspend fun migrate(fromKey: String, toKey: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (fromKey == toKey) return@withContext
            val map = read()
            val translation = map[fromKey] ?: return@withContext
            if (map.containsKey(toKey)) return@withContext
            write(map + (toKey to translation))
        }
    }

    private fun read(): Map<String, StoreTranslation> {
        val f = file()
        val result = if (!f.isFile) {
            emptyMap()
        } else {
            runCatching {
                json.decodeFromString<Map<String, StoreTranslation>>(f.readText(Charsets.UTF_8))
            }.getOrElse {
                FileLogger.w(TAG, "读取商店翻译缓存失败", it)
                emptyMap()
            }
        }
        snapshot = result
        return result
    }

    private fun write(map: Map<String, StoreTranslation>) {
        runCatching {
            file().writeText(json.encodeToString(map), Charsets.UTF_8)
        }.onSuccess {
            snapshot = map
        }.onFailure {
            FileLogger.w(TAG, "写入商店翻译缓存失败", it)
        }
    }

    private fun file(): File {
        // 迁移仅在 mutex 锁内被调（read()/write() 只从 all()/putAll()/migrate() 进入）：
        // 此不变量靠调用约定维持，新增直接调 file() 的入口必须同样持锁。
        val f = File(context.filesDir, FILE_NAME)
        // 旧版缓存在 cacheDir（会被系统清理导致重复翻译）：首次读到时迁移到持久目录
        if (!f.isFile) {
            val legacy = File(context.cacheDir, FILE_NAME)
            if (legacy.isFile) runCatching { legacy.copyTo(f, overwrite = true) }
        }
        return f
    }

    companion object {
        private const val TAG = "StoreTranslationStore"
        private const val FILE_NAME = "store-translations.json"

        /** 源文指纹（与 [StoreItem.storeTranslationSourceHash] 同一算法）。 */
        fun sourceHashOf(name: String, description: String): String =
            "$name\u0000$description".hashCode().toString()

        /**
         * 按序匹配译文：本地键 → 源文指纹 → 源文名称。
         *
         * 后两步兜底「安装早于译文迁移」的存量条目：商店条目与本地条目的名称 / 描述通常同源，
         * 译文因此仍能命中；在线专家团的描述会被截断改写，故再以名称兜底。
         *
         * 兜底只在同类型条目内搜（取 [key] 的类型前缀）：技能与专家团可能同名，
         * 不限定类型会把别类条目的译文贴到当前卡片上。
         */
        fun find(
            map: Map<String, StoreTranslation>,
            key: String,
            name: String,
            description: String
        ): StoreTranslation? {
            map[key]?.let { return it }
            val prefix = key.substringBefore(':') + ":"
            val sameKind = map.filterKeys { it.startsWith(prefix) }
            val hash = sourceHashOf(name, description)
            sameKind.values.firstOrNull { it.sourceHash == hash }?.let { return it }
            return sameKind.values.firstOrNull {
                it.sourceName.isNotBlank() && it.sourceName.equals(name, ignoreCase = true)
            }
        }
    }
}
