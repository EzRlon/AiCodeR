package com.aicode.feature.settings.presentation

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.script.ScriptRepository
import com.aicode.feature.agent.domain.script.ScriptRunner
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [ScriptsViewModel] 的列表 / 运行 / 新建 / 删除状态流转（引擎用 mock，脚本目录与仓库用真实实现）。
 *
 * 仓库调用跑在真实的 `Dispatchers.IO` 上（生产代码内联 `withContext(Dispatchers.IO)`，项目无调度器注入约定），
 * 故这里用真实主线程 + 轮询等待状态落地，而不是虚拟时间。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptsViewModelTest {

    private lateinit var root: File
    private lateinit var engine: CommandEngine
    private lateinit var modeHolder: ExecutionModeHolder
    private lateinit var repository: ScriptRepository
    private lateinit var workspace: WorkspaceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        root = Files.createTempDirectory("scripts-view-model-test").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        modeHolder = mockk()
        every { modeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT
        repository = ScriptRepository(installer, modeHolder)

        engine = mockk()
        every { engine.notReadyHint() } returns null
        every { engine.defaultShell() } returns "/bin/bash"
        workspace = mockk()
        every { workspace.currentPath() } returns "/root/workspace"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun viewModel(): ScriptsViewModel =
        ScriptsViewModel(repository, ScriptRunner(engine, workspace, repository))

    /** 轮询等待条件成立：仓库读写走真实线程池，不能靠测试调度器的虚拟时间推进。 */
    private fun awaitUntil(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("等待超时：$description")
    }

    @Test
    fun refresh_listsScriptsWithDescriptionAndLastRun() {
        repository.save("cleanup.sh", "#!/bin/bash\n# 清理缓存\necho ok\n")
        repository.recordRun("cleanup.sh", 3)

        val model = viewModel()
        awaitUntil("列表刷新完成") { !model.state.value.loading }

        val state = model.state.value
        assertEquals(1, state.scripts.size)
        assertEquals("cleanup.sh", state.scripts.single().name)
        assertEquals("清理缓存", state.scripts.single().description)
        assertEquals(3, state.scripts.single().lastExitCode)
    }

    @Test
    fun run_keepsOutputAndExitCodeAndRecordsLastRun() {
        repository.save("cleanup.sh", "#!/bin/bash\necho ok\n")
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("all good", 0)

        val model = viewModel()
        awaitUntil("初始列表加载") { !model.state.value.loading }
        model.run("cleanup.sh")
        awaitUntil("运行结果落地") { model.state.value.runs["cleanup.sh"]?.running == false }

        val run = model.state.value.runs.getValue("cleanup.sh")
        assertTrue(run.output.contains("all good"))
        assertEquals(0, run.exitCode)
        coVerify(exactly = 1) {
            engine.runCommandSyncWithExit("/bin/bash \"/root/.aicode/scripts/cleanup.sh\"", "/root/workspace", 120_000L)
        }
        awaitUntil("上次运行写回清单") { model.state.value.scripts.single().lastExitCode == 0 }
    }

    @Test
    fun create_writesTemplateAndRequestsEditorOpen() {
        val model = viewModel()
        awaitUntil("初始列表加载") { !model.state.value.loading }
        model.create("report.py")
        awaitUntil("新建完成") { model.pendingOpen.value != null }

        assertEquals("report.py", model.pendingOpen.value)
        assertEquals("#!/usr/bin/env python3\n\nprint(\"hello from report.py\")\n", repository.read("report.py"))
    }

    @Test
    fun create_withInvalidNameReportsError() {
        val model = viewModel()
        awaitUntil("初始列表加载") { !model.state.value.loading }
        model.create("bad name.sh")
        awaitUntil("错误上报") { model.error.value != null }

        assertNull(model.pendingOpen.value)
        assertEquals(ScriptsError.CREATE_FAILED, model.error.value)
        assertEquals(0, model.state.value.scripts.size)
    }

    @Test
    fun delete_removesScriptAndClearsRunState() {
        repository.save("cleanup.sh", "#!/bin/bash\necho ok\n")
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)

        val model = viewModel()
        awaitUntil("初始列表加载") { !model.state.value.loading }
        model.run("cleanup.sh")
        awaitUntil("运行结果落地") { model.state.value.runs["cleanup.sh"]?.running == false }

        model.delete("cleanup.sh")
        awaitUntil("列表清空") { model.state.value.scripts.isEmpty() }

        assertTrue("runs=${model.state.value.runs}", model.state.value.runs.isEmpty())
        assertNull(repository.read("cleanup.sh"))
    }

    @Test
    fun run_whenScriptVanished_reportsFailureAndClearsRunState() {
        repository.save("cleanup.sh", "#!/bin/bash\necho ok\n")

        val model = viewModel()
        awaitUntil("初始列表加载") { !model.state.value.loading }
        File(repository.dir(), "cleanup.sh").delete()

        model.run("cleanup.sh")
        awaitUntil("失败上报") { model.error.value == ScriptsError.RUN_FAILED }

        assertTrue(model.state.value.runs.isEmpty())
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun refresh_dropsRunStateOfScriptsDeletedOutsideThePage() {
        repository.save("cleanup.sh", "#!/bin/bash\necho ok\n")
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)

        val model = viewModel()
        awaitUntil("初始列表加载") { !model.state.value.loading }
        model.run("cleanup.sh")
        awaitUntil("运行结果落地") { model.state.value.runs["cleanup.sh"]?.running == false }

        // 脚本被 AI 或定时任务从磁盘删掉，页面上的旧输出不该留着
        File(repository.dir(), "cleanup.sh").delete()
        model.refresh()
        awaitUntil("陈旧运行结果被丢弃") { model.state.value.runs.isEmpty() }

        assertTrue(model.state.value.scripts.isEmpty())
    }

    @Test
    fun remoteSshMode_marksPageUnavailableAndRefusesActions() {
        every { modeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH

        val model = viewModel()
        awaitUntil("标记不可用") { model.state.value.unavailableReason != null }

        model.run("cleanup.sh")
        awaitUntil("运行被拒") { model.error.value == ScriptsError.RUN_FAILED }

        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.runs.isEmpty())
        assertNotNull(model.state.value.unavailableReason)
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun remoteSshMode_refusesCreateWithoutWritingToDisk() {
        every { modeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH

        val model = viewModel()
        awaitUntil("标记不可用") { model.state.value.unavailableReason != null }

        model.create("new.sh")
        awaitUntil("新建被拒") { model.error.value == ScriptsError.CREATE_FAILED }

        assertNull(model.pendingOpen.value)
        assertNull(repository.read("new.sh"))
    }
}
