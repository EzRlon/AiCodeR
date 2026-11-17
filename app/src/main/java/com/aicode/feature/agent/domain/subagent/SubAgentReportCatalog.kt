package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/** 一份子代理报告的目录条目（供 `task(action="list")` 展示与事后追溯）。 */
data class SubAgentReportEntry(
    val subSessionId: String,
    val agentType: String,
    val title: String,
    val finishedAt: Long,
    val outcome: String,
    val filePath: String
)

/**
 * 子代理报告 catalog：子代理结束时把最终输出落到 `~/.aicode/subagent-reports/<时间>-<类型>-<会话id>.md`。
 *
 * 解决两个既有缺口：①子代理会话被 `task(del)` 删除后结果无处可查（此前只有 agent_messages 一处，
 * 随会话 purge 一起消失）；②qa/optimizer 的审查结论无法跨会话复用（同范围重复审查）。
 *
 * 落盘而非建表：报告是任务期产物、量小、天然按会话归档；文件制还能被 grep/readFile 直接检索，
 * 也随备份的 notes 段一起走（与 skills/agents 同一套约定）。写失败只记日志，绝不影响子代理主流程。
 */
@Singleton
class SubAgentReportCatalog @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    /**
     * 记录一次子代理结束（[outcome] 为 completed / failed）。
     * [content] 取子代理最终输出；为空时不落盘（空报告没有复用价值）。
     */
    fun record(
        subSessionId: String,
        agentType: String,
        title: String,
        outcome: String,
        content: String
    ) {
        if (content.isBlank()) return
        runCatching {
            val path = "${root()}/${fileName(System.currentTimeMillis(), agentType, subSessionId)}"
            fileAccess.writeFile(path, render(agentType, title, outcome, subSessionId, content), overwrite = true)
            FileLogger.i(TAG, "子代理报告已归档: $path")
        }.onFailure { e ->
            FileLogger.w(TAG, "归档子代理报告失败: session=$subSessionId", e)
        }
    }

    /** 读取某子会话归档的报告正文；未归档返回 null。 */
    fun read(subSessionId: String): String? = runCatching {
        val match = listEntries().firstOrNull { it.subSessionId == subSessionId } ?: return@runCatching null
        fileAccess.readFile(match.filePath)
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取子代理报告失败: session=$subSessionId", e)
        null
    }

    /** 列出全部归档报告（按结束时间倒序）。目录不存在时返回空列表。 */
    fun listEntries(): List<SubAgentReportEntry> = runCatching {
        val dir = root()
        if (!fileAccess.isDirectory(dir)) return@runCatching emptyList()
        fileAccess.listFiles(dir)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { entry -> parseEntry(entry.name, "$dir/${entry.name}") }
            .sortedByDescending { it.finishedAt }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描子代理报告目录失败", e)
        emptyList()
    }

    /** 文件名格式：`<毫秒时间>-<类型>-<会话id>.md`；类型里的 `/` 等字符换成 `_` 防越级。 */
    private fun fileName(finishedAt: Long, agentType: String, subSessionId: String): String {
        val safeType = agentType.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_").take(40)
        val safeId = subSessionId.replace(Regex("[^\\w-]"), "_").take(80)
        return "$finishedAt-$safeType-$safeId.md"
    }

    private fun parseEntry(fileName: String, path: String): SubAgentReportEntry? {
        val base = fileName.removeSuffix(".md")
        val parts = base.split("-", limit = 3)
        if (parts.size < 3) return null
        val finishedAt = parts[0].toLongOrNull() ?: return null
        // 只读一次头部：列表可能包含几百份报告，逐行读两遍会明显拖慢
        val head = runCatching { fileAccess.readFile(path).lineSequence().take(8).toList() }
            .getOrElse { emptyList() }
        fun field(prefix: String) = head.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
        return SubAgentReportEntry(
            subSessionId = parts[2],
            agentType = parts[1],
            title = field(TITLE_PREFIX),
            finishedAt = finishedAt,
            outcome = field(OUTCOME_PREFIX),
            filePath = path
        )
    }

    private fun render(agentType: String, title: String, outcome: String, subSessionId: String, content: String) = buildString {
        appendLine("# 子代理报告：$title")
        appendLine()
        appendLine("${OUTCOME_PREFIX}$outcome")
        appendLine("${TITLE_PREFIX}$title")
        appendLine("- 类型：$agentType")
        appendLine("- 子会话：$subSessionId")
        appendLine()
        appendLine(content.trim())
    }

    private fun root(): String = GLOBAL_ROOT

    companion object {
        private const val TAG = "SubAgentReportCatalog"
        const val GLOBAL_ROOT = "~/.aicode/subagent-reports"
        private const val TITLE_PREFIX = "- 标题："
        private const val OUTCOME_PREFIX = "- 结果："
    }
}
