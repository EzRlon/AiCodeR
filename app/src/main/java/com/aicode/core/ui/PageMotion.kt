package com.aicode.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection

/**
 * 整页切换的过渡规范（进入子页有纵深、返回严格镜像）。
 *
 * 顶层路由（NavHost）与设置页分区切换共用同一份，免得各处各写参数、节奏不一致。
 *
 * 为什么是同向小位移而不是 iOS 那种「新页盖住旧页 / 旧页抽走露出下层」：
 * `AnimatedContent` 里入场内容恒被放在最上层（同 zIndex 时 target 最后放置），而
 * navigation-compose 的 NavHost 不暴露 `targetContentZIndex`，返回时没法把被弹出的页面盖在上面。
 * 同向平移不依赖 z 序，任何方向都不会出现「该露出的页面被遮住」。
 *
 * 灵动感的来源是**曲线而不是时长**：位移走 spring（有加减速、末段轻微收束），
 * 只有透明度用 tween（弹簧做透明度会出现闪烁）。两页的 spring 刚度不同，
 * 退场页更软、起步慢半拍，形成「新页抢在前面、旧页随后让开」的层次。
 */
const val PAGE_MOTION_MS = 340

/** 入场位移：偏硬、起步快，新页立刻到位。 */
private val PageEnterSlideSpec = spring<IntOffset>(
    dampingRatio = 0.86f,
    stiffness = 420f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

/** 退场位移：偏软，比入场慢，旧页「跟在后面」退开。 */
private val PageExitSlideSpec = spring<IntOffset>(
    dampingRatio = 0.9f,
    stiffness = 300f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

/** 缩放：入场从略小推近，退场缩得更小，双向纵深。 */
private val PageEnterScaleSpec = spring<Float>(dampingRatio = 0.85f, stiffness = 400f)
private val PageExitScaleSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 320f)

/** 透明度只用 tween：spring 做 alpha 会在末段抖一下。 */
private const val PAGE_FADE_IN_MS = 240
private const val PAGE_FADE_IN_DELAY_MS = 60
private const val PAGE_FADE_OUT_MS = 200

/** 入场横移幅度（占容器宽度比例）。低于 0.25 肉眼几乎看不出位移。 */
private const val PAGE_ENTER_SLIDE_FRACTION = 0.35f

/** 退场横移幅度：大于入场，表达「旧页退得更远」。 */
private const val PAGE_EXIT_SLIDE_FRACTION = 0.55f

private const val PAGE_ENTER_SCALE = 0.94f
private const val PAGE_EXIT_SCALE = 0.90f

/** 当前布局方向是否为 RTL；转场据此镜像，保证「进入」永远从后缘来。 */
@Composable
fun isRtlLayout(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

/**
 * 系统「动画时长」明确关闭时返回 true（此时不做转场）。
 * 取不到就当作「未关闭」：宁可播动画，也不要因为一次反射失败让整页切换变成瞬切。
 */
@Composable
fun isAnimationDisabled(): Boolean = remember {
    runCatching {
        val cls = Class.forName("android.animation.ValueAnimator")
        val m = cls.getMethod("getDurationScale")
        (m.invoke(null) as Float) == 0f
    }.getOrDefault(false)
}

/**
 * 入场页过渡：[forward] 为 true（进入下一层）时从右侧滑入，false（返回上一层）时从左侧滑入。
 * [rtl] 为真时左右互换。[animate] 为 false 时直接切页（系统关闭动画）。
 */
fun pageEnter(forward: Boolean, rtl: Boolean = false, animate: Boolean = true): EnterTransition =
    if (!animate) EnterTransition.None else {
        val sign = if (forward != rtl) 1 else -1
        slideInHorizontally(PageEnterSlideSpec) { width -> sign * (width * PAGE_ENTER_SLIDE_FRACTION).toInt() } +
            fadeIn(tween(PAGE_FADE_IN_MS, delayMillis = PAGE_FADE_IN_DELAY_MS)) +
            scaleIn(initialScale = PAGE_ENTER_SCALE, animationSpec = PageEnterScaleSpec)
    }

/** 退场页过渡：与 [pageEnter] 同向（前进向左退、返回向右退），并缩小形成纵深。 */
fun pageExit(forward: Boolean, rtl: Boolean = false, animate: Boolean = true): ExitTransition =
    if (!animate) ExitTransition.None else {
        val sign = if (forward != rtl) 1 else -1
        slideOutHorizontally(PageExitSlideSpec) { width -> -sign * (width * PAGE_EXIT_SLIDE_FRACTION).toInt() } +
            fadeOut(tween(PAGE_FADE_OUT_MS, easing = FastOutLinearInEasing)) +
            scaleOut(targetScale = PAGE_EXIT_SCALE, animationSpec = PageExitScaleSpec)
    }

/**
 * Tab 切换（同级内容切换）：淡入淡出为主，位移只占 [TAB_SLIDE_FRACTION]，不整页横滑。
 * 同级切换没有纵深关系，大位移会误读成「进了一层」。
 */
const val TAB_MOTION_MS = 260

private const val TAB_SLIDE_FRACTION = 1f / 12f

private val TabSlideSpec = spring<IntOffset>(
    dampingRatio = 0.88f,
    stiffness = 380f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

fun tabEnter(forward: Boolean, rtl: Boolean = false, animate: Boolean = true): EnterTransition =
    if (!animate) EnterTransition.None else {
        val sign = if (forward != rtl) 1 else -1
        slideInHorizontally(TabSlideSpec) { width -> sign * (width * TAB_SLIDE_FRACTION).toInt() } +
            fadeIn(tween(200, easing = LinearOutSlowInEasing))
    }

fun tabExit(forward: Boolean, rtl: Boolean = false, animate: Boolean = true): ExitTransition =
    if (!animate) ExitTransition.None else {
        val sign = if (forward != rtl) 1 else -1
        slideOutHorizontally(TabSlideSpec) { width -> -sign * (width * TAB_SLIDE_FRACTION).toInt() } +
            fadeOut(tween(180, easing = FastOutLinearInEasing))
    }
