package com.aicode.feature.agent.domain.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * 外部 CLI 引擎的「派生子代理」调用归一化。
 *
 * 三个 CLI 对同一件事的叫法不同：Claude 用 `Task`（新版本也有 `Agent`）、Codex 用
 * `collab_tool_call`、OpenCode 用 `task`，而内置引擎统一叫 `task`。这里把外部名归一成
 * `task`，让图标、场景文案与成员名解析全部复用内置链路，避免 UI 上出现
 * `collab_tool_call` 这类原始协议名，也让「正在启动子代理」的提示对三家一致。
 */
internal object EngineSubagent {

    const val TOOL_NAME = "task"

    /** 取不到成员标识时的兜底（与内置默认通用子代理的 subagentType 一致）。 */
    const val DEFAULT_MEMBER = "subagent"

    /** 子会话标题上限：与内置 TaskTool 的任务描述同一口径。 */
    const val TITLE_MAX = 30

    private val ALIASES = setOf("task", "agent", "subagent", "collab_tool_call")

    /** 真正的代理名；`description`/`prompt` 是任务描述，不能拿来冒充成员名。 */
    private val AGENT_NAME_KEYS = listOf("agent", "subagent_type", "agent_type")

    /** 任务描述/指令的候选键（Claude 用 description + prompt，Codex 用 prompt）。 */
    private val DESCRIPTION_KEYS = listOf("description", "prompt")

    fun isSubagentTool(name: String?): Boolean =
        name != null && name.trim().lowercase() in ALIASES

    /** 归一成内置引擎的工具名；非子代理工具原样返回。 */
    fun normalizeToolName(name: String?): String =
        if (isSubagentTool(name)) TOOL_NAME else name ?: "tool"

    /**
     * 子代理成员名/任务描述：内置用 `agent`，Claude 用 `subagent_type`/`agent_type`，
     * Codex 用 `prompt`。按优先级取第一个非空值，取不到返回 null（UI 不标注成员）。
     */
    fun memberName(argsJson: String?): String? =
        agentName(argsJson) ?: descriptionOf(argsJson)

    /** 成员标识（子会话 `subagentType`）：只认代理名，取不到返回 null 由调用方兜底。 */
    fun agentName(argsJson: String?): String? {
        val obj = argsOf(argsJson) ?: return null
        return AGENT_NAME_KEYS.firstNotNullOfOrNull { text(obj, it) }
    }

    /** 子会话标题：任务描述优先，其次指令首行；空值返回 null。 */
    fun taskTitle(argsJson: String?): String? {
        val raw = descriptionOf(argsJson) ?: return null
        return raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TITLE_MAX)
            ?.takeIf { it.isNotEmpty() }
    }

    /** 子会话首条用户消息：完整任务指令（优先 prompt，没有再用 description）。 */
    fun taskPrompt(argsJson: String?): String? {
        val obj = argsOf(argsJson) ?: return null
        return text(obj, "prompt") ?: text(obj, "description")
    }

    /**
     * Codex 的 `collab_tool_call` 也承载等待/追加消息/关闭等控制调用，并非每次都是新派发。
     * 只有带任务指令（prompt）且没有明确非派发动作的调用才建子会话，否则会给同一次协作
     * 建出多条空壳子会话。
     */
    fun isSpawnCall(argsJson: String?): Boolean {
        val obj = argsOf(argsJson) ?: return false
        if (text(obj, "prompt") == null) return false
        val action = listOf("tool", "action", "op")
            .firstNotNullOfOrNull { text(obj, it) }
            ?.lowercase()
            ?: return true
        return SPAWN_ACTION_HINTS.any { action.contains(it) }
    }

    /**
     * Claude 的后台子代理启动结果：`Task` 工具立即返回 `{"isAsync":true,"status":"async_launched"}`，
     * 此时不能把工具行判为结束，否则子代理还在跑而 UI 已经显示完成。
     * 仅做字符串判定，兼容 CLI 各小版本的字段顺序/空格差异。
     */
    fun isAsyncLaunch(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        val compact = result.lowercase().filterNot { it == ' ' || it == '\n' || it == '\t' }
        return compact.contains("async_launched") || compact.contains("\"isasync\":true")
    }

    private val SPAWN_ACTION_HINTS = listOf("spawn", "start", "create", "new", "dispatch", "launch")

    private fun argsOf(argsJson: String?): JsonObject? {
        if (argsJson.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
    }

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun descriptionOf(argsJson: String?): String? {
        val obj = argsOf(argsJson) ?: return null
        return DESCRIPTION_KEYS.firstNotNullOfOrNull { text(obj, it) }
    }
}

/**
 * 外部引擎内部的子代理会话快照：桥接层每次派发时生成 id 并随
 * [com.aicode.feature.agent.domain.workflow.AgentEvent.SubagentSpawned] 下发，
 * ViewModel 用它建子会话；结束事件按同一个 [subSessionId] 收尾。
 */
internal data class EngineSubagentSession(
    val subSessionId: String,
    val memberName: String,
    val title: String,
    val prompt: String
)

/** 从工具调用参数生成子代理会话快照（id 在桥接层生成，保证「派发/结束」两事件能对上）。 */
internal fun newEngineSubagentSession(argsJson: String?): EngineSubagentSession {
    val member = EngineSubagent.agentName(argsJson) ?: EngineSubagent.DEFAULT_MEMBER
    return EngineSubagentSession(
        subSessionId = UUID.randomUUID().toString(),
        memberName = member,
        title = EngineSubagent.taskTitle(argsJson) ?: member,
        prompt = EngineSubagent.taskPrompt(argsJson).orEmpty()
    )
}
