package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理事件类型。生命周期事件影响活跃集合；收发消息事件只投递内容，不影响活跃集合。
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /**
     * 子代理会话已被 task(action="del") 删除（含全部消息）。
     *
     * 与 STOPPED 的区别：收集方不得再给该会话补送通知或起后续轮次——会话已不存在，
     * 补送只会烧一轮 LLM 并落孤儿消息。
     */
    DELETED,

    /** 主会话向子代理发来一条消息（发送方是父会话）。 */
    MESSAGE_FROM_PARENT,

    /** 子代理向主会话发来一条消息（发送方是子会话）。 */
    MESSAGE_FROM_SUB
}

/**
 * 子代理生命周期事件。
 *
 * @property subSessionId 子代理会话 id。
 * @property parentSessionId 父会话 id（子会话记录里 parentId）。
 * @property type 事件类型。
 * @property detail 附加说明：SPAWNED 为任务指令；COMPLETED/FAILED 为子代理最终输出/错误信息；
 *   MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB 为消息正文。
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = "",
    /** 消息投递追踪 id（MESSAGE_FROM_PARENT/SUB 由发送方生成并登记到投递追踪器）；生命周期事件为空。 */
    val messageId: String = ""
)

/**
 * 子代理事件总线：TaskTool 发出 SPAWNED（子代理已创建），ViewModel 收集后自动
 * 在子会话上启动 AI 工作流；子会话工作流结束时 ViewModel 再发 COMPLETED/FAILED，
 * 父会话据此注入后台通知（类比 terminal 的 notify=true 异步回调）。
 *
 * 同时维护活跃子代理会话 id 集合，供 TaskTool 查询并发上限（最多 5 个运行中）。
 *
 * 活跃集合与预留名额都在 [slotLock] 内更新：同一批工具调用是并行执行的，多个 task(create)
 * 会并发触达这里，「先读计数再创建」不持锁会丢更新（多占或漏占名额）。
 */
