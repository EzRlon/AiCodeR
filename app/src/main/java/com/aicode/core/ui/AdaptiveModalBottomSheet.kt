package com.aicode.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicode.core.theme.Spacing

/**
 * 修复 Material3 ModalBottomSheet 已知 bug（issuetracker 486562294）：
 * 内容接近全屏高度且有可滚动区域时，快速上滑 fling 到内容边界，未消费的向上速度会传给
 * sheet 的拖拽状态，导致 sheet 反复「向下拖动再弹回」振荡。
 *
 * 挂到可滚动内容外层：sheet 已完全展开时吞掉向上方向的剩余 fling 速度，使速度不再流向
 * sheet 的 AnchoredDraggableState；内容自身的惯性滚动不受影响（只在子滚动消费完后拦截剩余量）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetFlingFix(sheetState: SheetState): NestedScrollConnection =
    remember(sheetState) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if ((sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded) && available.y < 0f) {
                    available
                } else {
                    Velocity.Zero
                }
        }
    }

/** 弹层统一圆角：窄屏 sheet 与宽屏 dialog 共用，避免同一弹层在不同屏幕下圆角不一致。 */
private val SHEET_CORNER_RADIUS = 24.dp

/**
 * 自适应模态底栏/弹窗：
 * - 紧凑屏幕（手机竖屏、窄分屏等，< 600dp）：呈现为标准贴底 [ModalBottomSheet]，保留下拉手势与拖拽手柄，
 *   并内置 [rememberSheetFlingFix] 杜绝快速滑动到边界时的抽搐振荡；
 * - 宽屏/平板（>= 600dp）：呈现为规范的居中模态 [Dialog]，移除拖拽手柄，限制最大宽度并居中展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(
        topStart = SHEET_CORNER_RADIUS,
        topEnd = SHEET_CORNER_RADIUS
    ),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    sheetGesturesEnabled: Boolean = true,
    dialogMaxWidth: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isCompactWidth()) {
        val flingFix = rememberSheetFlingFix(sheetState)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            sheetGesturesEnabled = sheetGesturesEnabled
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                content()
            }
        }
    } else {
        // 宽屏 Dialog 本体无入场动画：补淡入 + 0.92→1 缩放（与 AnchorMenuPopup 同节奏），
        // 消除与窄屏 sheet（M3 默认动画）之间的形态不一致。
        val appear = remember { Animatable(0f) }
        // 入场动画结束后必须摘掉图层：常驻 graphicsLayer 会把弹层整块画进离屏图层，
        // 而该缓存在系统深浅色切换后不刷新 —— 表现就是「弹窗还开着时切主题，卡片里的文字
        // 与底色停在旧配色/半透明状态，只有胶囊标题等图层外内容跟着变」。
        // 与设置页三个分区、输入栏、聊天气泡同一类修复（grep「常驻离屏图层」可见）。
        var appearing by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, tween(durationMillis = Motion.NORMAL, easing = Motion.Easing))
            appearing = false
        }
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = modifier
                    .then(
                        if (appearing) {
                            Modifier.graphicsLayer {
                                alpha = appear.value
                                val scale = 0.92f + 0.08f * appear.value
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                        } else {
                            Modifier
                        }
                    )
                    .widthIn(min = 280.dp, max = dialogMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
                shape = RoundedCornerShape(SHEET_CORNER_RADIUS),
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md)
                ) {
                    content()
                }
            }
        }
    }
}
