package com.aicode.feature.settings.domain.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import kotlinx.coroutines.flow.Flow

interface AIProviderRepository {
    fun getAllProviders(): Flow<List<AIProviderConfig>>

    /** 最近一次读取时解密失败的 provider id 集合（卸载重装/签名变更导致 Keystore 密文不可解）。 */
    fun observeDecryptFailures(): kotlinx.coroutines.flow.Flow<Set<String>>
    suspend fun getProviderById(id: String): AIProviderConfig?
    suspend fun saveProvider(provider: AIProviderConfig)
    suspend fun deleteProvider(id: String)
    suspend fun setSelectedModel(id: String, model: String)
    suspend fun updateModels(id: String, models: List<String>)
    suspend fun setProviderEnabled(id: String, isEnabled: Boolean)

    /** 按传入顺序重排提供商（重分配 sortOrder 并整体保存）。 */
    suspend fun reorderProviders(providers: List<AIProviderConfig>)
}
