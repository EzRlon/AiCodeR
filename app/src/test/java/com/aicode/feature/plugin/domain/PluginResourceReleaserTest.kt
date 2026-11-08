package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.hooks.domain.HookDefinition
import com.aicode.feature.hooks.domain.HooksConfig
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import com.aicode.feature.agent.domain.container.ContainerInitState

/**
 * 装配 / 摘除的行为边界。
 *
 * 重点不是「能复制文件」，而是三条不能错的事：
 * 1. 撞名不覆盖（用户已有资源不能被插件静默改掉）；
 * 2. 账本记得全（卸载才能干净）；
 * 3. 摘除只删自己装的（不能按目录整片清）。
 */
class PluginResourceReleaserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = InMemoryFileAccess()
    private val host = FakeHostConfig()
    private val engine = FakeCommandEngine()
    private val store: PluginStateStore by lazy { PluginStateStore(tmp.root) }

    private fun releaser() = PluginResourceReleaser(fs, host, engine, store)

    private fun plugin(
        id: String = "com.demo.hello",
        capabilities: PluginCapabilities = PluginCapabilities(),
        slots: List<PluginSlotSpec> = emptyList()
    ): InstalledPlugin {
        val dir = tmp.newFolder(id)
        return InstalledPlugin(
            manifest = PluginManifest(
                id = id,
                name = "Demo",
                version = "1.0.0",
                capabilities = capabilities,
                slots = slots
            ),
            dir = dir,
            state = store.stateOf(id) ?: com.aicode.feature.plugin.data.PluginState()
        )
    }

    @Test
    fun `技能目录被复制到既有目录并记账`() = runBlocking {
        val p = plugin(capabilities = PluginCapabilities(skills = listOf("skills")))
        File(p.dir, "skills/alpha").mkdirs()
        File(p.dir, "skills/alpha/SKILL.md").writeText("# alpha")

        val outcome = releaser().attach(p)

        assertEquals(listOf("~/.aicode/skills/alpha"), outcome.ownedFiles)
        assertEquals("# alpha", fs.readFile("~/.aicode/skills/alpha/SKILL.md"))
        assertEquals(outcome.ownedFiles, store.stateOf(p.id)?.ownedFiles)
    }

    @Test
    fun `目标已存在同名内容时跳过且不覆盖`() = runBlocking {
        fs.mkdirs("~/.aicode/skills/alpha")
        fs.writeFile("~/.aicode/skills/alpha/SKILL.md", "用户自己的")
        val p = plugin(capabilities = PluginCapabilities(skills = listOf("skills")))
        File(p.dir, "skills/alpha").mkdirs()
        File(p.dir, "skills/alpha/SKILL.md").writeText("# 插件的")

        val outcome = releaser().attach(p)

        assertEquals("用户自己的", fs.readFile("~/.aicode/skills/alpha/SKILL.md"))
        assertEquals(
            listOf(ReleaseConflict(ConflictKind.TARGET_EXISTS, "~/.aicode/skills/alpha")),
            outcome.conflicts
        )
        assertTrue(outcome.ownedFiles.isEmpty())
    }

    @Test
    fun `MCP 追加到全局表，同名跳过`() = runBlocking {
        host.servers = mutableListOf(McpServerConfig(name = "user-mcp", command = "x"))
        val p = plugin(
            capabilities = PluginCapabilities(
                mcp = listOf(
                    McpSpec(name = "demo-mcp", command = "npx", args = listOf("demo")),
                    McpSpec(name = "user-mcp", command = "y")
                )
            )
        )

        val outcome = releaser().attach(p)

        assertEquals(listOf("demo-mcp"), outcome.ownedMcpServers)
        assertEquals(
            listOf(ReleaseConflict(ConflictKind.MCP_EXISTS, "user-mcp")),
            outcome.conflicts
        )
        assertEquals(setOf("user-mcp", "demo-mcp"), host.servers.map { it.name }.toSet())
        // 用户自己那条没被改
        assertEquals("x", host.servers.first { it.name == "user-mcp" }.command)
    }

    @Test
    fun `重复装配不会重复追加 MCP 与钩子`() = runBlocking {
        val p = plugin(
            capabilities = PluginCapabilities(
                mcp = listOf(McpSpec(name = "demo-mcp", command = "npx")),
                hooks = listOf(HookSpec(event = HooksConfig.EVENT_PRE_TOOL_USE, script = "hook.sh"))
            )
        )

        val first = releaser().attach(p)
        val second = releaser().attach(p)

        assertEquals(first.ownedFiles, second.ownedFiles)
        assertEquals(1, host.servers.count { it.name == "demo-mcp" })
        assertEquals(1, host.hookTable[HooksConfig.EVENT_PRE_TOOL_USE]?.size)
    }

    @Test
    fun `钩子按插件目录路径注册，禁用时整条移除`() = runBlocking {
        val p = plugin(
            capabilities = PluginCapabilities(
                hooks = listOf(
                    HookSpec(event = HooksConfig.EVENT_POST_TOOL_USE, script = "check.sh", matcher = "Bash")
                )
            )
        )

        releaser().attach(p)
        val registered = host.hookTable[HooksConfig.EVENT_POST_TOOL_USE]!!.single()
        assertEquals("~/.aicode/plugins/com.demo.hello/check.sh", registered.script)
        assertEquals("Bash", registered.matcher)

        releaser().detach(p.id)
        assertNull(host.hookTable[HooksConfig.EVENT_POST_TOOL_USE])
    }

    @Test
    fun `禁用后文件、MCP、钩子、账本全部清空`() = runBlocking {
        val p = plugin(
            capabilities = PluginCapabilities(
                skills = listOf("skills"),
                mcp = listOf(McpSpec(name = "demo-mcp", command = "npx")),
                hooks = listOf(HookSpec(event = HooksConfig.EVENT_PRE_TOOL_USE, script = "h.sh"))
            )
        )
        File(p.dir, "skills/alpha").mkdirs()
        File(p.dir, "skills/alpha/SKILL.md").writeText("# alpha")
        // 用户自己的资源，不应被摘除波及
        fs.mkdirs("~/.aicode/skills/user-owned")
        fs.writeFile("~/.aicode/skills/user-owned/SKILL.md", "mine")

        releaser().attach(p)
        releaser().detach(p.id)

        assertFalse(fs.exists("~/.aicode/skills/alpha"))
        assertTrue(fs.exists("~/.aicode/skills/user-owned/SKILL.md"))
        assertTrue(host.servers.isEmpty())
        assertTrue(host.hookTable.isEmpty())
        val state = store.stateOf(p.id)!!
        assertTrue(state.ownedFiles.isEmpty())
        assertTrue(state.ownedMcpServers.isEmpty())
        assertTrue(state.ownedHooks.isEmpty())
    }

    @Test
    fun `容器未就绪时环境准备只记跳过，不写标记`() = runBlocking {
        engine.ready = false
        val p = plugin(
            capabilities = PluginCapabilities(
                provision = ProvisionSpec(scenario = "demo-env", script = "setup.sh")
            )
        )

        val outcome = releaser().attach(p)

        assertEquals(
            listOf(ReleaseConflict(ConflictKind.PROVISION_SKIPPED, "demo-env")),
            outcome.conflicts
        )
        assertTrue(outcome.ownedProvisionMarkers.isEmpty())
        assertFalse(fs.exists("~/.aicode/.plugin-data/com.demo.hello/.provisioned"))
    }

    @Test
    fun `环境准备成功写标记，再次装配不重复执行`() = runBlocking {
        val p = plugin(
            capabilities = PluginCapabilities(
                provision = ProvisionSpec(scenario = "demo-env", script = "setup.sh")
            )
        )

        releaser().attach(p)
        assertEquals(1, engine.commands.size)
        assertTrue(fs.exists("~/.aicode/.plugin-data/com.demo.hello/.provisioned"))

        releaser().attach(p)
        assertEquals("标记存在时不应再跑脚本", 1, engine.commands.size)
    }

    @Test
    fun `声明的资源在插件目录里缺失时报出来`() = runBlocking {
        val p = plugin(capabilities = PluginCapabilities(skills = listOf("not-there")))

        val outcome = releaser().attach(p)

        assertEquals(
            listOf(ReleaseConflict(ConflictKind.SOURCE_MISSING, "not-there")),
            outcome.conflicts
        )
    }

    @Test
    fun `不支持的钩子事件被拒`() = runBlocking {
        val p = plugin(
            capabilities = PluginCapabilities(
                hooks = listOf(HookSpec(event = "Stop", script = "s.sh"))
            )
        )

        val outcome = releaser().attach(p)

        assertEquals(
            listOf(ReleaseConflict(ConflictKind.HOOK_EVENT_UNSUPPORTED, "Stop")),
            outcome.conflicts
        )
        assertTrue(host.hookTable.isEmpty())
    }
}

