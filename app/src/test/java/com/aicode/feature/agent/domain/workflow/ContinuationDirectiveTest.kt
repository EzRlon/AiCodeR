package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「继续」语义契约：只有整句就是继续/continue 才追加断点续跑指令；
 * 带具体内容的句子不能误判，否则会把正常需求当成中断续跑。
 */
class ContinuationDirectiveTest {

    @Test
    fun `整句继续命中`() {
        assertTrue(ContinuationDirective.isContinuation("继续"))
        assertTrue(ContinuationDirective.isContinuation(" 继续。 "))
        assertTrue(ContinuationDirective.isContinuation("接着做"))
        assertTrue(ContinuationDirective.isContinuation("Continue"))
        assertTrue(ContinuationDirective.isContinuation("go on"))
    }

    @Test
    fun `带具体内容不命中`() {
        assertFalse(ContinuationDirective.isContinuation("继续修一下登录页"))
        assertFalse(ContinuationDirective.isContinuation("继续，但先跑测试"))
        assertFalse(ContinuationDirective.isContinuation("把刚才的按钮改大一点"))
    }

    @Test
    fun `命中时追加断点续跑要求且保留原文`() {
        val result = ContinuationDirective.applyIfNeeded("继续")
        assertTrue(result.contains("中断续跑指令"))
        assertTrue(result.contains("不要重头开始"))
        assertTrue(result.endsWith("继续"))
    }

    @Test
    fun `未命中时原样返回`() {
        val request = "继续修一下登录页"
        assertTrue(ContinuationDirective.applyIfNeeded(request) == request)
    }
}
