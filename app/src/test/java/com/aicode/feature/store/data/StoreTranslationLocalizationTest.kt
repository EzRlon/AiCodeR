package com.aicode.feature.store.data

import com.aicode.core.util.containsCjk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专家团本地化纪律：源文已经是中文时绝不能被 en→zh 回译成英文摘要；
 * 存量脏缓存读取时也要按源文纠正回来。
 */
class StoreTranslationLocalizationTest {

    @Test
    fun `中文检测覆盖常用汉字`() {
        assertTrue("设计小组".containsCjk())
        assertTrue("美工设计团队：界面设计师定视觉方向".containsCjk())
        assertFalse("design-squad".containsCjk())
        assertFalse("In this paper, we study the mechanical properties".containsCjk())
    }

    @Test
    fun `中文源文保留源文而不是英文译文`() {
        val dirty = StoreTranslation(
            nameZh = "设计小组",
            descriptionZh = "In this paper, we study the mechanical properties.",
            sourceName = "design-squad"
        )
        val fixed = dirty.localizedAgainst(
            sourceName = "design-squad",
            sourceDescription = "美工设计团队：界面设计师定视觉方向，前端负责落地。"
        )
        assertEquals("美工设计团队：界面设计师定视觉方向，前端负责落地。", fixed.descriptionZh)
        assertEquals("设计小组", fixed.nameZh)
    }

    @Test
    fun `英文源文保留译文`() {
        val translation = StoreTranslation(
            nameZh = "代码审查",
            descriptionZh = "审查代码质量与安全问题。",
            sourceName = "code-review"
        )
        val fixed = translation.localizedAgainst(
            sourceName = "code-review",
            sourceDescription = "Review code quality and security."
        )
        assertEquals("代码审查", fixed.nameZh)
        assertEquals("审查代码质量与安全问题。", fixed.descriptionZh)
    }
}
