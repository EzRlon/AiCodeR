package com.aicode.feature.agent.domain.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旁路助手准入：只接管问题/反馈，不接管「继续」与长任务，避免只读子会话误接施工需求。
 */
class ReplySidecarTest {

    @Test
    fun `问题与反馈命中`() {
        assertTrue(ReplySidecar.shouldHandle("这个报错是什么原因？"))
        assertTrue(ReplySidecar.shouldHandle("为什么构建这么慢"))
        assertTrue(ReplySidecar.shouldHandle("能解释一下这里的设计吗"))
        assertTrue(ReplySidecar.shouldHandle("刚才那个改动正常吗"))
    }

    @Test
    fun `继续与长任务不命中`() {
        assertFalse(ReplySidecar.shouldHandle("继续"))
        assertFalse(ReplySidecar.shouldHandle("把登录页改成暗色，并补上单元测试"))
        assertFalse(ReplySidecar.shouldHandle("修一下这个 bug".repeat(100)))
        // 带问号的施工需求仍不能被只读旁路接走（任务动词可能出现在句中）
        assertFalse(ReplySidecar.shouldHandle("帮我修一下登录页，为什么点不动？"))
        assertFalse(ReplySidecar.shouldHandle("实现这个接口，为什么构建一直失败？"))
        assertFalse(ReplySidecar.shouldHandle("能不能把这个按钮改成红色？"))
        assertFalse(ReplySidecar.shouldHandle("这行为什么不对，帮我修一下"))
        assertFalse(ReplySidecar.shouldHandle("看一下登录页为什么报错，顺便修了"))
    }

    @Test
    fun `提示词包含只读约束与用户原文`() {
        val prompt = ReplySidecar.prompt("为什么构建失败？")
        assertTrue(prompt.contains("只读") || prompt.contains("不要修改任何文件"))
        assertTrue(prompt.contains("为什么构建失败？"))
    }
}
