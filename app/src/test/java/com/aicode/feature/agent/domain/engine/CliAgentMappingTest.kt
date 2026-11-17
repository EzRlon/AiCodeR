package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.subagent.AgentDefinition
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** App 子代理 → CLI 引擎 agent 配置的映射（批二）纯逻辑测试。 */
class CliAgentMappingTest {

    private fun agent(name: String, description: String = "desc", prompt: String = "you are $name") =
        AgentDefinition(name = name, description = description, prompt = prompt)

    @Test
    fun isCliSafeName_onlyAcceptsAsciiIdentifiers() {
        assertTrue(CliAgentMapping.isCliSafeName("reviewer"))
        assertTrue(CliAgentMapping.isCliSafeName("Explore"))
        assertTrue(CliAgentMapping.isCliSafeName("code-reviewer_2"))
        assertFalse(CliAgentMapping.isCliSafeName("code reviewer"))
        assertFalse(CliAgentMapping.isCliSafeName("代码审查"))
        assertFalse(CliAgentMapping.isCliSafeName(""))
        assertFalse(CliAgentMapping.isCliSafeName("x".repeat(41)))
    }

    @Test
    fun partition_splitsByReason_onceEach() {
        val result = CliAgentMapping.partition(
            listOf(agent("reviewer"), agent("代码审查"), agent("Build")),
            engineBuiltinNames = setOf("build")
        )

        assertEquals(listOf("reviewer"), result.mapped.map { it.name })
        assertEquals(
            listOf("代码审查" to "名称含非 ASCII 标识符字符", "Build" to "与引擎自带 agent 同名"),
            result.skipped.map { it.agent.name to it.reason }
        )
    }

    @Test
    fun partitionForOpenCode_skipsEngineBuiltinsCaseInsensitively() {
        val result = CliAgentMapping.partitionForOpenCode(
            listOf(agent("reviewer"), agent("Explore"), agent("plan"), agent("general"))
        )

        assertEquals(listOf("reviewer"), result.mapped.map { it.name })
        assertEquals(3, result.skipped.size)
        assertTrue(CliAgentMapping.OPENCODE_BUILTIN_NAMES.contains("explore"))
    }

    @Test
    fun claudeJson_mapsNameDescriptionPrompt() {
        val json = CliAgentMapping.claudeAgentsJson(listOf(agent("reviewer"))).jsonObject

        val reviewer = json.getValue("reviewer").jsonObject
        assertEquals("desc", reviewer.getValue("description").jsonPrimitive.content)
        assertEquals("you are reviewer", reviewer.getValue("prompt").jsonPrimitive.content)
    }

    @Test
    fun claudeJson_fallsBackToNameWhenDescriptionBlank() {
        val json = CliAgentMapping.claudeAgentsJson(listOf(agent("reviewer", description = " "))).jsonObject
        assertEquals("reviewer", json.getValue("reviewer").jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun openCodeJson_usesSubagentMode() {
        val json = CliAgentMapping.openCodeAgentsJson(listOf(agent("reviewer"))).jsonObject
        assertEquals("subagent", json.getValue("reviewer").jsonObject.getValue("mode").jsonPrimitive.content)
    }
}
