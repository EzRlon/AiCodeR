package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineModeCatalogTest {

    @Test
    fun `每个引擎都有且只有一个默认档`() {
        listOf(null, AgentEngines.CLAUDE_CODE, AgentEngines.CODEX, AgentEngines.OPENCODE).forEach { engine ->
            assertEquals(1, EngineModeCatalog.entriesFor(engine).count { it.isDefault })
        }
    }

    @Test
    fun `档位清单与各引擎原生取值数量一致`() {
        assertEquals(3, EngineModeCatalog.entriesFor(null).size)
        assertEquals(6, EngineModeCatalog.entriesFor(AgentEngines.CLAUDE_CODE).size)
        assertEquals(3, EngineModeCatalog.entriesFor(AgentEngines.CODEX).size)
        assertEquals(4, EngineModeCatalog.entriesFor(AgentEngines.OPENCODE).size)
    }

    @Test
    fun `切引擎后旧引擎的档位回落默认档，不会越权`() {
        // claude 的 bypassPermissions 落到 codex 上必须归一成默认档 workspace-write
        assertEquals("workspace-write", EngineModeCatalog.normalize(AgentEngines.CODEX, "bypassPermissions"))
        assertEquals(AgentMode.BUILD, EngineModeCatalog.semantic(AgentEngines.CODEX, "bypassPermissions"))
        // 空记录同样回默认档
        assertEquals("manual", EngineModeCatalog.normalize(AgentEngines.CLAUDE_CODE, null))
        assertEquals("build", EngineModeCatalog.normalize(AgentEngines.OPENCODE, null))
    }

    @Test
    fun `claude 六个原生档位的语义投影`() {
        fun sem(id: String) = EngineModeCatalog.semantic(AgentEngines.CLAUDE_CODE, id)
        assertEquals(AgentMode.BUILD, sem("manual"))
        assertEquals(AgentMode.BUILD, sem("acceptEdits"))
        assertEquals(AgentMode.PLAN, sem("plan"))
        assertEquals(AgentMode.AUTO, sem("auto"))
        assertEquals(AgentMode.BUILD, sem("dontAsk"))
        assertEquals(AgentMode.AUTO, sem("bypassPermissions"))
    }

    @Test
    fun `codex 三个原生档位的语义投影`() {
        fun sem(id: String) = EngineModeCatalog.semantic(AgentEngines.CODEX, id)
        assertEquals(AgentMode.PLAN, sem("read-only"))
        assertEquals(AgentMode.BUILD, sem("workspace-write"))
        assertEquals(AgentMode.AUTO, sem("danger-full-access"))
    }

    @Test
    fun `opencode 档位拆成 agent 身份与 auto 开关`() {
        assertFalse(EngineModeCatalog.openCodeAuto("build"))
        assertTrue(EngineModeCatalog.openCodeAuto("build+auto"))
        assertFalse(EngineModeCatalog.openCodeAuto("plan"))
        assertTrue(EngineModeCatalog.openCodeAuto("plan+auto"))

        assertEquals("build", EngineModeCatalog.openCodeAgent("build"))
        assertEquals("build", EngineModeCatalog.openCodeAgent("build+auto"))
        assertEquals("plan", EngineModeCatalog.openCodeAgent("plan"))
        // plan+auto 仍是 plan 身份，不能因为 auto 就降级成 build
        assertEquals("plan", EngineModeCatalog.openCodeAgent("plan+auto"))
        // plan+auto 仍是 plan 只读身份，语义轴不能因 --auto 误判为可写（BUILD 会放松权限门禁）
        assertEquals(AgentMode.PLAN, EngineModeCatalog.semantic(AgentEngines.OPENCODE, "plan+auto"))
    }

    @Test
    fun `每个档位都有语义投影且不含空 id`() {
        listOf(null, AgentEngines.CLAUDE_CODE, AgentEngines.CODEX, AgentEngines.OPENCODE).forEach { engine ->
            EngineModeCatalog.entriesFor(engine).forEach { entry ->
                assertTrue("${engine}:${entry.id}", entry.id.isNotBlank())
                assertEquals(entry.semantic, EngineModeCatalog.semantic(engine, entry.id))
            }
        }
    }
}
