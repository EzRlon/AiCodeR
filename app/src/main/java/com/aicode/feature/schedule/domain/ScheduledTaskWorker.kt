package com.aicode.feature.schedule.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 容器命令型定时任务执行 Worker：到点后在容器里跑一次任务命令，把退出码与输出写回任务记录。
 *
 * 由 [TaskScheduling] 以「每个任务一个唯一请求」方式入队；任务禁用/删除时用 [TaskScheduling.cancel] 取消。
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
        val taskId = inputData.getString(TaskScheduling.KEY_TASK_ID) ?: return Result.failure()
        // 任务可能在调度后被删除或禁用，这里复查一次，避免执行过期命令
        val task = repository.get(taskId) ?: return Result.success()
        if (!task.enabled) return Result.success()
        // 已过结束时间的任务跳过本次执行（调度侧不入队，这里兜底已入队但到期的情况）
        val endAt = task.schedule.endAt
        if (endAt != null && endAt <= System.currentTimeMillis()) {
            repository.updateLastRun(id = taskId, exitCode = null, output = ENDED_SKIPPED_TEXT)
            return Result.success()
        }

        val result = commandEngine.runCommandSyncWithExit(task.command, timeoutMs = COMMAND_TIMEOUT_MS)
        repository.updateLastRun(
            id = taskId,
            exitCode = result.exitCode,
            output = result.output.trim().take(MAX_OUTPUT_CHARS)
        )
        // ONCE 执行完即终态：自动禁用并取消调度，避免界面上仍显示「已启用」
        if (task.schedule.mode == ScheduleMode.ONCE) {
            repository.setEnabled(taskId, false)
            TaskScheduling.cancel(applicationContext, taskId)
        }
        return Result.success()
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MAX_OUTPUT_CHARS = 4_000
        private const val ENDED_SKIPPED_TEXT = "[已过结束时间，跳过执行]"
    }
}
