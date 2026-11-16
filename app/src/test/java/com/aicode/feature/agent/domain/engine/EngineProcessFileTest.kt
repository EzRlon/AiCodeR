package com.aicode.feature.agent.domain.engine

import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎 PID 落盘契约：跨进程回收完全依赖这份文件，字段漏掉等于漏杀。 */
class EngineProcessFileTest {

    private val dir = createTempDirectory("engine-process-file").toFile()
    private val file = dir.resolve("engine-processes.json")
    private val store = EngineProcessFile(file)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `写入后可完整读回`() {
        val record = EngineProcessRecord(
            pid = 4321,
            startTicks = 998877,
            engine = "claude",
            runId = "run-1",
            createdAt = 1_700_000_000_000
        )
        store.put("sid-1", record)

        assertEquals(mapOf("sid-1" to record), store.load())
    }

    @Test
    fun `删除后文件为空且不残留`() {
        store.put("sid-1", EngineProcessRecord(1, 2, "claude", "run-1", 0))
        store.remove("sid-1")

        assertTrue(store.load().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `损坏文件降级为空而不抛异常`() {
        file.writeText("{not-json")

        assertTrue(store.load().isEmpty())
        // 损坏后仍能正常覆盖写入
        val record = EngineProcessRecord(7, 8, null, "run-1", 0)
        store.put("sid-1", record)
        assertEquals(record, store.load()["sid-1"])
    }

    @Test
    fun `retainWhere 只保留当前 runId 的记录`() {
        val kept = EngineProcessRecord(1, 2, "claude", "run-2", 0)
        store.put("kept", kept)
        store.put("dropped", EngineProcessRecord(3, 4, "codex", "run-1", 0))

        store.retainWhere { _, record -> record.runId == "run-2" }

        assertEquals(mapOf("kept" to kept), store.load())
    }

    @Test
    fun `异步删除不会误删同会话新登记的记录`() {
        store.put("sid-1", EngineProcessRecord(100, 2, "claude", "run-1", 0))
        // 新回合已在同一 sessionId 写入新 PID；旧的异步删除任务随后才执行
        val newer = EngineProcessRecord(200, 3, "claude", "run-1", 1)
        store.put("sid-1", newer)

        store.remove("sid-1", expectedPid = 100)

        assertEquals(newer, store.load()["sid-1"])
    }
}
