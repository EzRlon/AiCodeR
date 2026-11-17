package com.aicode.feature.schedule.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.ScheduledAgentViewModelProvider
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 型定时任务执行 Worker：到点后建一个「⏰」会话，把任务指令（或已批准的计划正文）
 * 注入离屏装配的 [AIAgentViewModel] 运行一次完整 agent 轮次，结果写回任务记录并通知。
 *
 * 无人值守语义（与守夜对齐，见 unattended-security-plan §L2）：
 * - 运行期间在 [ScheduledAiRunState] 登记，权限引擎按预授权类别放行、超档走通知授权通道
 *   （10 分钟兜底超时），计划审查自动通过、AI 提问自动答复、预算耗尽直接收尾；
 * - 单次执行上限 [TIME_BOX_MS]（默认 12 小时），到点结束并汇报；
 * - 与容器命令型任务共用存储（[TaskScheduling] 统一调度），并发触发时跳过（下个周期重来）。
 */
@HiltWorker
class ScheduledAiTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: ScheduledTaskRepository,
    private val runState: ScheduledAiRunState,
    private val viewModelProvider: ScheduledAgentViewModelProvider,
    private val agentMessageDao: AgentMessageDao,
    private val notifier: ScheduledTaskNotifier
) : CoroutineWorker(context, workerParameters) {

    private companion object {
        const val TAG = "ScheduledAiTaskWorker"

        /** 单次执行上限：长任务到点结束并生成报告（决策 4，可配置化留 v1.5）。 */
        const val TIME_BOX_MS = 12 * 60 * 60 * 1000L
        const val MAX_OUTPUT_CHARS = 4_000
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(TaskScheduling.KEY_TASK_ID) ?: return Result.failure()
        val task = repository.get(taskId) ?: return Result.success()
        if (!task.enabled || !task.isAiType) return Result.success()

        val endAt = task.schedule.endAt
        if (endAt != null && endAt <= System.currentTimeMillis()) {
            repository.updateLastRun(taskId, exitCode = null, output = applicationContext.getString(R.string.schedule_skipped_ended))
            return Result.success()
        }
        if (runState.hasActive()) {
            FileLogger.i(TAG, "已有定时 AI 任务运行中，跳过本次: ${task.name}")
            return Result.success()
        }
        val workspacePath = task.workspacePath?.takeIf { it.isNotBlank() }
        if (workspacePath == null) {
            repository.updateLastRun(taskId, exitCode = 1, output = applicationContext.getString(R.string.schedule_ai_no_workspace))
            notifier.notifyCompletion(task, success = false)
            return Result.success()
        }

        val vm = viewModelProvider.create().apply { suppressAgentCompletionNotification = true }
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        // 用 ViewModelProvider 托管实例：store.clear()（finally 中）触发 onCleared，
        // 取消 viewModelScope 并释放保活，不留后台残留。
        val hosted = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[AIAgentViewModel::class.java]

        try {
            hosted.setWorkspace(workspacePath)
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            val title = applicationContext.getString(R.string.schedule_run_session_title, task.name, time)
            val sid = hosted.createScheduledRunSession(
                workspacePath = workspacePath,
                title = title,
                runMode = parseRunMode(task.effectiveRunMode)
            )
            runState.begin(
                ScheduledAiRunState.ActiveScheduledAiRun(
                    taskId = task.id,
                    taskName = task.name,
                    sessionId = sid,
                    authorizedCategories = task.authCategories.toSet(),
                    startedAt = System.currentTimeMillis()
                )
            )

            val request = buildRequest(task)
            val job = hosted.executeAgentRequestStream(
                request = request,
                projectRoot = workspacePath,
                targetSessionId = sid,
                skipTitleUpdate = true
            )
            val finished = withTimeoutOrNull(TIME_BOX_MS) { job.join() }
            if (finished == null) {
                // 时间盒到（L2.3 三级之「人类专属边界」）：结束并生成报告
                job.cancelAndJoin()
                repository.updateLastRun(taskId, exitCode = 1, output = applicationContext.getString(R.string.schedule_ai_timebox_stopped))
                notifier.notifyCompletion(task, success = false)
            } else {
                val ok = hosted.scheduledRunSucceeded(sid)
                val summary = lastAssistantSnippet(sid)
                repository.updateLastRun(taskId, exitCode = if (ok) 0 else 1, output = summary)
                notifier.notifyCompletion(task, success = ok)
            }
        } catch (e: CancellationException) {
            // Worker 被系统停止（如 Android 12+ 对非前台 Worker 的执行上限）或进程回收：
            // 在 NonCancellable 下尽力标记中断，再继续取消传播（finally 里释放运行登记）。
            runCatching {
                withContext(NonCancellable) {
                    repository.updateLastRun(
                        taskId,
                        exitCode = 1,
                        output = applicationContext.getString(R.string.schedule_ai_interrupted)
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "定时 AI 任务执行失败: ${task.name}", e)
            repository.updateLastRun(
                taskId,
                exitCode = 1,
                output = (e.message ?: e.javaClass.simpleName).take(MAX_OUTPUT_CHARS)
            )
            notifier.notifyCompletion(task, success = false)
        } finally {
            runState.end(task.id)
            store.clear()
        }

        // ONCE 执行完即终态：自动禁用并取消调度
        if (task.schedule.mode == ScheduleMode.ONCE) {
            repository.setEnabled(taskId, false)
            TaskScheduling.cancel(applicationContext, taskId)
        }
        return Result.success()
    }

    /** 组装注入指令：AI_TASK 用任务正文，AI_PLAN 用存档的计划正文，均附无人值守语义说明。 */
    private fun buildRequest(task: ScheduledTask): String {
        val hint = if (task.type == ScheduledTaskType.AI_PLAN) {
            applicationContext.getString(R.string.schedule_plan_run_hint)
        } else {
            applicationContext.getString(R.string.schedule_ai_run_hint, task.name)
        }
        val body = when (task.type) {
            ScheduledTaskType.AI_PLAN -> task.planText.orEmpty().ifBlank { task.prompt.orEmpty() }
            else -> task.prompt.orEmpty()
        }
        return "$body\n\n$hint"
    }

    /** 解析运行模式（存的是 AgentMode.name；无效值回落 BUILD）。 */
    private fun parseRunMode(raw: String): AgentMode =
        runCatching { AgentMode.valueOf(raw) }.getOrDefault(AgentMode.BUILD)

    /** 取会话最后一条有内容的 assistant 消息作为结果摘要（DAO 层 LIMIT 1，避免长任务数千条消息全量进内存）。 */
    private suspend fun lastAssistantSnippet(sessionId: String): String =
        runCatching { agentMessageDao.lastAssistantContent(sessionId) }
            .getOrNull()
            ?.trim()
            ?.take(MAX_OUTPUT_CHARS)
            ?: ""

}
