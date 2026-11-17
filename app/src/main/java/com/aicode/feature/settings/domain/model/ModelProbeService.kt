package com.aicode.feature.settings.domain.model

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.remote.ModelApiService
import com.aicode.feature.settings.data.repository.ModelCapability
import com.aicode.feature.settings.data.repository.ModelCapabilityRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型自动探测（4.1）：定期或配置变更时静默跑 fetchModels + testModel，把结果写进模型能力画像。
 *
 * 纪律（评审与计划共同要求）：
 * - **只使用用户已配置的 key 与 baseUrl**，不外发第三方（4.3 隐私边界）；
 * - **失败不阻塞主流程**：全部包在 runCatching 里，探测失败只是画像缺一条，不影响聊天；
 * - **TTL 缓存**：同一 provider+model 在 [TTL_MS] 内不重复探测，避免每次启动都打网络（功耗纪律）；
 * - 探测是网络型低频任务，由调用方决定时机（设置页/启动后延迟），本类不自己起常驻循环。
 */
@Singleton
class ModelProbeService @Inject constructor(
    private val aiProviderRepository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val capabilityRepository: ModelCapabilityRepository
) {
    /**
     * 探测一批 provider 的可用性并落画像。
     *
     * 只为「已启用且有可用 key」的 provider 探测其当前选中模型：探测本身要发一次真实请求，
     * 给未配置的 provider 探测纯属浪费流量。
     */
    suspend fun probeAll(force: Boolean = false): Int {
        var probed = 0
        runCatching {
            val providers = aiProviderRepository.getAllProviders().first()
                .filter { it.isEnabled && it.hasUsableApiKey }
            if (providers.isEmpty()) return 0
            val existing = capabilityRepository.capabilities()
            val now = System.currentTimeMillis()
            val results = mutableListOf<ModelCapability>()
            for (provider in providers) {
                val model = provider.selectedModel?.takeIf { it.isNotBlank() } ?: continue
                val key = "${provider.id}|$model"
                val cached = existing[key]
                if (!force && cached != null && now - cached.probedAt < TTL_MS) continue
                val capability = runCatching {
                    val result = modelApiService.testModel(
                        baseUrl = provider.baseUrl,
                        apiKey = provider.apiKey,
                        type = provider.type,
                        useFullUrl = provider.useFullUrl,
                        useResponseApi = provider.useResponseApi,
                        model = model
                    )
                    ModelCapability(
                        providerId = provider.id,
                        model = model,
                        available = result.success,
                        latencyMs = result.latencyMs.takeIf { result.success },
                        contextTokens = cached?.contextTokens ?: 0,
                        probedAt = now
                    )
                }.getOrElse { e ->
                    FileLogger.w(TAG, "探测模型失败: ${provider.id}/$model", e)
                    ModelCapability(providerId = provider.id, model = model, available = false, probedAt = now)
                }
                results += capability
                probed++
            }
            capabilityRepository.saveCapabilities(results)
        }.onFailure { e -> FileLogger.w(TAG, "自动探测整体失败（不影响主流程）", e) }
        return probed
    }

    private companion object {
        const val TAG = "ModelProbeService"

        /** 探测结果有效期：1 小时内不重复打网络（配置变更时调用方传 force=true）。 */
        const val TTL_MS = 60L * 60 * 1000
    }
}
