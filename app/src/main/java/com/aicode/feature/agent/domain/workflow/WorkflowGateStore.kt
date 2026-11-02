package com.aicode.feature.agent.domain.workflow

import com.aicode.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 验收门禁状态（进程内，不落库）：绑了专家团的父会话中，qa 子代理完成时解锁门禁；
 * 实现类子代理完成而门禁未解锁时，在其完成通知里追加验收要求。每子任务最多提醒
 * [MAX_REMINDERS] 次，超限建议转人工验收。
 *
 * 解锁只认 qa：Explore/optimizer 等只读子代理只豁免自身（完成不触发提醒），不产生
 * 解锁信号——否则团队会话开局一次调研就把门禁永久打开，「先调研后实现」流程下门禁失效。
 *
 * 为什么进程内就够：门禁的目的是「让验收要求必然送达父代理」，生命周期是单次托管的
 * 会话级行为，进程重启丢状态等于门禁自然重置，不会产生跨会话的脏数据。
 */
@Singleton
class WorkflowGateStore @Inject constructor() {

    /** 每个父会话一条记录：qa 解锁信号 + 各子任务的提醒计数。 */
    private class ParentState {
        val verifiedByQa = ConcurrentHashMap.newKeySet<String>()
        val reminderCounts = ConcurrentHashMap<String, Int>()
    }

    private val parents = ConcurrentHashMap<String, ParentState>()

    /** qa 子代理正常完成：解锁同父会话的实现类验收要求。 */
    fun markVerifiedByQa(parentSessionId: String, qaSubSessionId: String) {
        parents.getOrPut(parentSessionId) { ParentState() }.verifiedByQa.add(qaSubSessionId)
        FileLogger.d(TAG, "gate verified: parent=$parentSessionId qa=$qaSubSessionId")
    }

    /** 本父会话是否已有 qa 完成过（解锁门禁）。 */
    fun isVerified(parentSessionId: String): Boolean =
        parents[parentSessionId]?.verifiedByQa?.isNotEmpty() == true

    /**
     * 实现类子代理完成且门禁未解锁时，生成一条随完成通知注入的门禁提示；
     * 返回 null 表示不提醒（已解锁 / 已达提醒上限）。
     */
    fun reminderFor(parentSessionId: String, implSubSessionId: String): String? {
        if (isVerified(parentSessionId)) return null
        val state = parents.getOrPut(parentSessionId) { ParentState() }
        // 同 key 读改写非原子（qa 解锁可并发插入），竞争后果仅是多提醒一次，语义可接受；
        // 集合本身线程安全，无结构损坏风险。
        val count = state.reminderCounts.getOrDefault(implSubSessionId, 0)
        if (count >= MAX_REMINDERS) return null
        state.reminderCounts[implSubSessionId] = count + 1
        return if (count + 1 == MAX_REMINDERS) {
            "验收门禁：本会话绑定了专家团，但尚无任何 qa 子代理完成验证。这已是最后提醒，" +
                "建议直接向用户说明该任务未经独立验证即交付，由用户自行决定是否验收。"
        } else {
            "验收门禁：本会话绑定了专家团，但尚无任何 qa 子代理完成验证。对涉及代码改动的交付，" +
                "请用 task(agent=\"qa\") 派发独立验证（给出改动文件与验证标准），qa 完成后门禁自动解除。"
        }
    }

    /** 父会话销毁/交接时清理由此派生的状态（子代理 id 计数随会话生命周期走）。 */
    fun clear(parentSessionId: String) {
        parents.remove(parentSessionId)
    }

    private companion object {
        const val TAG = "WorkflowGateStore"
        const val MAX_REMINDERS = 2
    }
}