/** 内存文件系统：只实现 releaser 用到的方法，其余按「未使用即抛错」处理。 */
private class InMemoryFileAccess : FileAccessProvider {

    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()

    private fun norm(path: String) = path.trimEnd('/')

    override fun readFile(path: String): String =
        files[norm(path)]?.toString(Charsets.UTF_8) ?: throw FileNotFoundException(path)

    override fun readLines(path: String): Sequence<String> =
        readFile(path).lineSequence()

    override fun writeFile(
        path: String,
        content: String,
        overwrite: Boolean,
        encoding: java.nio.charset.Charset
    ) {
        writeBytes(path, content.toByteArray(encoding), overwrite)
    }

    override fun exists(path: String): Boolean = norm(path) in files || norm(path) in dirs

    override fun isDirectory(path: String): Boolean = norm(path) in dirs

    override fun isFile(path: String): Boolean = norm(path) in files

    override fun fileSize(path: String): Long = files[norm(path)]?.size?.toLong() ?: 0

    override fun lastModified(path: String): Long = 0

    override fun fingerprint(path: String): String? = null

    override fun permissions(path: String): String = "rwx"

    override fun listFiles(path: String): List<FileEntry> {
        val base = norm(path)
        return (files.keys + dirs)
            .filter { it.startsWith("$base/") && !it.removePrefix("$base/").contains('/') }
            .map { FileEntry(it.substringAfterLast('/'), it in dirs, 0, 0) }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        files.keys.filter { it.startsWith("${norm(path)}/") }.map { it.removePrefix("${norm(path)}/") }

    override fun readBytes(path: String): ByteArray =
        files[norm(path)] ?: throw FileNotFoundException(path)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val key = norm(path)
        if (!overwrite && key in files) throw FileAlreadyExistsException(path)
        files[key] = bytes
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val bytes = input.readBytes()
        writeBytes(path, bytes, overwrite)
        return bytes.size.toLong()
    }

