package com.aicode.feature.memorygraph.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆图谱的数据层：把「每条工作线索」的记忆维度聚合出来——
 * 目标（会话级 goal）、消息量与最后活动、待办进度、检查点、最后一条消息摘要。
 * 节点图仍由会话数据驱动；这里只负责详情卡与节点徽章所需的统计数据。
 */
@HiltViewModel
class MemoryGraphViewModel @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val checkpointDao: CheckpointDao
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
}