@Singleton
class SubAgentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    private val slotLock = Any()

    /** 已通过 [tryReserveSlots] 占住、尚未落到具体会话 id 上的名额数。 */
    @Volatile private var reservedSlots = 0

    /** 当前活跃（运行中）的子代理会话 id 集合。 */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /**
     * 子代理启动时刻（子会话 id → System.currentTimeMillis）。
     *
     * 只记启动时间不记心跳：子代理的 LLM 回合可能静默卡死（流式请求冻结，不报错不结束），
     * 表现为 state 永远 running。有了启动时刻，`task(action="list")` 就能报出「已运行多久」，
     * 主代理据此判断该 stop 重派，而不是无限等一个死掉的子代理。
     * 刻意不做常驻轮询/超时自动杀：卡死与「正常但很慢」在代码层无法区分，误杀会丢掉正在出结果的审查。
     */
    private val startedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 运行中的子代理数量（含已预留未创建的）。 */
    val activeCount: Int get() = synchronized(slotLock) { _activeSubSessionIds.value.size + reservedSlots }

    /** 是否已达并发上限（含已预留未创建的）。 */
    val isFull: Boolean get() = activeCount >= MAX_RUNNING

    /**
     * 为一次派发原子地占住 [count] 个并发名额：检查与占位在同一锁内完成，
     * 并行的 task(create) 不会双双通过检查后超额创建。
     *
     * 占位后随该会话 emit(SPAWNED) 转为正式活跃；创建中途失败用 [releaseSlots] 归还。
     */
    fun tryReserveSlots(count: Int): Boolean {
        if (count <= 0) return true
        synchronized(slotLock) {
            if (_activeSubSessionIds.value.size + reservedSlots + count > MAX_RUNNING) return false
            reservedSlots += count
            return true
        }
    }

    /** 归还 [tryReserveSlots] 占住但未用掉的名额（每个 SPAWNED 自动消耗一个）。 */
    fun releaseSlots(count: Int) {
        if (count <= 0) return
        synchronized(slotLock) {
            reservedSlots = (reservedSlots - count).coerceAtLeast(0)
        }
    }

    /**
     * 直接把某个子代理移出活跃集合，不广播事件；返回它此前是否处于活跃状态。
     *
     * 用户在界面上手动停止或删除运行中的子会话时走这条路径：不能改用 [emit]，
     * 因为 ViewModel 收到 STOPPED 事件后又会回调 stopAgentSession，形成无限循环。
     * 返回值同时用于区分「用户手动终止」与「TaskTool 已处理过」（后者返回 false），
     * 避免向父代理重复投递通知。
     */
    fun release(subSessionId: String): Boolean = synchronized(slotLock) {
        val current = _activeSubSessionIds.value
        if (subSessionId !in current) return false
        _activeSubSessionIds.value = current - subSessionId
        startedAt.remove(subSessionId)
        true
    }

    /** 子代理已运行毫秒数；不在运行返回 null。 */
    fun runningMillis(subSessionId: String, now: Long = System.currentTimeMillis()): Long? =
        startedAt[subSessionId]?.let { now - it }

    fun emit(event: SubAgentEvent) {
        synchronized(slotLock) {
            // 同步维护活跃集合；SPAWNED 同时把一个预留名额转为正式活跃
            when (event.type) {
                SubAgentEventType.SPAWNED -> {
                    _activeSubSessionIds.value = _activeSubSessionIds.value + event.subSessionId
                    startedAt[event.subSessionId] = System.currentTimeMillis()
                    reservedSlots = (reservedSlots - 1).coerceAtLeast(0)
                }
                SubAgentEventType.COMPLETED, SubAgentEventType.FAILED,
                SubAgentEventType.STOPPED, SubAgentEventType.DELETED -> {
                    _activeSubSessionIds.value = _activeSubSessionIds.value - event.subSessionId
                    startedAt.remove(event.subSessionId)
                    // 任务期工具准入随之失效：限制只约束本次派发，子代理后续被追问时回到自己的完整工具集
                    _stepToolLimits.remove(event.subSessionId)
                }
                SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                    // 收发消息不改变运行状态，活跃集合保持不变。
                }
            }
        }
        // 投递结果必须检查：缓冲区满时 tryEmit 返回 false，事件被静默丢弃——
        // SPAWNED 丢掉就是「子代理显示 running 但工作流永不启动」，且无任何报错可查。
        // 丢掉时回滚已占的活跃位，避免子会话永久占坑导致 isFull 到顶、后续派发全被拒。
        if (!_events.tryEmit(event)) {
            FileLogger.w(TAG, "子代理事件投递失败（缓冲区已满）type=${event.type} sub=${event.subSessionId}")
            when (event.type) {
                SubAgentEventType.SPAWNED -> synchronized(slotLock) {
                    _activeSubSessionIds.value = _activeSubSessionIds.value - event.subSessionId
                    startedAt.remove(event.subSessionId)
                }
                else -> Unit
            }
        }
    }

    /**
     * 任务期工具准入（step-owned）：`task(tools=[...])` 声明的本任务允许工具集，按子会话 id 登记。
     *
     * 只存内存不落库：限制属于「本次派发」的约束，App 重启后子代理已不在跑，限制自然失效；
     * 存库反而会让追问时被旧限制卡住。空集合 = 不限（继承子代理定义的工具集）。
     */
    private val _stepToolLimits = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** 登记/覆盖某子会话的任务期工具限制；传空集合表示清除限制。 */
    fun setStepToolLimit(subSessionId: String, tools: Set<String>) {
        if (tools.isEmpty()) _stepToolLimits.remove(subSessionId) else _stepToolLimits[subSessionId] = tools
    }

    /** 取某子会话的任务期工具限制；null 表示未声明限制。 */
    fun stepToolLimit(subSessionId: String): Set<String>? = _stepToolLimits[subSessionId]


    companion object {
        private const val TAG = "SubAgentEventBus"

        /** 同时运行的子代理上限。判定以此为准，工具层只引用不自己再写一份。 */
        const val MAX_RUNNING = 5
    }
}