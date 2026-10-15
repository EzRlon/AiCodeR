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
    val sourceHash: String = ""
)

/** 翻译缓存的 key，如 "SKILL:pdf-toolkit" / "MCP:ac.inference.sh/mcp"。 */
fun StoreItem.storeTranslationKey(): String = "${kind.name}:$id"

/** 需要翻译的源文指纹：名称与描述组成的稳定哈希。 */
fun StoreItem.storeTranslationSourceHash(): String = "$name\u0000$description".hashCode().toString()

/**
 * 商店条目翻译的本地缓存存储（存于 app 缓存目录，可随时被系统清理）。
 * key 为「类型:条目id」复合键；只在界面语言为中文时写入与展示。
 */
@Singleton
class StoreTranslationStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun all(): Map<String, StoreTranslation> = mutex.withLock {
        withContext(Dispatchers.IO) { read() }
    }

    suspend fun putAll(entries: Map<String, StoreTranslation>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            map.putAll(entries)
            write(map)
        }
    }

    private fun read(): Map<String, StoreTranslation> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, StoreTranslation>>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            FileLogger.w(TAG, "读取商店翻译缓存失败", it)
            emptyMap()
        }
    }

    private fun write(map: Map<String, StoreTranslation>) {
        runCatching {
            file().writeText(json.encodeToString(map), Charsets.UTF_8)
        }.onFailure {
            FileLogger.w(TAG, "写入商店翻译缓存失败", it)
        }
    }

    private fun file(): File = File(context.cacheDir, FILE_NAME)

    private companion object {
        const val TAG = "StoreTranslationStore"
        const val FILE_NAME = "store-translations.json"
    }
}
