package com.aicode.feature.agent.domain.prompt

import com.aicode.core.util.FileLogger
import java.io.File

/**
 * 世界书条目：一条可独立启停、按触发词召回的设定。
 *
 * 与「记忆」的区别：记忆常驻注入，条目随会话累积；世界书按关键词命中才注入，
 * 适合体量大的设定集（人物、地点、规则），避免全量常驻撑爆上下文。
 */
data class LoreEntry(
    /** 条目名，用于展示与去重。 */
    val name: String,
    /** 正文，命中后整体注入。 */
    val content: String,
    /** 触发词：用户输入或任务文本命中任一即召回。空表示常驻（相当于普通笔记）。 */
    val keywords: List<String>,
    /** 是否启用；停用的条目即使命中也不注入。 */
    val enabled: Boolean = true,
    /** 优先级：同时命中多条时按此降序取，值大者优先。 */
    val priority: Int = 0,
    /** 是否大小写敏感，默认不敏感。 */
    val caseSensitive: Boolean = false,
)

/**
 * 世界书加载与召回。
 *
 * 存储形态选文件而非 Room：条目是纯文本设定，用户可直接用编辑器增删改、随项目一起
 * 版本管理，也便于从 SillyTavern 等生态导入导出。目录为 `<projectRoot>/.aicode/lorebook/`，
 * 每条一个 `.md` 文件，用 frontmatter 声明触发词与优先级：
 *
 * ```markdown
 * ---
 * keywords: 张三, 主角, 剑士
 * priority: 10
 * enabled: true
 * caseSensitive: false
 * ---
 * 张三，三十岁，佩剑「霜落」。
 * ```
 *
 * 没有 frontmatter 的文件按「常驻条目」处理（keywords 为空），行为等同普通笔记。
 */
object LorebookStore {
    private const val TAG = "LorebookStore"

    /** 世界书目录（相对项目根）。 */
    const val DIR = "/.aicode/lorebook"

    /** 单次召回注入的字符上限：超出后按优先级截断，避免少数长条目吃掉全部预算。 */
    const val MAX_INJECT_CHARS = 12_000

    /** 目录内最多扫描的文件数，防止超大目录拖慢每次请求。 */
    private const val MAX_SCAN_FILES = 500

    /**
     * 读取全部条目。文件读失败时跳过该条并记日志，不影响其余条目。
     */
    fun load(projectRoot: String): List<LoreEntry> {
        if (projectRoot.isBlank()) return emptyList()
        val dir = File(projectRoot.trimEnd('/') + DIR)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
            ?: return emptyList()
        val out = ArrayList<LoreEntry>()
        for (file in files.sortedBy { it.name }.take(MAX_SCAN_FILES)) {
            val entry = try {
                parse(file)
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取世界书条目失败 ${file.name}: ${e.message}")
                null
            }
            if (entry != null) out.add(entry)
        }
        return out
    }

    private fun parse(file: File): LoreEntry {
        val text = file.readText()
        val name = file.name.removeSuffix(".md")
        val (meta, body) = splitFrontmatter(text)
        val keywords = meta["keywords"]
            ?.split(',', '，')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return LoreEntry(
            name = name,
            content = body.trim(),
            keywords = keywords,
            enabled = meta["enabled"]?.trim()?.lowercase() != "false",
            priority = meta["priority"]?.trim()?.toIntOrNull() ?: 0,
            caseSensitive = meta["casesensitive"]?.trim()?.lowercase() == "true",
        )
    }

    /**
     * 拆出 frontmatter 与正文。只认文件开头的 `---` 块；没有该块时整体视为正文。
     */
    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, String>() to normalized
        val end = normalized.indexOf("\n---", startIndex = 4)
        if (end < 0) return emptyMap<String, String>() to normalized
        val header = normalized.substring(4, end)
        val body = normalized.substring(end + 4).removePrefix("\n")
        val meta = LinkedHashMap<String, String>()
        for (line in header.split('\n')) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            meta[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return meta to body
    }

    /**
     * 按触发词召回。
     *
     * [haystack] 是待匹配的文本（通常是用户本轮输入）。空 keywords 的条目视为常驻，
     * 只要 enabled 就会注入。结果按 priority 降序，累计字符数受 [MAX_INJECT_CHARS] 约束。
     */
    fun recall(entries: List<LoreEntry>, haystack: String): List<LoreEntry> {
        val hit = entries.filter { it.enabled && (it.keywords.isEmpty() || matches(it, haystack)) }
            .sortedByDescending { it.priority }
        val out = ArrayList<LoreEntry>()
        var used = 0
        for (e in hit) {
            val cost = e.content.length
            if (used + cost > MAX_INJECT_CHARS && out.isNotEmpty()) break
            out.add(e)
            used += cost
        }
        return out
    }

    private fun matches(entry: LoreEntry, haystack: String): Boolean {
        if (haystack.isBlank()) return false
        for (kw in entry.keywords) {
            if (kw.isEmpty()) continue
            val found = if (entry.caseSensitive) {
                haystack.contains(kw)
            } else {
                haystack.contains(kw, ignoreCase = true)
            }
            if (found) return true
        }
        return false
    }

    /**
     * 写入条目。新建或覆盖同名文件；[renameFrom] 非空时先删旧文件（重命名场景）。
     */
    fun save(projectRoot: String, entry: LoreEntry, renameFrom: String? = null) {
        if (projectRoot.isBlank() || entry.name.isBlank()) return
        val dir = File(projectRoot.trimEnd('/') + DIR)
        dir.mkdirs()
        if (renameFrom != null && renameFrom != entry.name) {
            File(dir, "$renameFrom.md").delete()
        }
        File(dir, "${entry.name}.md").writeText(serialize(entry))
    }

    /** 删除条目对应的文件。 */
    fun delete(projectRoot: String, name: String) {
        if (projectRoot.isBlank() || name.isBlank()) return
        File(projectRoot.trimEnd('/') + DIR, "$name.md").delete()
    }

    /** 序列化为带 frontmatter 的 Markdown；keywords 为空时省略该字段（即常驻条目）。 */
    private fun serialize(entry: LoreEntry): String {
        val sb = StringBuilder("---\n")
        if (entry.keywords.isNotEmpty()) {
            sb.append("keywords: ").append(entry.keywords.joinToString(", ")).append('\n')
        }
        if (entry.priority != 0) sb.append("priority: ").append(entry.priority).append('\n')
        if (!entry.enabled) sb.append("enabled: false\n")
        if (entry.caseSensitive) sb.append("caseSensitive: true\n")
        sb.append("---\n").append(entry.content).append('\n')
        return sb.toString()
    }

    /**
     * 渲染为注入块。没有任何条目时返回 null，调用方据此省略整段。
     */
    fun render(entries: List<LoreEntry>): String? {
        if (entries.isEmpty()) return null
        val sb = StringBuilder()
        for (e in entries) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("### ").append(e.name).append('\n').append(e.content)
        }
        return sb.toString()
    }
}
