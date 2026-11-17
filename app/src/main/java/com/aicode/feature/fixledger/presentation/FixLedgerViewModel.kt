package com.aicode.feature.fixledger.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.fixledger.data.FixLedgerRepository
import com.aicode.feature.fixledger.domain.model.FixLedgerItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FixLedgerViewModel @Inject constructor(
    private val repository: FixLedgerRepository
) : ViewModel() {

    val items: StateFlow<List<FixLedgerItem>> = repository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, note: String = "") = viewModelScope.launch {
        if (title.isBlank()) return@launch
        repository.add(title.trim(), note.trim())
    }

    /**
     * 从标注转入：状态沿用标注当前状态；同一标注只转一次（重复点按 no-op 并回调 false）。
     * [fallbackTitle] 由 UI 层用本地化文案构造（标注未填说明时的标题）。
     */
    fun addFromAnnotation(
        item: AnnotationItem,
        fallbackTitle: String,
        onResult: (Boolean) -> Unit = {}
    ) = viewModelScope.launch {
        if (repository.hasEntryForAnnotation(item.id)) {
            onResult(false)
            return@launch
        }
        repository.add(
            title = item.note.ifBlank { fallbackTitle },
            note = "",
            sourceAnnotationId = item.id,
            status = item.status
        )
        onResult(true)
    }

    fun updateStatus(id: String, status: AnnotationStatus) = viewModelScope.launch {
        repository.updateStatus(id, status)
    }

    fun delete(id: String) = viewModelScope.launch {
        repository.delete(id)
    }
}
