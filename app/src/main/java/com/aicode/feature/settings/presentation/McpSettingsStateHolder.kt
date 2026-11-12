package com.aicode.feature.settings.presentation

import android.content.Context
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.domain.mcp.McpToolDescriptor
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthCancelledException
import com.aicode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页的 MCP 域状态持有者：MCP server 的增删改、OAuth 授权、JSON 导入、重连与状态流。
 *
 * 从 SettingsViewModel（3262 行 / 98 个 StateFlow 的上帝类）拆出的第一步——MCP 域对
 * VM 其它状态零依赖（只碰 mcpConfigRepository / mcpManager / context），边界干净。
 * 后续 provider / 容器 / 日志等域按同样模式逐块迁移。
 *
 * [coroutineScope] 传 SettingsViewModel 的 viewModelScope：协程生命周期与 VM 绑定，
 * VM 销毁时 MCP 相关操作随之取消，不残留。
 */
class McpSettingsStateHolder @Inject constructor(
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager
) {
    private lateinit var coroutineScope: CoroutineScope

    /** 由 SettingsViewModel 构造时注入自己的 viewModelScope。 */
    fun attach(scope: CoroutineScope) {
        coroutineScope = scope
        coroutineScope.launch {
            mcpConfigRepository.effectiveEntriesFlow.collectLatest {
                _mcpEntries.value = it
            }
        }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit) = coroutineScope.launch(block = block)

    private val _mcpEntries = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpEntries: StateFlow<List<McpServerEntry>> = _mcpEntries.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _mcpAuthState = MutableStateFlow(McpAuthUiState())
    val mcpAuthState: StateFlow<McpAuthUiState> = _mcpAuthState.asStateFlow()

    private val _mcpImportState = MutableStateFlow(McpImportUiState())
    val mcpImportState: StateFlow<McpImportUiState> = _mcpImportState.asStateFlow()

    fun upsertMcpServer(originalName: String?, initialScope: McpScope?, config: McpServerConfig, scope: McpScope) {
        launch {
            saveMcpServerInternal(originalName, initialScope, config, scope)
        }
    }

    private suspend fun saveMcpServerInternal(
        originalName: String?,
        initialScope: McpScope?,
        config: McpServerConfig,
        scope: McpScope
    ) {
        // 作用域迁移：仅当保存作用域与原来不同时，从原作用域移除旧条目，
        // 避免残留条目在合并时（项目优先）继续覆盖新作用域的配置。
        if (originalName != null && initialScope != null && initialScope != scope) {
            val oldBase = if (initialScope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val oldUpdated = oldBase.filterNot { it.name == originalName }
            if (initialScope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(oldUpdated) else mcpConfigRepository.setProjectServers(oldUpdated)
        }
        val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
        val ordered = LinkedHashMap<String, McpServerConfig>()
        base.forEach { ordered[it.name] = it }
        if (originalName != null && originalName != config.name) {
            ordered.remove(originalName)
        }
        ordered[config.name] = config
        val updated = ordered.values.toList()
        if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
        _mcpReloading.value = true
        try {
            // 仅重连被改动的 server，其他 server 不受影响；重命名时先断开旧名。
            if (originalName != null && originalName != config.name) {
                mcpManager.removeServer(originalName)
            }
            mcpManager.reloadServer(config.name)
        } finally {
            _mcpReloading.value = false
        }
    }

    /** 保存最新表单配置并立即发起 OAuth 授权，确保弹窗中修改的 client_id 等字段即时生效。 */
    fun saveAndAuthorizeMcpServer(
        originalName: String?,
        initialScope: McpScope?,
        config: McpServerConfig,
        scope: McpScope
    ) {
        if (_mcpAuthState.value.inProgress) return
        launch {
            saveMcpServerInternal(originalName, initialScope, config, scope)
            _mcpAuthState.value = McpAuthUiState(serverName = config.name, inProgress = true)
            val result = mcpManager.authorize(config.name)
            _mcpAuthState.value = when {
                result.isSuccess -> McpAuthUiState(serverName = config.name, success = true)
                result.exceptionOrNull() is McpOAuthCancelledException -> McpAuthUiState()
                else -> McpAuthUiState(serverName = config.name, error = result.exceptionOrNull()?.message)
            }
        }
    }

    fun deleteMcpServer(name: String, scope: McpScope) {
        launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.filterNot { it.name == name }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.removeServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun setMcpServerEnabled(name: String, enabled: Boolean, scope: McpScope) {
        launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.map { if (it.name == name) it.copy(enabled = enabled) else it }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 触发某个 server 的 OAuth 授权，结果（含失败原因）写入 [mcpAuthState] 供编辑弹窗展示。 */
    fun authorizeMcpServer(name: String) {
        if (_mcpAuthState.value.inProgress) return
        launch {
            _mcpAuthState.value = McpAuthUiState(serverName = name, inProgress = true)
            val result = mcpManager.authorize(name)
            _mcpAuthState.value = when {
                result.isSuccess -> McpAuthUiState(serverName = name, success = true)
                result.exceptionOrNull() is McpOAuthCancelledException -> McpAuthUiState()
                else -> McpAuthUiState(serverName = name, error = result.exceptionOrNull()?.message)
            }
        }
    }

    fun cancelMcpAuthorize(name: String) {
        mcpManager.cancelAuthorize(name)
    }

    /** 从标准 mcpServers JSON 批量导入到指定作用域；同名覆盖。 */
    fun importMcpServersFromJson(context: Context, raw: String, scope: McpScope) {
        launch {
            _mcpImportState.value = McpImportUiState(inProgress = true)
            val parsed = runCatching { mcpConfigRepository.parse(raw) }.getOrElse {
                _mcpImportState.value = McpImportUiState(error = it.message)
                return@launch
            }
            val valid = parsed.filter { McpServerConfig.isValidName(it.name) }
            if (valid.isEmpty()) {
                _mcpImportState.value = McpImportUiState(error = context.getString(R.string.mcp_json_import_empty))
                return@launch
            }
            runCatching {
                val existing = if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.getGlobalServers()
                } else {
                    mcpConfigRepository.getProjectServers()
                }
                val merged = LinkedHashMap<String, McpServerConfig>()
                existing.forEach { merged[it.name] = it }
                valid.forEach { merged[it.name] = it }
                val list = merged.values.toList()
                if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.setGlobalServers(list)
                } else {
                    mcpConfigRepository.setProjectServers(list)
                }
            }.onSuccess {
                _mcpImportState.value = McpImportUiState(importedCount = valid.size)
                mcpManager.reload()
            }.onFailure {
                _mcpImportState.value = McpImportUiState(error = it.message)
            }
        }
    }

    fun clearMcpImportState() {
        _mcpImportState.value = McpImportUiState()
    }

    fun logoutMcpServer(name: String) {
        launch {
            mcpManager.logout(name)
            _mcpAuthState.value = McpAuthUiState()
        }
    }

    fun clearMcpAuthState() {
        _mcpAuthState.value = McpAuthUiState()
    }

    fun reloadMcp() {
        launch {
            _mcpReloading.value = true
            try {
                mcpManager.reload()
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 仅重连指定 server（编辑弹窗右上角刷新工具用）。 */
    fun reloadMcpServer(name: String) {
        launch {
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun getMcpServerTools(serverName: String?): List<McpToolDescriptor> {
        if (serverName.isNullOrBlank()) return emptyList()
        return mcpManager.getServerTools(serverName)
    }
}
