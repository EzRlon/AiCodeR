package com.aicode.feature.annotation.presentation.component

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.capture.FullScreenCapture
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.ComponentHit
import com.aicode.core.ui.ComposeSemanticsHitTester
import com.aicode.core.ui.appTextFieldColors
import com.aicode.feature.annotation.domain.model.AnnotationMark
import com.aicode.feature.annotation.domain.model.AnnotationType
import com.aicode.feature.annotation.presentation.AnnotationViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUpRight
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Minus
import compose.icons.feathericons.MousePointer
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.RotateCw
import compose.icons.feathericons.Square
import compose.icons.feathericons.Trash
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import kotlin.math.roundToInt

/** 浮动工具架位置记忆（SharedPreferences）。 */
private const val BAR_PREF_NAME = "live_annotation_bar"
private const val BAR_PREF_X = "x"
private const val BAR_PREF_Y = "y"

/**
 * 标注中心统一画布（反馈 6）：一块画布 + 一条工具架，活标注与截屏标注在同一界面完成。
 *
 * - 未定格（活标注态）：画布透明叠在真实界面上，工具未激活时触摸穿透（弹窗照弹、按钮照点）；
 *   「选择」工具可点中真实组件（虚线框住）后加文字/画箭头；
 * - 点「定格」：隐藏画布 → MediaProjection 抓一帧整屏（含弹窗）→ 作为底图，已画 marks 保留
 *   （屏幕坐标与图像坐标天然对齐）；底图态支持双指缩放平移；
 * - 「保存」：弹类型 + 说明，写入标注账本（入账后画布保留可继续标注）。
 */
