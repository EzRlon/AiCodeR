package com.aicode.feature.annotation.presentation.component

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationPoint
import com.aicode.feature.annotation.domain.model.AnnotationType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

/** 标注工具：矩形 / 直线 / 箭头 / 自由笔 / 文字。 */
internal enum class AnnotationTool { RECT, LINE, ARROW, STROKE, TEXT }

/** 元素命中测试的手指容差（像素）。 */
private const val HIT_PAD_PX = 24f

/** 箭头两翼的像素长度与半角（弧度）。 */
private const val ARROW_WING_PX = 26f
private const val ARROW_WING_ANGLE = 0.45f

/** 缩放平移的倍率上限。 */
private const val CANVAS_MAX_SCALE = 8f

/**
 * 统一画布：底图（可空）之上绘制与编辑标注。
 *
 * - [baseImage] 为空 = 活标注态：fit 覆盖整个容器，手势层由调用方按工具条件组合，
 *   工具未激活时触摸穿透到真实界面；
 * - [baseImage] 非空 = 冻结态：底图 letterbox 适配，支持双指缩放平移（缩放经
 *   graphicsLayer 施加，绘制手势在层内拿到的已是局部坐标，归一化计算不受影响）。
 *
 * 点击已画元素即选中（[onSelectedIndexChange]）并可拖动平移；文字工具抬手后经
 * [onPlaceText] 回调交由调用方弹输入。
 */
@Composable
internal fun AnnotationCanvas(
    modifier: Modifier = Modifier,
    baseImage: Bitmap?,
    marks: MutableList<AnnotationMark>,
    tool: AnnotationTool?,
    type: AnnotationType,
    arrowStartOverride: Offset? = null,
    onArrowOverrideConsumed: () -> Unit = {},
    onSelectedIndexChange: (Int) -> Unit = {},
    onPlaceText: (Offset) -> Unit = {}
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draft by remember { mutableStateOf<AnnotationMark?>(null) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    val fit = remember(canvasSize, baseImage) {
        if (baseImage != null && canvasSize.width > 0 && canvasSize.height > 0) {
            computeFitRect(
                containerWidth = canvasSize.width.toFloat(),
                containerHeight = canvasSize.height.toFloat(),
                imageWidth = baseImage.width.toFloat(),
                imageHeight = baseImage.height.toFloat()
            )
        } else {
            FitRect(0f, 0f, canvasSize.width.toFloat().coerceAtLeast(1f), canvasSize.height.toFloat().coerceAtLeast(1f))
        }
    }

    val transformState = rememberTransformableState { zoom, pan, _ ->
        if (baseImage == null) return@rememberTransformableState
        scale = (scale * zoom).coerceIn(1f, CANVAS_MAX_SCALE)
        panOffset += pan
    }
    LaunchedEffect(baseImage) {
        scale = 1f
        panOffset = Offset.Zero
        draft = null
        selectedIndex = -1
    }
    LaunchedEffect(marks) {
        snapshotFlow { marks.size }.collect {
            if (selectedIndex >= it) {
                selectedIndex = -1
                onSelectedIndexChange(-1)
            }
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .then(if (baseImage != null) Modifier.transformable(transformState) else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        ) {
            baseImage?.let { bitmap ->
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                marks.forEachIndexed { index, mark ->
                    drawMark(mark, fit, fade = false, selected = index == selectedIndex, textMeasurer = textMeasurer)
                }
                draft?.let { drawMark(it, fit, fade = true, textMeasurer = textMeasurer) }
            }

            if (tool == AnnotationTool.TEXT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(fit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
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
                                if (!moved && fit.contains(pos)) {
                                    down.consume()
                                    onPlaceText(pos)
                                }
                            }
                        }
                )
            } else if (tool != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tool, type, fit, marks.size, baseImage) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!fit.contains(down.position)) return@awaitEachGesture

                                // 1) 命中已画元素：选中并拖动平移（无论当前工具）
                                val hitIndex = marks.indexOfLast { markHitTest(it, down.position, fit) }
                                if (hitIndex >= 0) {
                                    selectedIndex = hitIndex
                                    onSelectedIndexChange(hitIndex)
                                    val startMark = marks[hitIndex]
                                    val startPos = down.position
                                    down.consume()
                                    var dragged = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.anyConsumed()) break
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val total = change.position - startPos
                                        if (!dragged && total.getDistance() > viewConfiguration.touchSlop) dragged = true
                                        if (dragged) {
                                            change.consume()
                                            marks[hitIndex] = translateMark(startMark, total, fit)
                                        }
                                        if (!change.pressed) break
                                    }
                                    return@awaitEachGesture
                                }

                                // 2) 未命中：清除选中并绘制新元素
                                selectedIndex = -1
                                onSelectedIndexChange(-1)
                                val start = arrowStartOverride ?: down.position
                                if (arrowStartOverride != null && tool == AnnotationTool.ARROW) onArrowOverrideConsumed()
                                draft = createDraft(tool, type, start, fit)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.anyConsumed()) {
                                        // 双指接管（缩放平移）：丢弃半成品草稿
                                        draft = null
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val d = draft ?: break
                                    draft = updateDraft(d, change.position, fit)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                val d = draft
                                draft = null
                                if (d != null && d.isMeaningful()) marks += d
                            }
                        }
                )
            }
        }
    }
}

/** 本轮指针事件是否已被其它手势（如双指缩放）消费。 */
private fun androidx.compose.ui.input.pointer.PointerEvent.anyConsumed(): Boolean =
    changes.any { it.isConsumed }

/** 依据工具创建草稿元素（归一化起点）。 */
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

/** 拖动中的草稿更新：RECT/LINE/ARROW 记差值，STROKE 追加点。 */
internal fun updateDraft(draft: AnnotationMark, pos: Offset, fit: FitRect): AnnotationMark = when (draft.kind) {
    AnnotationMark.KIND_STROKE -> draft.copy(points = draft.points + fit.normalize(pos))
    else -> draft.copy(
        w = fit.normalizeX(pos.x) - draft.x,
        h = fit.normalizeY(pos.y) - draft.y
    )
}

/** 平移元素：整体位移（归一化并夹在画布内）。 */
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

/** 命中测试：矩形用包围盒（含容差）；直线/箭头用点到线段距离；笔迹用点距；文字用保守框。 */
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
 * 画布、账本预览共用本函数。
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
