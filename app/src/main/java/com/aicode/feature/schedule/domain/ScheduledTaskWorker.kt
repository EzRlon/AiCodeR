package com.aicode.feature.schedule.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 定时任务执行 Worker：到点后在容器里跑一次任务命令，把退出码与输出写回任务记录。
 *
 * 由 [schedule] 以「每个任务一个唯一周期任务」方式入队；任务禁用/删除时用 [cancel] 取消。
 * 进程被回收后 WorkManager 仍会按计划唤醒本 Worker。
 */
@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: ScheduledTaskRepository,
    private val commandEngine: CommandEngine
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        // 任务可能在调度后被删除或禁用，这里复查一次，避免执行过期命令
        val task = repository.get(taskId) ?: return Result.success()
        if (!task.enabled) return Result.success()

        val result = commandEngine.runCommandSyncWithExit(task.command, timeoutMs = COMMAND_TIMEOUT_MS)
        repository.updateLastRun(
            id = taskId,
            exitCode = result.exitCode,
            output = result.output.trim().take(MAX_OUTPUT_CHARS)
        )
        return Result.success()
    }

    companion object {
        private const val KEY_TASK_ID = "task_id"
        private const val COMMAND_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MAX_OUTPUT_CHARS = 4_000

        private fun uniqueName(id: String) = "scheduled-task-$id"

        /**
         * 按任务的当前计划重新调度（幂等）。
         *
         * 用「先取消再入队」而不是 UPDATE：周期任务的 UPDATE 会保留下次运行时间，
         * 用户改了每日时刻后不会立即生效。任务被禁用时只取消、不入队。
         */
        fun schedule(context: Context, task: ScheduledTask) {
            cancel(context, task.id)
            if (!task.enabled) return

            val input = workDataOf(KEY_TASK_ID to task.id)
            val request = when (task.schedule.mode) {
                ScheduleMode.DAILY ->
                    PeriodicWorkRequestBuilder<ScheduledTaskWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(
                            delayToNextDaily(task.schedule.dailyHour, task.schedule.dailyMinute),
                            TimeUnit.MILLISECONDS
                        )
                        .setInputData(input)
                        .build()

                ScheduleMode.INTERVAL -> {
                    val minutes = task.schedule.intervalMinutes
                        .coerceAtLeast(TaskSchedule.MIN_INTERVAL_MINUTES)
                        .toLong()
                    PeriodicWorkRequestBuilder<ScheduledTaskWorker>(minutes, TimeUnit.MINUTES)
                        .setInputData(input)
                        .build()
                }
            }

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueName(task.id),
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
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
}
