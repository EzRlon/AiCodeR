package com.aicode.feature.memorygraph.domain

/**
 * 结论解析：把压缩摘要里的结构化结论段落解析成条目。
 *
 * 为什么不调模型：上下文压缩提示词（agent/compact-summary.md）已要求输出九段式摘要，
 * 其中「关键决定与理由」「教训与坑」「未完成」正是需要沉淀的结论。压缩时那次模型调用
 * 已经付过费，这里只做文本解析——零额外 token、零额外延迟（性能功耗纪律）。
 *
 * 本类刻意不依赖仓库（纯函数）：写入图谱的职责在 GraphRepository，避免两者互相注入成环。
 */
object ConclusionParser {

    /** 摘要段落名 → 结论类型标签（与压缩提示词的九段式标题对齐）。 */
    private val sectionLabels = mapOf(
        "关键决定与理由" to "定案",
        "教训与坑" to "坑",
        "未完成" to "待续"
    )

    /** 解析九段式摘要里的结论段落。段落标题形如「5. 关键决定与理由：」「7. 教训与坑：」。 */
    fun parse(summary: String, limitPerSection: Int = DEFAULT_LIMIT_PER_SECTION): List<Conclusion> {
        if (summary.isBlank()) return emptyList()
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
                result.add(item.copy(label = label))
            }
        }

        lines.forEach { raw ->
            val line = raw.trim()
            val matchedLabel = sectionLabels.entries.firstOrNull { (title, _) ->
                // 允许「5. 关键决定与理由：」「关键决定与理由：」「**关键决定与理由**」等写法，
                // 但要求标题出现在行首（前面只能有编号与强调符号）：正文里提到这几个词
                // 不算标题，否则一句话就把当前段提前 flush 掉、后续行归到错标签。
                val lead = SECTION_LEADING.find(line)
                val body = if (lead != null) line.substring(lead.range.last + 1) else line
                body.startsWith(title)
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

    /** 每段最多取几条：压缩摘要一段常列 3-8 条，取前几条即够，避免一次灌进过多节点。 */
    const val DEFAULT_LIMIT_PER_SECTION = 4
    private const val NAME_MAX_CHARS = 40
    private const val SUMMARY_MAX_CHARS = 200

    /** 数字段标题：「5. xxx」「5、xxx」「(5) xxx」——用于判断当前段结束。 */
    private val NUMBERED_SECTION = Regex("^[*\\s]*[（(]?\\d{1,2}[.、）)]\\s*\\S.*$")

    /** 段标题前的修饰：编号、粗体/星号与空白；剥掉后行首即标题才算段标题。 */
    private val SECTION_LEADING = Regex("^[*\\s]*([（(]?\\d{1,2}[.、）)])?[*\\s]*")

    /** 列表符号：- / • / * / 1. / （1）。 */
    private val BULLET_PREFIX = Regex("^[*\\s]*([-•*]|[（(]?\\d{1,2}[.、）)])\\s*")
}
