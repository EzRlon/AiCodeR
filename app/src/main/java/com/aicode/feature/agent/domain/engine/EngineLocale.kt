package com.aicode.feature.agent.domain.engine

import java.util.Locale

/**
 * CLI 引擎（Claude Code / Codex / OpenCode）的界面语言现状与 App 侧可做的事。
 *
 * 三家 CLI 的 TUI 文案目前都**硬编码英文**，官方均未提供界面本地化：
 * - Claude Code：`settings.json` 的 `language` 只影响**模型回复语言**，不改菜单/对话框（官方 issue #31413、#66637）；
 * - Codex：CLI 完全没本地化，设 `localeOverride` 与 `LANG` 都无效（官方 issue #30060 原话）；
 * - OpenCode：官方 TUI 也没有 locale 设置，中文界面只存在于第三方魔改版（opencode-cn）。
 *
 * 所以 App 能做的只有「让模型用中文回复」，界面语言无法从 App 侧改变。这里统一提供判定，
 * 供三处引擎桥接按需注入，避免各自重复写 Locale 判断。
 */
internal object EngineLocale {

    /** 系统语言是否为中文（简/繁都算）。 */
    fun isChinese(): Boolean = Locale.getDefault().language == "zh"

    /**
     * 给引擎追加的「请用中文回复」指令；非中文系统返回 null（不注入）。
     *
     * 只约束**输出语言**，不碰代码、命令与工具调用——模型把 shell 命令或路径翻译成中文会直接跑挂。
     */
    fun replyLanguageDirective(): String? = if (isChinese()) {
        "Reply in Simplified Chinese (简体中文) for all explanations and summaries, " +
            "matching the user's language. Keep code, file paths, shell commands, identifiers, " +
            "commit messages and tool arguments unchanged in their original form."
    } else {
        null
    }
}