    override fun copyToLocal(path: String): File = throw UnsupportedOperationException("测试不需要")

    override fun delete(path: String) {
        files.remove(norm(path))
        dirs.remove(norm(path))
    }

    override fun deleteRecursively(path: String) {
        val base = norm(path)
        files.keys.filter { it == base || it.startsWith("$base/") }.forEach { files.remove(it) }
        dirs.filter { it == base || it.startsWith("$base/") }.forEach { dirs.remove(it) }
    }

    override fun rename(path: String, newPath: String) = throw UnsupportedOperationException("测试不需要")

    override fun copy(path: String, newPath: String, overwrite: Boolean) =
        throw UnsupportedOperationException("测试不需要")

    override fun move(path: String, newPath: String, overwrite: Boolean) =
        throw UnsupportedOperationException("测试不需要")

    override fun mkdirs(path: String) {
        var current = norm(path)
        while (current.isNotEmpty()) {
            dirs += current
            current = current.substringBeforeLast('/', "")
        }
    }

    override fun parentPath(path: String): String? {
        val parent = norm(path).substringBeforeLast('/', "")
        return parent.ifEmpty { null }
    }

    override fun toDisplayPath(path: String): String = path
}

private class FakeHostConfig : PluginHostConfig {
    var servers: MutableList<McpServerConfig> = mutableListOf()

    /** 不叫 hooks：接口方法 setHooks 会与属性 setter 撞 JVM 签名。 */
    var hookTable: Map<String, List<HookDefinition>> = emptyMap()

    override suspend fun mcpServers(): List<McpServerConfig> = servers

    override suspend fun setMcpServers(servers: List<McpServerConfig>) {
        this.servers = servers.toMutableList()
    }

    override fun hooks(): Map<String, List<HookDefinition>> = hookTable

    override fun setHooks(hooks: Map<String, List<HookDefinition>>) {
        hookTable = hooks
    }
}

private class FakeCommandEngine : CommandEngine {
    var ready = true
    val commands = mutableListOf<String>()

    override val initProgress: kotlinx.coroutines.flow.StateFlow<ContainerInitState> =
        kotlinx.coroutines.flow.MutableStateFlow(ContainerInitState.Ready)

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = emptyFlow()

    override suspend fun runCommandSync(command: String, projectPath: String?, timeoutMs: Long): String =
        throw UnsupportedOperationException("测试不需要")

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = throw UnsupportedOperationException("测试不需要")

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = throw UnsupportedOperationException("测试不需要")

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!ready) return null
        commands += command
        return CommandResult(output = "ok", exitCode = 0)
    }

    override fun isContainerInstalled(): Boolean = ready

    override fun isProvisioned(): Boolean = ready

    override fun defaultShell(): String = "/bin/sh"

    override suspend fun ensureInstalled() = Unit
}
