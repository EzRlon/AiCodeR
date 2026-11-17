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

    @Test
    fun `成员标识只认代理名，不拿任务描述冒充`() {
        assertEquals("Explore", EngineSubagent.agentName("""{"subagent_type":"Explore","description":"查代码"}"""))
        assertEquals("qa", EngineSubagent.agentName("""{"agent":"qa","prompt":"审一下"}"""))
        assertNull(EngineSubagent.agentName("""{"description":"写测试","prompt":"完整指令"}"""))
    }

    @Test
    fun `子会话标题取描述首行并截断，指令取完整正文`() {
        val args = """{"description":"  修复登录 bug  \n第二行","prompt":"完整指令正文"}"""
        assertEquals("修复登录 bug", EngineSubagent.taskTitle(args))
        assertEquals("完整指令正文", EngineSubagent.taskPrompt(args))
        assertNull(EngineSubagent.taskTitle("""{"prompt":"   "}"""))
        assertEquals(EngineSubagent.TITLE_MAX, EngineSubagent.taskTitle("""{"description":"${"长".repeat(80)}"}""")!!.length)
    }

    @Test
    fun `Codex 控制类协作调用不建子会话，新派发才建`() {
        // 带任务指令、无动作字段 → 视为派发
        assertTrue(EngineSubagent.isSpawnCall("""{"prompt":"实现登录"}"""))
        assertTrue(EngineSubagent.isSpawnCall("""{"tool":"spawn_agent","prompt":"实现登录"}"""))
        // 等待/追加消息/关闭等控制调用不建
        assertFalse(EngineSubagent.isSpawnCall("""{"tool":"wait","prompt":"继续"}"""))
        assertFalse(EngineSubagent.isSpawnCall("""{"tool":"send_input","prompt":"补充"}"""))
        // 没有任务指令也不是派发
        assertFalse(EngineSubagent.isSpawnCall("""{"tool":"spawn_agent"}"""))
    }

    @Test
    fun `会话快照带稳定 id 与兜底成员名`() {
        val sub = newEngineSubagentSession("""{"description":"调研","prompt":"看一下"}""")
        assertEquals(EngineSubagent.DEFAULT_MEMBER, sub.memberName)
        assertEquals("调研", sub.title)
        assertEquals("看一下", sub.prompt)
        assertTrue(sub.subSessionId.isNotBlank())
        assertTrue(sub.subSessionId != newEngineSubagentSession("""{"prompt":"再派一次"}""").subSessionId)
    }
}
