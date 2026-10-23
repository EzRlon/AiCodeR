package com.aicode.feature.schedule.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.schedule.data.ScheduledTaskRepository
import com.aicode.feature.schedule.domain.ScheduledTask
import com.aicode.feature.schedule.domain.ScheduledTaskType
import com.aicode.feature.schedule.domain.TaskScheduling
import com.aicode.feature.schedule.domain.TaskSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 定时任务页状态。 */
data class ScheduledTasksUiState(
    val tasks: List<ScheduledTask> = emptyList(),
    val loading: Boolean = true
)

/**
 * 定时任务页：读取任务列表，保存/删除时同步重排 WorkManager 调度。
 *
 * 持久化与调度必须一起变更，否则会出现「界面上已删掉、后台仍在跑」这种不一致。
 */
@HiltViewModel
class ScheduledTasksViewModel @Inject constructor(
    private val repository: ScheduledTaskRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val state: StateFlow<ScheduledTasksUiState> = repository.tasksFlow
        .map { ScheduledTasksUiState(tasks = it, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduledTasksUiState())

    init {
        // 对账：把落盘为启用却未成功入队的任务重新调度（如上次 enqueue 失败或进程中断），
        // 修补「界面显示启用、后台却永不触发」的缺口。删除/禁用方向的残留由 Worker 自身复查兜底。
        viewModelScope.launch {
            repository.tasksFlow.first().filter { it.enabled }.forEach { task ->
                TaskScheduling.schedule(context, task)
            }
        }
    }

    /** 新增或覆盖一条任务，并按最新内容重排调度。 */
    fun save(task: ScheduledTask) {
        viewModelScope.launch {
            repository.upsert(task)
            TaskScheduling.schedule(context, task)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            TaskScheduling.cancel(context, id)
        }
    }

    fun setEnabled(task: ScheduledTask, enabled: Boolean) = save(task.copy(enabled = enabled))

    /**
     * 编辑器保存：[existing] 为 null 时新建（生成 id），否则在原任务上更新名称/命令/计划。
     *
     * [type] 只在新建时生效：既有任务的类型不可改（编辑器在编辑既有任务时不显示类型选择），
     * 未在编辑器暴露的字段一律保留原值。
     */
    fun saveFromEditor(
        existing: ScheduledTask?,
        name: String,
        command: String,
        scriptName: String,
        type: ScheduledTaskType,
        schedule: TaskSchedule
    ) {
        val task = if (existing != null) {
            // AI 型任务的 command 字段不参与编辑（内容只读），避免误清空
            existing.copy(
                name = name,
                command = if (existing.isAiType) existing.command else command,
                // 脚本名只在脚本型任务上可编辑，其它类型保持原值
                scriptName = if (existing.type == ScheduledTaskType.SCRIPT) scriptName else existing.scriptName,
                schedule = schedule
            )
        } else {
            ScheduledTask(
                id = UUID.randomUUID().toString(),
                name = name,
                command = command,
                scriptName = scriptName.takeIf { type == ScheduledTaskType.SCRIPT },
                type = type,
                schedule = schedule
            )
        }
        save(task)
    }
}
