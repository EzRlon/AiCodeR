package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.provider.NormalizedUsage
import com.aicode.feature.agent.domain.provider.UsageNormalizer
import com.aicode.feature.agent.domain.workflow.AgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * dsh headless `--json` NDJSON 事件流 → [AgentEvent] 的纯翻译器：无容器/网络依赖，可独立单测。
 *
 * 事件契约按 @deepseek-ai/dsh 0.2.2-rc.5 实测校准（非仅文档）：
 * - 事件序列 session → status(phase=turn_start/step_start/step_end/turn_end) → tool_call →
 *   tool_result → text/thinking → final；每行一个 JSON 对象。
 * - 终态只认 final：final.reason.kind ∈ {completed, error, aborted…}，答案在 final.text
 *   （字段名 text，无 answer）；错误细节在 final.reason.error.{code,name,message}。
 * - 无 final 行即退出（未知 --session-id 报 "session … does not exist" 且 exit 1）；
 *   无凭据时 final.reason.error.code=MISSING_CREDENTIAL、消息含 "no API key for provider route"。
 * - text/thinking 在步骤提交点整段到达，非逐 token 增量；token 用量承载在 status 事件的
 *   usage 对象（字段名 snake/camel 均有出现可能，多候选探测）。
 * - tool_result 可能不带 id：按 FIFO 配对最近的未完成 tool_call。
 */
