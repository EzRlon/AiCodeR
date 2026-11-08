package com.aicode.feature.store.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.store.data.StoreApiClient
import com.aicode.feature.store.data.StoreCatalogCache
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreSource
import com.aicode.feature.store.data.StoreSourceConfig
import com.aicode.feature.store.data.CmSkill
import com.aicode.feature.store.data.CmSkillsResponse
import com.aicode.feature.store.data.StoreKind
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.store.data.PluginStoreItem
import com.aicode.feature.store.data.TeamStoreItem
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.StoreTranslationService
import com.aicode.feature.store.data.StoreTranslationStore
import com.aicode.feature.store.data.nextCursorOrNull
import com.aicode.feature.store.data.storeTranslationKey
import com.aicode.feature.store.data.storeTranslationSourceHash
import com.aicode.feature.store.data.toStoreItem
import com.aicode.feature.store.domain.ScanResult
import com.aicode.feature.store.domain.EnginePluginInstaller
import com.aicode.feature.store.domain.StoreInstallScanner
import com.aicode.feature.store.domain.StoreInstaller
import com.aicode.feature.store.domain.SkillInstallPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 扫描命中后挂起的安装：携带完成安装所需的全部数据，确认后无需重新下载。
 * [key] 用于安装中状态与扫描结论缓存，[title] 展示在确认弹窗。
 */
sealed class PendingScanGate {
    abstract val scan: ScanResult
    abstract val key: String
    abstract val title: String

    data class Skill(val plan: SkillInstallPlan) : PendingScanGate() {
        override val scan: ScanResult get() = plan.scan
        override val key: String get() = plan.item.id
        override val title: String get() = plan.item.name
    }

    data class Mcp(val item: StoreItem, override val scan: ScanResult) : PendingScanGate() {
        override val key: String get() = item.id
        override val title: String get() = item.name
    }

    data class Team(val plan: TeamInstallPlan) : PendingScanGate() {
        override val scan: ScanResult get() = plan.scan
        override val key: String get() = plan.item.fileName
        override val title: String get() = plan.item.name
    }
}

/**
 * 专家团安装计划：内容已就绪、扫描已出结论，确认后直接写盘。
 * [agents] 为（目标文件名，正文）；builtin = 成员名，online = 清洗后的定义名。
 */
