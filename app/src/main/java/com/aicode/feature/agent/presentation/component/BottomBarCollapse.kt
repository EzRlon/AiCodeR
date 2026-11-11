package com.aicode.feature.agent.presentation.component

/**
 * 底栏折叠/展开事件。
 *
 * ### 为什么要有这套状态机
 *
 * 底栏除了输入框还叠着待办、用量面板、目标条、排队面板。任务跑起来后它们会把屏幕下半截
 * 占满，用户想读消息就得来回滑。折叠成一条「任务轨道」能把高度收回 44dp，但**自动收起必须
 * 极其克制**——用户正在读流式输出时被收走是最招骂的交互。
 *
 * 因此收起只由两个来源触发：用户点了把手，或任务结束回到空闲。其余全部是**展开**事件，
 * 且按「是否可以无视用户意图」分成两级：
 * - [BLOCKING] / [NEED_INTERVENTION]：不展开用户就没法继续，必须打断用户的手动折叠；
 * - [DELIVERY] / [COMPLETED]：属于「通知类」，用户手动收起过就尊重他，不再弹回来。
 *
 * ### 为什么不做定时收起
 *
 * 「任务跑多久自动收起」与「完成时自动展开」会互相打架，还会把用户正在读的内容抽走。
 * 时间在本功能里只服务展示（轨道上的运行时长），不参与状态迁移。
 */
enum class BottomBarCollapseEvent {
    /** 用户点了把手：收起整条底栏。 */
    USER_COLLAPSED,

    /** 用户点了轨道：展开。 */
    USER_EXPANDED,

    /** 用户发了新消息：意图变了，展开并清掉「本轮手动收起」的粘性。 */
    USER_SENT,

    /** 阻塞型事件：请求授权 / AI 提问 / 计划审批 / 预算审批。 */
    BLOCKING,

    /** 需介入：目标受阻、预算耗尽、回合出错。 */
    NEED_INTERVENTION,

    /** 有交付物（send_file 交付卡片）。 */
    DELIVERY,

    /** 任务全部完成且已空闲。 */
    COMPLETED
}

/**
 * 底栏折叠状态。
 *
 * @param collapsed 当前是否收起成任务轨道。
 * @param manualCollapsedThisTurn 本轮是否由用户手动收起过。[DELIVERY] / [COMPLETED]
 *        这类通知级事件遇到它为 true 时不会展开——「我不看」是明确意图，不该被反复打扰。
 *        新消息、新回合、阻塞事件会清掉它。
 */
data class BottomBarCollapseState(
    val collapsed: Boolean = false,
    val manualCollapsedThisTurn: Boolean = false
) {
    companion object {
        /**
         * 旋转屏幕 / 分屏尺寸变化时要保住折叠态：Activity 重建后如果弹回展开，
         * 正在看轨道的用户会突然被整条底栏糊一脸。
         */
        // listSaver 的返回类型是 Saver<T, Any>（中间态由 Bundle 承载，不保证是 List），
        // 这里必须按实际类型声明，否则会编译失败。
        val Saver: androidx.compose.runtime.saveable.Saver<BottomBarCollapseState, Any> =
            androidx.compose.runtime.saveable.listSaver(
                save = { listOf(it.collapsed, it.manualCollapsedThisTurn) },
                restore = { BottomBarCollapseState(collapsed = it[0], manualCollapsedThisTurn = it[1]) }
            )
    }
}

/** 状态迁移：纯函数，方便单测钉住「谁能让底栏展开」的优先级。 */
fun BottomBarCollapseState.reduce(event: BottomBarCollapseEvent): BottomBarCollapseState =
    when (event) {
        BottomBarCollapseEvent.USER_COLLAPSED ->
            BottomBarCollapseState(collapsed = true, manualCollapsedThisTurn = true)

        BottomBarCollapseEvent.USER_EXPANDED,
        BottomBarCollapseEvent.USER_SENT ->
            BottomBarCollapseState()

        // 阻塞/需介入：必须展开，且清掉粘性——用户下一轮再收起仍然有效。
        BottomBarCollapseEvent.BLOCKING,
        BottomBarCollapseEvent.NEED_INTERVENTION ->
            BottomBarCollapseState()

        // 通知类：用户本轮手动收起过就不打扰。
        BottomBarCollapseEvent.DELIVERY,
        BottomBarCollapseEvent.COMPLETED ->
            if (manualCollapsedThisTurn) this else BottomBarCollapseState()
    }

/**
 * 是否应该渲染任务轨道。
 *
 * 空闲时折叠态没有意义（没有任务可看，且用户需要输入框），因此只有「折叠 + 忙碌」才出轨道。
 */
fun BottomBarCollapseState.showsRail(isBusy: Boolean): Boolean = collapsed && isBusy
