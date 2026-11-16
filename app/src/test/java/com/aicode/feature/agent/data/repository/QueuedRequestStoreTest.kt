package com.aicode.feature.agent.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排队消息 DTO 的序列化契约：跨进程恢复依赖它，字段漏掉等于消息被吞。
 */
class QueuedRequestStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `排队请求可往返序列化`() {
        val dto = QueuedRequestStore.RequestDto(
            id = "req-1",
            request = "继续",
            modelRequest = "【续跑】继续",
            currentFile = "src/main.kt",
            selectedCode = "val a = 1",
            projectRoot = "/root/workspace",
            isAutoTrigger = false,
            intentType = "task",
            persistUserMessage = true,
            attachments = listOf(
                QueuedRequestStore.AttachmentDto(
                    fileName = "a.txt",
                    containerPath = "/root/workspace/a.txt",
                    localPath = "/data/user/0/com.aicoder/files/a.txt",
                    mimeType = "text/plain",
                    sizeBytes = 12,
                    isImage = false
                )
            ),
            droppedImages = 1
        )
        val text = json.encodeToString(mapOf("session-1" to listOf(dto)))
        val back = json.decodeFromString<Map<String, List<QueuedRequestStore.RequestDto>>>(text)
        assertEquals(dto, back["session-1"]?.first())
    }

    @Test
    fun `空队列编码后仍可解码`() {
        val text = json.encodeToString(emptyMap<String, List<QueuedRequestStore.RequestDto>>())
        val back = json.decodeFromString<Map<String, List<QueuedRequestStore.RequestDto>>>(text)
        assertEquals(0, back.size)
    }
}
