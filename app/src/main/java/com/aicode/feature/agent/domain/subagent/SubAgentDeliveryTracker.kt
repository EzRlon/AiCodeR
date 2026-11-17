package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 编队消息投递追踪（CCCC 思想吸收）：给代理间消息（task send / messageParent）挂
 * 「送达 → 必须回复」状态机，防长任务丢消息、漏汇报。
 *
 * 第一期纯内存态（对齐 AgentNotificationCenter 的 peek/ack 先例）：进程死亡时运行中的
 * 子代理工作流本身也全部终止，重投给已死子代理没有意义——跨进程对账留待有真实需求再建表。
 *
 * 语义：
 * - SENT：发送方已发出（track 登记），限时 [DELIVERED_DEADLINE_MS] 内须 DELIVERED；
 * - DELIVERED：收件方会话已入队/搭车（VM 投递成功后置位）；requireReply 的消息转为
 *   等待回复（限时 [REPLY_DEADLINE_MS]），收件方来话即视为已回复并结清；
 * - 超时：经 [onExpired] 通知宿主（重投一次后仍超时则提醒发起方），不新增常驻轮询——
 *   仅在有未决追踪时挂扫描协程，全部结清即取消。
 */
@Singleton
class SubAgentDeliveryTracker @Inject constructor() {

    data class Tracked(
        val messageId: String,
        val fromSessionId: String,
        val toSessionId: String,
        val requireReply: Boolean,
        /** 完整正文：重投时原样回发（qa P1-3：截断预览会丢正文）。 */
        val content: String,
        var delivered: Boolean,
        var retryCount: Int,
        var deadlineAt: Long
    )

    private val pending = ConcurrentHashMap<String, Tracked>()
    private val sweepJob = AtomicReference<Job?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 超时处理器列表：多 VM（主界面/定时任务）可并存注册，互不覆盖（qa FULL P1-3）。 */
    private val expiredHandlers = java.util.concurrent.CopyOnWriteArrayList<(Tracked) -> Unit>()

    /** 注册超时回调（onCleared 时按引用注销）。 */
    fun addExpiredHandler(handler: (Tracked) -> Unit) {
        expiredHandlers.add(handler)
    }

    /** 注销超时回调（仅移除自己的引用，不影响其它 VM）。 */
    fun removeExpiredHandler(handler: (Tracked) -> Unit) {
        expiredHandlers.remove(handler)
    }

    companion object {
        const val TAG = "SubAgentDeliveryTracker"
        const val SWEEP_INTERVAL_MS = 30_000L
        const val DELIVERED_DEADLINE_MS = 120_000L
        const val REPLY_DEADLINE_MS = 10 * 60_000L
        const val MAX_RETRY = 1
    }

    fun track(messageId: String, fromSessionId: String, toSessionId: String, requireReply: Boolean, content: String) {
        pending[messageId] = Tracked(
            messageId = messageId,
            fromSessionId = fromSessionId,
            toSessionId = toSessionId,
            requireReply = requireReply,
            content = content,
            delivered = false,
            retryCount = 0,
            deadlineAt = System.currentTimeMillis() + DELIVERED_DEADLINE_MS
        )
        ensureSweep()
        FileLogger.d(TAG, "track: id=$messageId to=$toSessionId requireReply=$requireReply")
    }

    /** 重投后刷新时限（qa P1-2：不刷新则重投窗口塌缩到下一个 sweep tick）。 */
    fun extendDeadline(messageId: String, ms: Long) {
        pending[messageId]?.let { it.deadlineAt = System.currentTimeMillis() + ms }
    }

    /** 收件方已入队/搭车：DELIVERED；必须回复的消息转为等待回复（时限放宽）。 */
    fun markDelivered(messageId: String) {
        val t = pending[messageId] ?: return
        if (t.requireReply) {
            t.delivered = true
            t.deadlineAt = System.currentTimeMillis() + REPLY_DEADLINE_MS
        } else {
            pending.remove(messageId)
        }
        FileLogger.d(TAG, "delivered: id=$messageId requireReply=${t.requireReply}")
    }

    /** 目标会话来话即视为对未决消息的回复（已读/已回一并结清）。 */
    fun markRepliedFromTarget(targetSessionId: String) {
        pending.entries.removeAll { it.value.toSessionId == targetSessionId }
    }

    /** 发送方来话（对向回复）同样结清其未决外发消息。 */
    fun markRepliedBySender(senderSessionId: String) {
        pending.entries.removeAll { it.value.fromSessionId == senderSessionId }
    }

    private fun ensureSweep() {
        if (sweepJob.get()?.isActive == true) return
        val job = scope.launch {
            while (true) {
                delay(SWEEP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                pending.entries.removeAll { (_, t) ->
                    if (now >= t.deadlineAt) {
                        t.retryCount++
                        FileLogger.w(TAG, "投递超时: id=${t.messageId} delivered=${t.delivered} retry=${t.retryCount}")
                        expiredHandlers.forEach { runCatching { it(t) } }
                        t.retryCount > MAX_RETRY
                    } else false
                }
                if (pending.isEmpty()) break
            }
        }
        sweepJob.set(job)
    }
}
