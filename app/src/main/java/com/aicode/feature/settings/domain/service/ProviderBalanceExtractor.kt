package com.aicode.feature.settings.domain.service

import com.aicode.feature.settings.domain.model.AdaptiveCardElement
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.model.BadgeElement
import com.aicode.feature.settings.domain.model.ColumnElement
import com.aicode.feature.settings.domain.model.ColumnSetElement
import com.aicode.feature.settings.domain.model.ContainerElement
import com.aicode.feature.settings.domain.model.FactSetElement
import com.aicode.feature.settings.domain.model.FlowRowElement
import com.aicode.feature.settings.domain.model.MetricElement
import com.aicode.feature.settings.domain.model.RowElement
import com.aicode.feature.settings.domain.model.ScrollRowElement
import com.aicode.feature.settings.domain.model.TextBlockElement

/**
 * 从供应商自定义面板（Adaptive Card）里**尽力**抠出「余额」这一项，供顶栏状态条展示。
 *
 * ### 为什么是"尽力"
 *
 * 余额没有统一协议：官方 OpenAI / Anthropic 根本没有余额接口，中转站、积分平台、AI 网关
 * 各写各的。App 唯一能拿到的余额，就是用户给该渠道配的面板脚本输出的卡片文案。因此这里
 * 只能按文案语义做匹配——**宁可返回 null（状态条回落成"估算花费"），也不能瞎猜一个数字**：
 * 把 token 余量当成余额显示，比不显示更糟。
 *
 * 匹配规则（按优先级）：
 * 1. 先看 `compact`（收起态那一行，通常就是「可用余额 $18.42」），再看 `body`；
 * 2. 文案里必须出现余额类标签（余额 / 额度 / balance / credit / quota）；
 * 3. 金额必须带货币符号，或带「积分 / 次 / credits」这类单位；
 * 4. **含 token 字样的文案一律跳过**——`FactSet` 里的「Token 剩余 12,450,000」是最常见
 *    的误命中来源。
 */
object ProviderBalanceExtractor {

    /** 余额类标签。故意不含「剩余」二字：单独的「剩余」在额度卡片里大多指 token。 */
    private val BALANCE_LABEL = Regex(
        "(余额|可用额度|额度|balance|credits?|quota|remaining)",
        RegexOption.IGNORE_CASE
    )

    /** 带货币符号的金额，或带「积分/点/次/credits」单位的数量。 */
    private val AMOUNT_WITH_CURRENCY = Regex("""[¥￥$€£]\s?\d[\d,]*(?:\.\d+)?""")
    private val AMOUNT_WITH_UNIT = Regex("""\d[\d,]*(?:\.\d+)?\s?(积分|分|点|次|credits?|calls?)""",
        RegexOption.IGNORE_CASE)

    /** token 相关文案：命中即跳过，避免把「Token 剩余」当成余额。 */
    private val TOKEN_HINT = Regex("token", RegexOption.IGNORE_CASE)

    /** 「整段就是一个金额」——用于跨元素取值时，避免把「本月累计消费 $6.58」当成余额。 */
    private val PURE_AMOUNT = Regex(
        """^[¥￥$€£]?\s*\d[\d,]*(?:\.\d+)?\s*(积分|分|点|次|credits?|cny|usd|rmb)?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 提取余额展示串（如 `$18.42`、`340 积分`）；拿不到返回 null。
     */
    fun extract(card: AdaptiveCardRoot?): String? {
        if (card == null) return null

        // 收起态优先：它本来就是给「一眼看余额」设计的摘要行。
        card.compact?.let { compact ->
            val texts = mutableListOf<String>()
            collect(compact, texts)
            extractFrom(texts)?.let { return it }
        }

        val texts = mutableListOf<String>()
        card.body.forEach { collect(it, texts) }
        return extractFrom(texts)
    }

    private fun extractFrom(texts: List<String>): String? {
        for ((index, text) in texts.withIndex()) {
            if (!BALANCE_LABEL.containsMatchIn(text)) continue
            if (TOKEN_HINT.containsMatchIn(text)) continue
            amountIn(text)?.let { return it }

            // 大号余额常被拆成「标签一行、数字一行」（官方示例脚本的 body 就是这种排版），
            // 允许看紧邻的下一段。只多看一段而不是整块扫描：再远就可能串到别的指标上
            // （如「本月累计消费」的金额），那比漏判更糟。
            val next = texts.getOrNull(index + 1) ?: continue
            if (TOKEN_HINT.containsMatchIn(next)) continue
            // 跨元素只认「整段就是一个金额」的文本，否则会把别的指标的金额串过来。
            next.trim().takeIf { PURE_AMOUNT.matches(it) }?.let { return it }
        }
        return null
    }

    /** 从单段文本里取金额：优先带货币符号的，其次带「积分/次/credits」单位的。 */
    private fun amountIn(text: String): String? =
        AMOUNT_WITH_CURRENCY.find(text)?.value?.trim()
            ?: AMOUNT_WITH_UNIT.find(text)?.value?.trim()

    /** 深度优先收集卡片里所有可读文案。 */
    private fun collect(element: AdaptiveCardElement, out: MutableList<String>) {
        when (element) {
            is TextBlockElement -> out += element.text
            is BadgeElement -> out += element.text
            is MetricElement -> {
                out += element.label
                out += element.value
                element.unit.takeIf { it.isNotBlank() }?.let { out += it }
                element.subText.takeIf { it.isNotBlank() }?.let { out += it }
            }
            is FactSetElement -> element.facts.forEach {
                out += "${it.title} ${it.value}"
            }
            is ContainerElement -> element.items.forEach { collect(it, out) }
            is ColumnSetElement -> element.columns.forEach { collect(it, out) }
            is ColumnElement -> element.items.forEach { collect(it, out) }
            is RowElement -> element.items.forEach { collect(it, out) }
            is FlowRowElement -> element.items.forEach { collect(it, out) }
            is ScrollRowElement -> element.items.forEach { collect(it, out) }
            else -> Unit
        }
    }
}
