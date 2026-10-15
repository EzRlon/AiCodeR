package com.aicode.feature.store.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.store.data.StoreApiClient
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.StoreTranslationService
import com.aicode.feature.store.data.StoreTranslationStore
import com.aicode.feature.store.data.nextCursorOrNull
import com.aicode.feature.store.data.storeTranslationKey
import com.aicode.feature.store.data.storeTranslationSourceHash
import com.aicode.feature.store.data.toStoreItem
import com.aicode.feature.store.domain.StoreInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
    val message: String? = null,
    /** 条目 key（[storeTranslationKey]）→ 中文翻译（界面上双语展示用；仅在中文界面下生成）。 */
    val translations: Map<String, StoreTranslation> = emptyMap(),
    /** 正在后台翻译（列表顶部显示提示行）。 */
    val translating: Boolean = false
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
    private val languageSettings: LanguageSettingsRepository
) : ViewModel() {

    private companion object {
        const val PAGE_SIZE = 20
        const val MCP_PAGE_SIZE = 50
        const val TAG = "StoreViewModel"
    }

    private val _state = MutableStateFlow(StoreUiState())
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    private var page = 1
    private var mcpCursor: String? = null

    /** 翻译任务：单 Job 串行，列表重新加载时取消旧任务重开（见 [kickTranslation]）。 */
    private var translationJob: Job? = null

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
                    kickTranslation()
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
                    // 去重：MCP Registry 可能返回重复 name，导致 LazyColumn key 冲突
                    val dedupedIncoming = incoming.distinctBy { it.id }
                    _state.update {
                        val merged = if (next) (it.items + dedupedIncoming).distinctBy { it.id } else dedupedIncoming
                        it.copy(
                            items = merged,
                            loading = false,
                            loadingMore = false,
                            hasMore = mcpCursor != null
                        )
                    }
                    refreshInstalled()
                    kickTranslation()
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

    /**
     * 触发列表翻译（后台行为，不阻塞加载）：取消旧任务后单 Job 串行执行。
     * 界面语言非中文直接跳过；缓存命中的条目先载入 state，未命中的按批翻译后落缓存。
     */
    private fun kickTranslation() {
        translationJob?.cancel()
        // LAZY + 赋值后再 start：确保协程体执行时 translationJob 已是本任务，finally 的当前任务判定才可靠
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (isChineseUi()) translatePendingItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "商店翻译任务异常", e)
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

    private suspend fun translatePendingItems() {
        // 缓存先载入 state：已有译文立即可见，再只翻译缺失 / 源文变化的条目
        val cached = translationStore.all()
        _state.update { it.copy(translations = it.translations + cached) }
        val pending = _state.value.items.filter { item ->
            val entry = cached[item.storeTranslationKey()]
            entry == null || entry.sourceHash != item.storeTranslationSourceHash()
        }
        if (pending.isEmpty()) return
        _state.update { it.copy(translating = true) }
        for (batch in pending.chunked(StoreTranslationService.MAX_BATCH_SIZE)) {
            val result = runCatching { translationService.translateBatch(batch) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    FileLogger.w(TAG, "商店翻译批次失败", e)
                    emptyMap()
                }
            if (result.isEmpty()) continue
            translationStore.putAll(result)
            _state.update { it.copy(translations = it.translations + result) }
        }
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
                    }
                }.toSet()
                s.copy(installedIds = installed)
            }
        }
    }
}
