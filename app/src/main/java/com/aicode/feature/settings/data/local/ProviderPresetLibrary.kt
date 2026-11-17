package com.aicode.feature.settings.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 内置/动态 provider 的展示预设：包含 name/type/baseUrl 及推荐与推广扩展属性。 */
@Serializable
data class ProviderPreset(
    val id: String,
    val name: String,
    /** 协议类型：OPENAI / ANTHROPIC / GEMINI。 */
    val type: String,
    /** Base URL，选中后自动填充，可在编辑页修改。 */
    val baseUrl: String,
    /** 关联的模型 id 列表（可选，选中后导入为可用模型）。 */
    val models: List<String> = emptyList(),
    /** 是否推荐/置顶 */
    val isRecommended: Boolean = false,
    /** 徽标/标签文案（如「推荐」、「赞助」） */
    val badge: String? = null,
    /** 供应商官网或推广注册链接 */
    val websiteUrl: String? = null,
    /** 特性/福利标签列表（如 ["注册送10元"]） */
    val tags: List<String> = emptyList()
)

/**
 * 官方及自定义 provider 预设库。
 *
 * 自建分支：数据源已内置化——只读 assets 内置 providers.json，不再从官方仓库动态拉取
 * （官方源的预设没有自建分支的 models 预填字段，联网拉取会把它盖掉）。如需恢复远端
 * 动态源，参照官方版补回 RepoDataFetcher 双轨逻辑（远端优先 + 磁盘缓存 + assets 兜底）。
 */
object ProviderPresetLibrary {
    const val PROVIDERS_ASSET_FILE_NAME = "providers.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ProviderPreset>? = null

    /** UI 专用快速同步读取：内存缓存 -> assets 内置文件。纯本地 IO，秒级返回。 */
    fun loadOfficial(context: Context): List<ProviderPreset> {
        cached?.let { return it }
        val fallback = loadFromAssets(context)
        cached = fallback
        return fallback
    }

    private fun loadFromAssets(context: Context): List<ProviderPreset> = runCatching {
        val text = context.assets.open(PROVIDERS_ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        val list = json.decodeFromString<List<ProviderPreset>>(text)
        list.sortedWith(
            compareByDescending<ProviderPreset> { it.isRecommended }
                .thenBy { it.name.lowercase() }
        )
    }.getOrDefault(emptyList())
}
