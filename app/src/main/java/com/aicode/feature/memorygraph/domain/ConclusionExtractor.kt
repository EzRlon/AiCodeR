package com.aicode.feature.memorygraph.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.memorygraph.data.GraphRepository
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeScope
import com.aicode.feature.memorygraph.data.local.entity.GraphNodeType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 结论抽取：把已存在的压缩摘要里的结构化结论段落解析成图谱节点。
 *
 * 为什么不额外调模型：上下文压缩提示词（agent/compact-summary.md）已要求输出九段式摘要，
 * 其中「关键决定与理由」「教训与坑」正是需要沉淀的结论。压缩时那次模型调用已经付过费，
 * 这里只做文本解析——零额外 token、零额外延迟（性能功耗纪律）。
 *
 * 内容真源仍是 memory md：节点只存「一句话结论 + 出处会话」，正文由 AI 需要时用
 * `memory(action="read")` 取回，避免同一事实两处存储互相打架。
 */
@Singleton
class ConclusionExtractor @Inject constructor(
    private val graphRepository: GraphRepository
) {

    /** 摘要段落名 → 结论类型标签（与压缩提示词的九段式标题对齐）。 */
    private val sectionLabels = mapOf(
        "关键决定与理由" to "定案",
        "教训与坑" to "坑",
        "未完成" to "待续"
    )

    /**
     * 从压缩摘要抽取结论并写入图谱（幂等：同一会话同一结论不重复建节点）。
     * 摘要为空或没有可解析段落时静默返回 0——抽取是附加价值，绝不能让压缩本身失败。
     */
    suspend fun extractFromSummary(
        workspacePath: String,
        sessionId: String,
        summary: String,
        limitPerSection: Int = DEFAULT_LIMIT_PER_SECTION
    ): Int {
        if (workspacePath.isBlank() || summary.isBlank()) return 0
        val conclusions = parse(summary, limitPerSection)
        if (conclusions.isEmpty()) return 0
        var written = 0
        conclusions.forEach { conclusion ->
            runCatching {
                graphRepository.recordConclusion(
                    workspacePath = workspacePath,
                    name = conclusion.name,
                    summary = conclusion.text,
                    label = conclusion.label,
                    sourceSessionId = sessionId
                )
                written++
            }.onFailure { FileLogger.w(TAG, "写结论节点失败「${conclusion.name}」: ${it.message}") }
        }
        return written
    }

    /** 解析九段式摘要里的结论段落。段落标题形如「5. 关键决定与理由：」「7. 教训与坑：」。 */
    fun parse(summary: String, limitPerSection: Int = DEFAULT_LIMIT_PER_SECTION): List<Conclusion> {
        val result = mutableListOf<Conclusion>()
        val lines = summary.lines()
        var currentLabel: String? = null
        val buffer = StringBuilder()

        fun flush() {
            val label = currentLabel ?: return
            val text = buffer.toString().trim()
            buffer.clear()
            if (text.isBlank()) return
            splitItems(text).take(limitPerSection).forEach { item ->
                result.add(Conclusion(name = item.name, text = item.text, label = label))
            }
        }

        lines.forEach { raw ->
            val line = raw.trim()
            val matchedLabel = sectionLabels.entries.firstOrNull { (title, _) ->
                // 允许「5. 关键决定与理由：」「关键决定与理由：」「**关键决定与理由**」等写法
                line.contains(title)
            }
            if (matchedLabel != null) {
                flush()
                currentLabel = matchedLabel.value
                // 标题行本身可能带内容：「5. 关键决定与理由：选了 A 方案」
                val afterColon = line.substringAfter("：", "").ifBlank { line.substringAfter(":", "") }.trim()
                if (afterColon.isNotBlank() && afterColon != line) buffer.append(afterColon).append('\n')
            } else if (currentLabel != null) {
                // 遇到下一个数字段标题就结束当前段
                if (NUMBERED_SECTION.matches(line)) {
                    flush()
                    currentLabel = null
                } else {
                    buffer.append(line).append('\n')
                }
            }
        }
        flush()
        return result
    }

    /** 段内条目拆分：优先按列表符号（- / • / 1. / （1）），否则整段算一条。 */
    private fun splitItems(text: String): List<Conclusion> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEach
            if (BULLET_PREFIX.containsMatchIn(line)) {
                if (current.isNotBlank()) items.add(current.toString().trim())
                current.clear()
                current.append(BULLET_PREFIX.replace(line, "")).append(' ')
            } else {
                current.append(line).append(' ')
            }
        }
        if (current.isNotBlank()) items.add(current.toString().trim())
        return items.filter { it.isNotBlank() }.map { body ->
            // 结论名取首个分句（到第一个句读为止），最长 [NAME_MAX_CHARS] 字
            val name = body.substringBefore('：').substringBefore('。').substringBefore('，')
                .trim().take(NAME_MAX_CHARS).ifBlank { body.take(NAME_MAX_CHARS) }
            Conclusion(name = name, text = body.take(SUMMARY_MAX_CHARS), label = "")
        }
    }

    /** 抽取结果：一句话结论（名称 + 正文 + 类型标签）。 */
    data class Conclusion(val name: String, val text: String, val label: String)

    companion object {
        private const val TAG = "ConclusionExtractor"

        /** 每段最多取几条：压缩摘要一段常列 3-8 条，取前几条即够，避免一次灌进过多节点。 */
        const val DEFAULT_LIMIT_PER_SECTION = 4
        private const val NAME_MAX_CHARS = 40
        private const val SUMMARY_MAX_CHARS = 200

        /** 数字段标题：「5. xxx」「5、xxx」「(5) xxx」——用于判断当前段结束。 */
        private val NUMBERED_SECTION = Regex("^[*\\s]*[（(]?\\d{1,2}[.、）)]\\s*\\S.*$")

        /** 列表符号：- / • / * / 1. / （1）。 */
        private val BULLET_PREFIX = Regex("^[*\\s]*([-•*]|[（(]?\\d{1,2}[.、）)])\\s*")
    }
}
