package com.aicode.feature.agent.domain.workflow

/**
 * 「继续」语义：用户在上一次回合被中断后说「继续」，期望的是从断点接着做并修掉中断造成的
 * 半成品/错误，而不是把同一句话当成新任务重头再来。
 *
 * 外部引擎的 `--resume` 只恢复对话历史，不会自动检查工作区里的半截改动；这里在真正发给
 * 引擎的请求前追加一条短指令，要求它先核对当前状态（git status / 未完成待办 / 上次输出），
 * 再续做并修复，避免重复施工或漏掉中断点。持久化的用户消息仍保留原文，指令只进模型请求。
 */
object ContinuationDirective {

    private val CONTINUE_ONLY = Regex(
        "^\\s*(继续|接着|接着做|接着干|continue|go\\s+on)\\s*[。.!！]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    private const val DIRECTIVE =
        "【中断续跑指令】这是上一次回合被中断后的「继续」。请先从当前工作区状态判断中断点" +
            "（如 git status、未完成的待办、上次输出到哪里），接着完成未完成的工作，并主动修复" +
            "中断造成的半成品、报错或未落盘改动；不要重头开始，也不要重复已经完成的部分。" +
            "完成后简要说明从哪个断点接续、修了哪些中断遗留问题。"

    fun isContinuation(request: String): Boolean = CONTINUE_ONLY.matches(request.trim())

    /** 非「继续」请求原样返回；「继续」请求在正文前追加续跑指令。 */
    fun applyIfNeeded(request: String): String =
        if (isContinuation(request)) "$DIRECTIVE\n\n$request" else request
}
