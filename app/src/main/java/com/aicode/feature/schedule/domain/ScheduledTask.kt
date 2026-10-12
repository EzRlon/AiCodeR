package com.aicode.feature.schedule.domain

import kotlinx.serialization.Serializable

/** 触发计划模式：每天固定时刻 / 固定间隔。 */
@Serializable
enum class ScheduleMode { DAILY, INTERVAL }

/** 定时任务的触发计划。 */
@Serializable
data class TaskSchedule(
    val mode: ScheduleMode = ScheduleMode.DAILY,
    /** 每天执行的时刻（24 小时制），仅 [ScheduleMode.DAILY] 有效。 */
    val dailyHour: Int = 9,
    val dailyMinute: Int = 0,
    /** 间隔分钟数，仅 [ScheduleMode.INTERVAL] 有效；下限见 [MIN_INTERVAL_MINUTES]。 */
    val intervalMinutes: Int = 60
) {
    companion object {
        /** WorkManager 周期任务允许的最小间隔。 */
        const val MIN_INTERVAL_MINUTES = 15
    }
}

/** 一条定时执行的容器命令任务。 */
@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val command: String,
    val enabled: Boolean = true,
    val schedule: TaskSchedule = TaskSchedule(),
    /** 上次执行时间（epoch millis）。 */
    val lastRunAt: Long? = null,
    /** 上次退出码（超时/异常为 null）。 */
    val lastExitCode: Int? = null,
    /** 上次执行输出（已限幅）。 */
    val lastOutput: String? = null
)
