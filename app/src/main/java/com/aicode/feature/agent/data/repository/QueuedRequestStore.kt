package com.aicode.feature.agent.data.repository

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.QueuedRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 排队消息的跨进程持久化。
 *
 * 问题：用户在主代理忙碌时发的消息只存在 `_queuedRequests` 内存里；App 被 LMK/划掉后
 * 队列直接消失，用户感知就是「消息被吞」。这里把文字与附件元数据落到 filesDir，
 * App 重启后恢复并继续排队执行。
 *
 * 取舍：图片 base64 不落盘（可能几十 MB，且属于敏感内容），恢复时丢弃并在日志里注明；
 * 文字、当前文件、选中代码、附件路径等足以让任务继续。
 */
@Singleton
class QueuedRequestStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "QueuedRequestStore"
        private const val FILE_NAME = "queued-requests.json"
    }

    @Serializable
    data class AttachmentDto(
        val fileName: String,
        val containerPath: String,
        val localPath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val isImage: Boolean
    )

    @Serializable
    data class RequestDto(
        val id: String,
        val request: String,
        val modelRequest: String,
        val currentFile: String? = null,
        val selectedCode: String? = null,
        val projectRoot: String = "",
        val isAutoTrigger: Boolean = false,
        val intentType: String? = null,
        val persistUserMessage: Boolean = true,
        val attachments: List<AttachmentDto> = emptyList(),
        /** 恢复时被丢弃的图片数量（base64 不落盘）。 */
        val droppedImages: Int = 0
    )

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun load(): Map<String, List<RequestDto>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching emptyMap()
            val text = file.readText()
            if (text.isBlank()) emptyMap()
            else json.decodeFromString<Map<String, List<RequestDto>>>(text)
        }.onFailure { FileLogger.w(TAG, "读取排队消息失败", it) }
            .getOrDefault(emptyMap())
    }

    suspend fun save(queues: Map<String, List<RequestDto>>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                // 空会话键不落盘，避免文件里长期残留 `sid: []`
                val normalized = queues.filterValues { it.isNotEmpty() }
                if (normalized.isEmpty()) {
                    if (file.exists()) file.delete()
                } else {
                    // 临时文件 + rename：进程被杀在写盘窗口时，旧文件仍然完整可读，
                    // 不会出现半截 JSON 导致整队消息丢失。
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(json.encodeToString(normalized))
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        if (!tmp.renameTo(file)) {
                            tmp.copyTo(file, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
            }.onFailure { FileLogger.w(TAG, "保存排队消息失败", it) }
        }
    }

    fun QueuedRequest.toDto(): RequestDto = RequestDto(
        id = id,
        request = request,
        modelRequest = modelRequest,
        currentFile = currentFile,
        selectedCode = selectedCode,
        projectRoot = projectRoot,
        isAutoTrigger = isAutoTrigger,
        intentType = intentType,
        persistUserMessage = persistUserMessage,
        attachments = inputAttachments.map {
            AttachmentDto(
                fileName = it.fileName,
                containerPath = it.containerPath,
                localPath = it.localPath,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                isImage = it.isImage
            )
        },
        droppedImages = inputImages.size
    )

    fun RequestDto.toModel(): QueuedRequest = QueuedRequest(
        id = id,
        request = request,
        modelRequest = modelRequest,
        currentFile = currentFile,
        selectedCode = selectedCode,
        projectRoot = projectRoot,
        inputImages = emptyList(),
        inputAttachments = attachments.map {
            AgentAttachment(
                fileName = it.fileName,
                containerPath = it.containerPath,
                localPath = it.localPath,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                isImage = it.isImage
            )
        },
        isAutoTrigger = isAutoTrigger,
        intentType = intentType,
        persistUserMessage = persistUserMessage
    )
}
