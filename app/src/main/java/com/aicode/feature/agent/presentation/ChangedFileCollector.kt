package com.aicode.feature.agent.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * 从一次工具调用的参数里取出「被改动的文件路径」。
 *
 * 用途：回合结束时自动补录改动账本（见 `AIAgentViewModel.maybeAutoRecordChange`）。
 * 账本此前只有「模型主动调 changelog」这一个入口，模型一忘就整页空白；这里改用客观事实——
 * 本轮到底写过哪些文件。
 *
 * 只认真正会落盘改内容的工具：读文件、搜代码、执行命令都不算改动。
 * 纯函数、无 Android 依赖，可直接单测。
 */
internal object ChangedFileCollector {

    /** 会改动文件内容的工具名 → 参数里的路径字段名。 */
    private val WRITE_TOOLS = mapOf(
        "writeFile" to "path",
        "editFile" to "path",
        "write_file" to "path",
        "edit_file" to "path",
        "createFile" to "path",
        "applyPatch" to "path",
        "notebookEdit" to "path"
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 返回该工具调用改动到的文件路径；不是写操作或取不到路径时返回 null。
     * [argsPreview] 为工具参数 JSON 文本（`AgentEvent.ToolCallFinished.argsPreview`）。
     */
    fun extractPath(toolName: String, argsPreview: String?): String? {
        val field = WRITE_TOOLS[toolName] ?: return null
        val raw = argsPreview?.takeIf { it.isNotBlank() } ?: return null
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val value = (obj[field] as? JsonPrimitive)?.contentOrNull?.trim()
        return value?.takeIf { it.isNotEmpty() && it.length <= MAX_PATH_CHARS }
    }

    /** 从一组工具名里挑出写操作文件名（供测试与统计用）。 */
    fun isWriteTool(toolName: String): Boolean = toolName in WRITE_TOOLS

    /** 多文件补丁类工具的路径列表（当前仅用于将来扩展，保持签名稳定）。 */
    fun extractPaths(toolName: String, argsPreview: String?): List<String> {
        val single = extractPath(toolName, argsPreview)
        if (single != null) return listOf(single)
        val raw = argsPreview?.takeIf { it.isNotBlank() } ?: return emptyList()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val array = obj["paths"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** 单条路径长度上限：超长多半是模型把内容塞进了 path 字段。 */
    private const val MAX_PATH_CHARS = 512
}
