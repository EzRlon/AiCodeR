package com.aicode.feature.agent.presentation

import com.aicode.core.util.escapeLikePattern
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 聊天记录全局搜索（限当前工作区）：关键词防抖查询、片段截取与「打开命中」跳转。
 *
 * 从 AIAgentViewModel 拆出的独立域。与 VM 的耦合点只有三个，用回调注入：
 * 当前工作区路径、切换会话、按需抬高会话消息分页上限。
 */
@OptIn(FlowPreview::class)
class ChatSearchStateHolder @Inject constructor(
    private val agentMessageDao: AgentMessageDao
) {
    private companion object {
        const val SEARCH_LIMIT = 50
        const val DEBOUNCE_MS = 300L
        const val SNIPPET_CONTEXT = 40
        const val MESSAGE_LIMIT_MARGIN = 5
    }

    private lateinit var coroutineScope: CoroutineScope
    private lateinit var workspaceFlow: StateFlow<String>
    private lateinit var onSelectSession: (String) -> Unit
    private lateinit var onEnsureMessageLimit: suspend (sessionId: String, needed: Int) -> Unit

    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    lateinit var chatSearchState: StateFlow<ChatSearchState>
        private set

    /** 由 VM 注入作用域、工作区流与三个耦合点回调；重复调用无副作用。 */
    fun attach(
        scope: CoroutineScope,
        workspaceFlow: StateFlow<String>,
        onSelectSession: (String) -> Unit,
        onEnsureMessageLimit: suspend (String, Int) -> Unit
    ) {
        if (::coroutineScope.isInitialized) return
        coroutineScope = scope
        this.workspaceFlow = workspaceFlow
        this.onSelectSession = onSelectSession
        this.onEnsureMessageLimit = onEnsureMessageLimit
        chatSearchState = combine(workspaceFlow, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(DEBOUNCE_MS)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLikePattern(keyword),
                                SEARCH_LIMIT
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, ChatSearchState())
    }

    fun updateQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clear() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * 打开一条搜索命中：切到对应会话，把该会话分页上限抬到足够包含目标消息，并登记待定位消息。
     * 实际滚动由聊天页在消息就绪后完成。搜索词与结果保留，重开侧边栏仍停在结果列表。
     */
    fun openHit(hit: ChatSearchHit) {
        onSelectSession(hit.sessionId)
        coroutineScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + MESSAGE_LIMIT_MARGIN
                onEnsureMessageLimit(hit.sessionId, needed)
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** 以命中词为中心截取片段：折叠换行空白，两端按需加省略号；未命中时退回开头一段。 */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(SNIPPET_CONTEXT * 2)
        val start = (index - SNIPPET_CONTEXT).coerceAtLeast(0)
        val end = (index + keyword.length + SNIPPET_CONTEXT).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('…')
            append(flat, start, end)
            if (end < flat.length) append('…')
        }
    }
}
