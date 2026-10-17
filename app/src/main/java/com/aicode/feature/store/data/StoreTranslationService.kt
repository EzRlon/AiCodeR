package com.aicode.feature.store.data

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProviderFactory
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 商店条目 AI 翻译：用用户配置的默认模型把「技能 / MCP」条目的名称与描述翻译成简体中文。
 *
 * [translateBatch] 一次处理一批（调用方按 ≤[MAX_BATCH_SIZE] 条分组）：结果带源文指纹返回，
 * 由调用方落缓存；模型未配置、Key 不可用、请求失败或解析失败时返回空 map（静默，界面维持原文）。
 */
@Singleton
class StoreTranslationService @Inject constructor(
    private val providerFactory: AIProviderFactory,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val store: StoreTranslationStore
) {
    companion object {
        /** 单批最大条数（调用方按此分组调用）。 */
        const val MAX_BATCH_SIZE = 8

        private const val TAG = "StoreTranslationService"

        /** 单条描述进入提示词前的截断长度。 */
        private const val DESCRIPTION_MAX_CHARS = 400

        private val FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val THINKING_TAG_REGEX = Regex("(?s)&lt;thinking&gt;.*?&lt;/thinking&gt;", RegexOption.IGNORE_CASE)
        private val CODE_FENCE_LINE_REGEX = Regex("(?s)```.*?\\n", RegexOption.IGNORE_CASE)

        /** 翻译提示词（硬编码，不走 prompts 目录，属商店页内部协议，与文件无关）。 */
        private const val SYSTEM_PROMPT = """你是翻译引擎。输入是一个 JSON 数组，每个元素包含在线商店条目的 id、name（名称）与 description（描述）。
把每条目的 name 和 description 翻译成简体中文；技术术语与专有名词（如 MCP、API、CLI、GitHub、npm）保留原文。
只输出 JSON 数组本身，元素格式为 {"id": "原样保留输入的 id", "name_zh": "名称的中文翻译", "description_zh": "描述的中文翻译"}；
不要 markdown 代码围栏，不要解释，不要输出任何其他文字。"""
    }

    private val json = Json { isLenient = true }

    /** 单条模型解析结果。 */
    private data class ParsedEntry(val id: String, val nameZh: String, val descriptionZh: String)

    /**
     * 翻译一批商店条目。返回 key（[storeTranslationKey]）→ 翻译；不再请求已缓存且源文未变的条目。
     */
    suspend fun translateBatch(items: List<StoreItem>): Map<String, StoreTranslation> {
        if (items.isEmpty()) return emptyMap()
        val providerId = defaultModelSettingsRepository.getDefaultProviderId()
        val model = defaultModelSettingsRepository.getDefaultModel()
        if (providerId.isBlank() || model.isBlank()) return emptyMap()
        val config = aiProviderRepository.getProviderById(providerId) ?: return emptyMap()
        if (!config.isEnabled || !config.hasUsableApiKey) return emptyMap()
        // 双保险：已缓存且源文未变的条目不再请求（调用方漏过滤时也不重复花 token）。
        val cached = store.all()
        val pending = items.filter { item ->
            val entry = cached[item.storeTranslationKey()]
            entry == null || entry.sourceHash != item.storeTranslationSourceHash()
        }
        if (pending.isEmpty()) return emptyMap()

        val provider = providerFactory.create(config.copy(selectedModel = model), sessionId = null)
        val response = provider.complete(
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(AgentMessage.UserMessage(content = buildInput(pending))),
            tools = emptyList()
        )
        val parsed = parseTranslations(response.content)
        if (parsed == null) {
            FileLogger.w(TAG, "翻译结果解析失败：${response.content.take(200)}")
            return emptyMap()
        }
        val byKey = pending.associateBy { it.storeTranslationKey() }
        return parsed.mapNotNull { entry ->
            val item = byKey[entry.id] ?: return@mapNotNull null
            entry.id to StoreTranslation(
                nameZh = entry.nameZh.trim(),
                descriptionZh = entry.descriptionZh.trim(),
                sourceHash = item.storeTranslationSourceHash()
            )
        }.toMap()
    }

    /** 批输入 JSON：[{"id","name","description"}]。 */
    private fun buildInput(items: List<StoreItem>): String = buildJsonArray {
        items.forEach { item ->
            addJsonObject {
                put("id", item.storeTranslationKey())
                put("name", item.name)
                put("description", item.description.take(DESCRIPTION_MAX_CHARS))
            }
        }
    }.toString()

    /**
     * 解析模型返回的翻译数组。两级容错：先按原样解析，失败后清洗（剥 markdown 围栏 /
     * 提取首尾方括号之间的片段）再解析；仍失败返回 null。
     */
    private fun parseTranslations(raw: String): List<ParsedEntry>? =
        parseArrayOrNull(raw.trim()) ?: parseArrayOrNull(sanitizeResponse(raw))

    private fun parseArrayOrNull(text: String): List<ParsedEntry>? = runCatching {
        json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ParsedEntry(
                id = id,
                nameZh = (obj["name_zh"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                descriptionZh = (obj["description_zh"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            )
        }
    }.getOrNull()

    /**
     * 剥掉 markdown 围栏；无围栏时退回提取首尾方括号之间的片段。
     * 部分思考型模型（如 deepseek-reasoner）会在 JSON 前附加思考段落，这里再去除
     * `&lt;thinking&gt;…&lt;/thinking&gt;` 等标记段，只保留 JSON 数组本身。
     */
    private fun sanitizeResponse(text: String): String {
        FENCE_REGEX.find(text)?.let { return it.groupValues[1].trim() }
        val withoutThinking = text
            .replace(THINKING_TAG_REGEX, "")
            .replace(CODE_FENCE_LINE_REGEX, "")
            .trim()
        val start = withoutThinking.indexOf('[')
        val end = withoutThinking.lastIndexOf(']')
        return if (start in 0 until end) withoutThinking.substring(start, end + 1) else withoutThinking
    }
}
