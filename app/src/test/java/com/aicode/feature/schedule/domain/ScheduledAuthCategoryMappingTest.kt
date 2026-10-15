package com.aicode.feature.schedule.domain

import com.aicode.feature.agent.domain.tool.ToolCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工具能力 → 预授权类别」判定的单元覆盖（定时 AI 任务运行中的放行规则）。
 * 纯函数，无 Android 依赖；命令删除动作的解析细节由 [ShellCommandParserTest] 独立覆盖。
 */
class ScheduledAuthCategoryMappingTest {

    private fun judge(
        caps: Set<ToolCapability>,
        command: String? = null,
        authorized: Set<ScheduledAuthCategory>
    ) = ScheduledAuthCategoryMapping.judge(caps, command, authorized)

    @Test
    fun readCapability_coveredByRead() {
        val verdict = judge(setOf(ToolCapability.READ_WORKSPACE), null, setOf(ScheduledAuthCategory.READ))
        assertTrue(verdict is AuthCategoryVerdict.Covered)
    }

    @Test
    fun readCapability_notCoveredWhenReadMissing() {
        val verdict = judge(setOf(ToolCapability.READ_WORKSPACE), null, setOf(ScheduledAuthCategory.NETWORK))
        assertTrue(verdict is AuthCategoryVerdict.NotCovered)
    }

    @Test
    fun networkCapability_requiresNetwork() {
        val covered = judge(setOf(ToolCapability.NETWORK_READ), null, setOf(ScheduledAuthCategory.NETWORK))
        assertTrue(covered is AuthCategoryVerdict.Covered)
        val notCovered = judge(setOf(ToolCapability.NETWORK_READ), null, setOf(ScheduledAuthCategory.READ))
        assertTrue(notCovered is AuthCategoryVerdict.NotCovered)
    }

    @Test
    fun multiCapabilities_requireAllCategories() {
        val verdict = judge(
            setOf(ToolCapability.NETWORK_READ, ToolCapability.WRITE_WORKSPACE),
            null,
            setOf(ScheduledAuthCategory.NETWORK)
        )
        assertTrue(verdict is AuthCategoryVerdict.NotCovered)
    }

    @Test
    fun commandWithRm_requiresDeleteInAdditionToExecute() {
        val base = judge(
            setOf(ToolCapability.EXECUTE_COMMANDS),
            "rm app/build/tmp.txt",
            setOf(ScheduledAuthCategory.EXECUTE)
        )
        assertTrue(base is AuthCategoryVerdict.NotCovered)

        val full = judge(
            setOf(ToolCapability.EXECUTE_COMMANDS),
            "rm app/build/tmp.txt",
            setOf(ScheduledAuthCategory.EXECUTE, ScheduledAuthCategory.DELETE)
        )
        assertTrue(full is AuthCategoryVerdict.Covered)
    }

    @Test
    fun commandWithFindDelete_requiresDelete() {
        val verdict = judge(
            setOf(ToolCapability.EXECUTE_COMMANDS),
            "find . -name '*.tmp' -delete",
            setOf(ScheduledAuthCategory.EXECUTE)
        )
        assertTrue(verdict is AuthCategoryVerdict.NotCovered)
    }

    @Test
    fun plainCommand_doesNotRequireDelete() {
        val verdict = judge(
            setOf(ToolCapability.EXECUTE_COMMANDS),
            "ls -la && git status",
            setOf(ScheduledAuthCategory.EXECUTE)
        )
        assertTrue(verdict is AuthCategoryVerdict.Covered)
    }

    @Test
    fun agentConfigModification_neverCovered() {
        val verdict = judge(
            setOf(ToolCapability.MODIFY_AGENT_CONFIG),
            null,
            ScheduledAuthCategory.entries.toSet()
        )
        assertTrue(verdict is AuthCategoryVerdict.NotCovered)
        assertTrue((verdict as AuthCategoryVerdict.NotCovered).reason.contains("agent 配置"))
    }

    @Test
    fun internalCapabilities_notApplicable() {
        val verdict = judge(
            setOf(ToolCapability.MODIFY_TODO_STATE, ToolCapability.USER_INTERACTION),
            null,
            emptySet()
        )
        assertEquals(AuthCategoryVerdict.NotApplicable, verdict)
    }

    @Test
    fun emptyCapabilities_notApplicable() {
        assertEquals(AuthCategoryVerdict.NotApplicable, judge(emptySet(), null, emptySet()))
    }

    @Test
    fun mcpExternalTool_requiresExecute() {
        val notCovered = judge(setOf(ToolCapability.EXTERNAL_TOOL), null, setOf(ScheduledAuthCategory.READ))
        assertTrue(notCovered is AuthCategoryVerdict.NotCovered)
        val covered = judge(setOf(ToolCapability.EXTERNAL_TOOL), null, setOf(ScheduledAuthCategory.EXECUTE))
        assertTrue(covered is AuthCategoryVerdict.Covered)
    }
}
