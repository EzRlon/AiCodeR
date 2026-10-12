package com.aicode.feature.annotation.presentation.component

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.appTextFieldColors
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationPoint
import com.aicode.feature.annotation.domain.model.AnnotationType
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Square
import compose.icons.feathericons.Trash
import compose.icons.feathericons.X

/** 标注工具：矩形框 / 自由笔。 */
private enum class AnnotationTool { RECT, STROKE }

/**
 * 截图标注编辑：全屏对话框，底图为刚截下的界面，手势在图上画标注。
 * 坐标全程按「图片内容区域」归一化（考虑 Fit letterbox 的偏移与缩放），与像素尺寸解耦。
 */
@Composable
internal fun AnnotationEditorDialog(
    bitmap: Bitmap,
    onSave: (marks: List<AnnotationMark>, type: AnnotationType, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentType by remember { mutableStateOf(AnnotationType.BUG) }
    var tool by remember { mutableStateOf(AnnotationTool.RECT) }
    val marks = remember { mutableStateListOf<AnnotationMark>() }
    var draftMark by remember { mutableStateOf<AnnotationMark?>(null) }
    var note by remember { mutableStateOf("") }
    var imageArea by remember { mutableStateOf(IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ── 顶栏：关闭 / 类型四色 / 工具 / 撤销 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(FeatherIcons.X, contentDescription = stringResource(R.string.common_close), tint = Color.White)
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    AnnotationType.entries.forEach { type ->
                        TypeChip(
                            type = type,
                            selected = currentType == type,
                            onClick = { currentType = type }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Spacer(Modifier.weight(1f))
                    ToolToggle(
                        selected = tool == AnnotationTool.RECT,
                        icon = { Icon(FeatherIcons.Square, null, tint = Color.White, modifier = Modifier.size(16.dp)) },
                        onClick = { tool = AnnotationTool.RECT }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    ToolToggle(
                        selected = tool == AnnotationTool.STROKE,
                        icon = { Icon(FeatherIcons.Edit3, null, tint = Color.White, modifier = Modifier.size(16.dp)) },
                        onClick = { tool = AnnotationTool.STROKE }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    IconButton(
                        onClick = { marks.removeLastOrNull() },
                        enabled = marks.isNotEmpty()
                    ) {
                        Icon(
                            FeatherIcons.Trash,
                            contentDescription = stringResource(R.string.annotation_undo),
                            tint = if (marks.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f)
                        )
                    }
                }

                // ── 图片 + 画布 ──
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val areaWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                    val areaHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                    val fit = remember(imageArea, areaWidthPx, areaHeightPx, bitmap) {
                        computeFitRect(
                            containerWidth = areaWidthPx,
                            containerHeight = areaHeightPx,
                            imageWidth = bitmap.width.toFloat(),
                            imageHeight = bitmap.height.toFloat()
                        )
                    }
                    // 尺寸变化（旋转/窗口变化）会取消手势协程，兜底清掉未完成的草稿笔，避免半成品滞留挡住保存
                    LaunchedEffect(fit) { draftMark = null }
                    Image(
                        bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { imageArea = it }
                            .pointerInput(currentType, tool, fit) {
                                if (fit.width <= 0f) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { start ->
                                        if (!fit.contains(start)) return@detectDragGestures
                                        draftMark = when (tool) {
                                            AnnotationTool.RECT -> AnnotationMark(
                                                type = currentType.name,
                                                kind = AnnotationMark.KIND_RECT,
                                                x = fit.normalizeX(start.x),
                                                y = fit.normalizeY(start.y),
                                                w = 0f,
                                                h = 0f
                                            )
                                            AnnotationTool.STROKE -> AnnotationMark(
                                                type = currentType.name,
                                                kind = AnnotationMark.KIND_STROKE,
                                                points = listOf(fit.normalize(start))
                                            )
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val pos = change.position
                                        val draft = draftMark ?: return@detectDragGestures
                                        draftMark = when (draft.kind) {
                                            AnnotationMark.KIND_RECT -> draft.copy(
                                                w = fit.normalizeX(pos.x) - draft.x,
                                                h = fit.normalizeY(pos.y) - draft.y
                                            )
                                            else -> draft.copy(points = draft.points + fit.normalize(pos))
                                        }
                                    },
                                    onDragEnd = {
                                        val draft = draftMark
                                        draftMark = null
                                        if (draft != null && draft.isMeaningful()) {
                                            marks += draft
                                        }
                                    },
                                    onDragCancel = { draftMark = null }
                                )
                            }
                    ) {
                        marks.forEach { mark -> drawMark(mark, fit, fade = false) }
                        draftMark?.let { drawMark(it, fit, fade = true) }
                    }
                }

                // ── 底栏：说明 + 保存 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = stringResource(R.string.annotation_note_hint),
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Button(
                        onClick = {
                            if (draftMark == null) onSave(marks.toList(), currentType, note)
                        },
                        enabled = marks.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.annotation_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeChip(type: AnnotationType, selected: Boolean, onClick: () -> Unit) {
    val color = annotationColor(type)
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = if (selected) color.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f),
        onClick = onClick
    ) {
        Text(
            text = stringResource(type.labelRes()),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 5.dp)
        )
    }
}

@Composable
private fun ToolToggle(selected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
        onClick = onClick,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

/** 标注类型 → 颜色（错误红 / 警示橙 / 第三色 / 主色）。 */
@Composable
internal fun annotationColor(type: AnnotationType): Color = when (type) {
    AnnotationType.BUG -> MaterialTheme.colorScheme.error
    AnnotationType.TO_CHANGE -> Brand.Orange
    AnnotationType.DONT -> MaterialTheme.colorScheme.tertiary
    AnnotationType.OPTIMIZE -> MaterialTheme.colorScheme.primary
}

internal fun AnnotationType.labelRes(): Int = when (this) {
    AnnotationType.BUG -> R.string.annotation_type_bug
    AnnotationType.TO_CHANGE -> R.string.annotation_type_to_change
    AnnotationType.DONT -> R.string.annotation_type_dont
    AnnotationType.OPTIMIZE -> R.string.annotation_type_optimize
}

/** 图片在容器内的 Fit 显示区域（letterbox 偏移 + 缩放），负责像素坐标与归一化坐标互转。 */
internal data class FitRect(
    val offsetX: Float,
    val offsetY: Float,
    val width: Float,
    val height: Float
) {
    fun contains(pos: Offset): Boolean =
        pos.x in offsetX..(offsetX + width) && pos.y in offsetY..(offsetY + height)

    fun normalizeX(x: Float): Float = ((x - offsetX) / width).coerceIn(0f, 1f)
    fun normalizeY(y: Float): Float = ((y - offsetY) / height).coerceIn(0f, 1f)
    fun normalize(pos: Offset): AnnotationPoint = AnnotationPoint(normalizeX(pos.x), normalizeY(pos.y))
    fun toPixel(x: Float, y: Float): Offset = Offset(offsetX + x * width, offsetY + y * height)
}

internal fun computeFitRect(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float
): FitRect {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return FitRect(0f, 0f, 0f, 0f)
    }
    val scale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    return FitRect((containerWidth - w) / 2f, (containerHeight - h) / 2f, w, h)
}

private fun AnnotationMark.isMeaningful(): Boolean = when (kind) {
    AnnotationMark.KIND_RECT -> kotlin.math.abs(w) > 0.01f && kotlin.math.abs(h) > 0.01f
    else -> points.size >= 2
}

internal fun DrawScope.drawMark(mark: AnnotationMark, fit: FitRect, fade: Boolean) {
    if (fit.width <= 0f) return
    val color = when (mark.type) {
        AnnotationType.BUG.name -> Color(0xFFE53935)
        AnnotationType.TO_CHANGE.name -> Color(0xFFFB8C00)
        AnnotationType.DONT.name -> Color(0xFF8E24AA)
        else -> Color(0xFF1E88E5)
    }.let { if (fade) it.copy(alpha = 0.6f) else it }
    val strokeWidth = 2.5.dp.toPx()
    when (mark.kind) {
        AnnotationMark.KIND_RECT -> {
            val start = fit.toPixel(minOf(mark.x, mark.x + mark.w), minOf(mark.y, mark.y + mark.h))
            val size = Size(kotlin.math.abs(mark.w) * fit.width, kotlin.math.abs(mark.h) * fit.height)
            drawRect(
                color = color.copy(alpha = color.alpha * 0.15f),
                topLeft = start,
                size = size
            )
            drawRect(
                color = color,
                topLeft = start,
                size = size,
                style = Stroke(width = strokeWidth)
            )
        }
        else -> {
            if (mark.points.size < 2) return
            val path = Path()
            mark.points.forEachIndexed { index, p ->
                val px = fit.toPixel(p.x, p.y)
                if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
            }
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
