package com.aicode.feature.memorygraph.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.feature.memorygraph.data.GraphRepository
import com.aicode.feature.memorygraph.domain.model.GraphEdge
import com.aicode.feature.memorygraph.domain.model.GraphNode
import com.aicode.feature.memorygraph.domain.model.GraphSearchHit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆图谱的数据层：把「每条工作线索」的记忆维度聚合出来——
 * 目标（会话级 goal）、消息量与最后活动、待办进度、检查点、最后一条消息摘要。
 * 节点图仍由会话数据驱动；这里只负责详情卡与节点徽章所需的统计数据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoryGraphViewModel @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val checkpointDao: CheckpointDao,
    private val graphRepository: GraphRepository
) : ViewModel() {

    /** 每会话活动统计（sessionId → 行）。 */
    data class SessionActivity(
        val sessionId: String,
        val messageCount: Int,
        val lastActiveAt: Long,
        val todoDone: Int = 0,
        val todoTotal: Int = 0
    )

    /** 详情卡的完整线索画像。 */
    data class SessionDetail(
        val sessionId: String,
        val title: String,
        val goal: String?,
        val goalStatus: String?,
        val messageCount: Int,
        val lastActiveAt: Long,
        val lastMessagePreview: String?,
        val todoDone: Int,
        val todoTotal: Int,
        val todoActiveTitle: String?,
        val checkpointCount: Int
    )

    private val _activity = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val activity: StateFlow<Map<String, SessionActivity>> = _activity.asStateFlow()

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    /** 全量刷新会话活动统计（进图/刷新时调用）：消息聚合 + 待办进度聚合。 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val msgRows = agentMessageDao.sessionActivity().associateBy { it.sessionId }
                val todoRows = runCatching { todoItemDao.todoCountsBySession() }.getOrDefault(emptyList())
                    .associateBy { it.sessionId }
                _activity.value = msgRows.mapValues { (_, row) ->
                    val todo = todoRows[row.sessionId]
                    SessionActivity(
                        sessionId = row.sessionId,
                        messageCount = row.messageCount,
                        lastActiveAt = row.lastActiveAt,
                        todoDone = todo?.done ?: 0,
                        todoTotal = todo?.total ?: 0
                    )
                }
            }
        }
    }

    /** 打开详情卡：懒加载该会话的待办/检查点/最后消息。 */
    fun openDetail(sessionId: String, title: String, goal: String?, goalStatus: String?, activity: SessionActivity?) {
        _detail.value = SessionDetail(
            sessionId = sessionId,
            title = title,
            goal = goal,
            goalStatus = goalStatus,
            messageCount = activity?.messageCount ?: 0,
            lastActiveAt = activity?.lastActiveAt ?: 0L,
            lastMessagePreview = null,
            todoDone = 0,
            todoTotal = 0,
            todoActiveTitle = null,
            checkpointCount = 0
        )
        viewModelScope.launch(Dispatchers.IO) {
            val todos = runCatching { todoItemDao.getBySessionOnce(sessionId) }.getOrDefault(emptyList())
            val checkpoints = runCatching { checkpointDao.getCheckpointsForSession(sessionId) }.getOrDefault(emptyList())
            val lastMsg = runCatching { agentMessageDao.lastMessageContent(sessionId) }.getOrNull()
            val current = _detail.value ?: return@launch
            if (current.sessionId != sessionId) return@launch
            _detail.value = current.copy(
                lastMessagePreview = lastMsg?.replace(Regex("\\s+"), " ")?.take(200),
                todoTotal = todos.size,
                todoDone = todos.count { it.status == TodoStatus.COMPLETED.name },
                todoActiveTitle = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS.name }?.subject,
                checkpointCount = checkpoints.size
            )
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    // ── 记忆图谱 v2：节点/边层（GraphRepository）──────────────────

    private val graphWorkspace = MutableStateFlow("")

    /** 当前工作区的节点/边（同步后经 DB 流自动反映）。 */
    val graph: StateFlow<Pair<List<GraphNode>, List<GraphEdge>>?> = graphWorkspace
        .flatMapLatest { ws ->
            if (ws.isBlank()) flowOf(null)
            else graphRepository.observeGraph(ws).map { it as Pair<List<GraphNode>, List<GraphEdge>>? }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 检索结果；null = 未搜索（展示全量节点）。 */
    private val _graphHits = MutableStateFlow<List<GraphSearchHit>?>(null)
    val graphHits: StateFlow<List<GraphSearchHit>?> = _graphHits.asStateFlow()

    /** 进图/切工作区/刷新：先幂等同步（会话+记忆双源 → 节点/边），DB 流自动刷新 UI。 */
    fun syncGraph(workspacePath: String) {
        if (workspacePath.isBlank()) return
        graphWorkspace.value = workspacePath
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graphRepository.syncWorkspaceGraph(workspacePath) }
        }
    }

    private var graphSearchJob: Job? = null

    fun searchGraph(workspacePath: String, query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 过短查询退回全量（置 null），避免「删空搜索框显示假空态」（qa P1-4）
            if (query.trim().length < 2) {
                graphSearchJob?.cancel()
                _graphHits.value = null
                return@launch
            }
            // 键击防抖（optimizer #2）：取消前一次未完成的检索
            graphSearchJob?.cancel()
            graphSearchJob = launch {
                delay(250)
                runCatching {
                    _graphHits.value = graphRepository.search(workspacePath, query)
                }.onFailure { FileLogger.e("MemoryGraphVM", "图谱检索失败", it) }
            }
        }
    }

    fun clearGraphSearch() {
        _graphHits.value = null
    }

    fun addGraphNode(workspacePath: String, name: String, summary: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graphRepository.addCustomNode(workspacePath, name, summary) }
        }
    }

    fun deleteGraphNode(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graphRepository.deleteNode(id) }
        }
    }
}
