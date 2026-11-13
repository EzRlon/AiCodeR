package com.aicode.feature.agent.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部引擎子代理调用归一化：三家的叫法都收敛到内置的 task。 */
class EngineSubagentTest {

    @Test
    fun `三家子代理工具名都归一成 task`() {
        assertEquals("task", EngineSubagent.normalizeToolName("Task"))
        assertEquals("task", EngineSubagent.normalizeToolName("Agent"))
        assertEquals("task", EngineSubagent.normalizeToolName("collab_tool_call"))
        assertEquals("task", EngineSubagent.normalizeToolName("task"))
    }

    @Test
    fun `非子代理工具名原样返回`() {
        assertEquals("bash", EngineSubagent.normalizeToolName("bash"))
        assertEquals("mcp__aicode_bridge__send_file", EngineSubagent.normalizeToolName("mcp__aicode_bridge__send_file"))
        assertFalse(EngineSubagent.isSubagentTool("TodoWrite"))
    }

    @Test
    fun `成员名优先取 agent，其次 subagent_type，再退回 description`() {
        assertEquals(
            "backend-architect",
            EngineSubagent.memberName("""{"agent":"backend-architect","description":"实现后端"}""")
        )
        assertEquals(
            "general-purpose",
            EngineSubagent.memberName("""{"subagent_type":"general-purpose","description":"调研"}""")
        )
        assertEquals("写测试", EngineSubagent.memberName("""{"description":"写测试"}"""))
    }

    @Test
    fun `Codex 的 prompt 可作为成员名，空串视为无`() {
        assertEquals("修复构建", EngineSubagent.memberName("""{"prompt":"修复构建"}"""))
        assertNull(EngineSubagent.memberName("""{"agent":"   "}"""))
    }

    @Test
    fun `非法或空参数不抛异常`() {
        assertNull(EngineSubagent.memberName(null))
        assertNull(EngineSubagent.memberName(""))
        assertNull(EngineSubagent.memberName("not-json"))
        assertNull(EngineSubagent.memberName("[]"))
    }

    @Test
    fun `后台启动结果可识别，普通结果不误判`() {
        assertTrue(EngineSubagent.isAsyncLaunch("""{"isAsync":true,"status":"async_launched"}"""))
        assertTrue(EngineSubagent.isAsyncLaunch("""{"isAsync": true}"""))
        assertFalse(EngineSubagent.isAsyncLaunch("""{"status":"completed","content":"ok"}"""))
        assertFalse(EngineSubagent.isAsyncLaunch(null))
    }
}
