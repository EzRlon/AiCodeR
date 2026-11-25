package com.aicode.feature.settings.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.file.Files

class SubprogramRegistryTest {

    /** 独立临时工作区 + CommandEngine mock（探活测试里按需覆写）。 */
    private fun newRegistry(): Triple<SubprogramRegistry, java.io.File, CommandEngine> {
        val workspace = Files.createTempDirectory("subprogram-test").toFile()
        val workspaceRepository = mockk<WorkspaceRepository>()
        every { workspaceRepository.currentPath() } returns workspace.absolutePath
        val commandEngine = mockk<CommandEngine>()
        return Triple(SubprogramRegistry(workspaceRepository, commandEngine), workspace, commandEngine)
    }

    private val sub = SubprogramRegistry.Subprogram(
        name = "subtest", packageName = "com.aicoder.app.subtest", port = 41100, token = "tok-1"
    )

    // ---------- load / save ----------

    /** 空工作区（无注册文件）：load 返回空列表，不抛错。 */
    @Test
    fun load_noFile_returnsEmpty() {
        val (registry, workspace, _) = newRegistry()

        assertTrue(registry.load().isEmpty())
        assertFalse(java.io.File(workspace, ".aicode/subprograms.json").exists())
    }

    /** save → load 往返：名称/包名/端口/token 全字段保留。 */
    @Test
    fun saveThenLoad_roundTrip() {
        val (registry, _, _) = newRegistry()

        registry.save(listOf(sub))
        val loaded = registry.load()

        assertEquals(listOf(sub), loaded)
    }

    /** 多个子程序按序持久化；删除条目后 save 生效（文件不保留旧条目）。 */
    @Test
    fun saveThenDelete_removesEntry() {
        val (registry, _, _) = newRegistry()
        val second = sub.copy(name = "another", port = 41101, token = "tok-2")

        registry.save(listOf(sub, second))
        assertEquals(2, registry.load().size)

        registry.save(listOf(second))
        val loaded = registry.load()

        assertEquals(listOf(second), loaded)
    }

    /** 文件里端口非正/名称空白的脏条目在 load 时被过滤，不污染注册表。 */
    @Test
    fun load_filtersInvalidEntries() {
        val (registry, workspace, _) = newRegistry()
        val dir = java.io.File(workspace, ".aicode").apply { mkdirs() }
        java.io.File(dir, "subprograms.json").writeText(
            """{"subprograms":[
                {"name":"ok","packageName":"p.ok","port":41100,"token":"t1"},
                {"name":"","packageName":"p.bad","port":41101,"token":"t2"},
                {"name":"noport","packageName":"p.np","port":0,"token":"t3"},
                {"name":"notoken","packageName":"p.nt","port":41102,"token":""}
            ]}"""
        )

        val loaded = registry.load()

        assertEquals(listOf(SubprogramRegistry.Subprogram(name = "ok", packageName = "p.ok", port = 41100, token = "t1")), loaded)
    }

    /** 损坏的 JSON 文件：load 返回空列表（runCatching 兜底），不抛异常。 */
    @Test
    fun load_corruptedJson_returnsEmpty() {
        val (registry, workspace, _) = newRegistry()
        java.io.File(java.io.File(workspace, ".aicode").apply { mkdirs() }, "subprograms.json")
            .writeText("not json at all")

        assertTrue(registry.load().isEmpty())
    }

    /** save 写出的 JSON 结构为 {"subprograms":[...]}，字段名与模板 README 约定一致。 */
    @Test
    fun save_writesDocumentedSchema() {
        val (registry, workspace, _) = newRegistry()

        registry.save(listOf(sub))

        val root = JSONObject(java.io.File(workspace, ".aicode/subprograms.json").readText())
        val arr = root.getJSONArray("subprograms")
        assertEquals(1, arr.length())
        val entry = arr.getJSONObject(0)
        assertEquals("subtest", entry.getString("name"))
        assertEquals("com.aicoder.app.subtest", entry.getString("packageName"))
        assertEquals(41100, entry.getInt("port"))
        assertEquals("tok-1", entry.getString("token"))
    }

    // ---------- 探活 check ----------

    /** MCP 端点返回含 "result" 的 JSON-RPC 响应且退出码 0 → 在线。 */
    @Test
    fun check_successResponse_returnsTrue() = runTest {
        val (registry, _, commandEngine) = newRegistry()
        coEvery {
            commandEngine.runCommandSyncWithExit(any(), any(), any())
        } returns com.aicode.feature.agent.domain.container.CommandResult(
            output = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2026-07-28"}}""",
            exitCode = 0
        )

        assertTrue(registry.check(sub))
    }

    /** 服务离线（curl 退出码非 0）：不在线，且不抛异常。 */
    @Test
    fun check_connectionRefused_returnsFalse() = runTest {
        val (registry, _, commandEngine) = newRegistry()
        coEvery {
            commandEngine.runCommandSyncWithExit(any(), any(), any())
        } returns com.aicode.feature.agent.domain.container.CommandResult(
            output = "curl: (7) Failed to connect",
            exitCode = 7
        )

        assertFalse(registry.check(sub))
    }

    /** 端口通但 token 错（401 页面无 "result" 字样）：不在线。 */
    @Test
    fun check_authRejected_returnsFalse() = runTest {
        val (registry, _, commandEngine) = newRegistry()
        coEvery {
            commandEngine.runCommandSyncWithExit(any(), any(), any())
        } returns com.aicode.feature.agent.domain.container.CommandResult(
            output = """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"Unauthorized"}}""",
            exitCode = 0
        )

        assertFalse(registry.check(sub))
    }

    /** 探活命令必须带 Bearer token 且打向 127.0.0.1:<端口>/mcp（安全三件套回归）。 */
    @Test
    fun check_commandCarriesTokenAndLoopbackPort() = runTest {
        val (registry, _, commandEngine) = newRegistry()
        var captured = ""
        coEvery {
            commandEngine.runCommandSyncWithExit(any(), any(), any())
        } answers {
            captured = firstArg()
            com.aicode.feature.agent.domain.container.CommandResult(output = """{"result":{}}""", exitCode = 0)
        }

        registry.check(sub)

        assertTrue("命令应指向回环端口 /mcp：$captured", captured.contains("http://127.0.0.1:41100/mcp"))
        assertTrue("命令应带 Bearer token：$captured", captured.contains("Authorization: Bearer tok-1"))
    }
}
