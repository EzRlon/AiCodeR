package com.aicode.feature.hooks.domain

import kotlinx.serialization.Serializable

/** 一个钩子定义：匹配哪些工具、执行什么脚本。 */
@Serializable
data class HookDefinition(
    /**
     * 工具名匹配：精确（`Bash`）、竖线分隔多选（`Bash|editFile`）、`*` 全匹配。
     * 大小写不敏感。
     */
    val matcher: String = "*",
    /** 脚本路径（容器路径，如 ~/.aicode/hooks/check.sh；.sh/.py/.js 按后缀选择解释器）。 */
    val script: String,
    /** 超时（毫秒）。 */
    val timeoutMs: Long = 10_000
)

/** hooks.json 顶层结构：事件名 → 钩子列表。事件：PreToolUse / PostToolUse。 */
@Serializable
data class HooksConfig(
    val hooks: Map<String, List<HookDefinition>> = emptyMap()
) {
    companion object {
        const val EVENT_PRE_TOOL_USE = "PreToolUse"
        const val EVENT_POST_TOOL_USE = "PostToolUse"
    }
}

/** 钩子执行结果。[blocked] 为 true 时（PreToolUse 脚本非零退出）工具调用被否决。 */
data class HookOutcome(
    val blocked: Boolean = false,
    /** 脚本 stdout/stderr 合并输出（附加到工具结果或作为否决原因）。 */
    val message: String? = null
)
