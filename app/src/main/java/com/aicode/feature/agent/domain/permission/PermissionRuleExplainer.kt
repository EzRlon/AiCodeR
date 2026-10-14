package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R

/**
 * 授权规则的中文解释器：把「工具名 + 匹配模式」翻译成用户看得懂的一句话；
 * 原始 pattern 由 UI 以小字附在主行下方（次要参考）。
 *
 * 解释来源两层：①预设词条库（工具中文名 + 常用命令前缀）；②模板兜底——
 * 任何规则都保证有中文主行，绝不裸展示英文 pattern。
 */
object PermissionRuleExplainer {

    /** shell 形态工具的集合（pattern 为命令前缀）。 */
    private val SHELL_TOOLS = setOf("Bash", "Shizuku", "terminal")

    /** 常用命令前缀词条：key 为「程序名」或「程序名 子命令」。 */
    private val COMMAND_GLOSSARY: Map<String, Int> = mapOf(
        "git" to R.string.perm_glossary_git,
        "git pull" to R.string.perm_glossary_git_pull,
        "git push" to R.string.perm_glossary_git_push,
        "git status" to R.string.perm_glossary_git_status,
        "git log" to R.string.perm_glossary_git_log,
        "git diff" to R.string.perm_glossary_git_diff,
        "git add" to R.string.perm_glossary_git_add,
        "git commit" to R.string.perm_glossary_git_commit,
        "git checkout" to R.string.perm_glossary_git_checkout,
        "git merge" to R.string.perm_glossary_git_merge,
        "git stash" to R.string.perm_glossary_git_stash,
        "git clone" to R.string.perm_glossary_git_clone,
        "npm" to R.string.perm_glossary_npm,
        "npm install" to R.string.perm_glossary_npm_install,
        "npm run" to R.string.perm_glossary_npm_run,
        "npm test" to R.string.perm_glossary_npm_test,
        "pnpm" to R.string.perm_glossary_pnpm,
        "yarn" to R.string.perm_glossary_yarn,
        "pip" to R.string.perm_glossary_pip,
        "pip install" to R.string.perm_glossary_pip_install,
        "python" to R.string.perm_glossary_python,
        "python3" to R.string.perm_glossary_python,
        "node" to R.string.perm_glossary_node,
        "rm" to R.string.perm_glossary_rm,
        "mv" to R.string.perm_glossary_mv,
        "cp" to R.string.perm_glossary_cp,
        "mkdir" to R.string.perm_glossary_mkdir,
        "ls" to R.string.perm_glossary_ls,
        "cat" to R.string.perm_glossary_cat,
        "grep" to R.string.perm_glossary_grep,
        "find" to R.string.perm_glossary_find,
        "curl" to R.string.perm_glossary_curl,
        "wget" to R.string.perm_glossary_wget,
        "ssh" to R.string.perm_glossary_ssh,
        "scp" to R.string.perm_glossary_scp,
        "tar" to R.string.perm_glossary_tar,
        "unzip" to R.string.perm_glossary_unzip,
        "chmod" to R.string.perm_glossary_chmod,
        "docker" to R.string.perm_glossary_docker,
        "gradle" to R.string.perm_glossary_gradle,
        "make" to R.string.perm_glossary_make,
        "apk" to R.string.perm_glossary_apk,
        "apt" to R.string.perm_glossary_apt,
        "echo" to R.string.perm_glossary_echo
    )

    /**
     * 单条规则的中文解释（主行）。
     * 例：「允许运行 `git pull` 命令（拉取远端最新代码）」「禁止使用「写入文件」工具」。
     */
    fun explain(context: Context, rule: PermissionRule): String {
        val allow = rule.decision == PermissionDecision.ALLOW
        val toolLabel = ToolPermissionCatalog.infoOf(rule.toolName)
            ?.let { context.getString(it.labelRes) }
            ?: fallbackToolLabel(rule.toolName)

        if (rule.pattern == PermissionRule.WHOLE_TOOL) {
            return context.getString(
                if (allow) R.string.perm_explain_allow_tool else R.string.perm_explain_deny_tool,
                toolLabel
            )
        }

        if (rule.toolName in SHELL_TOOLS) {
            val action = context.getString(
                if (allow) R.string.perm_explain_allow_cmd else R.string.perm_explain_deny_cmd,
                rule.pattern
            )
            val gloss = glossaryOf(rule.pattern)?.let { context.getString(it) }
            return if (gloss != null) context.getString(R.string.perm_explain_with_gloss, action, gloss) else action
        }

        // 非 shell 工具带 pattern（罕见）：给出通用但完整的中文。
        return context.getString(
            if (allow) R.string.perm_explain_allow_tool_pattern else R.string.perm_explain_deny_tool_pattern,
            toolLabel,
            rule.pattern
        )
    }

    /** 词条查找：先试「程序名 子命令」双词，再退「程序名」单词。 */
    private fun glossaryOf(pattern: String): Int? {
        val tokens = pattern.trim().split(Regex("\\s+"))
        val two = tokens.take(2).joinToString(" ")
        return COMMAND_GLOSSARY[two] ?: COMMAND_GLOSSARY[tokens.firstOrNull().orEmpty()]
    }

    /** 未收录工具（如动态 MCP 工具）的展示名兜底。 */
    private fun fallbackToolLabel(toolName: String): String =
        toolName.removePrefix("mcp__").replace("__", " · ")
}
