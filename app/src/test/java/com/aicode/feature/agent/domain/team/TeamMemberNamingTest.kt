package com.aicode.feature.agent.domain.team

import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.store.data.StoreTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TeamMemberNaming] 契约：成员标识 → 用户可读名字，优先级链与兜底都不能出错。
 *
 * 这条链服务于「同一成员在选择面板 / 消息流 / 侧栏 / 成员条显示同一个名字」，一旦某级
 * 误判（如把整段描述当名字、或商店译文认错人），四处会一起错。
 */
class TeamMemberNamingTest {

    private fun def(name: String, description: String) = AgentDefinition(
        name = name,
        description = description,
        prompt = "",
        filePath = "/tmp/$name.md"
    )

    private fun translation(nameZh: String, sourceName: String) =
        StoreTranslation(nameZh = nameZh, descriptionZh = "", sourceName = sourceName)

    @Test
    fun `商店译文优先于内置名表与描述`() {
        val name = TeamMemberNaming.displayName(
            memberId = "backend",
            definition = def("backend", "后端/逻辑实现者：独立完成业务逻辑"),
            translations = mapOf("EXPERT:x" to translation("后端工程师", "backend"))
        )
        assertEquals("后端工程师", name)
    }

    @Test
    fun `内置角色用官方中文名而非描述首段`() {
        // PM / qa 这类缩写标识若走描述首段会得到偏长的句子，内置名表给出更统一的短名。
        assertEquals("产品经理", TeamMemberNaming.displayName(memberId = "PM"))
        assertEquals("质检", TeamMemberNaming.displayName(memberId = "qa"))
        assertEquals("前端", TeamMemberNaming.displayName(memberId = "frontend"))
        assertEquals("后端", TeamMemberNaming.displayName(memberId = "backend"))
    }

    @Test
    fun `内置名表匹配忽略大小写`() {
        assertEquals("质检", TeamMemberNaming.displayName(memberId = "QA"))
        assertEquals("架构师", TeamMemberNaming.displayName(memberId = "Architect"))
    }

    @Test
    fun `非内置成员回落到描述首段`() {
        val name = TeamMemberNaming.displayName(
            memberId = "custom-reviewer",
            definition = def("custom-reviewer", "安全审查员：审查命令拼接与凭据外发")
        )
        assertEquals("安全审查员", name)
    }

    @Test
    fun `冒号能截断出短名`() {
        assertEquals("界面/视觉设计师", TeamMemberNaming.shortName("界面/视觉设计师：产出配色、布局"))
    }

    @Test
    fun `首段超过长度上限时不硬当名字用`() {
        // 14 字硬塞进行首会破坏排版：约定退回标识。
        assertNull(
            TeamMemberNaming.shortName("产品视角的需求梳理与方案定义：把模糊需求整理成清晰的产品定义")
        )
    }

    @Test
    fun `描述过长且非内置时退回标识`() {
        val long = "这是一个非常长而且没有任何分隔符的名字段落需要退回标识处理"
        val name = TeamMemberNaming.displayName(memberId = "custom-x", definition = def("custom-x", long))
        assertEquals("custom-x", name)
    }

    @Test
    fun `取不到定义时用标识兜底`() {
        assertEquals("unknown-agent", TeamMemberNaming.displayName(memberId = "unknown-agent"))
    }

    @Test
    fun `空标识原样返回`() {
        assertEquals("", TeamMemberNaming.displayName(memberId = "  "))
    }

    @Test
    fun `译文匹配忽略大小写`() {
        val name = TeamMemberNaming.displayName(
            memberId = "Custom-Dev",
            translations = mapOf("EXPERT:x" to translation("自定义开发", "custom-dev"))
        )
        assertEquals("自定义开发", name)
    }
}
