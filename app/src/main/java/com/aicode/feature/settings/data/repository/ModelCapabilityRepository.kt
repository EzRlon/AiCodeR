package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一个模型的可用性画像（4.1 自动探测的产物）。
 *
 * 只记「实测到的」事实，不猜：探测失败就记失败，宁可缺档也不编一个成本档出来。
 */
@Serializable
data class ModelCapability(
    val providerId: String,
    val model: String,
    /** 最近一次探测是否可用（fetchModels/testModel 成功）。 */
    val available: Boolean = false,
    /** 首字/整轮延迟（毫秒），探测失败为 null。 */
    val latencyMs: Long? = null,
    /** 上下文窗口（tokens），来自 ModelMetadataService；0 = 未知。 */
    val contextTokens: Int = 0,
    /** 最近探测时间（epoch 毫秒）。 */
    val probedAt: Long = 0
)

/**
 * 路由档位（4.2 静态路由的成本/能力档）。
 * 档位是配置数据而非硬编码：路由表由探测结果与用户配置共同决定，可热更新。
 */
@Serializable
enum class ModelTier {
    /** 快档：便宜、低延迟，适合范围明确的机械任务（读文件、找调用点、格式检查）。 */
    FAST,

    /** 中档：默认档，适合 qa 审查、一般实现任务。 */
    BALANCED,

    /** 强档：贵但判断力好，适合架构评审、复杂重构、疑难定位。 */
    STRONG
}

/**
 * 路由表（配置数据，4.2）：任务类型 → 模型档位 → 具体模型。
 *
 * 采用「任务类型静态路由为主 + 失败升级级联为兜底」：不做训练型路由器、不做 LLM-as-Router。
 * 表本身存 DataStore JSON，可由 4.1 探测结果生成，也可被用户/后续版本热更新覆盖。
 */
@Serializable
data class ModelRoutingTable(
    /** 任务类型 → 档位。key 为任务类型标识（如 qa / optimizer / explore / default）。 */
    val taskTiers: Map<String, ModelTier> = DEFAULT_TASK_TIERS,
    /** 档位 → 具体模型（providerId 与 model 成对，避免拼出跨家幽灵组合）。 */
    val tierModels: Map<String, TierModel> = emptyMap(),
    /** 失败升级链：当前档位失败后升到哪一档；空 = 不升级。 */
    val escalation: Map<String, ModelTier> = DEFAULT_ESCALATION
) {
    /** 取某任务类型该用的模型；未配置档位模型时返回 null（由调用方回退到现有逻辑）。 */
    fun modelFor(taskType: String): TierModel? {
        val tier = taskTiers[taskType] ?: taskTiers[DEFAULT_TASK] ?: ModelTier.BALANCED
        return tierModels[tier.name]
    }

    /** 失败后应升级到的档位；无升级路径返回 null。 */
    fun escalateFrom(taskType: String): TierModel? {
        val tier = taskTiers[taskType] ?: taskTiers[DEFAULT_TASK] ?: ModelTier.BALANCED
        val next = escalation[tier.name] ?: return null
        return tierModels[next.name]
    }

    companion object {
        const val DEFAULT_TASK = "default"

        /** 初版映射：用现有三个固定角色建表（评审采纳的过渡方案）。 */
        val DEFAULT_TASK_TIERS: Map<String, ModelTier> = mapOf(
            "explore" to ModelTier.FAST,
            "qa" to ModelTier.BALANCED,
            "optimizer" to ModelTier.STRONG,
            "default" to ModelTier.BALANCED
        )

        /** 失败升级：快→中→强，强档不再升级。 */
        val DEFAULT_ESCALATION: Map<String, ModelTier> = mapOf(
            ModelTier.FAST.name to ModelTier.BALANCED,
            ModelTier.BALANCED.name to ModelTier.STRONG
        )
    }
}

@Serializable
data class TierModel(val providerId: String, val model: String)

private val Context.routingDataStore by preferencesDataStore(name = "model_routing")

/**
 * 模型能力画像 + 路由表仓库（4.1/4.2 的存储层）。
 *
 * 刻意不建表：画像是「探测快照」，丢了重新探测即可，不值得占一条迁移；
 * 路由表是配置数据，与 defaultModelDataStore 同级，便于热更新与导入导出。
 */
@Singleton
class ModelCapabilityRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 全部已探测模型（按 provider+model 索引）。 */
    suspend fun capabilities(): Map<String, ModelCapability> = runCatching {
        val raw = context.routingDataStore.data.map { it[CAPABILITIES_KEY] }.first().orEmpty()
        if (raw.isBlank()) emptyMap()
        else json.decodeFromString<Map<String, ModelCapability>>(raw)
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取模型画像失败", e)
        emptyMap()
    }

    /** 写入/合并一批探测结果。 */
    suspend fun saveCapabilities(items: Collection<ModelCapability>) {
        if (items.isEmpty()) return
        runCatching {
            val merged = capabilities().toMutableMap()
            items.forEach { merged[key(it.providerId, it.model)] = it }
            context.routingDataStore.edit {
                it[CAPABILITIES_KEY] = json.encodeToString(merged)
            }
        }.onFailure { e -> FileLogger.w(TAG, "保存模型画像失败", e) }
    }

    /** 当前路由表；未配置时返回默认表（三角色初版映射）。 */
    suspend fun routingTable(): ModelRoutingTable = runCatching {
        val raw = context.routingDataStore.data.map { it[ROUTING_KEY] }.first().orEmpty()
        if (raw.isBlank()) ModelRoutingTable()
        else json.decodeFromString<ModelRoutingTable>(raw)
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取路由表失败，回退默认", e)
        ModelRoutingTable()
    }

    /** 覆盖路由表（热更新入口：探测结果或用户配置变化时调用）。 */
    suspend fun saveRoutingTable(table: ModelRoutingTable) {
        runCatching {
            context.routingDataStore.edit { it[ROUTING_KEY] = json.encodeToString(table) }
        }.onFailure { e -> FileLogger.w(TAG, "保存路由表失败", e) }
    }

    private fun key(providerId: String, model: String) = "$providerId|$model"

    private companion object {
        const val TAG = "ModelCapabilityRepository"
        val CAPABILITIES_KEY = stringPreferencesKey("model_capabilities")
        val ROUTING_KEY = stringPreferencesKey("model_routing_table")
    }
}
