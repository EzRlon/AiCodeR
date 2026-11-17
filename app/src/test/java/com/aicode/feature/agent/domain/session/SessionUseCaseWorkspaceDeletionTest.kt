package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 删除工作区时按 workspacePath 级联清理会话与消息的行为。 */
class SessionUseCaseWorkspaceDeletionTest {

    private fun session(id: String, workspacePath: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = workspacePath,
        parentId = parentId
    )

    @Test
    fun deleteSessionsByWorkspace_deletesMessagesThenSessions() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val todoDao = mockk<TodoItemDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnceIncludingTrashed("/ws/a") } returns listOf(
            session("root", "/ws/a"),
            session("sub", "/ws/a", parentId = "root")
        )

        val useCase = SessionUseCase(chatDao, messageDao, todoDao, mockk(relaxed = true))
        val deleted = useCase.deleteSessionsByWorkspace("/ws/a")

        assertEquals(2, deleted)
        coVerify(exactly = 1) { messageDao.deleteBySession("root") }
        coVerify(exactly = 1) { messageDao.deleteBySession("sub") }
        coVerify(exactly = 1) { chatDao.deleteByWorkspace("/ws/a") }
    }

    @Test
    fun deleteSessionsByWorkspace_noSessions_skipsDeletion() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val todoDao = mockk<TodoItemDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnceIncludingTrashed("/ws/empty") } returns emptyList()

        val useCase = SessionUseCase(chatDao, messageDao, todoDao, mockk(relaxed = true))
        val deleted = useCase.deleteSessionsByWorkspace("/ws/empty")

        assertEquals(0, deleted)
        coVerify(exactly = 0) { messageDao.deleteBySession(any()) }
        coVerify(exactly = 0) { chatDao.deleteByWorkspace(any()) }
    }

    /** 删除会话 = 入回收站：整组打标记，消息与会话行都不删。 */
    @Test
    fun deleteSession_marksWholeGroupDeletedInsteadOfRemovingRows() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val useCase = SessionUseCase(chatDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("root") } returns listOf(
            session("sub", "/ws/a", parentId = "root")
        )
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("sub") } returns emptyList()

        val group = useCase.deleteSession("root")

        assertEquals(listOf("root", "sub"), group)
        coVerify(exactly = 1) { chatDao.markDeleted(listOf("root", "sub"), any()) }
        coVerify(exactly = 0) { chatDao.delete(any()) }
        coVerify(exactly = 0) { chatDao.deleteByIds(any()) }
    }

    /** 从回收站恢复：整组清掉标记。 */
    @Test
    fun restoreSession_clearsDeletedAtForWholeGroup() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val useCase = SessionUseCase(chatDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("root") } returns emptyList()

        useCase.restoreSession("root")

        coVerify(exactly = 1) { chatDao.restoreFromTrash(listOf("root")) }
    }

    /** 彻底删除：消息、待办与会话行一并清掉（检查点由上层清）。 */
    @Test
    fun purgeSession_removesMessagesTodosAndRows() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val todoDao = mockk<TodoItemDao>(relaxed = true)
        val useCase = SessionUseCase(chatDao, messageDao, todoDao, mockk(relaxed = true))
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("root") } returns listOf(
            session("sub", "/ws/a", parentId = "root")
        )
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("sub") } returns emptyList()

        useCase.purgeSession("root")

        coVerify(exactly = 1) { messageDao.deleteBySession("root") }
        coVerify(exactly = 1) { messageDao.deleteBySession("sub") }
        coVerify(exactly = 1) { todoDao.deleteBySession("root") }
        coVerify(exactly = 1) { todoDao.deleteBySession("sub") }
        coVerify(exactly = 1) { chatDao.deleteByIds(listOf("root", "sub")) }
    }

    /** 逾期清理：按阈值取逾期会话并逐组彻底删除。 */
    @Test
    fun purgeExpiredTrash_purgesEveryExpiredGroup() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val todoDao = mockk<TodoItemDao>(relaxed = true)
        val useCase = SessionUseCase(chatDao, messageDao, todoDao, mockk(relaxed = true))
        coEvery { chatDao.getExpiredTrashed(1000L) } returns listOf(
            session("a", "/ws/a"),
            session("b", "/ws/a")
        )
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("a") } returns emptyList()
        coEvery { chatDao.getSubSessionsByParentOnceIncludingTrashed("b") } returns emptyList()

        val purged = useCase.purgeExpiredTrash(1000L)

        assertEquals(listOf("a", "b"), purged)
        coVerify(exactly = 1) { chatDao.deleteByIds(listOf("a")) }
        coVerify(exactly = 1) { chatDao.deleteByIds(listOf("b")) }
    }
}