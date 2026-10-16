package com.aicode.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * 整页切换的过渡规范（Material「shared axis X」的同向变体）：两个页面**同向**横移，
 * 前进时一起向左推、返回时一起向右退，配合错开的淡出/淡入。顶层路由（NavHost）与设置页内部分区共用同一份，
 * 免得两处各写一套参数、动画节奏不一致。
 *
 * 为什么是同向小位移而不是 iOS 那种「新页盖住旧页 / 旧页抽走露出下层」：
 * `AnimatedContent` 里**入场内容恒被放在最上层**（同 zIndex 时 target 最后放置），而
 * navigation-compose 的 NavHost 不暴露 `targetContentZIndex`，返回时没法把被弹出的页面盖在上面。
 * 同向平移不依赖 z 序，任何方向都不会出现「该露出的页面被遮住」。
 *
 * 节奏设计（2026-10-15 调优，针对「不够丝滑灵动」的反馈）：
 * - 退场页滑程大于入场页（让位更快），形成轻微「推进去」的层次感，而不是两页同速平移的传送带感；
 * - 退场淡出放宽到比滑动略短、而不是「瞬隐」——旧页随滑动渐隐，消除「唰地消失」的断裂；
 * - 淡入延后一点、稍快：双页同显窗口约 80ms，兼顾连贯与防混色。
 */
const val PAGE_MOTION_MS = 240

private const val PAGE_FADE_IN_MS = 200
private const val PAGE_FADE_IN_DELAY_MS = 60
private const val PAGE_FADE_OUT_MS = 140

/** 入场横移幅度（占容器宽度比例）。 */
private const val PAGE_ENTER_SLIDE_FRACTION = 0.32f

/** 退场横移幅度：明显大于入场，表达「旧页让位更快」。 */
private const val PAGE_EXIT_SLIDE_FRACTION = 0.5f

private val PageSlideSpec = tween<IntOffset>(
    durationMillis = PAGE_MOTION_MS,
    easing = FastOutSlowInEasing
)

/**
 * 入场页过渡：[forward] 为 true（进入下一层）时从右侧滑入，false（返回上一层）时从左侧滑入。
 */
fun pageEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_ENTER_SLIDE_FRACTION).toInt()
        if (forward) offset else -offset
    } + fadeIn(tween(PAGE_FADE_IN_MS, delayMillis = PAGE_FADE_IN_DELAY_MS))

/** 退场页过渡：与 [pageEnter] 同向，前进时向左退出、返回时向右退出。 */
fun pageExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_EXIT_SLIDE_FRACTION).toInt()
        if (forward) -offset else offset
    } + fadeOut(tween(PAGE_FADE_OUT_MS))
