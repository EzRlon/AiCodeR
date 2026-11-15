package com.aicode.feature.agent.domain.orchestration

import com.aicode.testutil.TestFileAccessProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 共享任务板：状态机、证据门禁、依赖门禁与幂等。 */
class OrchestrationBoardServiceTest {

    private fun newBoard(): Pair<OrchestrationBoardService, String> {
        val root = Files.createTempDirectory("orchestration-board").toFile().absolutePath
        return OrchestrationBoardService(TestFileAccessProvider()) to root
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun str(value: String): JsonElement =
        kotlinx.serialization.json.JsonPrimitive(value)

    private fun deps(vararg values: String): JsonElement = buildJsonArray { values.forEach { add(it) } }

    @Test
    fun `创建 认领 带证据完成 的完整链路`() = runTest {
        val (service, root) = newBoard()
        val created = service.handle(root, args("action" to str("create"), "title" to str("实现登出")))
        assertTrue(created.ok)
        val list = service.handle(root, args("action" to str("list")))
        assertTrue(list.message.contains("实现登出"))
        assertTrue(list.message.contains("queued"))

        val claimed = service.handle(
            root,
            args("action" to str("claim"), "task" to str("实现登出"), "owner" to str("backend"))
        )
        assertTrue(claimed.ok)
        assertTrue(claimed.message.contains("doing"))

        val rejected = service.handle(
            root,
            args("action" to str("update"), "task" to str("实现登出"), "status" to str("done"))
        )
        assertFalse(rejected.ok)
        assertTrue(rejected.message.contains("evidence"))

        val done = service.handle(
            root,
            args(
                "action" to str("update"),
                "task" to str("实现登出"),
                "status" to str("done"),
                "evidence" to str("./gradlew test 通过")
            )
        )
        assertTrue(done.ok)
        val detail = service.handle(root, args("action" to str("read"), "task" to str("实现登出")))
        assertTrue(detail.message.contains("done"))
        assertTrue(detail.message.contains("./gradlew test 通过"))
    }

    @Test
    fun `blocked 与 waiting 必须写清 needs`() = runTest {
        val (service, root) = newBoard()
        service.handle(root, args("action" to str("create"), "title" to str("修复构建")))
        service.handle(
            root,
            args("action" to str("claim"), "task" to str("修复构建"), "owner" to str("backend"))
        )

        val noNeeds = service.handle(
            root,
            args("action" to str("update"), "task" to str("修复构建"), "status" to str("blocked"))
        )
        assertFalse(noNeeds.ok)
        assertTrue(noNeeds.message.contains("needs"))

        val blocked = service.handle(
            root,
            args(
                "action" to str("update"),
                "task" to str("修复构建"),
                "status" to str("blocked"),
                "needs" to str("需要用户授权访问私有仓库")
            )
        )
        assertTrue(blocked.ok)
    }

    @Test
    fun `依赖未完成不能认领`() = runTest {
        val (service, root) = newBoard()
        service.handle(root, args("action" to str("create"), "title" to str("方案设计")))
        val withDep = service.handle(
            root,
            args("action" to str("create"), "title" to str("实现登录"), "deps" to deps("方案设计"))
        )
        assertTrue(withDep.ok)

        val blockedClaim = service.handle(
            root,
            args("action" to str("claim"), "task" to str("实现登录"), "owner" to str("backend"))
        )
        assertFalse(blockedClaim.ok)
        assertTrue(blockedClaim.message.contains("依赖未完成"))

        service.handle(
            root,
            args(
                "action" to str("update"),
                "task" to str("方案设计"),
                "status" to str("done"),
                "evidence" to str("方案评审记录")
            )
        )
        val claim = service.handle(
            root,
            args("action" to str("claim"), "task" to str("实现登录"), "owner" to str("backend"))
        )
        assertTrue(claim.ok)
    }

    @Test
    fun `同名未结束任务重复创建保持幂等`() = runTest {
        val (service, root) = newBoard()
        val first = service.handle(root, args("action" to str("create"), "title" to str("接入支付")))
        val second = service.handle(root, args("action" to str("create"), "title" to str("接入支付")))
        assertTrue(first.ok)
        assertTrue(second.ok)
        assertTrue(second.message.contains("已存在"))
        val list = service.handle(root, args("action" to str("list")))
        assertEquals(1, Regex("t-[0-9a-f]{8}").findAll(list.message).count())
    }

    @Test
    fun `任务引用支持唯一标题子串`() = runTest {
        val (service, root) = newBoard()
        service.handle(root, args("action" to str("create"), "title" to str("修复登录页崩溃")))
        val claim = service.handle(
            root,
            args("action" to str("claim"), "task" to str("登录页"), "owner" to str("frontend"))
        )
        assertTrue(claim.ok)
    }

    @Test
    fun `空工作区与未知任务明确报错`() = runTest {
        val (service, _) = newBoard()
        assertFalse(service.handle("", args("action" to str("list"))).ok)
        val (service2, root2) = newBoard()
        assertFalse(service2.handle(root2, args("action" to str("read"), "task" to str("不存在"))).ok)
    }
}
