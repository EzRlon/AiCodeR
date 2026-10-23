package com.aicode.feature.agent.domain.script

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [ScriptRepository] 的清单/固化/索引逻辑（文件系统用临时目录，容器安装器用 mock）。 */
class ScriptRepositoryTest {

    private lateinit var root: File
    private lateinit var installer: ContainerInstaller
    private lateinit var modeHolder: ExecutionModeHolder
    private lateinit var repository: ScriptRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("script-repository-test").toFile()
        installer = mockk()
        every { installer.aicodeDir } returns root
        modeHolder = mockk()
        every { modeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT
        repository = ScriptRepository(installer, modeHolder)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeScript(name: String, body: String): File =
        File(root, "scripts").apply { mkdirs() }.let { dir ->
            File(dir, name).apply { writeText(body) }
        }

    @Test
    fun resolveInterpreter_mapsSupportedExtensionsOnly() {
        // .sh 走 bash（容器默认 shell），否则带 bashism 的脚本会在 sh 下失败
        assertEquals("bash", ScriptRepository.resolveInterpreter("cleanup.sh"))
        assertEquals("python3", ScriptRepository.resolveInterpreter("report.PY"))
        assertEquals("node", ScriptRepository.resolveInterpreter("task.js"))
        assertEquals("node", ScriptRepository.resolveInterpreter("task.mjs"))
        assertNull(ScriptRepository.resolveInterpreter("task.ts"))
        assertNull(ScriptRepository.resolveInterpreter("cleanup"))
    }

    @Test
    fun isValidName_rejectsTraversalAndUnsupportedExtensions() {
        assertTrue(ScriptRepository.isValidName("cleanup.sh"))
        assertTrue(ScriptRepository.isValidName("my.tool-2.py"))
        assertFalse(ScriptRepository.isValidName("../evil.sh"))
        assertFalse(ScriptRepository.isValidName("dir/evil.sh"))
        assertFalse(ScriptRepository.isValidName("has space.sh"))
        assertFalse(ScriptRepository.isValidName(".hidden.sh"))
        assertFalse(ScriptRepository.isValidName("task.ts"))
        assertFalse(ScriptRepository.isValidName("cleanup"))
    }

    @Test
    fun findScript_matchesExactNameThenUniqueExtensionLessName() {
        writeScript("cleanup.sh", "#!/bin/sh\necho ok\n")
        writeScript("report.py", "print('ok')\n")

        assertNotNull(ScriptRepository.findScript(repository.dir(), "cleanup.sh"))
        assertEquals("cleanup.sh", ScriptRepository.findScript(repository.dir(), "cleanup")?.name)
        assertNull(ScriptRepository.findScript(repository.dir(), "missing"))
        assertNull(ScriptRepository.findScript(repository.dir(), "../cleanup.sh"))
    }

    @Test
    fun findScript_ambiguousExtensionLessNameIsNotGuessed() {
        writeScript("cleanup.sh", "echo sh\n")
        writeScript("cleanup.py", "print('py')\n")

        assertNull(ScriptRepository.findScript(repository.dir(), "cleanup"))
        assertEquals("cleanup.py", ScriptRepository.findScript(repository.dir(), "cleanup.py")?.name)
    }

    @Test
    fun commentText_skipsShebangCodingDeclarationAndNonComments() {
        assertNull(ScriptRepository.commentText("#!/bin/sh"))
        assertNull(ScriptRepository.commentText("# -*- coding: utf-8 -*-"))
        assertNull(ScriptRepository.commentText("echo hello"))
        assertNull(ScriptRepository.commentText("   "))
        assertEquals("清理容器缓存", ScriptRepository.commentText("# 清理容器缓存"))
        assertEquals("trim me", ScriptRepository.commentText("//   trim me  "))
    }

    @Test
    fun list_skipsHiddenAndUnsupportedFilesAndReadsLeadingComment() {
        writeScript("cleanup.sh", "#!/bin/sh\n# 清理容器缓存\necho ok\n")
        writeScript("notes.txt", "not a script\n")
        File(repository.dir(), ".index.json").writeText("{}")

        val listed = repository.list()

        assertEquals(1, listed.size)
        val info = listed.single()
        assertEquals("cleanup.sh", info.name)
        assertEquals("bash", info.interpreter)
        assertEquals("清理容器缓存", info.description)
        assertNull(info.lastRunAt)
    }

    @Test
    fun save_thenResolveAndRecordRun_roundTripsThroughIndex() {
        val saved = repository.save("cleanup.sh", "#!/bin/sh\n# 清理\necho done\n")

        assertTrue(saved.isFile)
        assertEquals("#!/bin/sh\n# 清理\necho done\n", repository.read("cleanup"))

        repository.recordRun("cleanup.sh", 0)

        val info = repository.list().single()
        assertNotNull(info.lastRunAt)
        assertEquals(0, info.lastExitCode)
    }

    @Test
    fun save_rejectsInvalidNameAndOversizedBody() {
        val invalid = runCatching { repository.save("../evil.sh", "echo x\n") }
        assertTrue(invalid.exceptionOrNull() is IllegalArgumentException)

        val tooBig = runCatching { repository.save("big.sh", "x".repeat(65 * 1024)) }
        assertTrue(tooBig.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun list_ignoresCorruptedIndex() {
        writeScript("cleanup.sh", "#!/bin/sh\necho ok\n")
        File(repository.dir(), ".index.json").writeText("{ not json")

        assertEquals("cleanup.sh", repository.list().single().name)
    }

    @Test
    fun containerPath_isContainerVisibleLocation() {
        assertEquals("/root/.aicode/scripts/cleanup.sh", repository.containerPath("cleanup.sh"))
        assertEquals(ScriptRepository.SCRIPTS_DIR, File(root, ScriptRepository.SCRIPTS_DIR).name)
    }

    @Test
    fun containerPath_rejectsNameThatCouldEscapeDirectory() {
        val result = runCatching { repository.containerPath("../evil.sh") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun save_leavesNoTemporaryFileBehind() {
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")
        repository.save("cleanup.sh", "#!/bin/sh\necho updated\n")

        val leftovers = repository.dir().listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty())
        assertEquals("#!/bin/sh\necho updated\n", repository.read("cleanup.sh"))
    }

    @Test
    fun delete_removesScriptAndItsRunRecord() {
        repository.save("cleanup.sh", "#!/bin/sh\necho ok\n")
        repository.save("keep.sh", "#!/bin/sh\necho keep\n")
        repository.recordRun("cleanup.sh", 0)
        repository.recordRun("keep.sh", 1)

        assertTrue(repository.delete("cleanup"))

        val remaining = repository.list().single()
        assertEquals("keep.sh", remaining.name)
        assertEquals(1, remaining.lastExitCode)
        assertNull(repository.read("cleanup.sh"))
        assertFalse(repository.delete("cleanup.sh"))
        assertFalse(repository.delete("../evil.sh"))
    }

    @Test
    fun unavailableReason_isNullLocallyAndSetOnRemoteSsh() {
        assertNull(repository.unavailableReason())

        every { modeHolder.currentMode() } returns ExecutionMode.REMOTE_SSH
        assertNotNull(ScriptRepository(installer, modeHolder).unavailableReason())
    }
}
