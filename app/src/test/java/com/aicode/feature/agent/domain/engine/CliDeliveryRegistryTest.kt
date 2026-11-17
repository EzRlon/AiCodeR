package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.presentation.AgentAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLI 交付中继的行为边界。
 *
 * 这是外部引擎「文件卡片 / 交付页」的唯一数据通路：bridge 的 send_file 在 App 侧登记，
 * 桥解析工具结果时取走挂到 ToolCallFinished.attachments 上。三件不能错的事：
 *
 * 1. **会话隔离**——多个 CLI 会话并发时，A 会话的交付不能串到 B 会话的卡片里；
 * 2. **取走即清空**——同一次调用只渲染一次卡片，重复取会让交付页出现重复记录；
 * 3. **空值不登记**——空 sessionId / 空列表不该在内存里留下垃圾键。
 */
class CliDeliveryRegistryTest {

    private val registry = CliDeliveryRegistry()

    private fun attachment(name: String, path: String = "/root/workspace/$name") = AgentAttachment(
        fileName = name,
        containerPath = path,
        localPath = "/tmp/$name",
        mimeType = "application/octet-stream",
        sizeBytes = 1024,
        isImage = false
    )

    @Test
    fun `登记后能取回`() {
        registry.record("s1", listOf(attachment("a.apk")))

        val drained = registry.drain("s1")

        assertEquals(1, drained.size)
        assertEquals("a.apk", drained.first().fileName)
    }

    @Test
    fun `取走后即清空，再取为空`() {
        registry.record("s1", listOf(attachment("a.apk")))

        assertEquals(1, registry.drain("s1").size)
        assertTrue("第二次取应为空（否则卡片会重复渲染）", registry.drain("s1").isEmpty())
    }

    @Test
    fun `会话之间互不串台`() {
        registry.record("s1", listOf(attachment("a.apk")))
        registry.record("s2", listOf(attachment("b.apk")))

        val first = registry.drain("s1")
        val second = registry.drain("s2")

        assertEquals(listOf("a.apk"), first.map { it.fileName })
        assertEquals(listOf("b.apk"), second.map { it.fileName })
    }

    @Test
    fun `未登记的会话取回为空`() {
        assertTrue(registry.drain("从未登记过").isEmpty())
    }

    @Test
    fun `同一会话多次登记会累积`() {
        registry.record("s1", listOf(attachment("a.apk")))
        registry.record("s1", listOf(attachment("b.apk")))

        val drained = registry.drain("s1")

        assertEquals(
            "同一次回合内多次 send_file 应都保留",
            listOf("a.apk", "b.apk"),
            drained.map { it.fileName }
        )
    }

    @Test
    fun `空列表不登记`() {
        registry.record("s1", emptyList())

        assertTrue(registry.drain("s1").isEmpty())
    }

    @Test
    fun `空白会话 id 不登记`() {
        registry.record("", listOf(attachment("a.apk")))
        registry.record("   ", listOf(attachment("b.apk")))

        assertTrue("空 id 不该在内存里留下键", registry.drain("").isEmpty())
        assertTrue(registry.drain("   ").isEmpty())
    }

    @Test
    fun `clear 清掉未取走的附件`() {
        registry.record("s1", listOf(attachment("a.apk")))

        registry.clear("s1")

        assertTrue("会话结束后未取走的附件应被清理，不该留在内存里", registry.drain("s1").isEmpty())
    }

    @Test
    fun `clear 不影响其它会话`() {
        registry.record("s1", listOf(attachment("a.apk")))
        registry.record("s2", listOf(attachment("b.apk")))

        registry.clear("s1")

        assertEquals(listOf("b.apk"), registry.drain("s2").map { it.fileName })
    }
}
