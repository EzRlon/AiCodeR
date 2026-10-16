package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.voiceDataStore by preferencesDataStore(
    name = "voice_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 语音输入引擎。 */
enum class VoiceEngine { SYSTEM, OFFLINE }

/**
 * 持久化「语音输入引擎」选择：系统识别（默认）或内置离线识别（中文，需先下载模型）。
 *
 * 另存「系统识别可用性」探测结果（[systemSttAvailableFlow]，null = 尚未探测）：
 * 无谷歌服务/无 VPN 的设备上系统识别不可用，语音入口据此自动降级到离线引擎
 * 或弹引导卡片下载模型（验收反馈 F2/F7）。
 *
 * 取值由语音输入功能在开始录音时读取；切换即时生效（下一次录音使用新引擎）。
 */
@Singleton
class VoiceSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ENGINE_KEY = stringPreferencesKey("voice_engine")
    private val SYSTEM_STT_KEY = booleanPreferencesKey("system_stt_available")

    /** 当前引擎；解析失败/未设置回退系统识别。 */
    val engineFlow: Flow<VoiceEngine> = context.voiceDataStore.data.map { prefs ->
        when (prefs[ENGINE_KEY]) {
            VoiceEngine.OFFLINE.name -> VoiceEngine.OFFLINE
            else -> VoiceEngine.SYSTEM
        }
    }

    suspend fun setEngine(engine: VoiceEngine) {
        context.voiceDataStore.edit { it[ENGINE_KEY] = engine.name }
    }

    /** 系统识别可用性（null = 尚未探测过）。 */
    val systemSttAvailableFlow: Flow<Boolean?> = context.voiceDataStore.data.map { prefs ->
        prefs[SYSTEM_STT_KEY]
    }

    suspend fun setSystemSttAvailable(available: Boolean) {
        context.voiceDataStore.edit { it[SYSTEM_STT_KEY] = available }
    }
}
