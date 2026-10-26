package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 子会话模式的权限边界：定义里写的 mode 只能往更严的方向收，不能放大。
 *
 * 允许放大就等于 PLAN 下派一个 `mode: build` 的子代理即可绕开只读限制去改文件。
 */
class SessionUseCaseModeTest {

    private val useCase = SessionUseCase(
        mockk<ChatSessionDao>(relaxed = true),
        mockk<AgentMessageDao>(relaxed = true),
        mockk<TodoItemDao>(relaxed = true)
    )

    private fun parent(mode: AgentMode) = ChatSessionEntity(
        id = "parent",
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = "/ws",
        mode = mode.name
    )

    @Test
    fun childMode_inheritsParentWhenNotRequested() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "parent",
            parent = parent(AgentMode.PLAN),
            subagentType = "explore"
        )
        assertEquals(AgentMode.PLAN.name, child.mode)
    }

    @Test
    fun childMode_canTightenFromAutoToPlan() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "parent",
            parent = parent(AgentMode.AUTO),
            subagentType = "explore",
            mode = AgentMode.PLAN
        )
        assertEquals(AgentMode.PLAN.name, child.mode)
    }

    @Test
    fun childMode_cannotLoosenPlanToBuild() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "parent",
            parent = parent(AgentMode.PLAN),
            subagentType = "optimizer",
            mode = AgentMode.BUILD
        )
        assertEquals(AgentMode.PLAN.name, child.mode)
    }

    @Test
    fun childMode_cannotLoosenBuildToAuto() {
        val child = useCase.newSubSessionEntity(
            title = "sub",
            parentId = "parent",
            parent = parent(AgentMode.BUILD),
            subagentType = "optimizer",
            mode = AgentMode.AUTO
        )
        assertEquals(AgentMode.BUILD.name, child.mode)
    }

    @Test
    fun resolveChildMode_keepsUnknownParentValue() {
        assertEquals("BROKEN", useCase.resolveChildMode(AgentMode.BUILD, "BROKEN"))
    }
}
