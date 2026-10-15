package com.aicode.feature.schedule.domain

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 定时任务的统一调度入口：按任务类型与触发计划分派到对应 Worker。
 *
 * - [ScheduledTaskType.COMMAND] → [ScheduledTaskWorker]（容器命令）
 * - AI 型 → [ScheduledAiTaskWorker]（驱动一次 AI 会话）
 * - [ScheduleMode.UNSET]（待排期）与已过 [TaskSchedule.endAt] 的任务不入队；
 * - ONCE 用一次性请求（执行后由 Worker 自动禁用），其余用周期请求。
 *
 * 用「先取消再入队」而不是 UPDATE：周期任务的 UPDATE 会保留下次运行时间，
 * 用户改了触发计划后不会立即生效。任务被禁用时只取消、不入队。
 */
object TaskScheduling {
    /** 任务 id 在 Worker 输入数据中的键（两个 Worker 共用）。 */
    const val KEY_TASK_ID = "task_id"

    private fun uniqueName(id: String) = "scheduled-task-$id"

    fun schedule(context: Context, task: ScheduledTask) {
        cancel(context, task.id)
        if (!task.enabled) return
        val endAt = task.schedule.endAt
        if (endAt != null && endAt <= System.currentTimeMillis()) return

        val wm = WorkManager.getInstance(context)
        val input = workDataOf(KEY_TASK_ID to task.id)

        when (task.schedule.mode) {
            ScheduleMode.UNSET -> Unit

            ScheduleMode.ONCE -> {
                val startAt = task.schedule.startAt ?: return
                // 已过时刻不排队：避免保存历史时间后立即触发（编辑器侧已校验未来时刻）
                if (startAt <= System.currentTimeMillis()) return
                val delay = startAt - System.currentTimeMillis()
                val request = if (task.type == ScheduledTaskType.COMMAND) {
                    OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .build()
                } else {
                    OneTimeWorkRequestBuilder<ScheduledAiTaskWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .build()
                }
                wm.enqueueUniqueWork(uniqueName(task.id), ExistingWorkPolicy.REPLACE, request)
            }

            ScheduleMode.DAILY -> {
                val delay = delayToNextDaily(task.schedule.dailyHour, task.schedule.dailyMinute)
                if (task.type == ScheduledTaskType.COMMAND) {
                    val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(24, TimeUnit.HOURS)
                    enqueuePeriodic(wm, uniqueName(task.id), request, delay, input)
                } else {
                    val request = PeriodicWorkRequestBuilder<ScheduledAiTaskWorker>(24, TimeUnit.HOURS)
                    enqueuePeriodic(wm, uniqueName(task.id), request, delay, input)
                }
            }

            ScheduleMode.INTERVAL -> {
                val minutes = task.schedule.intervalMinutes
                    .coerceAtLeast(TaskSchedule.MIN_INTERVAL_MINUTES).toLong()
                if (task.type == ScheduledTaskType.COMMAND) {
                    val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(minutes, TimeUnit.MINUTES)
                    enqueuePeriodic(wm, uniqueName(task.id), request, null, input)
                } else {
                    val request = PeriodicWorkRequestBuilder<ScheduledAiTaskWorker>(minutes, TimeUnit.MINUTES)
                    enqueuePeriodic(wm, uniqueName(task.id), request, null, input)
                }
            }
        }
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
    }

    private fun enqueuePeriodic(
        wm: WorkManager,
        name: String,
        request: PeriodicWorkRequest.Builder,
        delayMs: Long?,
        input: Data
    ) {
        val builder = request.setInputData(input)
        val withDelay = if (delayMs != null) {
            builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        } else {
            builder
        }
        wm.enqueueUniquePeriodicWork(
            name,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            withDelay.build()
        )
    }

    /** 距下一次指定时刻的毫秒数（今天该时刻已过则顺延到明天）。 */
    private fun delayToNextDaily(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }
}
