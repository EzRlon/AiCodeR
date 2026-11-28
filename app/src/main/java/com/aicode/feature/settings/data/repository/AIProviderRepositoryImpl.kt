package com.aicode.feature.settings.data.repository

import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.DEFAULT_KEY_WEIGHT
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.aicode.feature.settings.domain.model.ProviderKey
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.domain.model.sanitized
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderRepositoryImpl @Inject constructor(
    private val aiProviderDao: AIProviderDao,
    private val agentDatabase: AgentDatabase
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepo"
        private val json = Json { ignoreUnknownKeys = true }

        private fun encodeMap(map: Map<String, String>): String =
            if (map.isEmpty()) "" else json.encodeToString(map)

        private fun decodeMap(raw: String): Map<String, String> =
            if (raw.isBlank()) emptyMap()
            else runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

        /** 解析多 Key 存储串：每行最多三段 `值|权重|baseUrl覆盖`；
         *  兼容旧格式（无 `|` = 仅值，两段 = 值+权重），baseUrl 覆盖为空段 = 跟随全局。 */
        private fun parseProviderKeys(raw: String): List<ProviderKey> =
            raw.split("\n").mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val segments = trimmed.split('|')
                val value = segments[0].trim()
                if (value.isEmpty()) return@mapNotNull null
                val weight = segments.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_KEY_WEIGHT
                val baseUrlOverride = segments.getOrNull(2)?.trim().orEmpty()
                ProviderKey(value, weight.coerceIn(0, MAX_KEY_WEIGHT), baseUrlOverride)
            }
    }

    /** 解密失败的 provider id（最近一次读取）；供 UI 显性提示「密文在但解不开」，避免用户误以为没填过。 */
    private val decryptFailedIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    override fun observeDecryptFailures(): Flow<Set<String>> = decryptFailedIds

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return aiProviderDao.getAllProviders().map { entities ->
            val failed = entities.filter {
                KeystoreCipher.isDecryptFailed(it.apiKey) || KeystoreCipher.isDecryptFailed(it.apiKeys)
            }.map { it.id }.toSet()
            decryptFailedIds.value = failed
            entities.map { it.toDomainModel() }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? = withContext(Dispatchers.IO) {
        aiProviderDao.getProviderById(id)?.toDomainModel()
    }

    /**
     * 保存提供商。排序值以数据库当前值为准（重排可能异步持久化，UI 传入的
     * sortOrder 可能陈旧，直接使用会撤销刚完成的排序）；新提供商取最大排序 +1。
     */
    override suspend fun saveProvider(provider: AIProviderConfig): Unit = withContext(Dispatchers.IO) {
        agentDatabase.withTransaction {
            val current = aiProviderDao.getProviderById(provider.id)
            val sortOrder = current?.sortOrder ?: (aiProviderDao.getMaxSortOrder() + 1)
            FileLogger.i(TAG, "保存提供商 id=${provider.id} name=${provider.name} sortOrder=$sortOrder")
            val entity = provider.copy(sortOrder = sortOrder).sanitized().toEntity()
            // 解密失败防线：域模型里敏感字段为空，但库里同字段仍是「解密失败」的密文——说明空值
            // 是 Keystore 解密失败被静默置空的产物，不是用户主动清空。此时**原样保留库中的旧密文**
            // （entity 级替换，不经过加密），否则一次只改名字的保存就会把凭据永久覆盖成空串。
            // 用户真想清空的场景：编辑器中该字段本就显示为空、用户点保存前没有其它改动——与
            // 「解密失败」在域层不可区分，为安全起见保留旧值；用户重新填入新值（非空）则正常覆盖。
            val merged = if (current != null) {
                entity.copy(
                    apiKey = preserveUndecryptable(current.apiKey, entity.apiKey, "apiKey"),
                    apiKeys = preserveUndecryptable(current.apiKeys, entity.apiKeys, "apiKeys"),
                    proxyPassword = preserveUndecryptable(current.proxyPassword, entity.proxyPassword, "proxyPassword"),
                    customHeaders = preserveUndecryptable(current.customHeaders, entity.customHeaders, "customHeaders"),
                    scriptParams = preserveUndecryptable(current.scriptParams, entity.scriptParams, "scriptParams")
                )
            } else {
                entity
            }
            aiProviderDao.insertProvider(merged)
        }
    }

    /**
     * 敏感字段覆盖保护（防凭据静默丢失）：
     * 新值为空、旧值非空 → **一律保留旧密文**，不再区分旧值能否解密。
     *
     * 历史教训：旧实现只在「旧值解密失败」时保留。若某一刻 Keystore 解密失败使域模型变空，
     * 用户只改了名字就保存，而此时 Keystore 恰好恢复（旧密文本可解），空值就会被写回库里，
     * 明文密钥永久丢失——且失败路径极隐蔽（用户只是改了名称）。
     * 宁可让「清空密钥」这种极罕见诉求走删除供应商，也不能让一次普通保存销毁凭据。
     */
    private fun preserveUndecryptable(oldValue: String, newValue: String, field: String): String =
        if (newValue.isEmpty() && oldValue.isNotEmpty()) {
            FileLogger.w(TAG, "provider 敏感字段 $field 收到空值而库中有存量密文：保留旧值不覆盖")
            oldValue
        } else {
            newValue
        }

    /**
     * 按传入顺序重排提供商。只写 id + sortOrder 两列，
     * 避免整行 REPLACE 覆盖并发修改的其它字段（如 apiKey/模型列表）。
     */
    override suspend fun reorderProviders(providers: List<AIProviderConfig>) {
        FileLogger.d(TAG, "重排提供商 共 ${providers.size} 个")
        agentDatabase.withTransaction {
            providers.forEachIndexed { index, p -> aiProviderDao.updateSortOrder(p.id, index) }
        }
    }

    override suspend fun deleteProvider(id: String) {
        FileLogger.i(TAG, "删除提供商 id=$id")
        aiProviderDao.deleteProvider(id)
    }

    override suspend fun setSelectedModel(id: String, model: String) {
        FileLogger.i(TAG, "切换模型 provider=$id model=$model")
        aiProviderDao.setSelectedModel(id, model)
    }

    override suspend fun updateModels(id: String, models: List<String>) {
        FileLogger.d(TAG, "更新模型列表 provider=$id 共 ${models.size} 个")
        aiProviderDao.setModels(id, models.joinToString("\n"))
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        FileLogger.i(TAG, "设置提供商状态 provider=$id isEnabled=$isEnabled")
        aiProviderDao.setProviderEnabled(id, isEnabled)
    }

    private fun AIProviderEntity.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.substringBefore('|').trim() }.filter { it.isNotEmpty() }
        return AIProviderConfig(
            id = id,
            name = name,
            type = try { ProviderType.valueOf(type) } catch (e: Exception) { ProviderType.OPENAI },
            apiKey = KeystoreCipher.decryptString(apiKey),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = parseProviderKeys(KeystoreCipher.decryptString(apiKeys)),
            keyRotationStrategy = runCatching { KeyRotationStrategy.valueOf(keyRotationStrategy) }
                .getOrDefault(KeyRotationStrategy.SEQUENTIAL),
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes
                .split(",").mapNotNull { it.trim().toIntOrNull() }.distinct(),
            maxConcurrentRequests = maxConcurrentRequests,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = modelList,
            selectedModel = selectedModel.ifBlank { defaultModel },
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = runCatching { ProxyType.valueOf(proxyType) }.getOrDefault(ProxyType.HTTP),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = KeystoreCipher.decryptString(proxyPassword),
            customHeaders = decodeMap(KeystoreCipher.decryptString(customHeaders)),
            scriptParams = decodeMap(KeystoreCipher.decryptString(scriptParams))
        ).sanitized()
    }

    private fun AIProviderConfig.toEntity(): AIProviderEntity {
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            apiKey = KeystoreCipher.encryptString(apiKey),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = KeystoreCipher.encryptString(
                apiKeys.joinToString("\n") { key ->
                    if (key.baseUrlOverride.isBlank()) "${key.value}|${key.weight}"
                    else "${key.value}|${key.weight}|${key.baseUrlOverride}"
                }
            ),
            keyRotationStrategy = keyRotationStrategy.name,
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes.joinToString(","),
            maxConcurrentRequests = maxConcurrentRequests,
            baseUrl = baseUrl,
            useFullUrl = useFullUrl,
            defaultModel = defaultModel,
            models = models.joinToString("\n"),
            selectedModel = selectedModel,
            isEnabled = isEnabled,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = proxyType.name,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = KeystoreCipher.encryptString(proxyPassword),
            customHeaders = KeystoreCipher.encryptString(encodeMap(customHeaders)),
            scriptParams = KeystoreCipher.encryptString(encodeMap(scriptParams))
        )
    }
}
