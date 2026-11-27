package com.aicode.feature.settings.domain.model

import com.aicode.core.net.ProxyType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 供应商配置备份编解码：全部字段明文进 JSON（含 API Key、多 Key、代理凭据），
 * 导出文件由用户自行保管。格式版本 [FORMAT_VERSION]，解析按字段名逐个取值、
 * 缺失回默认，保证旧版本导出的文件在新版可导入。
 */
object ProviderBackupCodec {

    const val FORMAT_VERSION = 1
    const val FILE_EXTENSION = "json"
    const val MIME_TYPE = "application/json"

    fun encode(providers: List<AIProviderConfig>): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "providers",
            JSONArray().apply { providers.forEach { put(encodeProvider(it)) } }
        )
        return root.toString(2)
    }

    /** 非法文件（非 JSON / 缺 providers 数组）抛 [IllegalArgumentException]，调用方给用户报错。 */
    fun decode(text: String): List<AIProviderConfig> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的供应商备份文件", e)
        }
        val array = root.optJSONArray("providers")
            ?: throw IllegalArgumentException("备份文件缺少 providers 数据")
        return (0 until array.length()).mapNotNull { i ->
            runCatching { decodeProvider(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun encodeProvider(p: AIProviderConfig): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("type", p.type.name)
        put("apiKey", p.apiKey)
        put("multiKeyEnabled", p.multiKeyEnabled)
        put("apiKeys", JSONArray().apply {
            p.apiKeys.forEach { k ->
                put(
                    JSONObject().put("value", k.value).put("weight", k.weight)
                        .put("baseUrlOverride", k.baseUrlOverride)
                )
            }
        })
        put("keyRotationStrategy", p.keyRotationStrategy.name)
        put("keyFailoverThreshold", p.keyFailoverThreshold)
        put("keyCooldownMinutes", p.keyCooldownMinutes)
        put("keySwitchStatusCodes", JSONArray(p.keySwitchStatusCodes))
        put("keyNetworkFailoverEnabled", p.keyNetworkFailoverEnabled)
        put("baseUrl", p.baseUrl)
        put("defaultModel", p.defaultModel)
        put("models", JSONArray(p.models))
        put("selectedModel", p.selectedModel)
        put("isEnabled", p.isEnabled)
        put("useFullUrl", p.useFullUrl)
        put("useResponseApi", p.useResponseApi)
        put("anthropicCacheBreakpoints", p.anthropicCacheBreakpoints)
        put("openaiChatCacheKey", p.openaiChatCacheKey)
        put("dashboardScriptPath", p.dashboardScriptPath)
        put("dashboardRefreshInterval", p.dashboardRefreshInterval)
        put("customHeaders", JSONObject().apply { p.customHeaders.forEach { (k, v) -> put(k, v) } })
        put("sortOrder", p.sortOrder)
        put("proxyEnabled", p.proxyEnabled)
        put("proxyType", p.proxyType.name)
        put("proxyHost", p.proxyHost)
        put("proxyPort", p.proxyPort)
        put("proxyUsername", p.proxyUsername)
        put("proxyPassword", p.proxyPassword)
        put("scriptParams", JSONObject().apply { p.scriptParams.forEach { (k, v) -> put(k, v) } })
    }

    private fun decodeProvider(o: JSONObject): AIProviderConfig {
        val apiKeys = o.optJSONArray("apiKeys")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let {
                    ProviderKey(
                        value = it.optString("value"),
                        weight = it.optInt("weight", DEFAULT_KEY_WEIGHT),
                        baseUrlOverride = it.optString("baseUrlOverride")
                    )
                }
            }
        } ?: emptyList()

        fun optStringMap(key: String): Map<String, String> =
            o.optJSONObject(key)?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            } ?: emptyMap()

        return AIProviderConfig(
            id = o.getString("id"),
            name = o.optString("name"),
            type = runCatching { ProviderType.valueOf(o.getString("type")) }
                .getOrDefault(ProviderType.OPENAI),
            apiKey = o.optString("apiKey"),
            multiKeyEnabled = o.optBoolean("multiKeyEnabled", false),
            apiKeys = apiKeys,
            keyRotationStrategy = runCatching { KeyRotationStrategy.valueOf(o.optString("keyRotationStrategy")) }
                .getOrDefault(KeyRotationStrategy.SEQUENTIAL),
            keyFailoverThreshold = o.optInt("keyFailoverThreshold", 2),
            keyCooldownMinutes = o.optInt("keyCooldownMinutes", 5),
            keySwitchStatusCodes = o.optJSONArray("keySwitchStatusCodes")?.let { a ->
                (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optInt(i) }
            } ?: emptyList(),
            keyNetworkFailoverEnabled = o.optBoolean("keyNetworkFailoverEnabled", true),
            baseUrl = o.optString("baseUrl"),
            defaultModel = o.optString("defaultModel"),
            models = o.optJSONArray("models")?.let { a ->
                (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optString(i) }
            } ?: emptyList(),
            selectedModel = o.optString("selectedModel"),
            isEnabled = o.optBoolean("isEnabled", true),
            useFullUrl = o.optBoolean("useFullUrl", false),
            useResponseApi = o.optBoolean("useResponseApi", false),
            anthropicCacheBreakpoints = o.optBoolean("anthropicCacheBreakpoints", true),
            openaiChatCacheKey = o.optBoolean("openaiChatCacheKey", false),
            dashboardScriptPath = o.optString("dashboardScriptPath"),
            dashboardRefreshInterval = o.optInt("dashboardRefreshInterval", 5),
            customHeaders = optStringMap("customHeaders"),
            sortOrder = o.optInt("sortOrder", -1),
            proxyEnabled = o.optBoolean("proxyEnabled", false),
            proxyType = runCatching { ProxyType.valueOf(o.optString("proxyType")) }
                .getOrDefault(ProxyType.HTTP),
            proxyHost = o.optString("proxyHost"),
            proxyPort = o.optInt("proxyPort", 0),
            proxyUsername = o.optString("proxyUsername"),
            proxyPassword = o.optString("proxyPassword"),
            scriptParams = optStringMap("scriptParams")
        ).sanitized()
    }
}
