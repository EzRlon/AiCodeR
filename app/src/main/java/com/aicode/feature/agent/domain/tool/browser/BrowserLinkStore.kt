package com.aicode.feature.agent.domain.tool.browser

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 书签 / 浏览历史的一条链接记录（列表序：最新在前）。 */
@Serializable
data class BrowserLink(
    val title: String,
    val url: String,
    val time: Long
)

/**
 * 书签 / 浏览历史的轻量持久化：整表 JSON 存 SharedPreferences。
 * 数据量小（历史在写入侧截断），整读整写足够。
 */
internal class BrowserLinkStore(context: Context, prefsName: String) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(): List<BrowserLink> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BrowserLink>>(raw) }.getOrDefault(emptyList())
    }

    fun save(links: List<BrowserLink>) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(links)).apply()
        }
    }

    private companion object {
        const val KEY = "links_json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
