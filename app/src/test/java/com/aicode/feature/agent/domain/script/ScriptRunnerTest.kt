package com.aicode.feature.agent.domain.script

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** [ScriptRunner] 的脚本解析与命令拼装（引擎用 mock，脚本目录用临时目录）。 */
class ScriptRunnerTest {

    private lateinit var root: File
    private lateinit var engine: CommandEngine
    private lateinit var repository: ScriptRepository
    private lateinit var runner: ScriptRunner

    @Before
    fun setUp() {
        root = Files.createTempDirectory("script-runner-test").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        val modeHolder = mockk<ExecutionModeHolder>()
        every { modeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT
        repository = ScriptRepository(installer, modeHolder)

        engine = mockk()
        every { engine.notReadyHint() } returns null
        every { engine.defaultShell() } returns "/bin/bash"
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns "/root/workspace"

        runner = ScriptRunner(engine, workspace, repository)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun target_buildsQuotedCommandAndCompletesExtensionLessName() {
        repository.save("cleanup.sh", "#!/bin/bash\necho ok\n")

        val target = runner.target("cleanup")

        assertEquals("cleanup.sh", target?.scriptName)
        assertEquals("/bin/bash \"/root/.aicode/scripts/cleanup.sh\"", target?.command)
    }

    @Test
    fun target_usesEngineDefaultShellForShellScripts() {
        // 未 provision 时容器里还没有 bash，用 '/bin/sh' 才不会拿到 127
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")
        every { engine.defaultShell() } returns "/bin/sh"

        assertEquals("/bin/sh \"/root/.aicode/scripts/cleanup.sh\"", runner.target("cleanup.sh")?.command)
    }

    @Test
    fun target_returnsNullForMissingUnsupportedOrEscapingName() {
        // 不支持的后缀进不了 save()（名称校验会拒），直接落盘模拟目录里已有的非脚本文件
        File(repository.dir(), "report.ts").writeText("const x = 1\n")

        assertNull(runner.target("missing"))
        assertNull(runner.target("../evil.sh"))
        assertNull(runner.target("report.ts"))
    }
}
