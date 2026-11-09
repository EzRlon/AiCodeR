package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage

/**
 * 收尾验收的输入组装：把会话消息整理成验收模型能读的三段——目标清单之外的
 * 「工具真实返回」（硬证据）与「助手自述」（补充）。
 *
 * 从 [StatefulAgentWorkflow] 抽出来的原因：这两个函数只依赖 [AgentMessage] 与常量，
 * 是纯函数，但原先藏在有 27 个依赖的 workflow 类里，测不到。抽出来后可以单测，
 * 也便于以后调整预算参数时先看测试。
 *
 * 设计要点（改动前务必先看）：
 * - **证据优先**：只给验收员看助手自述，等于让被验收方自己出证词。命令执行类工具
 *   （[EVIDENCE_TOOLS]）的原始返回才是可核对的依据。
 * - **不回溯压缩点之前**：只覆盖仍在上下文里的尾部消息，早于压缩点的结果取不回来。
 * - **命令输出保留两端**：结论在头和尾（开头是回显、结尾是成败判定），中间截断。
 */
object VerificationContextBuilder {

    /** 单条证据的字符上限。 */
    const val EVIDENCE_ITEM_CHARS = 800

    /** 最多取多少条命令类工具结果。 */
    const val EVIDENCE_MAX_ITEMS = 6

    /** 最多取多少条子代理报告（半证据，单独限量，别把命令证据挤掉）。 */
    const val EVIDENCE_MAX_DELEGATED = 2

    /** 证据总长上限。 */
    const val EVIDENCE_MAX_CHARS = 5_000

    /** 对话记录条数上限。 */
    const val CONVERSATION_MESSAGES = 18

    /** 对话记录单条字符上限。 */
    const val MESSAGE_MAX_CHARS = 1_000

    /** 对话记录总长上限。 */
    const val CONVERSATION_MAX_CHARS = 7_000

    /** 算硬证据的工具：命令执行类。子代理报告另算半证据。 */
    val EVIDENCE_TOOLS = setOf("Bash", "terminal", "run_script")

    /** 子代理工具名。 */
    const val DELEGATED_TOOL = "task"

    /**
     * 拼证据段：命令执行类工具的原始返回，连命令一起带上。
     * 只给验收员看助手自述，等于让被验收方自己出证词；构建/测试的真实返回才是可核对的依据。
     */
    fun buildEvidence(messages: List<AgentMessage>): String {
        val argumentsByCall = HashMap<String, String>()
        messages.forEach { msg ->
            if (msg is AgentMessage.AssistantMessage) {
                msg.toolCalls.forEach { call ->
                    argumentsByCall[call.id] = call.arguments.entries
                        .joinToString(" ") { (key, value) ->
                            "$key=${value.toString().removeSurrounding("\"")}"
                        }
                        .take(EVIDENCE_ITEM_CHARS / 2)
                }
            }
        }
        val results = messages.filterIsInstance<AgentMessage.ToolResultMessage>()
            .filter { it.result.isNotBlank() }
        val picked = results.filter { it.toolName in EVIDENCE_TOOLS }
            .takeLast(EVIDENCE_MAX_ITEMS) +
            results.filter { it.toolName == DELEGATED_TOOL }.takeLast(EVIDENCE_MAX_DELEGATED)
        val text = picked.joinToString("\n") { msg ->
            val title = if (msg.toolName == DELEGATED_TOOL) {
                "子代理报告"
            } else {
                "${msg.toolName}：${argumentsByCall[msg.id].orEmpty()}"
            }
            "--- $title\n${clipEvidence(msg.result)}"
        }
        return if (text.length > EVIDENCE_MAX_CHARS) text.take(EVIDENCE_MAX_CHARS) else text
    }

    /**
     * 拼对话段：只取用户与助手的可见文本，按尾部窗口与字符上限截断。
     * 工具真实返回另走 [buildEvidence]，这里的自述只作补充。
     */
    fun buildConversation(messages: List<AgentMessage>): String {
        val sb = StringBuilder()
        messages
            .filter { it is AgentMessage.UserMessage || it is AgentMessage.AssistantMessage }
            .takeLast(CONVERSATION_MESSAGES)
            .forEach { msg ->
                when (msg) {
                    is AgentMessage.UserMessage ->
                        if (msg.content.isNotBlank()) {
                            sb.appendLine("用户：${msg.content.take(MESSAGE_MAX_CHARS)}")
                        }
                    is AgentMessage.AssistantMessage ->
                        if (msg.content.isNotBlank()) {
                            sb.appendLine("助手：${msg.content.take(MESSAGE_MAX_CHARS)}")
                        }
                    else -> {}
                }
            }
        val text = sb.toString()
        return if (text.length > CONVERSATION_MAX_CHARS) {
            text.takeLast(CONVERSATION_MAX_CHARS)
        } else {
            text
        }
    }

    /** 命令输出保留两端：结论在头和尾，中间截断。 */
    fun clipEvidence(text: String): String {
        if (text.length <= EVIDENCE_ITEM_CHARS) return text
        val head = EVIDENCE_ITEM_CHARS / 3
        val tail = EVIDENCE_ITEM_CHARS - head
        return text.take(head) + "\n…\n" + text.takeLast(tail)
    }
}
