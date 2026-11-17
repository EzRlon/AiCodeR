package com.aicode.feature.schedule.domain

import com.aicode.feature.agent.domain.permission.ShellCommandParser
import com.aicode.feature.agent.domain.tool.ToolCapability

/** 预授权类别判定结果。 */
sealed interface AuthCategoryVerdict {
    /** 所需类别全部被授权：直接放行 + 记录（[reason] 供审计）。 */
    data class Covered(val reason: String) : AuthCategoryVerdict

    /**
     * 需要预授权但未被覆盖（有未勾选类别，或含不可预授权能力）：
     * 走正常评估（ASK → 守夜兜底超时 / 通知授权通道）。
     */
    data class NotCovered(val reason: String) : AuthCategoryVerdict

    /** 无需预授权的内部操作（无风险能力）：由引擎主链按既有规则继续判定。 */
    data object NotApplicable : AuthCategoryVerdict
}

/**
 * 定时 AI 任务的「工具能力 → 预授权类别」判定（v1 类别级粒度）。
 *
 * 覆盖规则：工具所需类别集合是任务授权集合的子集才放行；含
 * [ToolCapability.MODIFY_AGENT_CONFIG]（agent 配置类，属守夜「永不自动放行」清单）的能力
 * 一律不参与覆盖，走正常评估。命令含删除动作（rm 变体 / find -exec rm / -delete）时
 * 额外要求 [ScheduledAuthCategory.DELETE]。
 */
object ScheduledAuthCategoryMapping {

    /** 用户脚本执行工具：命令串是「解释器 + 脚本路径」，脚本体内容不进 args。 */
    private const val RUN_SCRIPT_TOOL = "run_script"

    /**
     * @param toolName 调用方工具名；`run_script` 的执行动作在此额外要求
     *   [ScheduledAuthCategory.DELETE]——它执行的是脚本体，内容不可静态判定，可能在内部
     *   删除任何东西（引擎的 rm 判定只能看到命令行）。无人值守下必须由用户显式预授权该类别。
     */
    fun judge(
        capabilities: Set<ToolCapability>,
        commandText: String?,
        authorized: Set<ScheduledAuthCategory>,
        toolName: String? = null
    ): AuthCategoryVerdict {
        if (ToolCapability.MODIFY_AGENT_CONFIG in capabilities) {
            return AuthCategoryVerdict.NotCovered("含 agent 配置修改能力，不在预授权覆盖范围")
        }

        val required = mutableSetOf<ScheduledAuthCategory>()
        capabilities.forEach { cap ->
            when (cap) {
                ToolCapability.READ_WORKSPACE,
                ToolCapability.READ_AGENT_CONFIG -> required += ScheduledAuthCategory.READ

                ToolCapability.NETWORK_READ,
                ToolCapability.NETWORK_WRITE -> required += ScheduledAuthCategory.NETWORK

                ToolCapability.WRITE_WORKSPACE -> required += ScheduledAuthCategory.WRITE

                ToolCapability.EXECUTE_COMMANDS,
                ToolCapability.EXTERNAL_TOOL,
                ToolCapability.MODIFY_CONTAINER_ENV -> required += ScheduledAuthCategory.EXECUTE

                ToolCapability.MODIFY_AGENT_CONFIG -> Unit // 上方已拦截，此处仅为穷尽分支

                ToolCapability.USER_INTERACTION,
                ToolCapability.MODIFY_SESSION_STATE,
                ToolCapability.MODIFY_TODO_STATE -> Unit
            }
        }

        // 命令里的删除动作（含嵌套 shell 与 find 变体）额外要求 DELETE 类别
        if (ToolCapability.EXECUTE_COMMANDS in capabilities && !commandText.isNullOrBlank()) {
            val rmTargets = runCatching {
                ShellCommandParser.collectRmTargets(ShellCommandParser.analyze(commandText).segments)
            }.getOrDefault(emptyList())
            if (rmTargets.isNotEmpty()) required += ScheduledAuthCategory.DELETE
        }

        // 运行用户脚本：脚本体不可静态判定，一律按「可能含删除」要求 DELETE 类别。
        if (ToolCapability.EXECUTE_COMMANDS in capabilities && toolName == RUN_SCRIPT_TOOL) {
            required += ScheduledAuthCategory.DELETE
        }

        if (required.isEmpty()) return AuthCategoryVerdict.NotApplicable

        return if (required.all { it in authorized }) {
            AuthCategoryVerdict.Covered(required.joinToString("+") { it.name })
        } else {
            val missing = required.filterNot { it in authorized }
            AuthCategoryVerdict.NotCovered("超出预授权类别：" + missing.joinToString("+") { it.name })
        }
    }
}
