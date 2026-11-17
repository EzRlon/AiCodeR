package com.aicode.feature.agent.domain.engine

import com.aicode.feature.settings.domain.model.ProviderType

/**
 * CLI 引擎使用 App 供应商模型时的模型串规则（UI 生成与会话存储、桥内 provider 注入共用同一实现）。
 *
 * - Claude/Codex：模型串 = 裸模型名（`--model` / `-m` 原样透传），供应商靠 providerId 对齐 Key/baseURL。
 * - OpenCode：官方端点供应商复用内置 provider 名（`openai/`、`anthropic/` 前缀 + 环境变量 Key）；
 *   自定义端点供应商注入 OPENCODE_CONFIG_CONTENT 的 provider 段，键为 [providerKey]。
 */
object CliModelRef {

    /** OpenCode 内置 provider 名（models.dev 体系），凭据经标准环境变量透传。 */
    const val OPENCODE_OPENAI_KEY = "openai"
    const val OPENCODE_ANTHROPIC_KEY = "anthropic"

    /** 该供应商在 OpenCode 侧的模型引用串（`-m` 参数值）。 */
    fun opencodeModelRef(providerName: String, providerId: String, type: ProviderType, baseUrl: String, model: String): String {
        val prefix = if (isOfficialBase(type, baseUrl)) {
            when (type) {
                ProviderType.ANTHROPIC -> OPENCODE_ANTHROPIC_KEY
                else -> OPENCODE_OPENAI_KEY
            }
        } else {
            providerKey(providerName, providerId)
        }
        return "$prefix/$model"
    }

    /**
     * OpenCode 自定义 provider 段的键：ASCII 安全、含 provider id 哈希后缀避免同名冲突；
     * 与 [opencodeModelRef] 必须同源，否则 `-m` 与注入段对不上。
     */
    fun providerKey(providerName: String, providerId: String): String {
        val base = providerName
            .map { if (it.isLetterOrDigit() && it.code < 128) it.lowercaseChar() else '_' }
            .joinToString("")
            .trim('_')
            .take(24)
            .ifBlank { "app" }
        return "${base}_${(providerId.hashCode() and 0xFFFF).toString(16)}"
    }

    /** 官方端点判定（与三座桥的透传口径一致）：空 base 或官方域名视为官方。 */
    fun isOfficialBase(type: ProviderType, baseUrl: String): Boolean {
        val base = baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        if (base.isEmpty()) return true
        return when (type) {
            ProviderType.ANTHROPIC -> base.contains("api.anthropic.com")
            else -> base.contains("api.openai.com")
        }
    }

    /**
     * 旧版会话存的模型串可能带「供应商名或id/」前缀；仅当前缀命中该供应商时剥离。
     * 不能盲目 substringAfter('/')：z-ai/glm-5.3-flash、deepseek-ai/ 等是真实含斜杠的模型 id。
     */
    fun bareModelName(model: String, providerName: String?, providerId: String?): String {
        val owners = listOfNotNull(providerName, providerId).filter { it.isNotBlank() }
        return if (owners.any { model.startsWith("$it/") }) model.substringAfter('/') else model
    }
}
