package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 记忆管理页状态：按作用域分组的长期记忆列表（全局 / 项目）。
 *
 * [MemoryRepository.listMemories] 已对同名记忆做「项目级优先」去重，故两组结果不重叠。
 * 没有选中工作区时项目层不可读写，[hasWorkspace] 为 false。
 */
data class MemoryUiState(
    val global: List<Memory> = emptyList(),
    val project: List<Memory> = emptyList(),
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/** 记忆写操作的失败类型，供界面提示（写盘失败或项目级缺工作区）。 */
enum class MemoryError { SAVE_FAILED, DELETE_FAILED }

/**
 * 记忆管理页：读取 Auto Memory 文件（全局 + 项目两级）供查看、编辑、新建、删除。
 * 读写都是磁盘 IO，统一放 IO 线程。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<MemoryError?>(null)
    val error: StateFlow<MemoryError?> = _error.asStateFlow()

    /** 界面已消费失败提示后清除，避免重复弹出。 */
    fun consumeError() {
        _error.value = null
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                val all = repository.listMemories(projectRoot)
                MemoryUiState(
                    global = all.filter { it.scope == MemoryScope.GLOBAL },
                    project = all.filter { it.scope == MemoryScope.PROJECT },
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** 新建或覆盖一条记忆；保存后重读列表反映结果，失败时置错误供界面提示。 */
    fun save(name: String, description: String, content: String, scope: MemoryScope) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val ok = withContext(Dispatchers.IO) {
                repository.saveMemory(name, description, content, scope, projectRoot)
            }
            if (!ok) _error.value = MemoryError.SAVE_FAILED
            refresh()
        }
    }

    fun delete(name: String, scope: MemoryScope) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val ok = withContext(Dispatchers.IO) {
                repository.deleteMemory(name, scope, projectRoot)
            }
            if (!ok) _error.value = MemoryError.DELETE_FAILED
            refresh()
        }
    }
}
