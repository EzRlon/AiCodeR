package com.aicode.feature.annotation.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.annotation.data.AnnotationRepository
import com.aicode.feature.annotation.domain.model.AnnotationItem
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationMarksCodec
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
import kotlin.math.roundToInt

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
            // 结构化位置描述：把每个标注图形的归一化坐标翻译成百分比区间，
            // 让 AI 不用只靠看图猜位置（用户反馈：标注要能精准传达到位）。
            AnnotationMarksCodec.decode(item.marksJson).forEach { mark ->
                append('\n')
                append("   - ")
                append(describeMark(mark))
            }
        }
        append('\n')
        append(context.getString(R.string.annotation_summary_footer))
    }

    /** 单个标注图形 → 人/机都可读的位置描述（坐标均归一化百分比，与截图分辨率无关）。 */
    private fun describeMark(m: AnnotationMark): String {
        val pct = { v: Float -> (v.coerceIn(0f, 1f) * 100).roundToInt() }
        val main = when (m.kind) {
            AnnotationMark.KIND_RECT ->
                "框选：横向 ${pct(m.x)}%~${pct(m.x + m.w)}、纵向 ${pct(m.y)}%~${pct(m.y + m.h)}"
            AnnotationMark.KIND_ARROW ->
                "箭头：指向横向 ${pct(m.x + m.w)}%、纵向 ${pct(m.y + m.h)}% 处"
            AnnotationMark.KIND_LINE ->
                "直线：(${pct(m.x)}%,${pct(m.y)}%)→(${pct(m.x + m.w)}%,${pct(m.y + m.h)}%)"
            AnnotationMark.KIND_STROKE ->
                "笔迹：经过横向 ${pct(m.x)}%、纵向 ${pct(m.y)}% 附近"
            AnnotationMark.KIND_TEXT ->
                "文字标注 @横向 ${pct(m.x)}%、纵向 ${pct(m.y)}%"
            else -> "标注 @横向 ${pct(m.x)}%、纵向 ${pct(m.y)}%"
        }
        return if (m.text.isNotBlank() && m.kind != AnnotationMark.KIND_TEXT) {
            "$main（附注「${m.text}」）"
        } else {
            "$main${if (m.text.isNotBlank()) "「${m.text}」" else ""}"
        }
    }

    suspend fun readImageBytes(item: AnnotationItem): ByteArray? = repository.readImageBytes(item)
}
