package com.aicode.feature.schedule.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.script.ScriptRunner
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 容器命令型 / 运行用户脚本型定时任务执行 Worker：到点后在容器里跑一次，把退出码与输出写回任务记录。
 *
 * 由 [TaskScheduling] 以「每个任务一个唯一请求」方式入队；任务禁用/删除时用 [TaskScheduling.cancel] 取消。
 * 进程被回收后 WorkManager 仍会按计划唤醒本 Worker。
 *
 * 脚本型任务的解释器与命令拼装交给 [ScriptRunner]（与「设置 → 脚本」页、`run_script` 工具同源），
 * 跑完沿用 `run_script` 的「上次运行」记录；通知策略为成功静默、失败必报。
 */
@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: ScheduledTaskRepository,
    private val commandEngine: CommandEngine,
    private val scriptRunner: ScriptRunner,
    private val notifier: ScheduledTaskNotifier
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

        val outcome = when (task.type) {
            ScheduledTaskType.SCRIPT -> runScript(task)
            else -> runCommand(task)
        }
        repository.updateLastRun(
            id = taskId,
            exitCode = outcome.exitCode,
            output = outcome.output.trim().take(MAX_OUTPUT_CHARS)
        )
        // 脚本型任务成功静默、失败必报：无人值守跑的东西出问题得让人知道
        if (task.type == ScheduledTaskType.SCRIPT && outcome.exitCode != 0) {
            notifier.notifyCompletion(task, success = false)
        }
        // ONCE 执行完即终态：自动禁用并取消调度，避免界面上仍显示「已启用」
        if (task.schedule.mode == ScheduleMode.ONCE) {
            repository.setEnabled(taskId, false)
            TaskScheduling.cancel(applicationContext, taskId)
        }
        return Result.success()
    }

    private suspend fun runCommand(task: ScheduledTask): Outcome {
        val result = commandEngine.runCommandSyncWithExit(task.command, timeoutMs = COMMAND_TIMEOUT_MS)
        return Outcome(result.exitCode, result.output)
    }

    private suspend fun runScript(task: ScheduledTask): Outcome {
        val name = task.scriptName?.trim().orEmpty()
        if (name.isEmpty()) {
            return Outcome(FAILED_EXIT, applicationContext.getString(R.string.schedule_script_missing))
        }
        // 远程 SSH 模式下脚本目录与执行环境不在同一侧，跑起来只会得到远端「文件不存在」这类误导性报错
        scriptRunner.unavailableReason()?.let { return Outcome(FAILED_EXIT, it) }
        return try {
            // 解析也在兜底内：名字合法性与默认 shell 都由这里决定，异常不该冒泡出 doWork
            val target = scriptRunner.target(name)
                ?: return Outcome(FAILED_EXIT, applicationContext.getString(R.string.schedule_script_not_found, name))
            val result = scriptRunner.run(target, SCRIPT_TIMEOUT_MS)
            Outcome(result.exitCode, result.output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "定时脚本任务执行失败: ${task.name}", e)
            Outcome(FAILED_EXIT, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 一次执行的退出码与输出。 */
    private data class Outcome(val exitCode: Int?, val output: String)

    companion object {
        private const val TAG = "ScheduledTaskWorker"
        private const val COMMAND_TIMEOUT_MS = 5 * 60 * 1000L
        /**
         * 脚本单次执行上限：与命令型一致，且必须留在 WorkManager 对普通（非前台）Worker 的 10 分钟
         * 上限内——否则挂死的脚本会先被系统停掉 Worker，这一次既不落「上次运行」也不发失败通知。
         * 压到 5 分钟，让引擎 watchdog 收抬（落库 + 失败通知）。
         */
        private const val SCRIPT_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MAX_OUTPUT_CHARS = 4_000
        private const val FAILED_EXIT = 1
        private const val ENDED_SKIPPED_TEXT = "[已过结束时间，跳过执行]"
    }
}
