package com.aicode.feature.store.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.store.data.StoreApiClient
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import com.aicode.feature.store.data.TeamStoreItem
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.StoreTranslationService
import com.aicode.feature.store.data.StoreTranslationStore
import com.aicode.feature.store.data.nextCursorOrNull
import com.aicode.feature.store.data.storeTranslationKey
import com.aicode.feature.store.data.storeTranslationSourceHash
import com.aicode.feature.store.data.toStoreItem
import com.aicode.feature.store.domain.StoreInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val translating: Boolean = false,
    /** 翻译失败（默认模型不可用/返回异常）：提示行带重试入口，避免静默回退英文。 */
    val translationFailed: Boolean = false,
    /** 内置专家团（团队包）列表（EXPERT tab）。 */
    val teams: List<TeamStoreItem> = emptyList()
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
    private val languageSettings: LanguageSettingsRepository,
    private val fileAccess: com.aicode.feature.workspace.domain.FileAccessProvider,
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
            StoreKind.EXPERT -> Unit
        }
    }

    private fun loadTab(tab: StoreKind) {
        when (tab) {
            StoreKind.SKILL -> loadSkills(nextPage = false)
            StoreKind.MCP -> loadMcp(next = false)
            StoreKind.EXPERT -> loadTeams()
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
                    StoreKind.EXPERT -> {
                        FileLogger.w(TAG, "install() 收到专家条目，已忽略")
                        "专家请在内置专家页安装"
                    }
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

    /** 加载内置专家团（assets/teams 团队包）：解析 frontmatter；已安装 = ~/.aicode/teams 存在同名文件。 */
    fun loadTeams() {
        viewModelScope.launch {
            val teams = withContext(Dispatchers.IO) {
                runCatching {
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
            }
            // 在线专家团市场（wshobson/agents，经 gh-proxy 镜像）：失败静默，仅展示内置
            val online = withContext(Dispatchers.IO) { fetchOnlineTeams() }
            _state.update { it.copy(teams = teams + online) }
            // 专家团条目一并进翻译管线（用户反馈：专家团名称/描述此前不翻译）
            kickTranslation()
        }
    }

    /** 拉取在线专家团市场清单（Claude Code 插件市场格式：.claude-plugin/marketplace.json）。 */
    private fun fetchOnlineTeams(): List<TeamStoreItem> = runCatching {
        val request = okhttp3.Request.Builder().url(MARKET_URL).build()
        storeClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()
            val root = org.json.JSONObject(resp.body?.string().orEmpty())
            val arr = root.optJSONArray("plugins") ?: return@runCatching emptyList()
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
        emptyList()
    }

    /** 安装专家团：内置团队写团队定义并补齐成员；在线团队下载成员定义后生成团队。 */
    fun installTeam(item: TeamStoreItem) {
        if (_state.value.installingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(installingId = item.fileName) }
            val ok = withContext(Dispatchers.IO) {
                if (item.online) installOnlineTeam(item) else installBuiltinTeam(item)
            }
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
        }
    }

    /** 内置团队：写入团队定义，并补齐成员专家定义（已存在则不覆盖）。 */
    private fun installBuiltinTeam(item: TeamStoreItem): Boolean = runCatching {
        val teamText = context.assets.open("teams/${item.fileName}")
            .bufferedReader().use { it.readText() }
        fileAccess.writeFile("~/.aicode/teams/${item.fileName}", teamText)
        item.members.forEach { member ->
            val agentText = runCatching {
                context.assets.open("agents/$member.md").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@forEach
            if (!fileAccess.exists("~/.aicode/agents/$member.md")) {
                fileAccess.writeFile("~/.aicode/agents/$member.md", agentText)
            }
        }
        true
    }.getOrElse { e ->
        FileLogger.w(TAG, "安装内置专家团失败", e)
        false
    }

    /**
     * 在线团队（wshobson/agents 插件）：列出插件 agents 目录 → 逐个下载并清洗 frontmatter
     * （外部工具名与本地不一致，剔除 tools/model 行，默认全工具由权限引擎兜底）→
     * 写入 ~/.aicode/agents，再生成可直接召唤的团队定义。
     */
    private fun installOnlineTeam(item: TeamStoreItem): Boolean = runCatching {
        val base = item.pluginBase ?: return false
        val files = fetchAgentFiles(base) ?: return false
        if (files.isEmpty()) return false
        val installedNames = ArrayList<String>()
        for ((fileName, downloadUrl) in files) {
            val text = downloadText(downloadUrl) ?: continue
            val cleaned = sanitizeExternalAgent(text)
            val defName = Regex("(?m)^name:\\s*(.+)$").find(cleaned)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fileName.removeSuffix(".md")
            fileAccess.writeFile("~/.aicode/agents/$defName.md", cleaned)
            installedNames.add(defName)
        }
        if (installedNames.isEmpty()) return false
        val description = item.description.replace('\n', ' ').take(180)
        val team = buildString {
            appendLine("---")
            appendLine("name: ${item.name}")
            appendLine("description: $description")
            appendLine("members: [${installedNames.joinToString(", ")}]")
            appendLine("---")
            appendLine("你是「${item.name}」专家团的团长。可派发的成员子代理：${installedNames.joinToString("、")}（用 task 工具，agent 参数填成员名）。")
            appendLine("收到任务后先判断涉及哪些环节，再决定派谁与顺序；派活时写清目标、已知上下文、边界与期望产出（成员看不到你与用户的对话）；成员回报后整合结果并向用户交付。")
        }
        fileAccess.writeFile("~/.aicode/teams/${item.name}.md", team)
        true
    }.getOrElse { e ->
        FileLogger.w(TAG, "安装在线专家团失败: ${item.name}", e)
        false
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
        // 技能/MCP 列表 + 专家团（映射为 EXPERT 条目共用同一缓存键体系）
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
                    }
                }.toSet()
                s.copy(installedIds = installed)
            }
        }
    }
}