data class TeamInstallPlan(
    val item: TeamStoreItem,
    val scan: ScanResult,
    val teamText: String,
    val agents: List<Pair<String, String>>
)

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
    val message: String? = null,
    /** 条目 key（[storeTranslationKey]）→ 中文翻译（界面上双语展示用；仅在中文界面下生成）。 */
    val translations: Map<String, StoreTranslation> = emptyMap(),
    /** 正在后台翻译（列表顶部显示提示行）。 */
    val translating: Boolean = false,
    /** 翻译失败（默认模型不可用/返回异常）：提示行带重试入口，避免静默回退英文。 */
    val translationFailed: Boolean = false,
    /** 内置专家团（团队包）列表（EXPERT tab）。 */
    val teams: List<TeamStoreItem> = emptyList(),
    /** 扫描命中后挂起的安装（非空时展示确认 / 警示弹窗）；确认或取消后置回 null。 */
    val pendingScan: PendingScanGate? = null,
    /** 条目 key → 最近一次安装前安全扫描结论（详情弹层「安全扫描」区块展示）。 */
    val lastScans: Map<String, ScanResult> = emptyMap(),
    /** 引擎插件列表（PLUGIN tab）。 */
    val plugins: List<PluginStoreItem> = emptyList(),
    /** 插件引擎筛选：null = 全部。 */
    val pluginEngineFilter: EngineTarget? = null,
    /** 插件已安装的引擎组合（key = 插件 id，value = 已装的引擎集合）。 */
    val installedPluginEngines: Map<String, Set<EngineTarget>> = emptyMap()
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
    private val mcpConfig: McpConfigRepository,
    private val translationService: StoreTranslationService,
    private val translationStore: StoreTranslationStore,
    private val catalogCache: StoreCatalogCache,
    private val scanner: StoreInstallScanner,
    private val languageSettings: LanguageSettingsRepository,
    private val fileAccess: com.aicode.feature.workspace.domain.FileAccessProvider,
    private val sourceConfig: StoreSourceConfig,
    private val pluginInstaller: EnginePluginInstaller,
    baseClient: okhttp3.OkHttpClient,
    @param:ApplicationContext private val context: android.content.Context
) : ViewModel() {

    /** 商店在线源专用客户端（市场目录/在线专家团安装）：带超时，失败静默。 */
    private val storeClient: okhttp3.OkHttpClient = baseClient.newBuilder()
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private companion object {
        const val PAGE_SIZE = 20
        const val MCP_PAGE_SIZE = 50
        const val MCP_SEARCH_LIMIT = 50
        const val TAG = "StoreViewModel"
        /** 在线专家团市场（wshobson/agents，MIT）：经 gh-proxy 镜像访问。 */
        const val GH_PROXY = "https://gh-proxy.com/"
        const val MARKET_URL = GH_PROXY + "https://raw.githubusercontent.com/wshobson/agents/main/.claude-plugin/marketplace.json"
        const val PLUGIN_API_BASE = GH_PROXY + "https://api.github.com/repos/wshobson/agents/contents/"
    }

    private val _state = MutableStateFlow(StoreUiState())
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    private var page = 1
    private var mcpCursor: String? = null

    /** MCP 首页之后是否已翻页：后台刷新仅在本值为 false 时替换列表，避免覆盖用户已加载的页。 */
    private var mcpAdvanced = false

    /** 翻译任务：单 Job 串行，列表重新加载时取消旧任务重开（见 [kickTranslation]）。 */
    private var translationJob: Job? = null

    /** 已配置的商店源（含启用状态）；UI 用于展示来源与设置页。 */
    val sources: StateFlow<List<StoreSource>> = sourceConfig.sources

    init {
        viewModelScope.launch { sourceConfig.load() }
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

    /** 执行搜索：技能走 claudemarket 全库搜索，MCP 走官方 Registry 的 search 参数（服务端筛选）。 */
    fun search() {
        val q = _state.value.query.trim()
        if (q.isBlank()) {
            // 清空搜索回到浏览
            page = 1
            loadTab(_state.value.tab)
            return
        }
        if (_state.value.tab == StoreKind.MCP) {
            searchMcp(q)
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
                    kickTranslation()
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
            StoreKind.EXPERT -> Unit
            StoreKind.PLUGIN -> Unit
        }
    }

    private fun loadTab(tab: StoreKind) {
        when (tab) {
            StoreKind.SKILL -> loadSkills(nextPage = false)
            StoreKind.MCP -> loadMcp(next = false)
            StoreKind.EXPERT -> loadTeams()
            StoreKind.PLUGIN -> loadPlugins()
        }
    }

    private fun loadSkills(nextPage: Boolean) {
        viewModelScope.launch {
            if (nextPage) {
                _state.update { it.copy(loadingMore = true) }
                val target = page + 1
                // 翻页继续走默认源（缓存结构仍按单源 DTO 存），聚合只作用于首页
                api.listSkills(page = target, limit = PAGE_SIZE).fold(
                    onSuccess = { resp ->
                        if (_state.value.tab != StoreKind.SKILL) return@fold
                        page = target
                        val incoming = resp.results.map { it.toStoreItem() }
                        _state.update {
                            it.copy(
                                items = it.items + incoming,
                                loading = false,
                                loadingMore = false,
                                hasMore = incoming.size >= PAGE_SIZE
                            )
                        }
                        refreshInstalled()
                        kickTranslation()
                    },
                    onFailure = { e ->
                        if (_state.value.tab != StoreKind.SKILL) return@fold
                        _state.update {
                            it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败，请检查网络")
                        }
                    }
                )
                return@launch
            }
            // 首页：目录缓存秒出，网络后台刷新替换（stale-while-revalidate）
            page = 1
            val cached = catalogCache.readSkills()
            if (cached != null) {
                val items = cached.results.map { it.toStoreItem() }
                _state.update {
                    it.copy(items = items, loading = false, error = null, searching = false, hasMore = items.size >= PAGE_SIZE)
                }
                refreshInstalled()
                kickTranslation()
            } else {
                _state.update { it.copy(loading = true, error = null, searching = false) }
            }
            val fresh = fetchSkillsAggregated(PAGE_SIZE)
            if (fresh.isNotEmpty()) {
                catalogCache.saveSkills(CmSkillsResponse(count = fresh.size, total = fresh.size, results = fresh))
                // 守卫：切 tab / 已翻页 / 搜索中（搜索结果不能被浏览列表冲掉）
                if (_state.value.tab != StoreKind.SKILL || page != 1 ||
                    _state.value.searching || _state.value.query.isNotBlank()) return@launch
                val incoming = fresh.map { it.toStoreItem().withSource(it.source) }
                _state.update {
                    it.copy(items = incoming, loading = false, loadingMore = false, hasMore = false)
                }
                refreshInstalled()
                kickTranslation()
            } else if (_state.value.items.isEmpty()) {
                _state.update {
                    it.copy(loading = false, loadingMore = false, error = "加载失败，请检查网络")
                }
            } else {
                _state.update { it.copy(loading = false, loadingMore = false) }
            }
        }
    }

    /**
     * 多源聚合拉取技能：所有启用源并发请求各自首页，同 slug 高优先级源胜出，
     * 单源失败静默降级为仅其它源的结果；全部失败返回空列表。
     * 结果保留为 [CmSkill]（缓存序列化需要），来源信息写入其 source 字段供展示层转换。
     */
    private suspend fun fetchSkillsAggregated(limit: Int): List<CmSkill> = coroutineScope {
        val srcs = sourceConfig.enabledSources()
        if (srcs.isEmpty()) return@coroutineScope emptyList()
        val deferred = srcs.map { src ->
            async {
                if (src.id == "mcp-registry") return@async null
                api.listSkills(page = 1, limit = limit, baseUrl = src.baseUrl.takeIf { src.id != "claudemarket" })
                    .getOrNull()
                    ?.results
                    ?.map { r ->
                        // 来源信息借用 CmSkill.source 字段传递（原值仅作上游出处，展示层不依赖）
                        r.copy(source = src.id)
                    }
                    ?.let { src to it }
            }
        }
        val pairs = deferred.awaitAll().filterNotNull()
        if (pairs.isEmpty()) return@coroutineScope emptyList()
        // 高优先级源先入，同 slug 保留先见者
        val bySlug = LinkedHashMap<String, CmSkill>()
        for ((_, skills) in pairs.sortedByDescending { it.first.priority }) {
            for (s in skills) bySlug.putIfAbsent(s.slug, s)
        }
        bySlug.values.toList()
    }

    /** 按源 id 回填来源展示字段；内置/未知源回原样。 */
    private fun StoreItem.withSource(sourceId: String): StoreItem {
        val src = sourceConfig.sources.value.firstOrNull { it.id == sourceId } ?: return this
        return copy(sourceId = src.id, sourceName = src.name, sourceTrusted = src.trusted)
    }

    fun toggleSource(id: String, enabled: Boolean) {
        viewModelScope.launch { sourceConfig.setEnabled(id, enabled) }
    }

    fun removeSource(id: String) {
        viewModelScope.launch { sourceConfig.remove(id) }
    }

    fun addSource(source: StoreSource) {
        viewModelScope.launch { sourceConfig.add(source) }
    }

    /** MCP Registry 服务端搜索（只取一页，不接翻页）。 */
    private fun searchMcp(q: String) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, loading = true) }
            api.listMcpServers(limit = MCP_SEARCH_LIMIT, search = q).fold(
                onSuccess = { resp ->
                    if (_state.value.tab != StoreKind.MCP) return@fold
                    val items = resp.servers.map { it.server.toStoreItem() }.distinctBy { it.id }
                    mcpCursor = null
                    mcpAdvanced = true
                    _state.update {
                        it.copy(items = items, loading = false, searching = false, hasMore = false)
                    }
                    refreshInstalled()
                    kickTranslation()
                },
                onFailure = { e ->
                    if (_state.value.tab != StoreKind.MCP) return@fold
                    _state.update {
                        it.copy(loading = false, searching = false, error = e.message ?: "搜索失败，请检查网络")
                    }
                }
            )
        }
    }

    private fun loadMcp(next: Boolean) {
        viewModelScope.launch {
            if (next) {
                _state.update { it.copy(loadingMore = true) }
                mcpAdvanced = true
                api.listMcpServers(cursor = mcpCursor, limit = MCP_PAGE_SIZE).fold(
                    onSuccess = { resp ->
                        if (_state.value.tab != StoreKind.MCP) return@fold
                        mcpCursor = resp.nextCursorOrNull()
                        val incoming = resp.servers.map { it.server.toStoreItem() }
                        // 去重：MCP Registry 可能返回重复 name，导致 LazyColumn key 冲突
                        val dedupedIncoming = incoming.distinctBy { it.id }
                        _state.update {
                            it.copy(
                                items = (it.items + dedupedIncoming).distinctBy { item -> item.id },
                                loading = false,
                                loadingMore = false,
                                hasMore = mcpCursor != null
                            )
                        }
                        refreshInstalled()
                        kickTranslation()
                    },
                    onFailure = { e ->
                        if (_state.value.tab != StoreKind.MCP) return@fold
                        // 翻页失败回置守卫：仍在飞的首页刷新结果不应被丢弃
                        mcpAdvanced = false
                        _state.update {
                            it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败，请检查网络")
                        }
                    }
                )
                return@launch
            }
            // 首页：目录缓存秒出，网络后台刷新替换（stale-while-revalidate）
            mcpCursor = null
            mcpAdvanced = false
            val cached = catalogCache.readMcp()
            if (cached != null) {
                mcpCursor = cached.nextCursorOrNull()
                val items = cached.servers.map { it.server.toStoreItem() }.distinctBy { it.id }
                _state.update {
                    it.copy(items = items, loading = false, error = null, searching = false, hasMore = mcpCursor != null)
                }
                refreshInstalled()
                kickTranslation()
            } else {
                _state.update { it.copy(loading = true, error = null, searching = false) }
            }
            api.listMcpServers(cursor = null, limit = MCP_PAGE_SIZE).fold(
                onSuccess = { resp ->
                    catalogCache.saveMcp(resp)
                    // 守卫：切 tab / 已翻页 / 搜索中（搜索结果不能被浏览列表冲掉）
                    if (_state.value.tab != StoreKind.MCP || mcpAdvanced ||
                        _state.value.searching || _state.value.query.isNotBlank()) return@fold
                    mcpCursor = resp.nextCursorOrNull()
                    val incoming = resp.servers.map { it.server.toStoreItem() }
                    val dedupedIncoming = incoming.distinctBy { it.id }
                    _state.update {
                        it.copy(items = dedupedIncoming, loading = false, loadingMore = false, hasMore = mcpCursor != null)
                    }
                    refreshInstalled()
                    kickTranslation()
                },
                onFailure = { e ->
                    if (_state.value.tab != StoreKind.MCP) return@fold
                    _state.update {
                        if (it.items.isEmpty()) {
                            it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败，请检查网络")
                        } else {
                            it.copy(loading = false, loadingMore = false)
                        }
                    }
                }
            )
        }
    }

    /** 安装当前 tab 的条目：[item] 为 null 时表示缓存失效需重拉列表（此处不需要）。 */
    fun install(item: StoreItem) {
        if (_state.value.installingId != null || _state.value.pendingScan != null) return
        viewModelScope.launch {
            _state.update { it.copy(installingId = item.id) }
            try {
                when (item.kind) {
                    StoreKind.SKILL -> {
                        val plan = installer.planSkillInstall(item)
                        recordScan(plan.item.id, plan.scan)
                        resolveGate(PendingScanGate.Skill(plan)) {
                            val installed = installer.commitSkillInstall(plan)
                            linkTranslation(item.storeTranslationKey(), "SKILL:${installed.lowercase()}")
                            "已安装技能「$installed」（默认未启用，可在技能页开启）"
                        }
                    }
                    StoreKind.MCP -> {
                        val scan = installer.scanMcp(item)
                        recordScan(item.id, scan)
                        resolveGate(PendingScanGate.Mcp(item, scan)) {
                            val name = installer.installMcp(item)
                            linkTranslation(item.storeTranslationKey(), "MCP:$name")
                            "已添加 MCP 服务器「$name」（默认未启用，可在 MCP 页开启）"
                        }
                    }
                    StoreKind.EXPERT -> {
                        FileLogger.w(TAG, "install() 收到专家条目，已忽略")
                        _state.update { it.copy(installingId = null, message = "专家请在内置专家页安装") }
                    }
                    StoreKind.PLUGIN -> {
                        FileLogger.w(TAG, "install() 收到插件条目，已忽略")
                        _state.update { it.copy(installingId = null, message = "插件请在内置插件页按引擎安装") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(installingId = null, message = e.message ?: "安装失败") }
            }
        }
    }

    /** PASS 直接装（用户无感）；WARN/BLOCK 挂起等待用户确认（[onConfirm] 在确认后执行）。 */
    private suspend fun resolveGate(gate: PendingScanGate, onConfirm: suspend () -> String) {
        if (gate.scan is ScanResult.Pass) {
            _state.update { it.copy(installingId = null, message = onConfirm()) }
            refreshInstalled()
        } else {
            _state.update { it.copy(installingId = null, pendingScan = gate) }
        }
    }

    /** 用户在扫描弹窗确认「继续安装」：从挂起态取回数据直接落盘（不重新下载）。 */
    fun confirmPendingScan() {
        val gate = _state.value.pendingScan ?: return
        _state.update { it.copy(pendingScan = null, installingId = gate.key) }
        viewModelScope.launch {
            try {
                var teamInstalled = false
                val msg = when (gate) {
                    is PendingScanGate.Skill -> {
                        val installed = installer.commitSkillInstall(gate.plan)
                        linkTranslation(gate.plan.item.storeTranslationKey(), "SKILL:${installed.lowercase()}")
                        "已安装技能「$installed」（默认未启用，可在技能页开启）"
                    }
                    is PendingScanGate.Mcp -> {
                        val name = installer.installMcp(gate.item)
                        linkTranslation(gate.item.storeTranslationKey(), "MCP:$name")
                        "已添加 MCP 服务器「$name」（默认未启用，可在 MCP 页开启）"
                    }
                    is PendingScanGate.Team -> {
                        teamInstalled = withContext(Dispatchers.IO) { commitTeam(gate.plan) }
                        if (teamInstalled) {
                            context.getString(R.string.store_expert_installed_toast, gate.plan.item.name)
                        } else {
                            context.getString(R.string.store_expert_install_failed)
                        }
                    }
                }
                _state.update { s ->
                    s.copy(
                        installingId = null,
                        message = msg,
                        teams = if (gate is PendingScanGate.Team && teamInstalled) {
                            s.teams.map {
                                if (it.fileName == gate.plan.item.fileName) it.copy(installed = true) else it
                            }
                        } else {
                            s.teams
                        }
                    )
                }
                refreshInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(installingId = null, message = e.message ?: "安装失败") }
            }
        }
    }

    /** 用户在扫描弹窗取消：丢弃挂起的安装（已下载内容一并丢弃，下次安装重新走完整流程）。 */
    fun dismissPendingScan() {
        _state.update { it.copy(pendingScan = null) }
    }

    private fun recordScan(key: String, scan: ScanResult) {
        _state.update { it.copy(lastScans = it.lastScans + (key to scan)) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 加载专家团：内置团队（本地 assets）与上次在线市场缓存立即展示，在线市场后台刷新替换。 */
    fun loadTeams() {
        viewModelScope.launch {
            val builtin = withContext(Dispatchers.IO) { parseBuiltinTeams() }
            // 上次在线市场缓存立即合并；安装状态以本地现状为准（缓存可能是安装前存的）
            val cachedOnline = catalogCache.readOnlineTeams().orEmpty().map { team ->
                team.copy(installed = runCatching {
                    val path = if (team.online) "~/.aicode/teams/${team.name}.md" else "~/.aicode/teams/${team.fileName}"
                    fileAccess.exists(path)
                }.getOrDefault(false))
            }
            _state.update { it.copy(teams = builtin + cachedOnline) }
            // 专家团条目一并进翻译管线（用户反馈：专家团名称/描述此前不翻译）
            kickTranslation()
            // 在线市场刷新：失败或空结果保持缓存展示（上游字段漂移时 0 条不是真实状态），成功才替换
            val online = withContext(Dispatchers.IO) { fetchOnlineTeams() }
            if (_state.value.tab != StoreKind.EXPERT) return@launch
            if (!online.isNullOrEmpty()) {
                catalogCache.saveOnlineTeams(online)
                _state.update { it.copy(teams = builtin + online) }
                kickTranslation()
            }
        }
    }

    /**
     * 加载引擎插件：内置清单（assets/plugin_catalog.json）立即展示，随后按各引擎 `plugin list`
     * 回填已安装状态。已安装判定调引擎自己的 CLI，不读其插件目录。
     */
    fun loadPlugins() {
        viewModelScope.launch {
            val builtin = withContext(Dispatchers.IO) { parsePluginCatalog() }
            _state.update { it.copy(plugins = builtin) }
            kickTranslation()

            val installedByEngine = withContext(Dispatchers.IO) {
                EngineTarget.entries.associateWith { target ->
                    runCatching { pluginInstaller.listInstalled(target) }.getOrDefault(emptySet())
                }
            }
            if (_state.value.tab != StoreKind.PLUGIN) return@launch
            _state.update { s ->
                s.copy(
                    installedPluginEngines = s.plugins.associate { item ->
                        item.id to item.engines.filter { target ->
                            installedByEngine[target].orEmpty().any { it.contains(item.name) }
                        }.toSet()
                    }
                )
            }
        }
    }

    fun setPluginEngineFilter(target: EngineTarget?) {
        _state.update { it.copy(pluginEngineFilter = target) }
    }

    /** 安装插件到指定引擎：命令失败时把引擎输出带回 UI，不静默失败。 */
    fun installPlugin(item: PluginStoreItem, target: EngineTarget) {
        if (_state.value.installingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(installingId = item.id, error = null) }
            runCatching { pluginInstaller.install(item, target) }.fold(
                onSuccess = {
                    _state.update { s ->
                        s.copy(
                            installingId = null,
                            installedPluginEngines = s.installedPluginEngines.toMutableMap().apply {
                                put(item.id, get(item.id).orEmpty() + target)
                            }
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(installingId = null, error = e.message ?: "安装失败") }
                }
            )
        }
    }

    fun uninstallPlugin(item: PluginStoreItem, target: EngineTarget) {
        viewModelScope.launch {
            _state.update { it.copy(installingId = item.id, error = null) }
            runCatching { pluginInstaller.uninstall(item, target) }.fold(
                onSuccess = {
                    _state.update { s ->
                        s.copy(
                            installingId = null,
                            installedPluginEngines = s.installedPluginEngines.toMutableMap().apply {
                                put(item.id, get(item.id).orEmpty() - target)
                            }
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(installingId = null, error = e.message ?: "卸载失败") }
                }
            )
        }
    }

    /** 内置插件清单：assets/plugin_catalog.json（字段：id/name/description/engines/installRef/marketplace）。 */
    private fun parsePluginCatalog(): List<PluginStoreItem> = runCatching {
        val text = context.assets.open("plugin_catalog.json").bufferedReader().use { it.readText() }
        val arr = org.json.JSONObject(text).optJSONArray("plugins") ?: return@runCatching emptyList()
        val out = ArrayList<PluginStoreItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val engineNames = obj.optJSONArray("engines") ?: continue
            val engines = (0 until engineNames.length()).mapNotNull { idx ->
                when (engineNames.optString(idx)) {
                    "claude_code" -> EngineTarget.CLAUDE_CODE
                    "codex" -> EngineTarget.CODEX
                    "opencode" -> EngineTarget.OPENCODE
                    else -> null
                }
            }
            if (engines.isEmpty()) continue
            out.add(
                PluginStoreItem(
                    id = id,
                    name = obj.optString("name", id),
                    description = obj.optString("description"),
                    version = obj.optString("version"),
                    engines = engines,
                    installRef = obj.optString("installRef", id),
                    marketplaceSource = obj.optString("marketplaceSource").takeIf { it.isNotBlank() },
                    marketplaceName = obj.optString("marketplaceName").takeIf { it.isNotBlank() }
                )
            )
        }
        out.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "加载插件清单失败", e)
        emptyList()
    }

    /** 解析内置专家团（assets/teams 团队包 frontmatter）；已安装 = ~/.aicode/teams 存在同名文件。 */
    private fun parseBuiltinTeams(): List<TeamStoreItem> = runCatching {
        context.assets.list("teams").orEmpty()
            .filter { it.endsWith(".md") }
            .mapNotNull { file ->
                val text = context.assets.open("teams/$file")
                    .bufferedReader().use { it.readText() }
                val name = Regex("(?m)^name:\\s*(.+)$").find(text)
                    ?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val desc = Regex("(?m)^description:\\s*(.+)$").find(text)
                    ?.groupValues?.get(1)?.trim().orEmpty()
                val memberText = Regex("(?m)^members:\\s*\\[(.+?)]\\s*$").find(text)
                    ?.groupValues?.get(1).orEmpty()
                val members = memberText.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                TeamStoreItem(
                    fileName = file,
                    name = name,
                    description = desc,
                    members = members,
                    installed = runCatching {
                        fileAccess.exists("~/.aicode/teams/$file")
                    }.getOrDefault(false)
                )
            }.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "加载内置专家团失败", e)
        emptyList()
    }

    /** 拉取在线专家团市场清单（Claude Code 插件市场格式）；失败返回 null（调用方保持缓存展示）。 */
    private fun fetchOnlineTeams(): List<TeamStoreItem>? = runCatching {
        val request = okhttp3.Request.Builder().url(MARKET_URL).build()
        storeClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val root = org.json.JSONObject(resp.body?.string().orEmpty())
            val arr = root.optJSONArray("plugins") ?: return@runCatching null
            val plugins = ArrayList<TeamStoreItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                val source = obj.optString("source")
                if (!source.startsWith("./")) continue // 仅本地插件（外部条目无法保证可下载）
                plugins.add(
                    TeamStoreItem(
                        fileName = "online-$name.md",
                        name = name,
                        description = obj.optString("description"),
                        members = emptyList(),
                        installed = runCatching {
                            fileAccess.exists("~/.aicode/teams/$name.md")
                        }.getOrDefault(false),
                        online = true,
                        pluginBase = source.removePrefix("./")
                    )
                )
            }
            plugins.sortedBy { it.name.lowercase() }
        }
    }.getOrElse { e ->
        FileLogger.w(TAG, "拉取在线专家团市场失败", e)
        null
    }

    /** 安装专家团：内容就绪 + 安全扫描后写盘；扫描命中时挂起等待用户确认。 */
    fun installTeam(item: TeamStoreItem) {
        if (_state.value.installingId != null || _state.value.pendingScan != null) return
        viewModelScope.launch {
            _state.update { it.copy(installingId = item.fileName) }
            try {
                val plan = withContext(Dispatchers.IO) { prepareTeamPlan(item) }
                recordScan(item.fileName, plan.scan)
                if (plan.scan is ScanResult.Pass) {
                    val ok = withContext(Dispatchers.IO) { commitTeam(plan) }
                    _state.update { s ->
                        s.copy(
                            installingId = null,
                            teams = s.teams.map {
                                if (it.fileName == item.fileName && ok) it.copy(installed = true) else it
                            },
                            message = if (ok) {
                                context.getString(R.string.store_expert_installed_toast, item.name)
                            } else {
                                context.getString(R.string.store_expert_install_failed)
                            }
                        )
                    }
                } else {
                    _state.update {
                        it.copy(installingId = null, pendingScan = PendingScanGate.Team(plan))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "安装专家团失败: ${item.name}", e)
                _state.update {
                    it.copy(installingId = null, message = e.message ?: "安装失败，请重试")
                }
            }
        }
    }

    /** 专家团安装第一阶段：收齐将写入的全部文本（内置读 assets / 在线下载），过安全扫描。 */
    private fun prepareTeamPlan(item: TeamStoreItem): TeamInstallPlan {
        val (teamText, agents) = if (item.online) prepareOnlineTeam(item) else prepareBuiltinTeam(item)
        val scan = scanner.scanTexts(
            listOf("团队定义 ${item.fileName}" to teamText) +
                agents.map { (name, text) -> "成员 $name" to text }
        )
        return TeamInstallPlan(item, scan, teamText, agents)
    }

    /** 专家团安装第二阶段：把计划中的内容写盘（内置成员不覆盖已有定义）。 */
    private suspend fun commitTeam(plan: TeamInstallPlan): Boolean = runCatching {
        val teamPath = if (plan.item.online) {
            // name/成员名均来自远端不可信源（市场 JSON 经镜像），含路径分隔符即可写出目录外
            val safeTeam = safeName(plan.item.name) ?: return@runCatching false
            "~/.aicode/teams/$safeTeam.md"
        } else {
            "~/.aicode/teams/${plan.item.fileName}"
        }
        fileAccess.writeFile(teamPath, plan.teamText)
        plan.agents.forEach { (member, text) ->
            val safeMember = safeName(member) ?: return@forEach
            if (!fileAccess.exists("~/.aicode/agents/$safeMember.md")) {
                fileAccess.writeFile("~/.aicode/agents/$safeMember.md", text)
            }
        }
        // 译文按「商店键」写入，安装后本地按文件名查：迁移一份，设置页与专家团弹窗才能显示中文
        linkTranslation(plan.item.storeTranslationKey(), "EXPERT:${teamPath.substringAfterLast('/')}")
        true
    }.getOrElse { e ->
        FileLogger.w(TAG, "安装专家团失败", e)
        false
    }

    /** 安装完成后把商店条目的译文迁到本地条目键（失败不影响安装，界面回退原文）。 */
    private suspend fun linkTranslation(fromKey: String, toKey: String) {
        runCatching { translationStore.migrate(fromKey, toKey) }
            .onFailure { FileLogger.w(TAG, "迁移条目译文失败：$fromKey → $toKey", it) }
    }

    /** 远端提供的名称在拼进盘上路径前统一安全化：只留字母/数字/汉字/空格/._-，剔除分隔符与首部点号。 */
    private fun safeName(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() || it in "-_. " }.trim()
        return cleaned.takeIf { it.isNotEmpty() && !it.startsWith(".") }
    }

    /** 内置团队：读团队定义与成员专家 assets 原文（已存在则不覆盖的逻辑在 [commitTeam]）。 */
    private fun prepareBuiltinTeam(item: TeamStoreItem): Pair<String, List<Pair<String, String>>> {
        val teamText = context.assets.open("teams/${item.fileName}")
            .bufferedReader().use { it.readText() }
        val agents = item.members.mapNotNull { member ->
            runCatching {
                context.assets.open("agents/$member.md").bufferedReader().use { it.readText() }
            }.getOrNull()?.let { member to it }
        }
        return teamText to agents
    }

    /**
     * 在线团队（wshobson/agents 插件）安装内容准备：列出插件 agents 目录 → 逐个下载并清洗
     * frontmatter（外部工具名与本地不一致，剔除 tools/model 行，默认全工具由权限引擎兜底）→
     * 生成可直接召唤的团队定义。写盘动作在扫描确认后由 [commitTeam] 执行。
     */
    private fun prepareOnlineTeam(item: TeamStoreItem): Pair<String, List<Pair<String, String>>> {
        val base = item.pluginBase ?: throw IllegalStateException("该团队缺少插件目录信息")
        val files = fetchAgentFiles(base) ?: throw IllegalStateException("读取插件目录失败")
        if (files.isEmpty()) throw IllegalStateException("插件中没有成员定义")
        val agents = ArrayList<Pair<String, String>>()
        for ((fileName, downloadUrl) in files) {
            val text = downloadText(downloadUrl) ?: continue
            val cleaned = sanitizeExternalAgent(text)
            val defName = safeName(
                Regex("(?m)^name:\\s*(.+)$").find(cleaned)?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: fileName.removeSuffix(".md")
            ) ?: continue
            agents.add(defName to cleaned)
        }
        if (agents.isEmpty()) throw IllegalStateException("成员定义下载失败")
        val description = item.description.replace('\n', ' ').take(180)
        val team = buildString {
            appendLine("---")
            appendLine("name: ${item.name}")
            appendLine("description: $description")
            appendLine("members: [${agents.map { it.first }.joinToString(", ")}]")
            appendLine("---")
            appendLine("你是「${item.name}」专家团的团长。可派发的成员子代理：${agents.map { it.first }.joinToString("、")}（用 task 工具，agent 参数填成员名）。")
            appendLine("收到任务后先判断涉及哪些环节，再决定派谁与顺序；派活时写清目标、已知上下文、边界与期望产出（成员看不到你与用户的对话）；成员回报后整合结果并向用户交付。")
        }
        return team to agents
    }

    /** GitHub contents API 列插件 agents 目录；raw 直链统一改走 gh-proxy 镜像（国内可直连）。 */
    private fun fetchAgentFiles(pluginBase: String): List<Pair<String, String>>? = runCatching {
        val request = okhttp3.Request.Builder().url(PLUGIN_API_BASE + pluginBase + "/agents").build()
        storeClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val arr = org.json.JSONArray(resp.body?.string().orEmpty())
            val files = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (!name.endsWith(".md")) continue
                val url = obj.optString("download_url").takeIf { it.isNotBlank() } ?: continue
                files.add(name to (GH_PROXY + url))
            }
            files
        }
    }.getOrNull()

    private fun downloadText(url: String): String? = runCatching {
        val request = okhttp3.Request.Builder().url(url).build()
        storeClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** 清洗外部 agent 定义：剔除 tools/model/color 行（工具名与本地不一致，保留会造成空白名单）。 */
    private fun sanitizeExternalAgent(text: String): String {
        if (!text.startsWith("---")) return text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return text
        val frontmatter = text.substring(3, end)
            .lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("tools:") || t.startsWith("model:") || t.startsWith("color:")
            }
            .joinToString("\n")
        return "---" + frontmatter + "\n---" + text.substring(end + 4)
    }

    /**
     * 触发列表翻译（后台行为，不阻塞加载）：取消旧任务后单 Job 串行执行。
     * 界面语言非中文直接跳过；缓存命中的条目先载入 state，未命中的按批翻译后落缓存。
     */
    private fun kickTranslation() {
        translationJob?.cancel()
        _state.update { it.copy(translationFailed = false) }
        // LAZY + 赋值后再 start：确保协程体执行时 translationJob 已是本任务，finally 的当前任务判定才可靠
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (isChineseUi()) translatePendingItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "商店翻译任务异常", e)
                _state.update { it.copy(translationFailed = true) }
            } finally {
                // 已被新任务取代时不复位，避免踩掉新任务的 translating 状态
                if (translationJob === coroutineContext[Job]) {
                    _state.update { it.copy(translating = false) }
                }
            }
        }
        translationJob = job
        job.start()
    }

    /** 翻译失败提示行的重试入口。 */
    fun retryTranslation() {
        if (translationJob?.isActive == true) return
        kickTranslation()
    }

    private suspend fun translatePendingItems() {
        // 缓存先载入 state：已有译文立即可见，再只翻译缺失 / 源文变化的条目
        val cached = translationStore.all()
        _state.update { it.copy(translations = it.translations + cached) }
        // 技能/MCP 列表 + 专家团 + 引擎插件（映射为 StoreItem 共用同一缓存键体系）
        val candidates = _state.value.items + _state.value.teams.map { team ->
            StoreItem(
                id = team.fileName,
                kind = StoreKind.EXPERT,
                name = team.name,
                description = team.description,
                category = "",
                popularity = 0L,
                detailUrl = ""
            )
        } + _state.value.plugins.map { plugin ->
            StoreItem(
                id = plugin.id,
                kind = StoreKind.PLUGIN,
                name = plugin.name,
                description = plugin.description,
                category = "",
                popularity = 0L,
                detailUrl = ""
            )
        }
        val pending = candidates.filter { item ->
            val entry = cached[item.storeTranslationKey()]
            entry == null || entry.sourceHash != item.storeTranslationSourceHash()
        }
        if (pending.isEmpty()) return
        _state.update { it.copy(translating = true) }
        var allFailed = true
        for (batch in pending.chunked(StoreTranslationService.MAX_BATCH_SIZE)) {
            val result = runCatching { translationService.translateBatch(batch) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    FileLogger.w(TAG, "商店翻译批次失败", e)
                    emptyMap()
                }
            if (result.isEmpty()) continue
            allFailed = false
            translationStore.putAll(result)
            _state.update { it.copy(translations = it.translations + result) }
        }
        _state.update { it.copy(translationFailed = allFailed) }
    }

    /** 界面语言是否为中文：用户显式选择按 tag 判断，跟随系统时看系统语言。 */
    private suspend fun isChineseUi(): Boolean {
        val tag = languageSettings.snapshot()
        return if (tag.isNullOrBlank()) Locale.getDefault().language == "zh" else tag.startsWith("zh")
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
                        StoreKind.EXPERT -> null
                        StoreKind.PLUGIN -> null
                    }
                }.toSet()
                s.copy(installedIds = installed)
            }
        }
    }
}
