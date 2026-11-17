package com.aicode.feature.agent.presentation

import android.content.Context
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.voice.VoskModelManager
import com.aicode.feature.settings.data.repository.VoiceEngine
import com.aicode.feature.settings.data.repository.VoiceSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 语音输入域状态持有者：引擎选择、系统识别可用性探测与自动降级、离线模型下载引导。
 *
 * 从 AIAgentViewModel（4961 行 / 57 个注入依赖）拆出的第一个域——语音域对它其它状态零依赖
 * （只碰 voiceSettingsRepository / voskModelManager / context），边界干净。
 * 后续通知文案、引擎分流等域按同样模式逐块迁出。
 *
 * [coroutineScope] 由 AIAgentViewModel 在 init 中注入自己的 viewModelScope，生命周期随之绑定。
 */
class VoiceStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val voskModelManager: VoskModelManager
) {
    private companion object {
        const val TAG = "VoiceState"
    }

    private lateinit var coroutineScope: CoroutineScope

    /** 进程内只尝试一次：后台静默下载的节流标志。 */
    private var autoDownloadAttempted = false

    private val _voiceEngine = MutableStateFlow(VoiceEngine.SYSTEM)

    /** 语音输入引擎（设置里可切换；下一次录音生效）。 */
    val voiceEngine: StateFlow<VoiceEngine> = _voiceEngine.asStateFlow()

    /** 离线语音模型安装状态（引导卡片/设置页进度共用）。 */
    val voiceModelState: StateFlow<VoskModelManager.ModelState> = voskModelManager.state

    private val _systemSttAvailable = MutableStateFlow<Boolean?>(null)

    /** 系统识别可用性探测结果（null = 尚未探测；false = 本机无可用识别服务，如无谷歌设备）。 */
    val systemSttAvailable: StateFlow<Boolean?> = _systemSttAvailable.asStateFlow()

    /** 最近一次模型下载失败原因（引导卡片展示；无失败为 null）。 */
    val voiceModelFailureReason: String?
        get() = voskModelManager.lastFailureReason

    /** 由 AIAgentViewModel 注入 viewModelScope；重复调用无副作用。 */
    fun attach(scope: CoroutineScope) {
        if (::coroutineScope.isInitialized) return
        coroutineScope = scope
        scope.launch {
            voiceSettingsRepository.engineFlow.collect { _voiceEngine.value = it }
        }
        scope.launch {
            voiceSettingsRepository.systemSttAvailableFlow.collect { _systemSttAvailable.value = it }
        }

        // F2/F7：离线模型就绪且系统识别确认不可用时自动切离线引擎，语音输入立即可用。
        scope.launch {
            voskModelManager.state
                .filter { it is VoskModelManager.ModelState.Ready }
                .collect {
                    if (systemSttAvailable.value == false && voiceEngine.value == VoiceEngine.SYSTEM) {
                        voiceSettingsRepository.setEngine(VoiceEngine.OFFLINE)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.voice_auto_ready_toast),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }

    /** 探测系统识别可用性并持久化（瞬时同步调用，不耗资源；聊天页冷启动与语音失败时触发）。 */
    fun refreshSystemSttAvailability() {
        val available = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)
        if (systemSttAvailable.value != available) {
            coroutineScope.launch { voiceSettingsRepository.setSystemSttAvailable(available) }
        }
    }

    /**
     * 系统引擎语音识别失败（网络/服务类错误）时的处理：更新探测；离线模型已就绪 →
     * 自动切离线并返回 false（调用方提示重试）；未就绪 → 返回 true（调用方弹引导卡片）。
     */
    fun onSystemVoiceFailure(): Boolean {
        refreshSystemSttAvailability()
        if (VoskModelManager.isModelReady(context)) {
            coroutineScope.launch { voiceSettingsRepository.setEngine(VoiceEngine.OFFLINE) }
            return false
        }
        return true
    }

    /** 用户在引导卡片点「立即下载」。 */
    fun startVoiceModelDownload() = voskModelManager.startDownload()

    /**
     * 后台静默下载（每个进程至多一次）：现场探测确认系统识别不可用 ∧ 模型缺失 ∧ 不计费网络（WiFi）。
     * 计费网络不静默下载（42MB），交给用户在引导卡片里主动确认。
     */
    fun maybeAutoDownloadVoiceModel() {
        if (autoDownloadAttempted) return
        if (VoskModelManager.isModelReady(context)) return
        val systemAvailable = runCatching {
            android.speech.SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)
        if (systemAvailable) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        autoDownloadAttempted = true
        // 顺手持久化探测结果，供设置页/后续判断使用
        coroutineScope.launch { voiceSettingsRepository.setSystemSttAvailable(false) }
        FileLogger.i(TAG, "系统识别不可用且模型缺失，WiFi 下后台静默下载离线模型")
        voskModelManager.startDownload()
    }
}
