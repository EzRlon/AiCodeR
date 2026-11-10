package com.aicode.feature.agent.domain.engine

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.model.TodoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外部引擎原生待办 → App 待办表镜像。
 *
 * 背景：待办卡片（TodoDashboardBar）的数据源是 `todo_items` 表，此前只有内置引擎的 [TodoTool]
 * 会写这张表。三个外部引擎用各自 CLI 的原生待办（Claude 的 TodoWrite、Codex 的 todo_list、
 * OpenCode 的 todowrite），桥接层只把它们翻译成通用工具行事件、从不落 `todo_items`——导致外部
 * 引擎会话里待办卡片**永不出现**（自然也谈不上刷新与自动收起）。
 *
 * 这里做**快照式镜像**（与内置 [TodoTool.replaceTodos] 同一语义）：每次 CLI 汇报全量待办清单时，
 * 按主题（normalize 后）对齐旧条目保留 id/createdAt，整表替换该会话的待办。消费端 Flow 自动收到
 * 新列表，卡片的拉起/进度刷新/「全部完成 + AI 空闲自动收起」全部复用既有链路，无需 UI 改动。
 *
 * 各 CLI 载荷字段名互不相同（且随版本演进），解析**尽力而为**：按候选字段名逐个取值，
 * 取不到主题的条目跳过；整包解析失败只记日志，不影响回合事件流。
 */
@Singleton
class EngineTodoMirror @Inject constructor(
    private val todoItemDao: TodoItemDao
) {
    /** 一次镜像写入。 [todos] 为空列表 = 清空该会话待办（与内置工具传空数组同义）。 */
    suspend fun mirror(sessionId: String, todos: List<NativeTodo>) {
        if (sessionId.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = todoItemDao.getBySessionOnce(sessionId)
                    .groupBy { normalizeSubject(it.subject) }
                    .mapValues { (_, items) -> items.toMutableList() }
                val now = System.currentTimeMillis()
                val entities = mutableListOf<TodoItemEntity>()
                for ((idx, todo) in todos.withIndex()) {
                    if (todo.subject.isBlank()) continue
                    val previous = existing[normalizeSubject(todo.subject)]?.removeFirstOrNull()
                    entities += TodoItemEntity(
                        id = previous?.id ?: UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        subject = todo.subject,
                        description = todo.description,
                        status = todo.status.name,
                        priority = todo.priority,
                        order = idx,
                        createdAt = previous?.createdAt ?: now,
                        updatedAt = now
                    )
                }
                todoItemDao.deleteBySession(sessionId)
                if (entities.isNotEmpty()) todoItemDao.upsertAll(entities)
                FileLogger.d(TAG, "外部引擎待办镜像: sid=${sessionId.takeLast(8)} ${entities.size} 项")
            }.onFailure {
                FileLogger.w(TAG, "外部引擎待办镜像失败: ${it.message}", it)
            }
        }
    }

    /** 从 CLI 的待办 JSON 数组尽力解析（字段名跨引擎/版本差异大，逐个候选字段取值）。 */
    fun parseTodos(array: JsonArray?): List<NativeTodo> {
        if (array == null) return emptyList()
        val todos = mutableListOf<NativeTodo>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            // 主题：Claude 用 content/subject，Codex 用 step，OpenCode 用 content/contentHtml
            val subject = obj.firstPresent("content", "subject", "step", "task", "text", "title")
                ?.trim() ?: continue
            // 状态：全部引擎共用 pending / in_progress / completed（大小写与连字符归一）
            val status = obj.firstPresent("status", "state")?.let { parseStatus(it) } ?: TodoStatus.PENDING
            // 优先级：Codex 的 PlanItem 无 priority；Claude/OpenCode 也不一定给
            val priority = obj.firstPresent("priority")?.toIntOrNull() ?: 0
            // 描述：Claude 无独立描述；OpenCode 的 description 可能进 content
            val description = obj.firstPresent("description", "details", "activeForm") ?: ""
            todos += NativeTodo(subject = subject, description = description, status = status, priority = priority)
        }
        return todos
    }

    /** 在候选字段名里取第一个非空字符串值。 */
    private fun JsonObject.firstPresent(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            val content = value.contentOrNull ?: continue
            if (content.isNotBlank()) return content
    }
        return null
    }

    private fun parseStatus(raw: String): TodoStatus {
        val normalized = raw.trim().replace("-", "_").replace(" ", "_").uppercase()
        return runCatching { TodoStatus.valueOf(normalized) }.getOrDefault(TodoStatus.PENDING)
    }

    private fun normalizeSubject(subject: String): String = subject.trim().lowercase()

    /** CLI 原生待办条目的引擎无关投影。 */
    data class NativeTodo(
        val subject: String,
        val description: String = "",
        val status: TodoStatus = TodoStatus.PENDING,
        val priority: Int = 0
    )

    private companion object {
        const val TAG = "EngineTodoMirror"
    }
}
