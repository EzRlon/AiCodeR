package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.verificationModelDataStore by preferencesDataStore(
    name = "verification_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「独立验收专用模型」选择（providerId + model 两字符串）。
 *
 * 任务收尾时由该模型对目标完成度做交叉检查（见 StatefulAgentWorkflow 的收尾验收）。
 * 与其它专用模型不同，本项没有「跟随当前聊天模型」的回退语义：未配置即视为关闭
 * 收尾独立验收——用同一个模型验收自己等于没有交叉检查，还会多花一份 token。
 */
@Singleton
class VerificationModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.verificationModelDataStore, "verification_provider_id", "verification_model"
) {

    /** 写入独立验收专用模型（设空字符串即等同 [clear]）。 */
    suspend fun setVerificationModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——关闭收尾独立验收。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前验收专用 providerId（冷读用）。 */
    suspend fun getVerificationProviderId(): String = readProviderId()

    /** 读取一次当前验收专用 model（冷读用）。 */
    suspend fun getVerificationModel(): String = readModel()
}
