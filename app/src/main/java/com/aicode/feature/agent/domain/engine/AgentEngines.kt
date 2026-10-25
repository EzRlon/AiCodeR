package com.aicode.feature.agent.domain.engine

/** 会话执行引擎标识（chat_sessions.engine 列的取值域）。 */
object AgentEngines {
    /** Claude Code CLI 桥接（引擎切换一期）。 */
    const val CLAUDE_CODE = "claude_code"

    /** OpenAI Codex CLI 桥接（引擎切换三期）。 */
    const val CODEX = "codex"
}