@Composable
internal fun AnnotationCanvasScreen(
    annotationViewModel: AnnotationViewModel,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val marks = remember { mutableStateListOf<AnnotationMark>() }
    val redoStack = remember { mutableStateListOf<AnnotationMark>() }
    var clearedSnapshot by remember { mutableStateOf<List<AnnotationMark>?>(null) }
    var tool by remember { mutableStateOf<AnnotationTool?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(AnnotationType.BUG) }
    var baseImage by remember { mutableStateOf<Bitmap?>(null) }
    var captureInFlight by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var componentHit by remember { mutableStateOf<ComponentHit?>(null) }
    var pendingArrowStart by remember { mutableStateOf<Offset?>(null) }
    var pendingTextPos by remember { mutableStateOf<Offset?>(null) }
    var editingTextIndex by remember { mutableStateOf(-1) }
    var textDraft by remember { mutableStateOf("") }
    var saveSheetOpen by remember { mutableStateOf(false) }
    var saveType by remember { mutableStateOf(AnnotationType.BUG) }
    var saveNote by remember { mutableStateOf("") }
    var barOffset by remember { mutableStateOf<Offset?>(null) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var hostCanvasSize by remember { mutableStateOf(IntSize.Zero) }
    val barPrefs = remember { context.getSharedPreferences(BAR_PREF_NAME, Context.MODE_PRIVATE) }

    // 宿主侧 fit：与画布同尺寸同算法，供选中迷你条 / 组件命中 / 文字落点归一化使用
    val hostFit = remember(hostCanvasSize, baseImage) {
        if (baseImage != null && hostCanvasSize.width > 0 && hostCanvasSize.height > 0) {
            computeFitRect(
                hostCanvasSize.width.toFloat(),
                hostCanvasSize.height.toFloat(),
                baseImage!!.width.toFloat(),
                baseImage!!.height.toFloat()
            )
        } else {
            FitRect(
                0f, 0f,
                hostCanvasSize.width.toFloat().coerceAtLeast(1f),
                hostCanvasSize.height.toFloat().coerceAtLeast(1f)
            )
        }
    }

    fun undo() {
        when {
            marks.isNotEmpty() -> redoStack += marks.removeAt(marks.lastIndex)
            clearedSnapshot != null -> {
                marks.addAll(clearedSnapshot.orEmpty())
                clearedSnapshot = null
            }
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            clearedSnapshot = null
            marks += redoStack.removeAt(redoStack.lastIndex)
        }
    }

    fun clearAll() {
        if (marks.isEmpty()) return
        clearedSnapshot = marks.toList()
        redoStack.clear()
        marks.clear()
        selectedIndex = -1
        componentHit = null
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val activity = context as? Activity
        if (result.resultCode == Activity.RESULT_OK && data != null && activity != null) {
            FullScreenCapture.captureOnce(context, result.resultCode, data) { bitmap ->
                if (bitmap != null) {
                    baseImage = bitmap
                    selectMode = false
                    componentHit = null
                } else {
                    Toast.makeText(context, context.getString(R.string.annotation_live_capture_failed), Toast.LENGTH_SHORT).show()
                }
                captureInFlight = false
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
        // 先隐藏画布层，确保定格图里只有真实界面 + 用户已打开的弹窗
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
                marks += createDraft(AnnotationTool.TEXT, type, pendingTextPos!!, hostFit).copy(text = text)
            }
        }
        pendingTextPos = null
        editingTextIndex = -1
        textDraft = ""
    }

    BackHandler(enabled = !captureInFlight) { onExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { hostCanvasSize = it }
    ) {
        if (captureInFlight) return@Box

        // ── 1) 统一画布：底图（可空）+ 标注层 + 手势 ──
        AnnotationCanvas(
            modifier = Modifier.fillMaxSize(),
            baseImage = baseImage,
            marks = marks,
            tool = if (selectMode) null else tool,
            type = type,
            arrowStartOverride = pendingArrowStart,
            onArrowOverrideConsumed = { pendingArrowStart = null },
            onSelectedIndexChange = { selectedIndex = it },
            onPlaceText = { pos ->
                pendingTextPos = pos
                editingTextIndex = -1
                textDraft = ""
            }
        )

        // ── 1.5) 真实组件命中的双描边高亮（白外黑内，任意亮暗背景均可见）──
        Canvas(modifier = Modifier.fillMaxSize()) {
            componentHit?.let { hit ->
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(hit.bounds.left, hit.bounds.top),
                    size = Size(hit.bounds.width, hit.bounds.height),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
                )
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.75f),
                    topLeft = Offset(hit.bounds.left, hit.bounds.top),
                    size = Size(hit.bounds.width, hit.bounds.height),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
                )
            }
        }

        // ── 2) 占位引导（活标注态且无标注时） ──
        if (baseImage == null && marks.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = stringResource(R.string.annotation_canvas_guide),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }
        }

        // ── 3) 「选择」态手势层：先判已画元素（拖动），再判真实组件（虚线高亮）──
        if (selectMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(marks.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val hitIndex = marks.indexOfLast { markHitTest(it, down.position, hostFit) }
                            if (hitIndex >= 0) {
                                selectedIndex = hitIndex
                                componentHit = null
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    marks.getOrNull(hitIndex)?.let { marks[hitIndex] = translateMark(it, delta, hostFit) }
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
        }

        // ── 4) 浮动工具架（可拖动，带位置记忆）──
        val screenWidthPx = hostCanvasSize.width.toFloat()
        val screenHeightPx = hostCanvasSize.height.toFloat()
        val barWidthForClamp = if (barSize.width > 0) barSize.width.toFloat() else with(density) { 300.dp.toPx() }
        val barHeightForClamp = if (barSize.height > 0) barSize.height.toFloat() else with(density) { 110.dp.toPx() }
        fun clampBar(raw: Offset): Offset {
            val marginPx = with(density) { 8.dp.toPx() }
            val maxX = (screenWidthPx - barWidthForClamp - marginPx).coerceAtLeast(marginPx)
            val maxY = (screenHeightPx - barHeightForClamp - marginPx).coerceAtLeast(marginPx)
            return Offset(raw.x.coerceIn(marginPx, maxX), raw.y.coerceIn(marginPx, maxY))
        }
        LaunchedEffect(hostCanvasSize, barSize) {
            if (barOffset == null && hostCanvasSize.width > 0) {
                val saved = if (barPrefs.contains(BAR_PREF_X) && barPrefs.contains(BAR_PREF_Y)) {
                    Offset(barPrefs.getFloat(BAR_PREF_X, 0f), barPrefs.getFloat(BAR_PREF_Y, 0f))
                } else {
                    null
                }
                val fallback = Offset(
                    (screenWidthPx - barWidthForClamp - with(density) { 16.dp.toPx() }).coerceAtLeast(16f),
                    screenHeightPx * if (screenWidthPx > screenHeightPx) 0.4f else 0.62f
                )
                barOffset = clampBar(saved ?: fallback)
            }
        }
        val offset = barOffset ?: Offset(16f, 16f)
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .onSizeChanged { barSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            barOffset?.let {
                                barPrefs.edit().putFloat(BAR_PREF_X, it.x).putFloat(BAR_PREF_Y, it.y).apply()
                            }
                        }
                    ) { change, delta ->
                        barOffset = clampBar((barOffset ?: Offset.Zero) + delta)
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
                    ToolToggle(tool == AnnotationTool.RECT, FeatherIcons.Square, stringResource(R.string.annotation_tool_rect)) {
                        tool = AnnotationTool.RECT; selectMode = false
                    }
                    ToolToggle(tool == AnnotationTool.LINE, FeatherIcons.Minus, stringResource(R.string.annotation_tool_line)) {
                        tool = AnnotationTool.LINE; selectMode = false
                    }
                    ToolToggle(tool == AnnotationTool.ARROW, FeatherIcons.ArrowUpRight, stringResource(R.string.annotation_tool_arrow)) {
                        tool = AnnotationTool.ARROW; selectMode = false
                    }
                    ToolToggle(tool == AnnotationTool.STROKE, FeatherIcons.Edit3, stringResource(R.string.annotation_tool_stroke)) {
                        tool = AnnotationTool.STROKE; selectMode = false
                    }
                    ToolToggle(tool == AnnotationTool.TEXT, FeatherIcons.Type, stringResource(R.string.annotation_tool_text)) {
                        tool = AnnotationTool.TEXT; selectMode = false
                    }
                    // 选择真实组件只在活标注态有意义（底图态没有"真实界面"可命中）
                    if (baseImage == null) {
                        ToolToggle(selectMode, FeatherIcons.MousePointer, stringResource(R.string.annotation_live_select)) {
                            selectMode = !selectMode; tool = null
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolToggle(false, FeatherIcons.Camera, stringResource(R.string.annotation_canvas_capture)) { startCapture() }
                    ToolToggle(false, FeatherIcons.RotateCcw, stringResource(R.string.annotation_undo)) { undo() }
                    ToolToggle(false, FeatherIcons.RotateCw, stringResource(R.string.annotation_canvas_redo)) { redo() }
                    ToolToggle(false, FeatherIcons.Trash, stringResource(R.string.annotation_canvas_clear)) { clearAll() }
                    ToolToggle(false, FeatherIcons.Check, stringResource(R.string.annotation_canvas_save)) {
                        when {
                            baseImage == null -> Toast.makeText(
                                context,
                                context.getString(R.string.annotation_canvas_need_capture),
                                Toast.LENGTH_SHORT
                            ).show()
                            marks.isEmpty() -> Toast.makeText(
                                context,
                                context.getString(R.string.annotation_ledger_empty),
                                Toast.LENGTH_SHORT
                            ).show()
                            else -> {
                                saveType = type
                                saveNote = ""
                                saveSheetOpen = true
                            }
                        }
                    }
                    ToolToggle(false, FeatherIcons.X, stringResource(R.string.annotation_live_exit)) { onExit() }
                }
            }
        }

        // ── 5) 选中已画元素的迷你操作条 ──
        val selectedMark = marks.getOrNull(selectedIndex)
        if (selectedMark != null && (selectMode || baseImage != null)) {
            val anchor = hostFit.toPixel(selectedMark.x, selectedMark.y)
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
                        marks.getOrNull(selectedIndex)?.let {
                            redoStack += it
                            marks.removeAt(selectedIndex)
                        }
                        selectedIndex = -1
                    })
                    add(MiniAction(FeatherIcons.X, stringResource(R.string.annotation_live_deselect)) {
                        selectedIndex = -1
                    })
                }
            )
        }

        // ── 6) 界面组件命中后的迷你操作条（仅活标注「选择」态）──
        componentHit?.let { hit ->
            if (selectMode) {
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
                            selectMode = false
                            tool = AnnotationTool.ARROW
                        })
                        add(MiniAction(FeatherIcons.X, stringResource(R.string.annotation_live_deselect)) {
                            componentHit = null
                        })
                    }
                )
            }
        }

        // ── 7) 文字输入浮层 ──
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
                    OutlinedTextField(
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

        // ── 8) 保存入账本：类型 + 说明 ──
        if (saveSheetOpen) {
            Surface(
                shape = RoundedCornerShape(topStart = Radius.mdLarge, topEnd = Radius.mdLarge),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        AnnotationType.entries.forEach { entry ->
                            TypeChip(type = entry, selected = saveType == entry, onClick = { saveType = entry })
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTextField(
                            value = saveNote,
                            onValueChange = { saveNote = it },
                            placeholder = stringResource(R.string.annotation_note_hint),
                            singleLine = true,
                            colors = appTextFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        TextButton(
                            onClick = {
                                val bitmap = baseImage ?: return@TextButton
                                annotationViewModel.saveCapture(bitmap, marks.toList(), saveType, saveNote) { ok ->
                                    Toast.makeText(
                                        context,
                                        context.getString(if (ok) R.string.annotation_saved else R.string.annotation_save_failed),
                                        if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                    ).show()
                                    if (ok) {
                                        saveSheetOpen = false
                                        marks.clear()
                                        redoStack.clear()
                                        clearedSnapshot = null
                                        selectedIndex = -1
                                    }
                                }
                            },
                            enabled = marks.isNotEmpty()
                        ) {
                            Text(stringResource(R.string.annotation_canvas_save))
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

@Composable
private fun TypeChip(type: AnnotationType, selected: Boolean, onClick: () -> Unit) {
    val color = annotationColor(type)
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = if (selected) color.copy(alpha = 0.9f) else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Text(
            text = stringResource(type.labelRes()),
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 5.dp)
        )
    }
}
