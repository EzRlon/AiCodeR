package com.aicode.feature.agent.domain.prompt

import com.aicode.core.util.FileLogger
import java.io.File

/**
 * CLI 引擎桥（claude/codex/opencode）的知识注入块：知识库索引 + 世界书命中。
 *
 * 与内置引擎 SystemPromptProvider 的 ProjectNotesSource / LorebookSource 同源：
 * 知识库只注入索引（篇名 + 首行摘要），全文按需读原文件——每轮重发全文是 token 大头。
 * CLI 引擎每回合 spawn 一次进程，缓存无意义，直接现扫。
 */
object CliKnowledgeInjection {

    private const val TAG = "CliKnowledgeInjection"
    private const val PROJECT_NOTES_DIR = "/.aicode/notes"
    private const val MAX_NOTES_LISTED = 30

    /** 索引里每篇笔记的摘要行长度上限（字符）。 */
    private const val MAX_NOTE_SUMMARY_CHARS = 80

    /** 知识库索引：篇名 + 首行摘要，全文按需读原文件。 */
    fun notesIndex(projectRoot: String): String {
        val dir = File(projectRoot.trimEnd('/') + PROJECT_NOTES_DIR)
        if (!dir.isDirectory) return ""
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
            ?: return ""
        val sb = StringBuilder("（按需阅读：以下是项目知识库索引，需要某篇的完整内容时按文件名读原文件）\n")
        for (file in files.sortedBy { it.name }.take(MAX_NOTES_LISTED)) {
            val summary = try {
                file.readText().trim()
            } catch (e: Exception) {
                continue
            }
            if (summary.isEmpty()) continue
            val firstLine = summary.lineSequence()
                .map { it.trim().trimStart('#', ' ', '-', '*', '》') }
                .firstOrNull { it.isNotBlank() }
                ?.take(MAX_NOTE_SUMMARY_CHARS)
                ?: ""
            sb.append("- ").append(file.name.removeSuffix(".md"))
            if (firstLine.isNotBlank()) sb.append("：").append(firstLine)
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * CLI 引擎每回合注入的知识块：知识库索引 + 世界书按 [userMessage] 命中的设定条目。
     * 同时附使用引导（模型不主动检索知识库等于它没工作）。无任何内容返回 null。
     */
    fun build(projectRoot: String, userMessage: String): String? {
        if (projectRoot.isBlank()) return null
        val sb = StringBuilder()
        val notes = try {
            notesIndex(projectRoot)
        } catch (e: Exception) {
            FileLogger.w(TAG, "扫描项目知识库失败: ${e.message}", e)
            ""
        }
        if (notes.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("## 项目知识库\n").append(notes)
        }
        if (userMessage.isNotBlank()) {
            val lore = runCatching { LorebookStore.load(projectRoot) }
                .onFailure { FileLogger.w(TAG, "加载世界书失败: ${it.message}", it) }
                .getOrDefault(emptyList())
            if (lore.isNotEmpty()) {
                val hits = LorebookStore.recall(lore, userMessage)
                LorebookStore.render(hits)?.let { rendered ->
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append("## 世界书（本项目设定）\n").append(rendered)
                }
            }
        }
        if (sb.isEmpty()) return null
        sb.append("\n\n（开工前若上述索引与任务相关，先读对应原文件；遇到约定/坑先对照知识库；任务结束时用 kb_save 工具把新结论沉淀回 notes。）")
        // 每回合都会注入：给一个硬上限，避免知识库膨胀后每轮多烧几千 token。
        return sb.toString().trim().let { text ->
            if (text.length <= MAX_INJECT_CHARS) text
            else text.take(MAX_INJECT_CHARS) + "\n…（知识块过长，已截断；需要的条目按名称读取原文件）"
        }
    }

    /** 单回合知识块上限（约 1.5k token），超出截断并提示按需读取。 */
    private const val MAX_INJECT_CHARS = 6_000
}
