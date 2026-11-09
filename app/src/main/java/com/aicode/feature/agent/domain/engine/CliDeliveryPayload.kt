package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URLConnection

/** 交付校验的结果：要么全部通过（[attachments] 非空、[errors] 为空），要么整体失败。 */
data class DeliveryPayload(
    val attachments: List<AgentAttachment> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * 把 `send_file` 的入参（容器路径列表）校验成附件列表。
 *
 * 从 [PermissionBridgeServer.handleSendFile] 抽出来的原因：那段逻辑只依赖
 * [FileAccessProvider]，却藏在有 7 个依赖的类里，测不到。抽出来后可以单测，
 * 也能保证「原子语义」这条约束不被后续改动破坏。
 *
 * **原子语义（与内置 SendFileTool 一致，不能改）**：任一文件无效则整体失败，
 * 不产出任何附件。否则用户会看到半份交付，且模型收到的「成功」与实际不符。
 */
object CliDeliveryPayload {

    fun build(rawPaths: List<String>, fileAccess: FileAccessProvider): DeliveryPayload {
        val paths = rawPaths.map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) return DeliveryPayload(errors = listOf("paths 不能为空"))

        val attachments = mutableListOf<AgentAttachment>()
        val errors = mutableListOf<String>()
        paths.forEach { path ->
            runCatching {
                if (!fileAccess.exists(path) || fileAccess.isDirectory(path)) {
                    error("文件不存在：$path")
                }
                val local = fileAccess.copyToLocal(path)
                val name = path.substringAfterLast('/').ifBlank { local.name }
                val mime = URLConnection.guessContentTypeFromName(name) ?: DEFAULT_MIME
                AgentAttachment(
                    fileName = name,
                    containerPath = path,
                    localPath = local.absolutePath,
                    mimeType = mime,
                    sizeBytes = fileAccess.fileSize(path),
                    isImage = mime.startsWith("image/")
                )
            }.onSuccess { attachments += it }
                .onFailure { errors += (it.message ?: path) }
        }
        return if (errors.isNotEmpty()) {
            DeliveryPayload(errors = errors)
        } else {
            DeliveryPayload(attachments = attachments)
        }
    }

    /**
     * 从 `send_file` 的 JSON 入参里取路径数组。
     *
     * 只认**字符串**项：`contentOrNull` 对数字/布尔也会返回文本（`123` / `true`），
     * 用它会把这些误当成路径，校验时报「文件不存在」而不是「参数类型不对」——
     * 报错指向会跑偏。
     */
    fun extractPaths(input: JsonObject): List<String> =
        (input["paths"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.orEmpty()

    const val DEFAULT_MIME = "application/octet-stream"
}
