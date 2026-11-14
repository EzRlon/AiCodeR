package com.aicode.feature.changelog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.changelog.data.ChangeLedgerRepository
import com.aicode.feature.changelog.domain.model.ChangeEntry
import com.aicode.feature.changelog.domain.model.ChangeKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 改动账本页的数据层：把当前工作区的账目按状态/类型聚合成可直接渲染的形状。
 *
 * 「哪些改好了、哪些没做」用状态计数回答（[Summary]），列表本身按更新时间倒序；
 * 筛选在内存里做（单项目账目量级小，无需每次重建 Flow）。
 */
@HiltViewModel
class ChangeLedgerViewModel @Inject constructor(
    private val repository: ChangeLedgerRepository
) : ViewModel() {

    /** 概览计数：各状态条数 + 有无证据的比例，一眼看出「还有多少没做完」。 */
    data class Summary(
        val total: Int = 0,
        val pending: Int = 0,
        val doing: Int = 0,
        val review: Int = 0,
        val closed: Int = 0,
        val withoutEvidence: Int = 0
    ) {
        val verifiedRatio: Float get() = if (total == 0) 0f else closed.toFloat() / total
    }

    private val workspace = MutableStateFlow("")

    /** 当前工作区账目（新的在前）。 */
    val entries: StateFlow<List<ChangeEntry>> = workspace
        .flatMapLatest { ws ->
            if (ws.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.observe(ws)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 状态筛选：null = 全部。 */
    private val _statusFilter = MutableStateFlow<AnnotationStatus?>(null)
    val statusFilter: StateFlow<AnnotationStatus?> = _statusFilter.asStateFlow()

    /** 类型筛选：null = 全部。 */
    private val _kindFilter = MutableStateFlow<ChangeKind?>(null)
    val kindFilter: StateFlow<ChangeKind?> = _kindFilter.asStateFlow()

    /** 应用筛选后的列表，供 UI 直接渲染。 */
    val visibleEntries: StateFlow<List<ChangeEntry>> = kotlinx.coroutines.flow.combine(
        entries, _statusFilter, _kindFilter
    ) { list, status, kind ->
        list.filter { (status == null || it.status == status) && (kind == null || it.kind == kind) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val summary: StateFlow<Summary> = entries
        .map { list ->
            Summary(
                total = list.size,
                pending = list.count { it.status == AnnotationStatus.PENDING || it.status == AnnotationStatus.SENT },
                doing = list.count { it.status == AnnotationStatus.FIXING },
                review = list.count { it.status == AnnotationStatus.REVIEW },
                closed = list.count { it.status == AnnotationStatus.CLOSED },
                withoutEvidence = list.count { !it.hasEvidence }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Summary())

    fun bindWorkspace(workspacePath: String) {
        workspace.value = workspacePath
    }

    fun setStatusFilter(status: AnnotationStatus?) { _statusFilter.value = status }

    fun setKindFilter(kind: ChangeKind?) { _kindFilter.value = kind }

    /**
     * 推进状态。CLOSED 只允许用户手动触发（复验是用户验收）——AI 侧走工具时也受同一约束
     * （见 ChangeLedgerRepository.advanceByAnnotation）。
     */
    fun advance(id: String, status: AnnotationStatus) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.updateStatus(id, status) }
                .onFailure { FileLogger.e("ChangeLedgerVM", "更新账目状态失败: $id", it) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.delete(id) }
                .onFailure { FileLogger.e("ChangeLedgerVM", "删除账目失败: $id", it) }
        }
    }
}
