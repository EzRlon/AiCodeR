package com.aicode.feature.annotation.presentation.component

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.capture.FullScreenCapture
import com.aicode.core.ui.ComponentHit
import com.aicode.core.ui.ComposeSemanticsHitTester
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationType
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Minus
import compose.icons.feathericons.MousePointer
import compose.icons.feathericons.Square
import compose.icons.feathericons.Trash
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import kotlin.math.roundToInt

/** 活标注当前工具（null 表示操作态：触摸全部穿透到真实界面）。 */
private enum class LiveTool { RECT, LINE, ARROW, STROKE, TEXT, SELECT }

/**
 * 界面标注 v2「活标注」：真实界面之上的透明标注层。
 *
 * 交互（见 live-annotation-plan）：
 * - 操作态（默认）：本层无手势，触摸全部穿透——弹窗照弹、按钮照点（核心诉求）；
 * - 点浮条工具进入绘制态，画完自动回操作态；「选择」态点界面命中真实组件（虚线框住）或已画元素；
 * - 「完成」：隐藏标注层 → 抓一帧整屏（MediaProjection，含弹窗等全部窗口层）→
 *   原始图 + 标注 marks 交给 v1 编辑器精修保存（合成与落账走既有链路）。
 */
@Composable
internal fun LiveAnnotationOverlay(
    onExit: () -> Unit,
    onComplete: (Bitmap, List<AnnotationMark>) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val marks = remember { mutableStateListOf<AnnotationMark>() }
    var tool by remember { mutableStateOf<LiveTool?>(null) }
    var type by remember { mutableStateOf(AnnotationType.BUG) }
    var draft by remember { mutableStateOf<AnnotationMark?>(null) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var componentHit by remember { mutableStateOf<ComponentHit?>(null) }
    /** 组件迷你条【箭头】：箭头起点预置为组件右缘中点（像素）。 */
    var pendingArrowStart by remember { mutableStateOf<Offset?>(null) }
    /** 文字输入：待放置位置（null=未在输入）；或编辑已有文字（[editingTextIndex]>=0）。 */
    var pendingTextPos by remember { mutableStateOf<Offset?>(null) }
    var editingTextIndex by remember { mutableStateOf(-1) }
    var textDraft by remember { mutableStateOf("") }
    var barOffset by remember { mutableStateOf<Offset?>(null) }
    var captureInFlight by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(canvasSize) {
        FitRect(0f, 0f, canvasSize.width.toFloat().coerceAtLeast(1f), canvasSize.height.toFloat().coerceAtLeast(1f))
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val activity = context as? Activity
        if (result.resultCode == Activity.RESULT_OK && data != null && activity != null) {
            FullScreenCapture.captureOnce(context, result.resultCode, data) { bitmap ->
                if (bitmap != null) {
                    onComplete(bitmap, marks.toList())
                } else {
                    captureInFlight = false
                    Toast.makeText(context, context.getString(R.string.annotation_live_capture_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            captureInFlight = false
            Toast.makeText(context, context.getString(R.string.annotation_live_auth_cancelled), Toast.LENGTH_SHORT).show()
        }
    }

    fun startCapture() {
        val intent = FullScreenCapture.createPermissionIntent(context)
        if (intent == null) {
            Toast.makeText(context, context.getString(R.string.annotation_live_auth_failed), Toast.LENGTH_SHORT).show()
            return
        }
        // 先隐藏标注层（含浮条），确保定格图里只有真实界面 + 用户已打开的弹窗
        captureInFlight = true
        captureLauncher.launch(intent)
    }

    fun commitText() {
        val text = textDraft.trim()
        when {
            editingTextIndex >= 0 -> {
                val idx = editingTextIndex
                marks.getOrNull(idx)?.let { marks[idx] = it.copy(text = text) }
            }
            pendingTextPos != null && text.isNotBlank() -> {
                marks += createDraft(AnnotationTool.TEXT, type, pendingTextPos!!, fit).copy(text = text)
            }
        }
        pendingTextPos = null
        editingTextIndex = -1
        textDraft = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
    ) {
        if (captureInFlight) return@Box

        // ── 1) 标注绘制层：无手势修饰 —— 操作态触摸直接穿透到真实界面 ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            marks.forEachIndexed { index, mark ->
                drawMark(
                    mark,
                    fit,
                    fade = false,
                    selected = index == selectedIndex && tool == LiveTool.SELECT,
                    textMeasurer = textMeasurer
                )
            }
            draft?.let { drawMark(it, fit, fade = false, selected = false, textMeasurer = textMeasurer) }
            componentHit?.let { hit ->
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(hit.bounds.left, hit.bounds.top),
                    size = Size(hit.bounds.width, hit.bounds.height),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
                )
            }
        }

        // ── 2) 手势层：仅工具激活时存在（操作态无此层，触摸穿透的关键） ──
        when (tool) {
            LiveTool.RECT, LiveTool.LINE, LiveTool.ARROW, LiveTool.STROKE -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tool) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val start = pendingArrowStart ?: pos
                                draft = createDraft(tool!!.toAnnotationTool(), type, start, fit)
                            },
                            onDrag = { change, _ ->
                                draft = draft?.let { updateDraft(it, change.position, fit) }
                            },
                            onDragEnd = {
                                draft?.let { d -> if (d.isMeaningful()) marks += d }
                                draft = null
                                tool = null
                                pendingArrowStart = null
                            },
                            onDragCancel = { draft = null }
                        )
                    }
            )

            LiveTool.TEXT -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            pendingTextPos = up.position
                            editingTextIndex = -1
                            textDraft = ""
                            tool = null
                            down.consume()
                        }
                    }
            )

            LiveTool.SELECT -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(marks.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // 先判已画元素（可整段拖动）；再判界面组件（虚线高亮）
                            val hitIndex = marks.indexOfLast { markHitTest(it, down.position, fit) }
                            if (hitIndex >= 0) {
                                selectedIndex = hitIndex
                                componentHit = null
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    marks.getOrNull(hitIndex)?.let { marks[hitIndex] = translateMark(it, delta, fit) }
                                    change.consume()
                                }
                            } else {
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                selectedIndex = -1
                                componentHit = (context as? Activity)?.let {
                                    ComposeSemanticsHitTester.hitTest(it, up.position.x, up.position.y)
                                }
                                up.consume()
                            }
                        }
                    }
            )

            null -> Unit
        }

        // ── 3) 浮动工具条（可拖动）──
        val screenWidthPx = canvasSize.width.toFloat()
        val screenHeightPx = canvasSize.height.toFloat()
        LaunchedEffect(canvasSize) {
            if (barOffset == null && canvasSize.width > 0) {
                barOffset = Offset(
                    (screenWidthPx - with(density) { 200.dp.toPx() }).coerceAtLeast(16f),
                    screenHeightPx * 0.32f
                )
            }
        }
        val offset = barOffset ?: Offset(16f, 16f)
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, delta ->
                        barOffset = (barOffset ?: Offset.Zero) + delta
                        change.consume()
                    }
                }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 颜色：点击循环切换四种标注类型色
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.15f),
                        onClick = {
                            val entries = AnnotationType.entries
                            type = entries[(entries.indexOf(type) + 1) % entries.size]
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(annotationColor(type))
                            )
                        }
                    }
                    ToolToggle(tool == LiveTool.RECT, FeatherIcons.Square, stringResource(R.string.annotation_tool_rect)) { tool = LiveTool.RECT }
                    ToolToggle(tool == LiveTool.LINE, FeatherIcons.Minus, stringResource(R.string.annotation_tool_line)) { tool = LiveTool.LINE }
                    ToolToggle(tool == LiveTool.ARROW, FeatherIcons.ArrowUpRight, stringResource(R.string.annotation_tool_arrow)) { tool = LiveTool.ARROW }
                    ToolToggle(tool == LiveTool.STROKE, FeatherIcons.Edit3, stringResource(R.string.annotation_tool_stroke)) { tool = LiveTool.STROKE }
                    ToolToggle(tool == LiveTool.TEXT, FeatherIcons.Type, stringResource(R.string.annotation_tool_text)) { tool = LiveTool.TEXT }
                    ToolToggle(tool == LiveTool.SELECT, FeatherIcons.MousePointer, stringResource(R.string.annotation_live_select)) { tool = LiveTool.SELECT }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolToggle(false, FeatherIcons.Trash, stringResource(R.string.annotation_undo)) {
                        marks.removeLastOrNull()
                        selectedIndex = -1
                    }
                    ToolToggle(false, FeatherIcons.Check, stringResource(R.string.common_done)) { startCapture() }
                    ToolToggle(false, FeatherIcons.X, stringResource(R.string.annotation_live_exit)) { onExit() }
                }
            }
        }

        // ── 4) 选中已画元素的迷你操作条 ──
        val selectedMark = marks.getOrNull(selectedIndex)
        if (selectedMark != null && tool == LiveTool.SELECT) {
            val anchor = fit.toPixel(selectedMark.x, selectedMark.y)
            MiniBar(
                xPx = anchor.x,
                yPx = anchor.y - with(density) { 44.dp.toPx() },
                items = buildList {
                    if (selectedMark.kind == AnnotationMark.KIND_TEXT) {
                        add(MiniAction(FeatherIcons.Edit3, stringResource(R.string.annotation_edit_text)) {
                            editingTextIndex = selectedIndex
                            textDraft = selectedMark.text
                        })
                    }
                    add(MiniAction(FeatherIcons.Trash, stringResource(R.string.annotation_delete_selected)) {
                        marks.getOrNull(selectedIndex)?.let { marks.removeAt(selectedIndex) }
                        selectedIndex = -1
                    })
                    add(MiniAction(FeatherIcons.X, stringResource(R.string.annotation_live_deselect)) {
                        selectedIndex = -1
                    })
                }
            )
        }

        // ── 5) 界面组件命中后的迷你操作条 ──
        componentHit?.let { hit ->
            if (tool == LiveTool.SELECT) {
                MiniBar(
                    xPx = hit.bounds.left,
                    yPx = hit.bounds.top - with(density) { 44.dp.toPx() },
                    items = buildList {
                        add(MiniAction(FeatherIcons.Type, stringResource(R.string.annotation_live_add_text)) {
                            pendingTextPos = Offset((hit.bounds.left + hit.bounds.right) / 2f, hit.bounds.top)
                            textDraft = ""
                            editingTextIndex = -1
                            componentHit = null
                        })
                        add(MiniAction(FeatherIcons.ArrowUpRight, stringResource(R.string.annotation_live_arrow_from)) {
                            // 箭头起点预置为组件右缘中点，用户拖动决定方向与长度
                            pendingArrowStart = Offset(hit.bounds.right, (hit.bounds.top + hit.bounds.bottom) / 2f)
                            componentHit = null
                            tool = LiveTool.ARROW
                        })
                        add(MiniAction(FeatherIcons.X, stringResource(R.string.annotation_live_deselect)) {
                            componentHit = null
                        })
                    }
                )
            }
        }

        // ── 6) 文字输入浮层 ──
        if (pendingTextPos != null || editingTextIndex >= 0) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .imePadding()
                    .padding(horizontal = 32.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.annotation_text_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = textDraft,
                        onValueChange = { textDraft = it },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, onClick = {
                            pendingTextPos = null
                            editingTextIndex = -1
                            textDraft = ""
                        }) {
                            Text(
                                stringResource(R.string.common_cancel),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary, onClick = { commitText() }) {
                            Text(
                                stringResource(R.string.common_confirm),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 迷你操作条的一条动作。 */
private data class MiniAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val onClick: () -> Unit)

/** 迷你操作条：跟随锚点（像素）显示的小胶囊行。 */
@Composable
private fun MiniBar(xPx: Float, yPx: Float, items: List<MiniAction>) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.offset {
            IntOffset(xPx.roundToInt().coerceAtLeast(8), yPx.roundToInt().coerceAtLeast(8))
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { action ->
                ToolToggle(false, action.icon, action.label, action.onClick)
            }
        }
    }
}

private fun LiveTool.toAnnotationTool(): AnnotationTool = when (this) {
    LiveTool.RECT -> AnnotationTool.RECT
    LiveTool.LINE -> AnnotationTool.LINE
    LiveTool.ARROW -> AnnotationTool.ARROW
    LiveTool.STROKE -> AnnotationTool.STROKE
    LiveTool.TEXT -> AnnotationTool.TEXT
    LiveTool.SELECT -> AnnotationTool.RECT
}
