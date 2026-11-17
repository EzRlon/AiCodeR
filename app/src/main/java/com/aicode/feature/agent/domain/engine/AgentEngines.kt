package com.aicode.feature.agent.domain.engine

/** 会话执行引擎标识（chat_sessions.engine 列的取值域）。 */
object AgentEngines {
    /** Claude Code CLI 桥接（引擎切换一期）。 */
    const val CLAUDE_CODE = "claude_code"

    /** OpenAI Codex CLI 桥接（引擎切换三期）。 */
    const val CODEX = "codex"

    /** OpenCode CLI 桥接（引擎切换三期）。 */
    const val OPENCODE = "opencode"

    /** 全部外部引擎：这些引擎的上下文由 CLI 自己管理，App 侧不会触发上下文压缩。 */
    val ALL = setOf(CLAUDE_CODE, CODEX, OPENCODE)

    /**
     * 引擎展示名（产品名，中英一致）；非引擎 id 返回 null。
     * 外部引擎的用量记录以引擎标识作为 providerId，展示时用它翻译，避免直接露出内部 id。
     */
    fun displayName(id: String?): String? = when (id) {
        CLAUDE_CODE -> "Claude Code"
        CODEX -> "Codex"
        OPENCODE -> "OpenCode"
        else -> null
    }
}
