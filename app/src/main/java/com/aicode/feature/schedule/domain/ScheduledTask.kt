package com.aicode.feature.schedule.domain

import kotlinx.serialization.Serializable

/** 触发计划模式：仅一次 / 每天固定时刻 / 固定间隔 / 待排期。 */
@Serializable
enum class ScheduleMode { ONCE, DAILY, INTERVAL, UNSET }

/**
 * 定时任务的触发计划。
 * [ScheduleMode.ONCE] 时 [startAt] 为执行时刻（epoch millis）；创建后未配置时间的任务为
 * [ScheduleMode.UNSET]（待排期，不调度，列表带角标）。
 */
@Serializable
data class TaskSchedule(
    val mode: ScheduleMode = ScheduleMode.DAILY,
    /** 每天执行的时刻（24 小时制），仅 [ScheduleMode.DAILY] 有效。 */
    val dailyHour: Int = 9,
    val dailyMinute: Int = 0,
    /** 间隔分钟数，仅 [ScheduleMode.INTERVAL] 有效；下限见 [MIN_INTERVAL_MINUTES]。 */
    val intervalMinutes: Int = 60,
    /** 仅一次的执行时刻（epoch millis），仅 [ScheduleMode.ONCE] 有效。 */
    val startAt: Long? = null,
    /** 可选结束时刻（epoch millis）：已过则任务自动停用，到期那次跳过。 */
    val endAt: Long? = null
) {
    companion object {
        /** WorkManager 周期任务允许的最小间隔。 */
        const val MIN_INTERVAL_MINUTES = 15
    }
}

/** 任务类型：容器命令（E5 存量）/ AI 定时任务 / 定时 Plan（长任务）/ 运行用户脚本。 */
@Serializable
enum class ScheduledTaskType { COMMAND, AI_TASK, AI_PLAN, SCRIPT }

/**
 * 无人值守运行的预授权类别（v1 类别级粒度）：
 * 运行中按勾选类别自动放行对应工具，超出类别的操作走通知授权通道（守夜语义，10 分钟兜底）。
 */
@Serializable
enum class ScheduledAuthCategory { READ, NETWORK, WRITE, EXECUTE, DELETE }

/**
 * 一条定时任务。
 *
 * [ScheduledTaskType.COMMAND] 用 [command] 在容器里跑 shell；
 * AI 型用 [prompt]（定时任务的指令）或 [planText]（定时 Plan 存档的计划正文）驱动一次 AI 会话，
 * 会话模式取 [runMode]（默认 BUILD），预授权清单为 [authCategories]。
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val command: String = "",
    val type: ScheduledTaskType = ScheduledTaskType.COMMAND,
    /** AI 型指令正文（AI_TASK）。 */
    val prompt: String? = null,
    /** 定时 Plan 存档的计划正文（AI_PLAN），来自计划审批面板。 */
    val planText: String? = null,
    /** 计划来源会话 id（AI_PLAN，供追溯；运行时开新会话）。 */
    val sourceSessionId: String? = null,
    /** 运行会话模式（AgentMode.name），AI 型默认 BUILD。 */
    val runMode: String? = null,
    /** 预授权类别（AI 型）。 */
    val authCategories: List<ScheduledAuthCategory> = emptyList(),
    /** 创建时的工作区（AI 型运行时在工作区内开新会话）。 */
    val workspacePath: String? = null,
    /** 运行用户脚本型（SCRIPT）的脚本名（含后缀），存于 `~/.aicode/scripts/`。 */
    val scriptName: String? = null,
    /** 上次执行时间（epoch millis）。 */
    val lastRunAt: Long? = null,
    /** 上次退出码（超时/异常为 null）；AI 型 0=完成 1=失败。 */
    val lastExitCode: Int? = null,
    /** 上次执行输出/结果摘要（已限幅）。 */
    val lastOutput: String? = null,
    val enabled: Boolean = true,
    val schedule: TaskSchedule = TaskSchedule()
) {
    /** 是否为 AI 驱动型（AI_TASK / AI_PLAN）。 */
    val isAiType: Boolean
        get() = type == ScheduledTaskType.AI_TASK || type == ScheduledTaskType.AI_PLAN

    /** 运行会话模式（解析失败回落 BUILD）。 */
    val effectiveRunMode: String
        get() = runMode?.takeIf { it.isNotBlank() } ?: "BUILD"
}
