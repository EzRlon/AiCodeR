package com.aicode.feature.store.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.store.data.PluginStoreItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnginePluginInstaller] 的命令拼装与失败口径。
 *
 * 三引擎的插件子命令互不兼容，且都以「外部进程输出」为唯一事实源：这里逐个钉住
 * 每条引擎命令的参数顺序、marketplace 步骤的有无，以及失败时给用户看的信息。
 * 真实安装仍由真机验收覆盖（依赖容器与网络）。
 */
class EnginePluginInstallerTest {

    private val engine = FakeCommandEngine()
    private val installer = EnginePluginInstaller(engine)

    private fun item(
        engines: List<EngineTarget> = EngineTarget.entries,
        installRef: String = "formatter@acme-tools",
        marketplaceSource: String? = "https://github.com/acme/tools"
    ) = PluginStoreItem(
        id = "acme/formatter",
        name = "Formatter",
        engines = engines,
        installRef = installRef,
        marketplaceSource = marketplaceSource
    )

    // ── 前置校验 ───────────────────────────────────────────────────

    @Test
    fun `目标引擎不在条目支持列表时直接拒绝`() {
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { installer.install(item(engines = listOf(EngineTarget.CODEX)), EngineTarget.CLAUDE_CODE) }
        }
        assertTrue(e.message!!.contains("Formatter"))
        assertTrue("不应执行任何命令", engine.commands.isEmpty())
    }

    @Test
    fun `CLI 未安装时提示去设置里安装`() {
        engine.respond = { cmd ->
            if (cmd.startsWith("command -v")) CommandResult(output = "", exitCode = 127)
            else CommandResult(output = "", exitCode = 0)
        }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { installer.install(item(), EngineTarget.CLAUDE_CODE) }
        }
        assertTrue(e.message!!.contains("尚未安装"))
        assertEquals(listOf("command -v claude"), engine.commands)
    }

    @Test
    fun `旧版本没有 plugin 子命令时提示升级`() {
        engine.respond = { cmd ->
            if (cmd.endsWith("plugin --help")) CommandResult(output = "unknown command", exitCode = 1)
            else CommandResult(output = "", exitCode = 0)
        }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { installer.install(item(), EngineTarget.CLAUDE_CODE) }
        }
        assertTrue(e.message!!.contains("不支持插件命令"))
        assertEquals(listOf("command -v claude", "claude plugin --help"), engine.commands)
    }

    // ── 安装命令 ───────────────────────────────────────────────────

    @Test
    fun `Claude 先加 marketplace 再按 user scope 安装`() = runBlocking {
        installer.install(item(), EngineTarget.CLAUDE_CODE)

        assertEquals(
            listOf(
                "command -v claude",
                "claude plugin --help",
                "claude plugin marketplace add https://github.com/acme/tools --scope user",
                "claude plugin install formatter@acme-tools -y --scope user"
            ),
            engine.commands
        )
    }

    @Test
    fun `Claude 无 marketplace 源时跳过添加步骤`() = runBlocking {
        installer.install(item(marketplaceSource = null), EngineTarget.CLAUDE_CODE)

        assertEquals(
            listOf(
                "command -v claude",
                "claude plugin --help",
                "claude plugin install formatter@acme-tools -y --scope user"
            ),
            engine.commands
        )
    }

    @Test
    fun `Codex 加 marketplace 后 plugin add`() = runBlocking {
        installer.install(item(), EngineTarget.CODEX)

        assertEquals(
            listOf(
                "command -v codex",
                "codex plugin --help",
                "codex plugin marketplace add https://github.com/acme/tools",
                "codex plugin add formatter@acme-tools"
            ),
            engine.commands
        )
    }

    @Test
    fun `OpenCode 无 marketplace，直接 plugin add`() = runBlocking {
        installer.install(item(installRef = "github:acme/formatter"), EngineTarget.OPENCODE)

        assertEquals(
            listOf(
                "command -v opencode",
                "opencode plugin --help",
                "opencode plugin add github:acme/formatter"
            ),
            engine.commands
        )
    }

    @Test
    fun `install 命令使用长超时`() = runBlocking {
        installer.install(item(), EngineTarget.CLAUDE_CODE)

        val install = engine.invocations.single { it.command.contains("plugin install") }
        assertEquals(300_000L, install.timeoutMs)
    }

    // ── 卸载命令 ───────────────────────────────────────────────────

    @Test
    fun `各引擎卸载命令不同`() = runBlocking {
        installer.uninstall(item(), EngineTarget.CLAUDE_CODE)
        installer.uninstall(item(), EngineTarget.CODEX)
        installer.uninstall(item(installRef = "github:acme/formatter"), EngineTarget.OPENCODE)

        assertEquals(
            listOf(
                "command -v claude",
                "claude plugin uninstall formatter@acme-tools -y --scope user",
                "command -v codex",
                "codex plugin remove formatter@acme-tools",
                "command -v opencode",
                "opencode plugin remove github:acme/formatter"
            ),
            engine.commands
        )
    }

    // ── 失败口径 ───────────────────────────────────────────────────

    @Test
    fun `安装失败时带退出码与最后三行输出`() {
        engine.respond = { cmd ->
            when {
                cmd.contains("plugin install") -> CommandResult(
                    output = "line1\n\nline2\nline3\nline4 最后一行\n",
                    exitCode = 2
                )
                else -> CommandResult(output = "", exitCode = 0)
            }
        }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { installer.install(item(), EngineTarget.CLAUDE_CODE) }
        }
        // 只保留最后三行非空输出，空白行被过滤
        assertTrue(e.message!!.contains("退出码 2"))
        assertTrue(e.message!!.contains("line2；line3；line4 最后一行"))
        assertTrue(!e.message!!.contains("line1"))
    }

    // ── listInstalled ─────────────────────────────────────────────

    @Test
    fun `listInstalled 按引擎选命令并过滤空行与注释`() = runBlocking {
        engine.respond = { cmd ->
            when (cmd) {
                "claude plugin list --json" -> CommandResult(output = "a\n\n# comment\n b \n", exitCode = 0)
                // Codex / OpenCode 走普通输出
                else -> CommandResult(output = "x\n# c\n y\n", exitCode = 0)
            }
        }

        assertEquals(setOf("a", "b"), installer.listInstalled(EngineTarget.CLAUDE_CODE))
        assertEquals(setOf("x", "y"), installer.listInstalled(EngineTarget.CODEX))
        assertEquals(setOf("x", "y"), installer.listInstalled(EngineTarget.OPENCODE))
        assertTrue(engine.commands.contains("claude plugin list --json"))
        assertTrue(engine.commands.contains("codex plugin list"))
        assertTrue(engine.commands.contains("opencode plugin list"))
    }

    @Test
    fun `listInstalled 命令失败时返回空集合`() = runBlocking {
        engine.respond = { CommandResult(output = "", exitCode = 1) }
        assertTrue(installer.listInstalled(EngineTarget.CODEX).isEmpty())
    }
}

private data class Invocation(val command: String, val timeoutMs: Long)

/** 记录命令并按 [respond] 的规则返回结果；其余接口按「测试未使用」处理。 */
private class FakeCommandEngine : CommandEngine {

    val commands = mutableListOf<String>()
    val invocations = mutableListOf<Invocation>()
    var respond: (String) -> CommandResult = { CommandResult(output = "", exitCode = 0) }

    override val initProgress: StateFlow<ContainerInitState> =
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
    ): CommandResult {
        commands += command
        invocations += Invocation(command, timeoutMs)
        return respond(command)
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = throw UnsupportedOperationException("测试不需要")

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? = throw UnsupportedOperationException("测试不需要")

    override fun isContainerInstalled(): Boolean = true

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/sh"

    override suspend fun ensureInstalled() = Unit
}
