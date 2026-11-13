package com.aicode.feature.agent.domain.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    private val ALIASES = setOf("task", "agent", "subagent", "collab_tool_call")

    fun isSubagentTool(name: String?): Boolean =
        name != null && name.trim().lowercase() in ALIASES

    /** 归一成内置引擎的工具名；非子代理工具原样返回。 */
    fun normalizeToolName(name: String?): String =
        if (isSubagentTool(name)) TOOL_NAME else name ?: "tool"

    /**
     * 子代理成员名/任务描述：内置用 `agent`，Claude 用 `subagent_type`/`agent_type`，
     * Codex 用 `prompt`。按优先级取第一个非空值，取不到返回 null（UI 不标注成员）。
     */
    fun memberName(argsJson: String?): String? {
        if (argsJson.isNullOrBlank()) return null
        val obj = runCatching {
            Json.parseToJsonElement(argsJson) as? JsonObject
        }.getOrNull() ?: return null
        return listOf("agent", "subagent_type", "agent_type", "description", "prompt")
            .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
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
}
