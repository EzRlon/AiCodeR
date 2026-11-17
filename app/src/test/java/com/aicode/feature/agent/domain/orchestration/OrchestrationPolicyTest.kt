package com.aicode.feature.agent.domain.orchestration

import org.junit.Assert.assertTrue
import org.junit.Test

/** 编排守则与专家团段：三引擎共用，关键约束不能因文案调整而丢失。 */
class OrchestrationPolicyTest {

    @Test
    fun `通用守则包含分档 任务板 证据与权限升级`() {
        val text = OrchestrationPolicy.BASE
        assertTrue(text.contains("单文件小改"))
        assertTrue(text.contains("orchestration_board"))
        assertTrue(text.contains("evidence"))
        assertTrue(text.contains("blocked"))
        assertTrue(text.contains("不要绕过"))
        assertTrue(text.contains("独立审查"))
    }

    @Test
    fun `专家团段带上成员 生命周期与门禁`() {
        val text = OrchestrationPolicy.teamSection(
            teamName = "dev-squad",
            teamPrompt = "你是团长。",
            members = listOf("product-manager" to "澄清需求", "qa" to "独立验收"),
            lifecycle = listOf("需求", "实现", "验收"),
            gates = listOf("qa 未通过不得交付")
        )
        assertTrue(text.contains("dev-squad"))
        assertTrue(text.contains("product-manager: 澄清需求"))
        assertTrue(text.contains("1. 需求"))
        assertTrue(text.contains("qa 未通过不得交付"))
    }
}
