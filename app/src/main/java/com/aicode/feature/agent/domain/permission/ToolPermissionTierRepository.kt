package com.aicode.feature.agent.domain.permission

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolTierDataStore by preferencesDataStore(name = "tool_permission_tiers")

/**
 * 工具档位仓库：持久化「工具授权中心」里用户设置的每工具三档权限（自动允许 / 每次询问 / 禁止）。
 *
 * 只存「用户显式改过」的项；未设置的工具有序回退 [ToolPermissionCatalog] 的默认档位，
 * 因此应用升级新增工具时无需迁移数据。档位为全局设置（不随项目切换）。
 */
@Singleton
class ToolPermissionTierRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val KEY_PREFIX = "tier_"
    }

    /** 显式设置的档位（toolName → tier）。 */
    val tiersFlow: Flow<Map<String, ToolPermissionTier>> =
        context.toolTierDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                val name = key.name
                if (!name.startsWith(KEY_PREFIX)) return@mapNotNull null
                val tier = ToolPermissionTier.fromKey(value as? String) ?: return@mapNotNull null
                name.removePrefix(KEY_PREFIX) to tier
            }.toMap()
        }

    /** 引擎评估用：显式设置优先，否则回退目录默认档位（未收录工具=每次询问）。 */
    suspend fun tierOf(toolName: String): ToolPermissionTier =
        tiersFlow.first()[toolName] ?: ToolPermissionCatalog.defaultTierOf(toolName)

    /** 设置单个工具的档位（写为显式设置；选回默认档也保留为显式值，语义一致）。 */
    suspend fun setTier(toolName: String, tier: ToolPermissionTier) {
        context.toolTierDataStore.edit {
            it[stringPreferencesKey(KEY_PREFIX + toolName)] = tier.key
        }
    }

    /** 应用预置档案：清空全部显式设置（回到默认），再写入档案覆盖项。 */
    suspend fun applyProfile(profile: ToolPermissionProfile) {
        context.toolTierDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(KEY_PREFIX) }
                .forEach { prefs.remove(it) }
            profile.overrides.forEach { (tool, tier) ->
                prefs[stringPreferencesKey(KEY_PREFIX + tool)] = tier.key
            }
        }
    }
}
