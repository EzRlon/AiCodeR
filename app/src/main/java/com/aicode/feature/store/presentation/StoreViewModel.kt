package com.aicode.feature.store.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.store.data.StoreApiClient
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import com.aicode.feature.store.data.nextCursorOrNull
import com.aicode.feature.store.data.toStoreItem
import com.aicode.feature.store.domain.StoreInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 商店页 UI 状态。 */
data class StoreUiState(
    val tab: StoreKind = StoreKind.SKILL,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** 当前列表（浏览模式）；搜索时替换为搜索结果。 */
    val items: List<StoreItem> = emptyList(),
    val query: String = "",
    val searching: Boolean = false,
    /** 是否还有下一页（技能=分页 / MCP=cursor）。 */
    val hasMore: Boolean = false,
    /** 已安装条目 id 集合（技能=名称小写；MCP=配置名）。 */
    val installedIds: Set<String> = emptySet(),
    /** 正在安装的条目 id；null 表示空闲。 */
    val installingId: String? = null,
    /** 一次性提示消息（UI 消费后调 [consumeMessage]）。 */
    val message: String? = null
)

/**
 * 在线商店：技能（claudemarket.ai）与 MCP 服务器（官方 Registry）浏览 / 搜索 / 安装。
 *
 * 安装走 [StoreInstaller]（安装 ≠ 启用，默认禁用到用户显式启用）；已装状态与本地
 * 技能/ MCP 配置实时对齐（每次列表加载与安装完成后刷新）。
 */
@HiltViewModel
class StoreViewModel @Inject constructor(
    private val api: StoreApiClient,
    private val installer: StoreInstaller,
    private val skillRepository: SkillRepository,
    private val mcpConfig: McpConfigRepository
) : ViewModel() {

    private companion object {
        const val PAGE_SIZE = 20
        const val MCP_PAGE_SIZE = 50
    }

    private val _state = MutableStateFlow(StoreUiState())
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    private var page = 1
    private var mcpCursor: String? = null

    init {
        loadTab(StoreKind.SKILL)
    }

    fun setTab(tab: StoreKind) {
        if (_state.value.tab == tab) return
        _state.value = StoreUiState(tab = tab)   // 重置列表与搜索
        loadTab(tab)
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** 执行搜索（仅技能 tab；MCP 不提供搜索）。 */
    fun search() {
        val q = _state.value.query.trim()
        if (q.isBlank()) {
            // 清空搜索回到浏览
            page = 1
            loadTab(_state.value.tab)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, loading = true) }
            val result = api.search(q)
            result.fold(
                onSuccess = { resp ->
                    val skills = resp.results.filter { it.type == "skill" }.map {
                        StoreItem(
                            id = it.url.substringAfterLast('/'),
                            kind = StoreKind.SKILL,
                            name = it.name,
                            description = it.description,
                            category = it.category,
                            popularity = it.popularity,
                            detailUrl = it.url
                        )
                    }
                    _state.update { it.copy(items = skills, loading = false, searching = false, hasMore = false) }
                    refreshInstalled()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, searching = false, error = e.message ?: "搜索失败，请检查网络")
                    }
                }
            )
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore || s.searching) return
        when (s.tab) {
            StoreKind.SKILL -> loadSkills(nextPage = true)
            StoreKind.MCP -> loadMcp(next = true)
        }
    }

    private fun loadTab(tab: StoreKind) {
        when (tab) {
            StoreKind.SKILL -> loadSkills(nextPage = false)
            StoreKind.MCP -> loadMcp(next = false)
        }
    }

    private fun loadSkills(nextPage: Boolean) {
        viewModelScope.launch {
            if (nextPage) _state.update { it.copy(loadingMore = true) } else {
                page = 1
                _state.update { it.copy(loading = true, error = null, searching = false) }
            }
            val target = if (nextPage) page + 1 else 1
            api.listSkills(page = target, limit = PAGE_SIZE).fold(
                onSuccess = { resp ->
                    page = target
                    val incoming = resp.results.map { it.toStoreItem() }
                    _state.update {
                        it.copy(
                            items = if (nextPage) it.items + incoming else incoming,
                            loading = false,
                            loadingMore = false,
                            hasMore = incoming.size >= PAGE_SIZE
                        )
                    }
                    refreshInstalled()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败，请检查网络")
                    }
                }
            )
        }
    }

    private fun loadMcp(next: Boolean) {
        viewModelScope.launch {
            if (next) _state.update { it.copy(loadingMore = true) } else {
                mcpCursor = null
                _state.update { it.copy(loading = true, error = null, searching = false) }
            }
            api.listMcpServers(cursor = if (next) mcpCursor else null, limit = MCP_PAGE_SIZE).fold(
                onSuccess = { resp ->
                    mcpCursor = resp.nextCursorOrNull()
                    val incoming = resp.servers.map { it.server.toStoreItem() }
                    _state.update {
                        it.copy(
                            items = if (next) it.items + incoming else incoming,
                            loading = false,
                            loadingMore = false,
                            hasMore = mcpCursor != null
                        )
                    }
                    refreshInstalled()
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败，请检查网络")
                    }
                }
            )
        }
    }

    /** 安装当前 tab 的条目：[item] 为 null 时表示缓存失效需重拉列表（此处不需要）。 */
    fun install(item: StoreItem) {
        if (_state.value.installingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(installingId = item.id) }
            val result = runCatching {
                when (item.kind) {
                    StoreKind.SKILL -> "已安装技能「${installer.installSkill(item)}」（默认未启用，可在技能页开启）"
                    StoreKind.MCP -> "已添加 MCP 服务器「${installer.installMcp(item)}」（默认未启用，可在 MCP 页开启）"
                }
            }
            result.fold(
                onSuccess = { msg ->
                    _state.update { it.copy(installingId = null, message = msg) }
                    refreshInstalled()
                },
                onFailure = { e ->
                    _state.update { it.copy(installingId = null, message = e.message ?: "安装失败") }
                }
            )
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 刷新「已安装」集合：本地技能名（忽略大小写）+ 本地 MCP 配置名。 */
    private fun refreshInstalled() {
        viewModelScope.launch {
            val skillNames = runCatching {
                skillRepository.listAllSkills().map { it.skill.name.lowercase() }.toSet()
            }.getOrDefault(emptySet())
            val mcpNames = runCatching {
                mcpConfig.getGlobalServers().map { it.name }.toSet()
            }.getOrDefault(emptySet())
            _state.update { s ->
                val installed = s.items.mapNotNull { item ->
                    when (item.kind) {
                        StoreKind.SKILL -> if (item.name.lowercase() in skillNames) item.id else null
                        StoreKind.MCP -> if (installer.sanitizeMcpName(item) in mcpNames) item.id else null
                    }
                }.toSet()
                s.copy(installedIds = installed)
            }
        }
    }
}
