package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.presentation.AgentAttachment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLI 引擎的「交付中继」：`aicode_bridge` 的 `send_file` 工具在 App 侧登记附件，
 * 三个引擎桥在解析到该工具的结果时取走并挂到 `ToolCallFinished.attachments` 上。
 *
 * 为什么要绕这一道：外部引擎的工具调用发生在 CLI 子进程里，桥只拿得到工具名和结果文本，
 * **拿不到「哪些文件是交付物」这个语义**。内置引擎靠 `sendFile` 工具显式表达交付，
 * 外部引擎必须有个等价物，否则交付页永远是空的（消息附件才是交付页的唯一数据源）。
 *
 * 用 sessionId 作键：同一时刻可能有多个 CLI 会话在跑，彼此的附件不能串。
 * [drain] 取走即清空——同一次调用只应渲染一次卡片，重复渲染会多出重复交付记录。
 */
@Singleton
class CliDeliveryRegistry @Inject constructor() {

    private val pending = ConcurrentHashMap<String, MutableList<AgentAttachment>>()

    fun record(sessionId: String, attachments: List<AgentAttachment>) {
        if (sessionId.isBlank() || attachments.isEmpty()) return
        pending.computeIfAbsent(sessionId) { mutableListOf() }.addAll(attachments)
    }

    /** 取走该会话已登记的附件（取走后清空）。 */
    fun drain(sessionId: String): List<AgentAttachment> {
        val items = pending.remove(sessionId) ?: return emptyList()
        return items.toList()
    }

    /** 会话结束时清理，避免残留（未取走的附件不该留在内存里）。 */
    fun clear(sessionId: String) {
        pending.remove(sessionId)
    }
}
