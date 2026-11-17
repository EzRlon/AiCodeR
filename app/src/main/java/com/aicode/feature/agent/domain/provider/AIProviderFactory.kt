package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 独立的 [AIProvider] 实例工厂。
 *
 * 按 [AIProviderConfig] 创建全新的、独立的 Provider 实例，用于上下文压缩、标题生成、
 * 独立验收、商店翻译等单次请求场景，完全不占用或修改主对话所用的 Provider 单例。
 */
@Singleton
class AIProviderFactory @Inject constructor(
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val keyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository
) {

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    suspend fun create(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（会话内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }
}
