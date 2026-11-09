package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineModeMappingTest {

    @Test
    fun `Claude 三态映射`() {
        assertEquals("manual", EngineModeMapping.claudePermissionMode(AgentMode.BUILD))
        assertEquals("plan", EngineModeMapping.claudePermissionMode(AgentMode.PLAN))
        assertEquals("bypassPermissions", EngineModeMapping.claudePermissionMode(AgentMode.AUTO))
    }

    @Test
    fun `Claude prompts 只在 BUILD 走权限桥`() {
        assertEquals("host", EngineModeMapping.claudePermissionPrompts(AgentMode.BUILD))
        assertEquals("none", EngineModeMapping.claudePermissionPrompts(AgentMode.PLAN))
        assertEquals("none", EngineModeMapping.claudePermissionPrompts(AgentMode.AUTO))
    }

    @Test
    fun `Codex 沙箱三态`() {
        assertEquals("workspace-write", EngineModeMapping.codexSandbox(AgentMode.BUILD))
        assertEquals("read-only", EngineModeMapping.codexSandbox(AgentMode.PLAN))
        assertEquals("danger-full-access", EngineModeMapping.codexSandbox(AgentMode.AUTO))
    }

    @Test
    fun `OpenCode plan 用只读 agent，其余用默认`() {
        assertEquals("plan", EngineModeMapping.openCodeAgent(AgentMode.PLAN))
        assertNull(EngineModeMapping.openCodeAgent(AgentMode.BUILD))
        assertNull(EngineModeMapping.openCodeAgent(AgentMode.AUTO))
    }

    @Test
    fun `OpenCode 只有 AUTO 自动批准`() {
        assertFalse(EngineModeMapping.openCodeAuto(AgentMode.BUILD))
        assertFalse(EngineModeMapping.openCodeAuto(AgentMode.PLAN))
        assertTrue(EngineModeMapping.openCodeAuto(AgentMode.AUTO))
    }

    @Test
    fun `PLAN 在三个引擎都必须是只读档`() {
        assertEquals("plan", EngineModeMapping.claudePermissionMode(AgentMode.PLAN))
        assertEquals("read-only", EngineModeMapping.codexSandbox(AgentMode.PLAN))
        assertEquals("plan", EngineModeMapping.openCodeAgent(AgentMode.PLAN))
    }
}
