package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.engineOptionsDataStore by preferencesDataStore(
    name = "engine_options_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * CLI 引擎的行为选项（引擎卡小开关），全局生效、非会话级。
 * 桥接在每次回合起进程前冷读，改后对下一次发送生效。
 */
@Singleton
class EngineOptionsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val CODEX_SANDBOX_FULL_ACCESS_KEY = booleanPreferencesKey("codex_sandbox_full_access")
        val OPENCODE_AUTO_APPROVE_KEY = booleanPreferencesKey("opencode_auto_approve")
    }

    /** Codex 沙箱是否完全放开：true = danger-full-access，false = workspace-write（默认）。 */
    val codexSandboxFullAccessFlow: Flow<Boolean> =
        context.engineOptionsDataStore.data.map { it[CODEX_SANDBOX_FULL_ACCESS_KEY] ?: false }

    suspend fun setCodexSandboxFullAccess(enabled: Boolean) {
        context.engineOptionsDataStore.edit { it[CODEX_SANDBOX_FULL_ACCESS_KEY] = enabled }
    }

    /** 冷读一次 Codex 沙箱档位（桥接起进程前用）。 */
    suspend fun isCodexSandboxFullAccess(): Boolean = codexSandboxFullAccessFlow.first()

    /** OpenCode 是否自动批准权限请求（--auto）：默认 true。 */
    val openCodeAutoApproveFlow: Flow<Boolean> =
        context.engineOptionsDataStore.data.map { it[OPENCODE_AUTO_APPROVE_KEY] ?: true }

    suspend fun setOpenCodeAutoApprove(enabled: Boolean) {
        context.engineOptionsDataStore.edit { it[OPENCODE_AUTO_APPROVE_KEY] = enabled }
    }

    /** 冷读一次 OpenCode 自动批准开关（桥接起进程前用）。 */
    suspend fun isOpenCodeAutoApprove(): Boolean = openCodeAutoApproveFlow.first()
}
