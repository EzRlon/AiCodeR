package com.aicode.feature.floatball

import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 悬浮球 mini 会话窗口（FB1/FB2）的进程级状态镜像：悬浮窗宿主 Service 与 MainActivity 同进程，
 * 单例即共享、无 IPC。
 *
 * - 消息/标题/运行态：由 AIAgentViewModel 写入（FB1 镜像）。
 * - **模型选择：本类自足提供**——注入 [AIProviderRepository]（模型清单）与 [SessionUseCase]
 *   （写回会话），不依赖任何页面存活；此前挂在聊天页组件上的注册在离开页面后即失效，
 *   导致面板「点模型没反应/提示回 App」，已废弃。
 * - 发送/停止等命令仍走 [FloatBallCommandCenter]（主界面注册）。
 */
@Singleton
class FloatBallSessionHub @Inject constructor(
    private val providerRepository: AIProviderRepository,
    private val sessionUseCase: SessionUseCase
) {
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 面板消息流的单条快照（content 已截断压平，避免悬浮窗渲染超长文本）。 */
    data class HubMessage(
        val role: String,              // USER / ASSISTANT / TOOL
        val text: String,              // 已按 300 字符截断、换行压平
        val isError: Boolean = false,
        val isVerification: Boolean = false
    )

    data class HubState(
        val sessionId: String? = null,
        val sessionTitle: String? = null,
        val messages: List<HubMessage> = emptyList(),  // 时间正序（渲染时最新在底部）
        val running: Boolean = false,
        val mode: com.aicode.feature.agent.domain.model.AgentMode? = null,
        val voiceEngine: com.aicode.feature.settings.data.repository.VoiceEngine? = null,
        val draft: String = "",  // 输入草稿（与 App 底栏同源同步）
        val providerId: String? = null,  // 当前会话绑定的模型提供商
        val model: String? = null        // 当前会话选中的模型
    )

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    /** 模型清单快照（repository 流 → 内存，面板 modelData 同步读取）。 */
    private val _providersSnapshot = MutableStateFlow<List<com.aicode.feature.settings.domain.model.AIProviderConfig>>(emptyList())

    init {
        hubScope.launch {
            providerRepository.getAllProviders().collect { all ->
                _providersSnapshot.value = all
            }
        }
    }

    fun updateSession(sessionId: String?, title: String?, running: Boolean) {
        _state.value = _state.value.copy(sessionId = sessionId, sessionTitle = title, running = running)
    }

    /** FB1 镜像扩展：随会话同步当前模型绑定（悬浮窗模型选择的选中态来源）。 */
    fun updateSessionModel(providerId: String?, model: String?) {
        _state.value = _state.value.copy(providerId = providerId, model = model)
    }

    fun updateDraft(draft: String) {
        _state.value = _state.value.copy(draft = draft)
    }

    fun updateModeEngine(mode: com.aicode.feature.agent.domain.model.AgentMode?, engine: com.aicode.feature.settings.data.repository.VoiceEngine?) {
        _state.value = _state.value.copy(mode = mode, voiceEngine = engine)
    }

    /** 用最近的 UI 消息刷新快照（取末尾 [limit] 条，正序）。 */
    fun updateMessages(all: List<HubMessage>, limit: Int = 30) {
        _state.value = _state.value.copy(messages = all.takeLast(limit))
    }

    /**
     * FB2：悬浮窗内嵌模型选择的数据快照。
     * 自足实现：模型清单来自 repository 快照（含未启用的，视图层自行过滤 isEnabled），
     * 选中态来自当前会话绑定的 providerId/model。
     */
    fun modelData(): FloatBallCommandCenter.FloatModelPickerData? {
        val all = _providersSnapshot.value
        if (all.isEmpty()) return null
        return FloatBallCommandCenter.FloatModelPickerData(
            currentProviderId = _state.value.providerId,
            currentModel = _state.value.model,
            providers = all,
            metadata = emptyMap()  // 悬浮窗精简列表只渲染名称，无需元数据
        )
    }

    /** FB2：选定模型（与 App 内模型 sheet 同一落点：写回会话并即时反映到面板选中态）。 */
    fun selectModel(providerId: String, model: String) {
        val sid = _state.value.sessionId ?: return
        _state.value = _state.value.copy(providerId = providerId, model = model)
        hubScope.launch {
            runCatching { sessionUseCase.updateProviderModel(sid, providerId, model) }
        }
    }
}
