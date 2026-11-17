package com.aicode.feature.agent.domain.tool.script

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.script.ScriptRepository
import com.aicode.feature.agent.domain.script.ScriptRunner
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [RunScriptTool] 的动作分发、命令拼装与错误码（命令引擎与工作区用 mock，脚本目录用临时目录）。 */
class RunScriptToolTest {

    private lateinit var root: File
    private lateinit var engine: CommandEngine
    private lateinit var modeHolder: ExecutionModeHolder
    private lateinit var repository: ScriptRepository
    private lateinit var tool: RunScriptTool

    private val workspacePath = "/root/workspace"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("run-script-tool-test").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        modeHolder = mockk()
        every { modeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT
        repository = ScriptRepository(installer, modeHolder)

        engine = mockk()
        every { engine.notReadyHint() } returns null
        every { engine.defaultShell() } returns "/bin/bash"
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workspacePath

        val runner = ScriptRunner(engine, workspace, repository)
        tool = RunScriptTool(runner, repository)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = pairs.toMap()

    private fun successText(result: ToolResult): String =
        ((result as ToolResult.Success).data as JsonPrimitive).content

    @Test
    fun listAction_returnsScriptsWithoutExecutingAnything() = runTest {
        repository.save("cleanup.sh", "#!/bin/sh\n# 清理容器缓存\necho ok\n")

        val text = successText(tool.execute(emptyMap()))

        assertTrue(text.contains("cleanup.sh"))
        assertTrue(text.contains("清理容器缓存"))
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun runAction_buildsQuotedContainerPathAndReturnsOutput() = runTest {
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")
        coEvery {
            engine.runCommandSyncWithExit(any(), any(), any())
        } returns CommandResult("all good", 0)

        val result = tool.execute(args("name" to JsonPrimitive("cleanup")))

        coVerify(exactly = 1) {
            engine.runCommandSyncWithExit(
                "/bin/bash \"/root/.aicode/scripts/cleanup.sh\"",
                workspacePath,
                120_000L
            )
        }
        assertTrue(successText(result).contains("all good"))
        assertEquals(0, repository.list().single().lastExitCode)
    }

    @Test
    fun runAction_interpreterFollowsExtension() = runTest {
        repository.save("report.py", "print('ok')\n")
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)

        tool.execute(args("name" to JsonPrimitive("report.py")))

        coVerify(exactly = 1) {
            engine.runCommandSyncWithExit("python3 \"/root/.aicode/scripts/report.py\"", workspacePath, 120_000L)
        }
    }

    @Test
    fun runAction_whenContainerNotReady_returnsGuidanceInsteadOfTimeoutError() = runTest {
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("请先进入终端页面初始化", null)
        every { engine.notReadyHint() } returns "请先进入终端页面初始化"

        val result = tool.execute(args("name" to JsonPrimitive("cleanup.sh")))

        assertTrue(result is ToolResult.Success)
        assertTrue(successText(result).contains("请先进入终端页面初始化"))
    }

    @Test
    fun remoteSshMode_refusesEveryActionWithoutTouchingEngineOrDisk() = runTest {
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")
        every { modeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH

        val run = tool.execute(args("name" to JsonPrimitive("cleanup.sh")))
        assertEquals("UNSUPPORTED_EXECUTION_MODE", (run as ToolResult.Error).code)

        val save = tool.execute(
            args("name" to JsonPrimitive("new.sh"), "content" to JsonPrimitive("echo hi\n"))
        )
        assertEquals("UNSUPPORTED_EXECUTION_MODE", (save as ToolResult.Error).code)

        val list = tool.execute(emptyMap())
        assertEquals("UNSUPPORTED_EXECUTION_MODE", (list as ToolResult.Error).code)

        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
        assertNull(repository.read("new.sh"))
    }

    @Test
    fun runAction_nonZeroExitBecomesErrorWithCode() = runTest {
        repository.save("boom.sh", "#!/bin/sh\nexit 2\n")
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("bad thing", 2)

        val result = tool.execute(args("name" to JsonPrimitive("boom.sh")))

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals("SCRIPT_EXIT_2", error.code)
        assertTrue(error.message.contains("bad thing"))
    }

    @Test
    fun runAction_rejectsTraversalNameWithoutTouchingEngine() = runTest {
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")

        val result = tool.execute(args("name" to JsonPrimitive("../cleanup.sh")))

        assertTrue(result is ToolResult.Error)
        assertEquals("SCRIPT_NOT_FOUND", (result as ToolResult.Error).code)
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun saveAction_writesScriptWithoutRunningIt() = runTest {
        val result = tool.execute(
            args(
                "name" to JsonPrimitive("deploy.sh"),
                "content" to JsonPrimitive("#!/bin/sh\n# 部署\necho deploy\n")
            )
        )

        assertTrue(successText(result).contains("已固化脚本 deploy.sh"))
        assertEquals("#!/bin/sh\n# 部署\necho deploy\n", repository.read("deploy.sh"))
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun saveAction_withoutNameReportsMissingName() = runTest {
        val result = tool.execute(args("content" to JsonPrimitive("echo hi\n")))

        assertEquals("MISSING_NAME", (result as ToolResult.Error).code)
    }

    @Test
    fun saveAction_invalidNameReportsSaveFailure() = runTest {
        val result = tool.execute(
            args("name" to JsonPrimitive("bad name.sh"), "content" to JsonPrimitive("echo hi\n"))
        )

        assertEquals("SAVE_FAILED", (result as ToolResult.Error).code)
    }

    @Test
    fun effectiveCapabilities_followTheAction() {
        assertEquals(
            setOf(ToolCapability.EXECUTE_COMMANDS),
            tool.effectiveCapabilities(args("name" to JsonPrimitive("cleanup.sh")))
        )
        assertEquals(
            setOf(ToolCapability.MODIFY_AGENT_CONFIG),
            tool.effectiveCapabilities(
                args("name" to JsonPrimitive("cleanup.sh"), "content" to JsonPrimitive("echo hi\n"))
            )
        )
        assertEquals(
            setOf(ToolCapability.READ_AGENT_CONFIG),
            tool.effectiveCapabilities(emptyMap())
        )
    }

    @Test
    fun streamingRun_emitsProgressAndCompleted() = runTest {
        repository.save("cleanup.sh", "#!/bin/sh\necho hello\n")
        every {
            engine.runCommandStream(any(), any(), any())
        } returns flowOf(CommandEvent.Line("hello"), CommandEvent.Exit(0))

        val events = tool.executeStream(
            args("name" to JsonPrimitive("cleanup.sh")),
            AgentContext(currentFile = null, selectedCode = null, projectRoot = workspacePath, language = null)
        ).toList()

        assertTrue(events.first() is ToolStreamEvent.Progress)
        val completed = events.last() as ToolStreamEvent.Completed
        assertTrue(successText(completed.result).contains("hello"))
        verify(exactly = 1) { engine.runCommandStream("/bin/bash \"/root/.aicode/scripts/cleanup.sh\"", workspacePath, 120_000L) }
        assertEquals(0, repository.list().single().lastExitCode)
    }

    @Test
    fun streamingRun_whenStreamAborts_keepsShownOutputAndRecordsNothing() = runTest {
        repository.save("cleanup.sh", "#!/bin/bash\necho hello\n")
        every { engine.runCommandStream(any(), any(), any()) } returns flow {
            emit(CommandEvent.Line("hello"))
            throw IllegalStateException("连接中断")
        }

        val events = tool.executeStream(
            args("name" to JsonPrimitive("cleanup.sh")),
            AgentContext(currentFile = null, selectedCode = null, projectRoot = workspacePath, language = null)
        ).toList()

        val completed = events.last() as ToolStreamEvent.Completed
        assertTrue(successText(completed.result).contains("hello"))
        // 中断的运行没有可信退出码，不写「上次运行」
        assertNull(repository.list().single().lastRunAt)
    }
}
