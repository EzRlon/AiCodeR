package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.subagent.AgentDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App 智能体定义 → CLI 引擎 agent 配置的映射（能力全域打通批二）。
 *
 * 只映射引擎侧能安全承接的字段：描述 + 提示词（Claude 的 `--agents`、OpenCode 的 `agent` 段）。
 * App 侧的工具白/黑名单与绑定模型不映射——两边工具命名与模型命名体系不同，硬塞会把定义变成
 * 引擎侧不可用或行为不符的配置（差异记在批二台账）。App 定义为子代理语义，OpenCode 侧固定 mode=subagent。
 */
internal object CliAgentMapping {
    /** CLI 侧 agent 名同时是标识符（Claude JSON 键）与文件名（OpenCode），只放行 ASCII 标识符。 */
    private val CLI_SAFE_NAME = Regex("[A-Za-z0-9_-]{1,40}")

    /**
     * OpenCode 自带 agent 名（小写）：映射时跳过，避免 App 定义覆盖引擎原生 agent
     * （engine-native-first 原则）。1.19.0 实测自带 build/compaction/explore/general/plan/summary/title。
     *
     * Claude 侧不做同名排除：`--agents` 定义的就是自定义 agent，其自带名清单未确认（待真机核实）。
     */
    val OPENCODE_BUILTIN_NAMES: Set<String> =
        setOf("build", "compaction", "explore", "general", "plan", "summary", "title")

    /** 不映射的定义及其原因（调用方记日志）。 */
    data class Skipped(val agent: AgentDefinition, val reason: String)

    /** 一次遍历的映射结果：可映射定义 + 被跳过定义及原因。 */
    data class Result(val mapped: List<AgentDefinition>, val skipped: List<Skipped>)

    fun isCliSafeName(name: String): Boolean = CLI_SAFE_NAME.matches(name.trim())

    /**
     * 一次遍历分出可映射与跳过两类（[engineBuiltinNames] 传该引擎自带 agent 名，小写比对）。
     * 跳过原因：名称含非 [A-Za-z0-9_-] 字符、超长（>40，claude --agents 限制）/ 与引擎自带 agent 同名。
     */
    fun partition(agents: List<AgentDefinition>, engineBuiltinNames: Set<String> = emptySet()): Result {
        val mapped = ArrayList<AgentDefinition>(agents.size)
        val skipped = ArrayList<Skipped>()
        for (agent in agents) {
            when {
                agent.name.length > 40 ->
                    skipped += Skipped(agent, "名称超长（>40 字符，claude --agents 不接受）")
                !isCliSafeName(agent.name) ->
                    skipped += Skipped(agent, "名称含非 ASCII 标识符字符")
                agent.name.trim().lowercase() in engineBuiltinNames ->
                    skipped += Skipped(agent, "与引擎自带 agent 同名")
                else -> mapped += agent
            }
        }
        return Result(mapped, skipped)
    }

    /** OpenCode 映射（额外排除其自带 agent 名）。 */
    fun partitionForOpenCode(agents: List<AgentDefinition>): Result =
        partition(agents, OPENCODE_BUILTIN_NAMES)

    /** Claude Code `--agents` 的 JSON 对象：{ name: { description, prompt } }。入参须已由 [partition] 过滤。 */
    fun claudeAgentsJson(mapped: List<AgentDefinition>): JsonObject = buildJsonObject {
        for (agent in mapped) {
            put(agent.name.trim(), buildJsonObject {
                put("description", agent.description.ifBlank { agent.name.trim() })
                put("prompt", agent.prompt)
            })
        }
    }

    /** OpenCode 配置 `agent` 段：{ name: { description, mode, prompt } }。入参须已由 [partitionForOpenCode] 过滤。 */
    fun openCodeAgentsJson(mapped: List<AgentDefinition>): JsonObject = buildJsonObject {
        for (agent in mapped) {
            put(agent.name.trim(), buildJsonObject {
                put("description", agent.description.ifBlank { agent.name.trim() })
                put("mode", "subagent")
                put("prompt", agent.prompt)
            })
        }
    }
}
