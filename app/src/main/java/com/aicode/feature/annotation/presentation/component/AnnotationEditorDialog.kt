package com.aicode.feature.annotation.presentation.component

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Square
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Trash
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 标注工具：矩形 / 直线 / 箭头 / 自由笔 / 文字。 */
internal enum class AnnotationTool { RECT, LINE, ARROW, STROKE, TEXT }

/** 元素命中测试的手指容差（像素）。 */
private const val HIT_PAD_PX = 24f

/** 箭头两翼的像素长度与半角（弧度）。 */
private const val ARROW_WING_PX = 26f
private const val ARROW_WING_ANGLE = 0.45f

/**
 * 截图标注编辑：全屏对话框，底图为刚截下的界面，手势在图上画标注。
 * 坐标全程按「图片内容区域」归一化（考虑 Fit letterbox 的偏移与缩放），与像素尺寸解耦。
 *
 * 交互（2026-10-13 增强）：点击已画元素即选中并可拖动平移；文字元素可编辑内容；
 * 工具集含矩形 / 直线 / 箭头 / 自由笔 / 文字五种。
 */
@Composable
internal fun AnnotationEditorDialog(
    bitmap: Bitmap,
    /** 预置标注（活标注 v2「完成」时带来的 marks；普通截屏标注为空）。 */
    initialMarks: List<AnnotationMark> = emptyList(),
    onSave: (marks: List<AnnotationMark>, type: AnnotationType, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentType by remember { mutableStateOf(AnnotationType.BUG) }
    var tool by remember { mutableStateOf(AnnotationTool.RECT) }
    val marks = remember { mutableStateListOf<AnnotationMark>().apply { addAll(initialMarks) } }
    var draftMark by remember { mutableStateOf<AnnotationMark?>(null) }
    var note by remember { mutableStateOf("") }
    var imageArea by remember { mutableStateOf(IntSize.Zero) }
    // 选中元素（-1 为空）；文字工具的放置待定位置与文字编辑态
    var selectedIndex by remember { mutableStateOf(-1) }
    var pendingTextPos by remember { mutableStateOf<Offset?>(null) }
    var editingTextIndex by remember { mutableStateOf(-1) }
    var textDraft by remember { mutableStateOf("") }
    val textMeasurer = rememberTextMeasurer()

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
                // ── 顶栏：关闭 / 类型四色 / 撤销 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                        .padding(top = Spacing.sm, bottom = Spacing.xs),
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
                    IconButton(
                        onClick = {
                            marks.removeLastOrNull()
                            selectedIndex = -1
                        },
                        enabled = marks.isNotEmpty()
                    ) {
                        Icon(
                            FeatherIcons.RotateCcw,
                            contentDescription = stringResource(R.string.annotation_undo),
                            tint = if (marks.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f)
                        )
                    }
                }

                // ── 工具条：五种工具 + 选中元素的快捷操作 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md)
                        .padding(bottom = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolToggle(
                        selected = tool == AnnotationTool.RECT,
                        icon = FeatherIcons.Square,
                        contentDescription = stringResource(R.string.annotation_tool_rect),
                        onClick = { tool = AnnotationTool.RECT }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    ToolToggle(
                        selected = tool == AnnotationTool.LINE,
                        icon = FeatherIcons.Minus,
                        contentDescription = stringResource(R.string.annotation_tool_line),
                        onClick = { tool = AnnotationTool.LINE }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    ToolToggle(
                        selected = tool == AnnotationTool.ARROW,
                        icon = FeatherIcons.ArrowUpRight,
                        contentDescription = stringResource(R.string.annotation_tool_arrow),
                        onClick = { tool = AnnotationTool.ARROW }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    ToolToggle(
                        selected = tool == AnnotationTool.STROKE,
                        icon = FeatherIcons.Edit3,
                        contentDescription = stringResource(R.string.annotation_tool_stroke),
                        onClick = { tool = AnnotationTool.STROKE }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    ToolToggle(
                        selected = tool == AnnotationTool.TEXT,
                        icon = FeatherIcons.Type,
                        contentDescription = stringResource(R.string.annotation_tool_text),
                        onClick = { tool = AnnotationTool.TEXT }
                    )
                    Spacer(Modifier.weight(1f))
                    // 选中元素的快捷操作：编辑文字（仅文字元素）/ 删除所选
                    val selectedMark = marks.getOrNull(selectedIndex)
                    if (selectedMark != null) {
                        if (selectedMark.kind == AnnotationMark.KIND_TEXT) {
                            ToolToggle(
                                selected = false,
                                icon = FeatherIcons.Edit3,
                                contentDescription = stringResource(R.string.annotation_edit_text),
                                onClick = {
                                    editingTextIndex = selectedIndex
                                    textDraft = selectedMark.text
                                }
                            )
                            Spacer(Modifier.width(Spacing.xs))
                        }
                        ToolToggle(
                            selected = false,
                            icon = FeatherIcons.Trash,
                            contentDescription = stringResource(R.string.annotation_delete_selected),
                            onClick = {
                                marks.removeAt(selectedIndex)
                                selectedIndex = -1
                            }
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
                            .pointerInput(currentType, tool, fit, marks.size) {
                                if (fit.width <= 0f) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!fit.contains(down.position)) return@awaitEachGesture

                                    // 1) 命中已画元素：选中并拖动平移（无论当前工具）
                                    val hitIndex = marks.indexOfLast { markHitTest(it, down.position, fit) }
                                    if (hitIndex >= 0) {
                                        selectedIndex = hitIndex
                                        val startMark = marks[hitIndex]
                                        val startPos = down.position
                                        down.consume()
                                        var dragged = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            val total = change.position - startPos
                                            if (!dragged && total.getDistance() > viewConfiguration.touchSlop) {
                                                dragged = true
                                            }
                                            if (dragged) {
                                                change.consume()
                                                marks[hitIndex] = translateMark(startMark, total, fit)
                                            }
                                            if (!change.pressed) break
                                        }
                                        return@awaitEachGesture
                                    }

                                    // 2) 未命中：清除选中
                                    selectedIndex = -1

                                    // 3) 文字工具：抬手后在该位置放置文字
                                    if (tool == AnnotationTool.TEXT) {
                                        var pos = down.position
                                        var moved = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if ((change.position - pos).getDistance() > viewConfiguration.touchSlop) moved = true
                                            if (!change.pressed) {
                                                pos = change.position
                                                break
                                            }
                                        }
                                        if (!moved) pendingTextPos = pos
                                        return@awaitEachGesture
                                    }

                                    // 4) 绘制：矩形 / 直线 / 箭头 / 自由笔
                                    draftMark = createDraft(tool, currentType, down.position, fit)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val draft = draftMark ?: break
                                        draftMark = updateDraft(draft, change.position, fit)
                                        change.consume()
                                        if (!change.pressed) break
                                    }
                                    val draft = draftMark
                                    draftMark = null
                                    if (draft != null && draft.isMeaningful()) {
                                        marks += draft
                                    }
                                }
                            }
                    ) {
                        marks.forEachIndexed { index, mark ->
                            drawMark(mark, fit, fade = false, selected = index == selectedIndex, textMeasurer = textMeasurer)
                        }
                        draftMark?.let { drawMark(it, fit, fade = true, textMeasurer = textMeasurer) }
                    }
                }

                // ── 底栏：说明 + 保存（小圆钮，与顶栏工具钮同款风格） ──
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
                    val canSave = marks.isNotEmpty() && draftMark == null
                    Surface(
                        shape = CircleShape,
                        color = if (canSave) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        onClick = {
                            if (canSave) onSave(marks.toList(), currentType, note)
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                FeatherIcons.Check,
                                contentDescription = stringResource(R.string.annotation_save),
                                tint = if (canSave) Color.White else Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 文字放置 / 编辑对话框
    if (pendingTextPos != null || editingTextIndex >= 0) {
        AlertDialog(
            onDismissRequest = {
                pendingTextPos = null
                editingTextIndex = -1
            },
            title = {
                Text(stringResource(if (editingTextIndex >= 0) R.string.annotation_edit_text else R.string.annotation_tool_text))
            },
            text = {
                AppTextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    placeholder = stringResource(R.string.annotation_text_hint),
                    singleLine = true,
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = textDraft.trim()
                    val pos = pendingTextPos
                    when {
                        editingTextIndex >= 0 && editingTextIndex < marks.size -> {
                            if (text.isNotEmpty()) {
                                marks[editingTextIndex] = marks[editingTextIndex].copy(text = text)
                            }
                        }
                        pos != null && text.isNotEmpty() -> {
                            val f = fitOf(pendingTextPos, imageArea, bitmap)
                            marks += AnnotationMark(
                                type = currentType.name,
                                kind = AnnotationMark.KIND_TEXT,
                                x = f?.normalizeX(pos.x) ?: 0f,
                                y = f?.normalizeY(pos.y) ?: 0f,
                                text = text
                            )
                            selectedIndex = marks.lastIndex
                        }
                    }
                    pendingTextPos = null
                    editingTextIndex = -1
                    textDraft = ""
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingTextPos = null
                    editingTextIndex = -1
                    textDraft = ""
                }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 放置文字时按当前容器与图片重新计算 Fit（对话框阶段画布尺寸已定）。 */
private fun fitOf(pos: Offset?, area: IntSize, bitmap: Bitmap): FitRect? {
    if (pos == null || area.width <= 0 || area.height <= 0) return null
    return computeFitRect(
        containerWidth = area.width.toFloat(),
        containerHeight = area.height.toFloat(),
        imageWidth = bitmap.width.toFloat(),
        imageHeight = bitmap.height.toFloat()
    )
}

/** 依据工具创建草稿元素（归一化起点）。v2 活标注复用。 */
internal fun createDraft(tool: AnnotationTool, type: AnnotationType, start: Offset, fit: FitRect): AnnotationMark = when (tool) {
    AnnotationTool.RECT -> AnnotationMark(
        type = type.name,
        kind = AnnotationMark.KIND_RECT,
        x = fit.normalizeX(start.x),
        y = fit.normalizeY(start.y)
    )
    AnnotationTool.LINE -> AnnotationMark(
        type = type.name,
        kind = AnnotationMark.KIND_LINE,
        x = fit.normalizeX(start.x),
        y = fit.normalizeY(start.y)
    )
    AnnotationTool.ARROW -> AnnotationMark(
        type = type.name,
        kind = AnnotationMark.KIND_ARROW,
        x = fit.normalizeX(start.x),
        y = fit.normalizeY(start.y)
    )
    AnnotationTool.STROKE -> AnnotationMark(
        type = type.name,
        kind = AnnotationMark.KIND_STROKE,
        points = listOf(fit.normalize(start))
    )
    AnnotationTool.TEXT -> AnnotationMark(type = type.name, kind = AnnotationMark.KIND_TEXT)
}

/** 拖动中的草稿更新：RECT/LINE/ARROW 记差值，STROKE 追加点。v2 活标注复用。 */
internal fun updateDraft(draft: AnnotationMark, pos: Offset, fit: FitRect): AnnotationMark = when (draft.kind) {
    AnnotationMark.KIND_STROKE -> draft.copy(points = draft.points + fit.normalize(pos))
    else -> draft.copy(
        w = fit.normalizeX(pos.x) - draft.x,
        h = fit.normalizeY(pos.y) - draft.y
    )
}

/** 平移元素：整体位移（归一化并夹在画布内）。v2 活标注复用。 */
internal fun translateMark(mark: AnnotationMark, delta: Offset, fit: FitRect): AnnotationMark {
    val dx = delta.x / fit.width
    val dy = delta.y / fit.height
    return when (mark.kind) {
        AnnotationMark.KIND_STROKE -> mark.copy(
            points = mark.points.map { p ->
                AnnotationPoint((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f))
            }
        )
        else -> mark.copy(
            x = (mark.x + dx).coerceIn(0f, 1f),
            y = (mark.y + dy).coerceIn(0f, 1f)
        )
    }
}

/** 命中测试：矩形用包围盒（含容差）；直线/箭头用点到线段距离；笔迹用点距；文字用保守框。v2 活标注复用。 */
internal fun markHitTest(mark: AnnotationMark, pos: Offset, fit: FitRect): Boolean {
    val padX = HIT_PAD_PX / fit.width
    val padY = HIT_PAD_PX / fit.height
    return when (mark.kind) {
        AnnotationMark.KIND_STROKE -> mark.points.any { p ->
            (fit.toPixel(p.x, p.y) - pos).getDistance() <= HIT_PAD_PX
        }
        AnnotationMark.KIND_LINE, AnnotationMark.KIND_ARROW -> {
            val start = fit.toPixel(mark.x, mark.y)
            val end = fit.toPixel(mark.x + mark.w, mark.y + mark.h)
            distanceToSegment(pos, start, end) <= HIT_PAD_PX
        }
        AnnotationMark.KIND_TEXT -> {
            val left = mark.x
            val top = mark.y
            val right = (mark.x + 0.05f + 0.012f * mark.text.length).coerceAtMost(1f)
            val bottom = (mark.y + 0.06f).coerceAtMost(1f)
            pos.x >= left - padX && pos.x <= right + padX &&
                pos.y >= top - padY && pos.y <= bottom + padY
        }
        else -> {
            val left = min(mark.x, mark.x + mark.w)
            val top = min(mark.y, mark.y + mark.h)
            val right = max(mark.x, mark.x + mark.w)
            val bottom = max(mark.y, mark.y + mark.h)
            pos.x >= left - padX && pos.x <= right + padX &&
                pos.y >= top - padY && pos.y <= bottom + padY
        }
    }
}

/** 点到线段的最短距离（像素坐标）。 */
internal fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lenSq = ab.x * ab.x + ab.y * ab.y
    if (lenSq <= 1e-4f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lenSq).coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
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
internal fun ToolToggle(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
        onClick = onClick,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(16.dp))
        }
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

internal fun AnnotationMark.isMeaningful(): Boolean = when (kind) {
    AnnotationMark.KIND_RECT -> abs(w) > 0.01f && abs(h) > 0.01f
    AnnotationMark.KIND_LINE, AnnotationMark.KIND_ARROW ->
        (abs(w) > 0.01f || abs(h) > 0.01f)
    AnnotationMark.KIND_TEXT -> text.isNotBlank()
    else -> points.size >= 2
}

/**
 * 绘制单个标注。[selected] 为真时加一圈虚线包围框；文字元素需要 [textMeasurer]（为空时跳过文字）。
 * 查看器与账本同样复用本函数（默认不传选中与测量器）。
 */
internal fun DrawScope.drawMark(
    mark: AnnotationMark,
    fit: FitRect,
    fade: Boolean,
    selected: Boolean = false,
    textMeasurer: TextMeasurer? = null
) {
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
            val start = fit.toPixel(min(mark.x, mark.x + mark.w), min(mark.y, mark.y + mark.h))
            val size = Size(abs(mark.w) * fit.width, abs(mark.h) * fit.height)
            drawRect(color = color.copy(alpha = color.alpha * 0.15f), topLeft = start, size = size)
            drawRect(color = color, topLeft = start, size = size, style = Stroke(width = strokeWidth))
        }
        AnnotationMark.KIND_LINE -> {
            drawLine(
                color = color,
                start = fit.toPixel(mark.x, mark.y),
                end = fit.toPixel(mark.x + mark.w, mark.y + mark.h),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
        AnnotationMark.KIND_ARROW -> {
            val start = fit.toPixel(mark.x, mark.y)
            val end = fit.toPixel(mark.x + mark.w, mark.y + mark.h)
            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
            val angle = atan2(end.y - start.y, end.x - start.x)
            val wing = ARROW_WING_PX
            val w1 = Offset(end.x - wing * cos(angle - ARROW_WING_ANGLE), end.y - wing * sin(angle - ARROW_WING_ANGLE))
            val w2 = Offset(end.x - wing * cos(angle + ARROW_WING_ANGLE), end.y - wing * sin(angle + ARROW_WING_ANGLE))
            drawLine(color = color, start = end, end = w1, strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color = color, start = end, end = w2, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
        AnnotationMark.KIND_TEXT -> {
            if (textMeasurer != null && mark.text.isNotBlank()) {
                val style = TextStyle(color = color, fontSize = (fit.height * 0.032f).toSp())
                val layout = textMeasurer.measure(AnnotatedString(mark.text), style)
                val topLeft = fit.toPixel(mark.x, mark.y) + Offset(4f, 2f)
                // 半透明底衬提升可读性
                drawRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = topLeft - Offset(4f, 2f),
                    size = Size(layout.size.width.toFloat() + 8f, layout.size.height.toFloat() + 4f)
                )
                drawText(layout, topLeft = topLeft)
            }
        }
        else -> {
            if (mark.points.size < 2) return
            val path = Path()
            mark.points.forEachIndexed { index, p ->
                val px = fit.toPixel(p.x, p.y)
                if (index == 0) path.moveTo(px.x, px.y) else path.lineTo(px.x, px.y)
            }
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }
    }
    if (selected) {
        // 选中：虚线包围框（元素几何的整体外框）
        val left = min(mark.x, mark.x + mark.w)
        val top = min(mark.y, mark.y + mark.h)
        val right = max(mark.x, mark.x + mark.w)
        val bottom = max(mark.y, mark.y + mark.h)
        val pad = 6f / fit.width
        val padY = 6f / fit.height
        drawRoundRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = fit.toPixel(left - pad, top - padY),
            size = Size((right - left + pad * 2) * fit.width, (bottom - top + padY * 2) * fit.height),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
        )
    }
}
