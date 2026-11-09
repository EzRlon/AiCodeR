package com.aicode.feature.agent.domain.workflow

/**
 * 会话运行状态的判定规则（纯逻辑，便于单测）。
 *
 * 调用方：`AIAgentViewModel` 的 job 持有判定、队列消费闸门与忙碌判定都已改走本对象，
 * 不再各写一份。改动这里的语义会同时影响那三处，务必对照 `SessionRunStateTest`。
 *
 * 背景（该 bug 已在 AIAgentViewModel 内修复，本对象只是把判定规则提炼出来）：
 * 曾经有一处竞态——同一个会话的 job 被注册两次且键不同，导致「忙碌判定」出现窗口期：
 * 用户在窗口内再发一条消息时，系统认为会话空闲，于是又起了一个 job。两个 job 抢同一会话，
 * 旧 job 收尾时算出「这轮不是我负责」，跳过队列消费，用户的消息就此消失且无任何提示。
 *
 * 把判定规则写成纯函数后，这些约束可以被测试钉住，而不是靠读 4700 行代码去推理。
 */
object SessionRunState {

    /**
     * 一个 job 是否是「当前由本会话持有」的那个。
     *
     * 判定语义是「map 里登记的那个是不是我自己」：只要不是，就说明已有新 job 接管，
     * 旧 job 应当只做不冲突的收尾，把队列交给新 job，不再消费。
     *
     * 用引用相等：新 job 会在启动前先写入 map（为了消除忙碌判定的窗口期），
     * 此时旧 job 尚未收尾。若换成值相等（equals），两个不同的 job 包装成同一个
     * 数据类实例时会被误判为本人——真实场景里用的是 `Job` 接口（未重写 equals，
     * `==` 与 `===` 行为相同），但这里按引用判定把语义钉死，避免以后换成别的类型时踩坑。
     */
    fun isOwnJob(registered: Any?, self: Any?): Boolean =
        self != null && registered === self

    /**
     * 收尾时是否应该消费队列。
     *
     * 两条都必须成立：
     * - 本轮确实是自己负责（否则已由新 job 接管，重复消费会让同一会话并行跑两个回合）；
     * - 队列里有待发请求（否则无事可做）。
     *
     * 另有两个前置守卫由调用方检查（暂停会话、斜杠命令执行期），因为它们依赖运行时状态。
     */
    fun shouldConsumeQueue(
        isOwnJob: Boolean,
        queuedCount: Int,
        paused: Boolean = false,
        commandRunning: Boolean = false
    ): Boolean {
        if (!isOwnJob) return false
        if (paused) return false
        if (commandRunning) return false
        return queuedCount > 0
    }

    /**
     * 启动新回合前，是否需要拒绝并转为排队。
     *
     * 忙碌判定必须同时覆盖三种占用：会话 job、命令 job、会话集合里的空位。
     * 漏掉任意一种，就会出现「同一会话两个执行者」。
     */
    fun isRunning(
        hasActiveSessionJob: Boolean,
        hasActiveCommandJob: Boolean,
        sessionInRunningCommands: Boolean
    ): Boolean = hasActiveSessionJob || hasActiveCommandJob || sessionInRunningCommands
}
