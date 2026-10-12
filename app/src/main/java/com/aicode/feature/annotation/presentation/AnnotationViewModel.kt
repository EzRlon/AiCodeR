package com.aicode.feature.annotation.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.annotation.data.AnnotationRepository
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationStatus
import com.aicode.feature.annotation.domain.model.AnnotationType
import com.aicode.feature.annotation.presentation.component.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 截图标注账本状态。 */
@HiltViewModel
class AnnotationViewModel @Inject constructor(
    private val repository: AnnotationRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val items: StateFlow<List<AnnotationItem>> = repository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveCapture(
        bitmap: android.graphics.Bitmap,
        marks: List<AnnotationMark>,
        type: AnnotationType,
        note: String,
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val ok = repository.saveCapture(bitmap, type, marks, note) != null
            onDone(ok)
        }
    }

    fun updateStatus(id: String, status: AnnotationStatus) {
        viewModelScope.launch { repository.updateStatus(id, status) }
    }

    /** 汇总注入成功后批量推进状态机（待确认 → 已发AI）。 */
    fun markSent(items: List<AnnotationItem>) {
        viewModelScope.launch {
            items.filter { it.status == AnnotationStatus.PENDING }.forEach {
                repository.updateStatus(it.id, AnnotationStatus.SENT)
            }
        }
    }

    fun delete(item: AnnotationItem) {
        viewModelScope.launch { repository.delete(item) }
    }

    /** 汇总文本（按当前界面语言生成，注入聊天输入框）。 */
    fun buildSummaryText(items: List<AnnotationItem>): String = buildString {
        append(context.getString(R.string.annotation_summary_header, items.size))
        items.forEachIndexed { index, item ->
            append('\n')
            append(
                context.getString(
                    R.string.annotation_summary_line,
                    index + 1,
                    context.getString(item.type.labelRes()),
                    item.note.ifBlank { context.getString(R.string.annotation_no_note) },
                    item.imagePath
                )
            )
        }
        append('\n')
        append(context.getString(R.string.annotation_summary_footer))
    }

    suspend fun readImageBytes(item: AnnotationItem): ByteArray? = repository.readImageBytes(item)
}