internal class DeepSeekEventTranslator(
    /** send_file 交付物提取（kind:data 经 PermissionBridgeServer 落库后 drain）；测试传空。 */
    private val deliveryDrain: (sessionId: String) -> List<com.aicode.feature.agent.presentation.AgentAttachment> = { emptyList() }
) {

    private val json = Json { ignoreUnknownKeys = true }

    internal fun turnAccumulator(sessionId: String): TurnAccumulator = TurnAccumulator(sessionId)

    /** 单回合累积状态：dsh 无逐 token 增量，text/thinking 在步骤提交点交付。 */
    internal class TurnAccumulator(val sessionId: String) {
        val reasoning = StringBuilder()
        /** tool_call id → 展示工具名：tool_result 回来时补齐 Finished 的 toolName；无 id 时按 FIFO 配对。 */
        val toolNames = LinkedHashMap<String, String>()
        val pendingToolCalls = ArrayDeque<String>()
        var threadId: String? = null
        var pendingUsage: NormalizedUsage? = null
        /** 最近一条顶层 error 文本：EOF 分类时兜底。 */
        var lastError: String? = null
        var emittedAny = false
        /** 本回合是否已发过 text 事件：final.text 只在没有 text 时兜底补发，避免重复。 */
        var hadText = false
    }

    internal fun parseLine(line: String, acc: TurnAccumulator): List<AgentEvent> {
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        return when (root.str("type")) {
            "session" -> {
                // 0.2.2-rc.x 实测字段为 sessionId（camelCase）；保留 snake 候选防上游变更
                root.str("sessionId")?.let { acc.threadId = it }
                root.str("session_id")?.let { acc.threadId = it }
                emptyList()
            }
            "status" -> {
                parseUsage(root)?.let { acc.pendingUsage = it }
                emptyList()
            }
            "tool_call" -> {
                val id = root.str("id") ?: "tc-${acc.pendingToolCalls.size}"
                val name = root.str("name") ?: root.str("tool") ?: "tool"
                acc.toolNames[id] = name
                acc.pendingToolCalls.addLast(id)
                val argsPreview = (root.obj("args") ?: root.obj("arguments"))?.toString() ?: root.str("args") ?: ""
                listOf(AgentEvent.ToolCallStarted(id = id, toolName = name, argsPreview = argsPreview.take(400)))
            }
            "tool_result" -> {
                val id = root.str("id") ?: acc.pendingToolCalls.removeFirstOrNull() ?: return emptyList()
                acc.pendingToolCalls.remove(id)
                val isError = root.str("status") == "error"
                val errorObj = root.obj("error")
                val resultText = when {
                    errorObj != null -> errorObj.str("reason") ?: errorObj.str("name") ?: "工具执行出错"
                    else -> root.str("result") ?: root.str("output") ?: ""
                }
                val toolName = acc.toolNames[id] ?: "tool"
                val attachments = if (toolName.contains(SEND_FILE_TOOL) && !isError) {
                    deliveryDrain(acc.sessionId)
                } else {
                    emptyList()
                }
                listOf(
                    AgentEvent.ToolCallFinished(
                        id = id,
                        toolName = toolName,
                        result = resultText.take(2000),
                        isError = isError,
                        attachments = attachments
                    )
                )
            }
            "text" -> {
                val content = root.str("text") ?: return emptyList()
                acc.hadText = true
                val reasoning = acc.reasoning.toString()
                acc.reasoning.setLength(0)
                listOf(AgentEvent.AssistantText(content = content, reasoning = reasoning))
            }
            "thinking" -> {
                // 0.2.2-rc.x 未在文档固化 thinking 载荷字段名，双候选兼容
                val text = root.str("text") ?: root.str("thinking") ?: return emptyList()
                acc.reasoning.append(text)
                listOf(AgentEvent.ReasoningDelta(accumulated = acc.reasoning.toString()))
            }
            "turn_end" -> emptyList() // final 是唯一权威终结信号；turn_end 仅供日志
            "final" -> {
                val reason = root.obj("reason")
                val reasonKind = reason?.str("kind")
                // 0.2.2-rc.x 实测：答案在 text 字段（无 answer 字段），错误细节在 reason.error.{message,code}
                val answer = root.str("text") ?: root.str("answer")
                val reasonDetail = reason?.obj("error")?.let { err ->
                    listOfNotNull(err.str("message"), err.str("code")).joinToString(" ").take(300).ifBlank { null }
                }
                val events = mutableListOf<AgentEvent>()
                // 整轮没有 text 事件而 final 答案有内容时（如极短回合），把答案补发出来
                if (!answer.isNullOrBlank() && !acc.hadText) {
                    events += AgentEvent.AssistantText(content = answer, reasoning = acc.reasoning.toString())
                    acc.reasoning.setLength(0)
                }
                events += if (reasonKind == null || reasonKind == "completed") {
                    AgentEvent.Completed
                } else {
                    val detail = reasonDetail ?: answer?.take(300)
                    AgentEvent.Failed(error = enrichErrorMessage("回合未完成（$reasonKind）" + (detail?.let { "：$it" } ?: "")))
                }
                events
            }
            "error" -> {
                acc.lastError = root.str("message") ?: root.str("error") ?: line.take(200)
                emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * 无 final 行即退出：区分「续接会话不存在/preset·cwd 不一致」（可重试全新回合）与
     * 认证/计费/其它失败。0.2.2-rc.x 实测词法见类注释。
     */
    internal fun classifyEarlyExit(stderrTail: String, lastStdoutError: String? = null): Exception {
        val lower = (stderrTail + "\n" + (lastStdoutError ?: "")).lowercase()
        if ("api key" in lower || "missing_credential" in lower || "unauthorized" in lower ||
            "authentication" in lower || "401" in lower
        ) {
            return RuntimeException(
                enrichErrorMessage("认证失败：" + stderrTail.takeLast(300).trim())
            )
        }
        if ("insufficient balance" in lower || "402" in lower) {
            return RuntimeException(
                "DeepSeek 账户余额不足：请到 platform.deepseek.com 充值后重试。原始错误：" +
                    stderrTail.takeLast(300).trim()
            )
        }
        return if ("does not exist" in lower && "session" in lower ||
            "session not found" in lower || "unknown session" in lower ||
            // 续接约束不满足（preset/cwd 与创建时不一致）也降级：旧会话续不上就开新的
            ("mismatch" in lower && ("preset" in lower || "cwd" in lower || "permission" in lower))
        ) {
            ResumeSessionMissingException(stderrTail.takeLast(400).trim())
        } else {
            RuntimeException("dsh 提前退出: " + stderrTail.takeLast(400).trim().ifEmpty { "（无 stderr 输出）" })
        }
    }

    /** 统一把底层认证/计费错误翻译成可操作的引导（final error 与 EOF 分类共用）。 */
    internal fun enrichErrorMessage(message: String): String {
        val lower = message.lowercase()
        return when {
            "missing_credential" in lower || "no api key" in lower || "api key" in lower ||
                "unauthorized" in lower || "authentication" in lower || "401" in lower ->
                "DeepSeek 引擎认证失败：请确认 API Key 有效。二选一——① 设置 → 模型 添加 DeepSeek " +
                    "供应商（platform.deepseek.com 创建，切引擎时自动透传）；② 容器 ~/.dsh/.env 写入 " +
                    "DEEPSEEK_API_KEY=sk-...。原始错误：$message"
            "insufficient balance" in lower || "402" in lower ->
                "DeepSeek 账户余额不足：请到 platform.deepseek.com 充值后重试。原始错误：$message"
            else -> message
        }
    }

    /** status 事件的用量：字段名按 snake/camel 双兼容探测（step 携带本步累计）。 */
    private fun parseUsage(root: JsonObject): NormalizedUsage? {
        val usage = root.obj("usage") ?: return null
        val input = usage.longOf("input_tokens", "inputTokens", "prompt_tokens", "promptTokens") ?: return null
        val output = usage.longOf("output_tokens", "outputTokens", "completion_tokens", "completionTokens") ?: 0L
        val cached = usage.longOf("cached_input_tokens", "cachedInputTokens", "cache_read_tokens", "cacheReadTokens") ?: 0L
        // DeepSeek API 是 OpenAI 兼容：prompt_tokens 含 cached（inputIncludesCache 语义）
        return UsageNormalizer.openAi(
            prompt = input.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            cached = cached.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            output = output.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        )
    }

    internal companion object {
        /** aicode_bridge 的交付工具名；dsh 侧工具名为 mcp__aicode_bridge__send_file，用包含匹配。 */
        const val SEND_FILE_TOOL = "send_file"
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.longOf(vararg keys: String): Long? {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        runCatching { return p.content.toLong() }
    }
    return null
}
