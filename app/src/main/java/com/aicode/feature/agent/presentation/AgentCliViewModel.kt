package com.aicode.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.cliagent.CliAgent
import com.aicode.feature.agent.domain.cliagent.CliAgentCatalog
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.engine.ClaudeCodeBridge
import com.aicode.feature.agent.domain.engine.CodexBridge
import com.aicode.feature.agent.domain.engine.OpenCodeBridge
import com.aicode.feature.settings.data.repository.EngineOptionsRepository
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 智能体终端 CLI（Claude Code / Codex / OpenCode）管理状态。
 *
 * 检测在 sheet 打开时发起（读容器命令，不触发容器初始化）；安装在后台终端标签跑，
 * 通过 [TerminalSessionProvider.tabFinishedEvents] 感知安装结束并自动复检版本。
 */
@HiltViewModel
class AgentCliViewModel @Inject constructor(
    private val cliAgentManager: CliAgentManager,
    private val terminalSessionProvider: TerminalSessionProvider,
    private val claudeCodeBridge: ClaudeCodeBridge,
    private val codexBridge: CodexBridge,
    private val openCodeBridge: OpenCodeBridge,
    private val engineOptionsRepository: EngineOptionsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    data class AgentCliState(
        val agent: CliAgent,
        /** 已安装时的首行版本输出；null 表示未安装或未检出。 */
        val version: String?,
        val checking: Boolean = false,
        val installing: Boolean = false,
        /** 认证态：null = 已登录（或可透传 App 内 Key）；非 null = 未登录引导文案。 */
        val authHint: String? = null
    )

    private val _states = MutableStateFlow(CliAgentCatalog.ALL.map { AgentCliState(it, version = null, checking = true) })
    val states: StateFlow<List<AgentCliState>> = _states.asStateFlow()

    /** 安装标签 tabId → agentId：安装结束事件按此回查要复检的智能体。 */
    private val installingTabs = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 最近一次安装/启动失败的提示（消费后置空），供设置页展示。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Codex 沙箱是否完全放开（引擎卡开关）：false = workspace-write（默认），true = danger-full-access。 */
    val codexSandboxFullAccess: StateFlow<Boolean> = engineOptionsRepository.codexSandboxFullAccessFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCodexSandboxFullAccess(enabled: Boolean) {
        viewModelScope.launch { engineOptionsRepository.setCodexSandboxFullAccess(enabled) }
    }

    /** OpenCode 是否自动批准权限请求（引擎卡开关）：默认 true。 */
    val openCodeAutoApprove: StateFlow<Boolean> = engineOptionsRepository.openCodeAutoApproveFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setOpenCodeAutoApprove(enabled: Boolean) {
        viewModelScope.launch { engineOptionsRepository.setOpenCodeAutoApprove(enabled) }
    }

    init {
        refresh()
        viewModelScope.launch {
            terminalSessionProvider.tabFinishedEvents.collect { event ->
                val agentId = installingTabs.value[event.tabId] ?: return@collect
                installingTabs.value = installingTabs.value - event.tabId
                if (event.exitCode != 0) {
                    // npm 的具体错误（超时/断流/磁盘）只存在于终端尾部输出里，落日志方便事后诊断。
                    com.aicode.core.util.FileLogger.w(
                        "AgentCliViewModel",
                        "CLI 安装失败 agent=$agentId exitCode=${event.exitCode}\n${event.tailOutput.orEmpty()}"
                    )
                    _error.value = context.getString(R.string.agent_cli_install_failed, event.exitCode)
                }
                setOne(agentId) { it.copy(installing = false, checking = true) }
                checkOne(agentId, forceRefresh = true)
            }
        }
        // 兼底：用户手动关闭安装标签时不会发 finished 事件（closeTab 先移除标签），
        // 改由标签列表流发现映射中的 tabId 消失，收尾并复检，避免 spinner 永远转。
        viewModelScope.launch {
            terminalSessionProvider.tabs.collect { tabs ->
                val live = tabs.map { it.id }.toSet()
                val vanished = installingTabs.value.keys - live
                if (vanished.isEmpty()) return@collect
                installingTabs.value = installingTabs.value - vanished
                vanished.forEach { agentId ->
                    setOne(agentId) { it.copy(installing = false, checking = true) }
                    checkOne(agentId, forceRefresh = true)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            CliAgentCatalog.ALL.forEach { setOne(it.id) { s -> s.copy(checking = true) } }
            CliAgentCatalog.ALL.forEach { checkOne(it.id) }
        }
    }

    fun install(agent: CliAgent) {
        viewModelScope.launch {
            setOne(agent.id) { it.copy(installing = true) }
            runCatching { cliAgentManager.install(agent) }
                .onSuccess { tabId ->
                    installingTabs.value = installingTabs.value + (tabId to agent.id)
                }
                .onFailure {
                    setOne(agent.id) { s -> s.copy(installing = false) }
                    _error.value = it.message
                }
        }
    }

    fun launch(agent: CliAgent, onLaunched: () -> Unit) {
        viewModelScope.launch {
            runCatching { cliAgentManager.launch(agent) }
                .onSuccess { tabId ->
                    // startBackgroundCommand 不抢焦点（仅无标签时才激活）；启动后聚焦新标签，
                    // 让 onLaunched 的「切到终端」看到的正是刚拉起的 CLI 会话。
                    runCatching { terminalSessionProvider.activate(tabId) }
                    onLaunched()
                }
                .onFailure { _error.value = it.message }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    private suspend fun checkOne(agentId: String, forceRefresh: Boolean = false) {
        val agent = CliAgentCatalog.ALL.first { it.id == agentId }
        val version = runCatching { cliAgentManager.detectVersion(agent, forceRefresh) }.getOrNull()
        // 已安装才查登录态：未装时探测无意义
        val authHint = if (version != null) runCatching { authHintOf(agentId) }.getOrNull() else null
        setOne(agentId) { it.copy(version = version, checking = false, authHint = authHint) }
    }

    private suspend fun authHintOf(agentId: String): String? = when (agentId) {
        "claude" -> claudeCodeBridge.authHint()
        "codex" -> codexBridge.authHint()
        else -> openCodeBridge.authHint()
    }

    private fun setOne(agentId: String, transform: (AgentCliState) -> AgentCliState) {
        _states.value = _states.value.map { if (it.agent.id == agentId) transform(it) else it }
    }
}
