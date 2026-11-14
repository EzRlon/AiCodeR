package com.aicode.feature.agent.domain.provider

/**
 * 思考档位的落库审计：把「用户所选」与「本轮实际发出」摊到一起，供 Token 统计页对照。
 *
 * 背景：我们发给服务端的档位会经过两类改写——`minimal→low` 之类的能力归一，
 * 以及「400 文案学到的白名单」向下钳制（见 [ReasoningLearning]）。改写发生过却没有留痕时，
 * 用户看到「选了 high」而模型表现像 low，只能靠猜；落库这两列后一查便知。
 */
data class ReasoningAudit(
    /** 实际发出的思考参数摘要（apiValue，或 `budget:8192` / `none` 这类等价形态）；不涉及思考时为 null。 */
    val wire: String?,
    /** 发生降级时记录用户原本所选；未降级（或无法比较）为 null。 */
    val clampedFrom: String?
)

/** 可参与「选中 / 实发」比较的 apiValue 集合；`budget:…`、`omitted` 等形态不参与，避免误报降级。 */
private val EFFORT_LEVELS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

/**
 * 由「用户所选档位」与「实际发出的摘要」推出审计结果。
 *
 * [requested] 与 [wire] 均为 apiValue（如 "high"）。仅当 [wire] 本身是一个可比较的档位
 * （命中 [EFFORT_LEVELS]）且与 [requested] 不同时才判定降级；任一侧为 null（未开思考 /
 * 无档位模型）或 [wire] 是预算形态时不判定，避免把「本来就没选」或「预算直传」误报成降级。
 */
fun reasoningAuditOf(requested: String?, wire: String?): ReasoningAudit {
    val clampedFrom = requested
        ?.takeIf { wire != null && wire in EFFORT_LEVELS && it != wire }
    return ReasoningAudit(wire = wire, clampedFrom = clampedFrom)
}
