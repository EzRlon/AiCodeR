package com.aicode.core.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 全项目统一动画 token：时长档位、标准缓动与 spring 预设的唯一出处。
 *
 * 新动画一律从这里取参数，不再内联魔法数字；存量逐步迁移，不求一次改完。
 * 档位提炼自既有实测参数（FloatingTabBar 380f/0.78f 弹性、SegmentedTabs 临界阻尼、
 * ExpandableChevronIcon/PageMotion 的 200/240ms），行为与迁移前一致。
 */
object Motion {
    /** 快速反馈：按压、小控件颜色渐变。 */
    const val FAST = 120

    /** 常规过渡：展开/收起、箭头旋转。 */
    const val NORMAL = 200

    /** 页面级过渡：整页/侧栏/面板开合，与 [PAGE_MOTION_MS] 同节奏。 */
    const val PAGE = PAGE_MOTION_MS

    /** 标准缓动曲线（全项目统一）。 */
    val Easing: Easing = FastOutSlowInEasing

    /**
     * 轻快弹性：位移/缩放类，轻微过冲（提炼自 FloatingTabBar 实测 0.78f/380f）。
     * 需要更慢更弹的场合（如指示器回弹）把 [stiffness] 调低即可。
     */
    fun <T> bouncy(stiffness: Float = 380f): SpringSpec<T> =
        spring(dampingRatio = 0.78f, stiffness = stiffness)

    /** 临界阻尼：位移到位即停、不过冲（提炼自 SegmentedTabs/AppSwitch）。 */
    fun <T> smooth(stiffness: Float = Spring.StiffnessMediumLow): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = stiffness)
}

/**
 * 统一按压反馈：按下缩到 [pressedScale]、松手弹回。
 *
 * 观察调用方传入的 [interactionSource]（与 clickable/combinedClickable 共用同一 source，
 * 不新增手势层），动画只走 graphicsLayer（draw 层），不触发重组。
 * 用法：`val src = remember { MutableInteractionSource() }`，
 * `Modifier.pressScale(src).clickable(interactionSource = src, ...) { ... }`。
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    label: String = "pressScale"
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = label
    )
    // 只在缩放进行中挂层：常驻 graphicsLayer 的离屏缓存在主题切换后不刷新，
    // 侧栏等调用点会停在旧配色（与输入栏/AIChatPanel 同型修复）。
    if (scale == 1f) Modifier else Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
