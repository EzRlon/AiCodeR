package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.CooldownProfile
import com.aicode.feature.settings.data.repository.KeyFailureKind
import com.aicode.feature.settings.data.repository.KeyPick
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.RateLimitBody
import com.aicode.feature.settings.data.repository.RateLimitHeaders
import com.aicode.feature.settings.data.repository.RateLimitInfo
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

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
        val initialPick = awaitHealthyKey(config, sessionId)
        provider.apiKey = initialPick.key
        // 每 Key 可覆盖 Base URL：初始装配就按选中 Key 生效（空覆盖则用全局）。
        provider.baseUrl = initialPick.baseUrlOverride.ifBlank { config.baseUrl }
        // 成功请求的用量/限速头记账：Key 账本据此维护冷却、剩余额度预判与统计。
        provider.usageReporter = { key, tokensIn, tokensOut, headers ->
            keyRotator.reportSuccess(
                config.id,
                key,
                tokensIn,
                tokensOut,
                headers?.let { RateLimitHeaders.parse(it) }
            )
        }
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    val codeMatched = error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)
                    // 多 Key 时网络断流/超时也自动切下一个 Key（用户可显式关掉保命中率）；
                    // triedKeys 去重保证同一请求至多每个 Key 试一遍，不会死循环。
                    val networkFailed = config.keyNetworkFailoverEnabled && error.isNetworkFailure()
                    if (!codeMatched && !networkFailed) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(
                            config.id,
                            sessionId,
                            failedKey,
                            triedKeys,
                            kind = if (!codeMatched && networkFailed) {
                                KeyFailureKind.NETWORK
                            } else {
                                keyFailureKindOf(error)
                            },
                            rateLimit = extractRateLimit(error),
                            profile = cooldownProfileOf(config.baseUrl)
                        )
                        if (switched != null) {
                            KeySwitchOutcome(
                                switched.newKey,
                                switched.newIndex,
                                switched.total,
                                effectiveBaseUrl = switched.newBaseUrlOverride.ifBlank { config.baseUrl }
                            )
                        } else {
                            // 没有健康候选：区分「全部冷却」（等最早恢复后重试）与「没得等」（直接报全部失败）。
                            // 不能把控制权交回退避重试——那会对冷却中的 Key 继续发请求，
                            // 在 NVIDIA 这类账号上会让锁定时间指数延长。
                            val earliest = keyRotator.earliestRecovery(config, triedKeys + failedKey)
                            if (earliest <= 0L) {
                                throw AllKeysFailedException(
                                    "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                    error
                                )
                            }
                            waitForRecovery(config, earliest)
                            when (val retryPick = keyRotator.pickKey(config, sessionId)) {
                                is KeyPick.Available -> {
                                    FileLogger.i(
                                        WAIT_TAG,
                                        "「${config.name}」全 Key 冷却，等待后恢复：改用第 ${retryPick.index}/${retryPick.total} 个 Key"
                                    )
                                    KeySwitchOutcome(
                                        retryPick.key,
                                        retryPick.index,
                                        retryPick.total,
                                        effectiveBaseUrl = retryPick.baseUrlOverride.ifBlank { config.baseUrl }
                                    )
                                }
                                is KeyPick.AllCooling -> throw AllKeysFailedException(
                                    allCoolingMessage(config, retryPick.earliestRecoveryAtMs),
                                    error
                                )
                            }
                        }
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

    /**
     * 取一个健康的 Key；全部冷却时等最早恢复的那个（有上限），到点后重选一次。
     * 恢复不了就带「最早恢复时间」报错，而不是含糊的「稍后重试」。
     */
    private suspend fun awaitHealthyKey(config: AIProviderConfig, sessionId: String?): KeyPick.Available {
        when (val pick = keyRotator.pickKey(config, sessionId)) {
            is KeyPick.Available -> return pick
            is KeyPick.AllCooling -> {
                if (pick.total <= 0) {
                    throw IllegalStateException("「${config.name}」没有可用的 API Key")
                }
                if (pick.earliestRecoveryAtMs <= 0L) {
                    throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
                }
                waitForRecovery(config, pick.earliestRecoveryAtMs)
                when (val retry = keyRotator.pickKey(config, sessionId)) {
                    is KeyPick.Available -> {
                        FileLogger.i(
                            WAIT_TAG,
                            "「${config.name}」全 Key 冷却，等待后恢复：改用第 ${retry.index}/${retry.total} 个 Key"
                        )
                        return retry
                    }
                    is KeyPick.AllCooling -> throw IllegalStateException(
                        allCoolingMessage(config, retry.earliestRecoveryAtMs)
                    )
                }
            }
        }
    }

    /** 等到恢复点再错开一点随机抖动，避免多会话同时醒来集中打同一个刚恢复的 Key。 */
    private suspend fun waitForRecovery(config: AIProviderConfig, earliestRecoveryAtMs: Long) {
        val cap = runCatching { generalSettingsRepository.allCoolingWaitMs() }
            .getOrDefault(ALL_COOLING_WAIT_CAP_MS)
        val waitMs = (earliestRecoveryAtMs - System.currentTimeMillis()).coerceIn(0L, cap)
        if (waitMs <= 0L) return
        val jitter = Random.nextLong(200L, 1_200L)
        FileLogger.i(
            WAIT_TAG,
            "「${config.name}」全部 Key 冷却中，等待恢复 ${waitMs}ms（最早 ${formatClock(earliestRecoveryAtMs)}）"
        )
        delay(waitMs + jitter)
    }

    private fun allCoolingMessage(config: AIProviderConfig, earliestRecoveryAtMs: Long): String {
        val remainMin = ((earliestRecoveryAtMs - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999) / 60_000
        return "「${config.name}」的全部 Key 都在限流冷却中，最早 ${formatClock(earliestRecoveryAtMs)} 恢复（约 $remainMin 分钟后）"
    }

    private fun formatClock(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    /**
     * 从异常链里的 HttpException 提取限速信号：响应头（OpenAI/Anthropic 类）优先，
     * 错误体补充（Gemini 的 RetryDelay、DeepSeek/中转站的 JSON 字段与消息文本）。
     * 头缺失时错误体往往拿得到——这是「广泛适配」的主力信号。
     */
    private fun extractRateLimit(error: Throwable): RateLimitInfo? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (current is HttpException) {
                val raw = current.response()?.raw()
                val headerInfo = raw?.headers?.let { RateLimitHeaders.parse(it.toList()) } ?: RateLimitInfo()
                val bodyInfo = runCatching { raw?.peekBody(BODY_PEEK_BYTES)?.string() }
                    .getOrNull()
                    ?.let { RateLimitBody.parse(it) }
                    ?: RateLimitInfo()
                val merged = RateLimitInfo(
                    retryAfterMs = headerInfo.retryAfterMs ?: bodyInfo.retryAfterMs,
                    remainingRequests = headerInfo.remainingRequests ?: bodyInfo.remainingRequests,
                    resetAfterMs = headerInfo.resetAfterMs,
                    remainingTokens = headerInfo.remainingTokens
                )
                return if (merged.isEmpty) null else merged
            }
            current = current.cause
            depth++
        }
        return null
    }

    /**
     * 按 baseUrl 识别限速画像：官方分钟窗口型域用 AGGRESSIVE（短冷却起步靠样本收敛），
     * NVIDIA 类惩罚延长型与未知中转站用 CONSERVATIVE（保守序列，宁可多等）。
     * 识别只影响「无服务端信号时的起点」；有头/体信号时一律用精确值。
     */
    private fun cooldownProfileOf(baseUrl: String): CooldownProfile {
        val host = runCatching { java.net.URI(baseUrl).host?.lowercase(Locale.US) }
            .getOrNull()
            ?: return CooldownProfile.CONSERVATIVE
        return if (AGGRESSIVE_HOSTS.any { host == it || host.endsWith(".$it") }) {
            CooldownProfile.AGGRESSIVE
        } else {
            CooldownProfile.CONSERVATIVE
        }
    }

    /** 401/403 归因为凭据失效；其余（429/quota）按限流处理。 */
    private fun keyFailureKindOf(error: Throwable): KeyFailureKind {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (current is HttpException && (current.code() == 401 || current.code() == 403)) {
                return KeyFailureKind.AUTH
            }
            current = current.cause
            depth++
        }
        return KeyFailureKind.RATE_LIMIT
    }

    private companion object {
        const val WAIT_TAG = "ProviderKeyWait"

        /** 全冷却等待上限：交互会话不宜无限挂起；后台任务同样先以该值为准。 */
        const val ALL_COOLING_WAIT_CAP_MS = 30_000L

        /** 429 错误体最多读 16KB 足够覆盖 JSON 关键字段。 */
        const val BODY_PEEK_BYTES = 16 * 1024L

        /** 分钟窗口型官方域：无头信号时用短冷却起步（其余域保守）。 */
        val AGGRESSIVE_HOSTS = setOf(
            "openai.com",
            "anthropic.com",
            "generativelanguage.googleapis.com",
            "deepseek.com"
        )
    }
}
