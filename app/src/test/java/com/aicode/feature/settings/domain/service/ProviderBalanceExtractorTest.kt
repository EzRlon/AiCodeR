package com.aicode.feature.settings.domain.service

import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.model.ColumnSetElement
import com.aicode.feature.settings.domain.model.ColumnElement
import com.aicode.feature.settings.domain.model.FactItem
import com.aicode.feature.settings.domain.model.FactSetElement
import com.aicode.feature.settings.domain.model.RowElement
import com.aicode.feature.settings.domain.model.TextBlockElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 余额提取只做「尽力而为」：命中要准，猜不到必须返回 null（回落到估算花费），
 * 绝不能把 token 余量当成余额显示——那比不显示更有害。
 */
class ProviderBalanceExtractorTest {

    private fun text(value: String) = TextBlockElement(text = value)

    @Test
    fun `从收起态摘要行提取余额`() {
        val card = AdaptiveCardRoot(
            compact = RowElement(items = listOf(text("可用余额 \$18.42"), text("(今日 \$0.45)")))
        )

        assertEquals("\$18.42", ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `没有收起态时回落到正文`() {
        val card = AdaptiveCardRoot(
            body = listOf(
                ColumnSetElement(
                    columns = listOf(
                        ColumnElement(items = listOf(text("当前可用余额"), text("\$7.30")))
                    )
                )
            )
        )

        assertEquals("\$7.30", ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `标签后面紧跟别的指标金额时不会误取`() {
        val card = AdaptiveCardRoot(
            body = listOf(text("可用余额"), text("本月累计消费 \$6.58"))
        )

        assertNull("跨元素取值只认整段就是金额的文本", ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `token 余量不会被当成余额`() {
        val card = AdaptiveCardRoot(
            body = listOf(
                FactSetElement(
                    facts = listOf(
                        FactItem("Token 剩余", "12,450,000"),
                        FactItem("速率限制", "500 RPM")
                    )
                )
            )
        )

        assertNull(ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `积分类额度带单位也能识别`() {
        val card = AdaptiveCardRoot(compact = RowElement(items = listOf(text("剩余额度 340 积分"))))

        assertEquals("340 积分", ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `只有金额没有余额标签时不猜`() {
        val card = AdaptiveCardRoot(
            body = listOf(text("≈ ¥132.60 CNY"), text("本月累计消费 \$6.58"))
        )

        assertNull("没有「余额/额度/balance」这类标签时不该把任意金额当余额",
            ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `有标签但没金额时返回 null`() {
        val card = AdaptiveCardRoot(compact = RowElement(items = listOf(text("可用余额"))))

        assertNull(ProviderBalanceExtractor.extract(card))
    }

    @Test
    fun `空卡片返回 null`() {
        assertNull(ProviderBalanceExtractor.extract(AdaptiveCardRoot()))
        assertNull(ProviderBalanceExtractor.extract(null))
    }
}
