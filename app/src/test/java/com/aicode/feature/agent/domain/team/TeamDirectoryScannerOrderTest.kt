package com.aicode.feature.agent.domain.team

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 专家团选择器顺序：按定义文件修改时间升序，新安装的排在末尾。
 * 用户从商店装完立刻打开选择器时，新条目应出现在最下方，而不是按名称插进中间。
 */
class TeamDirectoryScannerOrderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    private fun writeTeam(dir: File, name: String, mtime: Long) {
        val file = File(dir, "$name.md")
        file.writeText(
            """
            ---
            name: $name
            members: [explore]
            ---
            团长提示词
            """.trimIndent()
        )
        file.setLastModified(mtime)
    }

    @Test
    fun scan_ordersByModifiedTime_newInstallLandsLast() {
        val dir = tempFolder.newFolder("teams")
        // a-squad 名称靠前但安装时间最晚，应排在末尾而非按字母序排第一
        writeTeam(dir, "a-squad", 3_000L)
        writeTeam(dir, "z-squad", 1_000L)
        writeTeam(dir, "m-squad", 2_000L)

        val names = TeamDirectoryScanner.scan(provider, dir.absolutePath).map { it.name }

        assertEquals(listOf("z-squad", "m-squad", "a-squad"), names)
    }

    @Test
    fun scan_sameModifiedTime_fallsBackToName() {
        val dir = tempFolder.newFolder("teams")
        writeTeam(dir, "b-squad", 1_000L)
        writeTeam(dir, "a-squad", 1_000L)

        val names = TeamDirectoryScanner.scan(provider, dir.absolutePath).map { it.name }

        assertEquals(listOf("a-squad", "b-squad"), names)
    }

    @Test
    fun scan_missingDirectory_returnsEmpty() {
        val missing = File(tempFolder.root, "no-such-dir")

        assertEquals(emptyList<TeamDefinition>(), TeamDirectoryScanner.scan(provider, missing.absolutePath))
    }
}
