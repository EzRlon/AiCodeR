package com.aicode.feature.settings.data

import com.aicode.feature.settings.domain.model.AIProviderConfig

/** 官方密钥经第三方中转的风险提示：中转站可完整读取并留存密钥。 */
data class RelayKeyRisk(
    val host: String,
    /** 官方密钥所属平台（如 Anthropic / OpenAI）。 */
    val keyOwner: String
)

/**
 * 检测「官方密钥 + 第三方中转地址」组合：中转站（baseUrl 非官方域）能看到请求里的完整
 * 密钥，若用户填的是官方密钥则存在被盗用风险。返回 null 表示无需提醒。
 */
object RelayKeyRiskDetector {

    /** 各平台官方 API 域名（中转站域不在其中）。 */
    private val OFFICIAL_HOSTS = setOf(
        "api.anthropic.com",
        "api.openai.com",
        "generativelanguage.googleapis.com",
        "api.deepseek.com",
        "api.moonshot.cn",
        "api.moonshot.ai",
        "open.bigmodel.cn",
        "dashscope.aliyuncs.com",
        "api.x.ai",
        "api.mistral.ai",
        "api.groq.com",
        "api.siliconflow.cn",
        "ark.cn-beijing.volces.com"
    )

    /** 官方密钥前缀 → 平台名（中转站自发的 key 一般不刻意冒用这些前缀）。 */
    private val OFFICIAL_KEY_PREFIXES = listOf(
        "sk-ant-" to "Anthropic",
        "sk-proj-" to "OpenAI",
        "AIza" to "Gemini",
        "xai-" to "xAI"
    )

    fun detect(provider: AIProviderConfig): RelayKeyRisk? {
        val host = runCatching {
            java.net.URI(provider.baseUrl.trim()).host?.lowercase()
        }.getOrNull() ?: return null
        if (host in OFFICIAL_HOSTS) return null
        val keys = sequenceOf(provider.apiKey).plus(provider.apiKeys.asSequence().map { it.value })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (key in keys) {
            for ((prefix, owner) in OFFICIAL_KEY_PREFIXES) {
                if (key.startsWith(prefix)) return RelayKeyRisk(host, owner)
            }
        }
        return null
    }
}
