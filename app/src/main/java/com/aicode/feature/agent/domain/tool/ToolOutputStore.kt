package com.aicode.feature.agent.domain.tool

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class StoredToolOutput(
    val preview: String,
    val truncated: Boolean,
    val totalChars: Long,
    val outputPath: String? = null,
    val storageError: String? = null
)

@Singleton
class ToolOutputStore @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver
) {
    private companion object {
        const val TAG = "ToolOutputStore"
        const val OUTPUT_DIR = "tool-output"
        const val HEAD_CHARS = 20_000
        const val TAIL_CHARS = 20_000
        const val MAX_INLINE_CHARS = HEAD_CHARS + TAIL_CHARS
        val LARGE_TEXT_FIELDS = listOf("output", "content", "text", "stdout", "stderr", "body", "result")
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        /**
         * 工具输出存档目录的总字节上限；超过后每次写入按 mtime 淘汰最旧的文件。
         * 历史行为是无上限追加（每次大输出一个新文件），长开发会话下会持续增长。
         */
        const val MAX_STORE_BYTES = 512L * 1024 * 1024

        /** 存档目录文件数上限，兼底：大量小文件时按字节阈值可能很久不触发。 */
        const val MAX_STORE_FILES = 3000
    }

    private val json = Json { encodeDefaults = true }

    /**
     * 设备本地存档目录（宿主路径），仅供本机存储占用统计与清理。
     * 写入落在当前执行环境（本地即此目录；远程经 SFTP 落到服务器 home 下的 `.aicode/tool-output`），
     * 故远程模式下本目录为空、实际存档在远端。
     */
    val outputDir: File get() = File(containerInstaller.aicodeDir, OUTPUT_DIR)

    fun process(toolName: String, callId: String, result: ToolResult): ToolResult {
        return when (result) {
            is ToolResult.Success -> ToolResult.Success(processElement(toolName, callId, result.data))
            is ToolResult.Partial -> ToolResult.Partial(processElement(toolName, callId, result.data), result.message)
            is ToolResult.Error -> result
        }
    }

    fun boundText(toolName: String, callId: String, text: String): StoredToolOutput {
        if (text.length <= MAX_INLINE_CHARS) {
            return StoredToolOutput(
                preview = text,
                truncated = false,
                totalChars = text.length.toLong()
            )
        }

        val writeResult = writeFullOutput(toolName, callId, text)
        val preview = buildPreview(text, writeResult.outputPath)
        return StoredToolOutput(
            preview = preview,
            truncated = true,
            totalChars = text.length.toLong(),
            outputPath = writeResult.outputPath,
            storageError = writeResult.storageError
        )
    }

    private fun processElement(toolName: String, callId: String, element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive
        if (primitive?.isString == true) {
            val stored = boundText(toolName, callId, primitive.content)
            return if (stored.truncated) stored.toJsonObject("output") else element
        }

        val obj = element as? JsonObject
        if (obj != null) {
            val largeField = LARGE_TEXT_FIELDS
                .mapNotNull { key ->
                    val field = obj[key] as? JsonPrimitive
                    val value = if (field?.isString == true) field.content else null
                    if (value != null && value.length > MAX_INLINE_CHARS) key to value else null
                }
                .maxByOrNull { it.second.length }

            if (largeField != null) {
                val stored = boundText(toolName, callId, largeField.second)
                val updated = obj.toMutableMap()
                updated[largeField.first] = JsonPrimitive(stored.preview)
                addMetadata(updated, stored)
                return JsonObject(updated)
            }
        }

        val serialized = json.encodeToString(element)
        if (serialized.length <= MAX_INLINE_CHARS) return element

        val stored = boundText(toolName, callId, serialized)
        return stored.toJsonObject("content")
    }

    private fun addMetadata(target: MutableMap<String, JsonElement>, stored: StoredToolOutput) {
        target["output_truncated"] = JsonPrimitive(stored.truncated)
        target["output_total_chars"] = JsonPrimitive(stored.totalChars)
        stored.outputPath?.let { target["output_path"] = JsonPrimitive(it) }
        stored.storageError?.let { target["output_storage_error"] = JsonPrimitive(it) }
    }

    private fun StoredToolOutput.toJsonObject(primaryField: String): JsonObject {
        val data = mutableMapOf<String, JsonElement>(
            primaryField to JsonPrimitive(preview),
            "output_truncated" to JsonPrimitive(truncated),
            "output_total_chars" to JsonPrimitive(totalChars)
        )
        outputPath?.let { data["output_path"] = JsonPrimitive(it) }
        storageError?.let { data["output_storage_error"] = JsonPrimitive(it) }
        return JsonObject(data)
    }

    private fun buildPreview(text: String, outputPath: String?): String {
        val omitted = text.length - HEAD_CHARS - TAIL_CHARS
        val storageHint = if (outputPath != null) {
            "完整内容已保存到 $outputPath"
        } else {
            "完整内容保存失败"
        }
        return buildString {
            append(text.take(HEAD_CHARS))
            append("\n\n...[输出过长，已省略中间 ")
            append(omitted)
            append(" 个字符；")
            append(storageHint)
            append("]...\n\n")
            append(text.takeLast(TAIL_CHARS))
        }
    }

    private fun writeFullOutput(toolName: String, callId: String, text: String): StoredPathResult {
        return try {
            val dir = "${pathHomeResolver.aicodeRoot()}/$OUTPUT_DIR"
            fileAccess.mkdirs(dir)
            val path = uniqueOutputPath(dir, toolName, callId)
            fileAccess.writeFile(path, text, overwrite = false)
            FileLogger.i(TAG, "工具输出已保存: $path (${text.length} chars)")
            pruneIfNeeded(dir, keepPath = path)
            StoredPathResult(outputPath = path)
        } catch (e: Exception) {
            FileLogger.w(TAG, "保存工具输出失败: ${e.message}", e)
            StoredPathResult(storageError = e.message ?: "保存工具输出失败")
        }
    }

    /**
     * 按 mtime 从旧到新删除，直到目录总量低于 [MAX_STORE_BYTES] 且文件数低于 [MAX_STORE_FILES]。
     * [keepPath] 是本次刚写入的存档，永不淘汰——AI 本轮正要按这个路径回读它。
     * 删除失败不抛：存档目录不可写时只告警，不影响当前回合。
     */
    private fun pruneIfNeeded(dir: String, keepPath: String) {
        runCatching {
            val files = fileAccess.listFiles(dir).filter { !it.isDirectory }
            if (files.isEmpty()) return@runCatching
            var total = files.sumOf { it.size }
            var count = files.size
            if (total <= MAX_STORE_BYTES && count <= MAX_STORE_FILES) return@runCatching
            val dirSlash = dir.trimEnd('/')
            for (entry in files.sortedBy { it.lastModified }) {
                if (total <= MAX_STORE_BYTES && count <= MAX_STORE_FILES) break
                val entryPath = "$dirSlash/${entry.name}"
                if (entryPath == keepPath) continue
                runCatching {
                    fileAccess.delete(entryPath)
                    total -= entry.size
                    count--
                }
            }
        }.onFailure { FileLogger.w(TAG, "工具输出存档清理失败: ${it.message}") }
    }

    private fun uniqueOutputPath(dir: String, toolName: String, callId: String): String {
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        val baseName = buildString {
            append(timestamp)
            append('-')
            append(sanitize(toolName).ifBlank { "tool" })
            val id = sanitize(callId).take(12)
            if (id.isNotBlank()) {
                append('-')
                append(id)
            }
        }

        val dirSlash = dir.trimEnd('/')
        var candidate = "$dirSlash/$baseName.log"
        var index = 1
        while (fileAccess.exists(candidate)) {
            candidate = "$dirSlash/$baseName-$index.log"
            index++
        }
        return candidate
    }

    private fun sanitize(value: String): String {
        return value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("").trim('-')
    }

    private data class StoredPathResult(
        val outputPath: String? = null,
        val storageError: String? = null
    )
}
