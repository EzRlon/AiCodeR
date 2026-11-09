package com.aicode.feature.agent.domain.engine

/**
 * Codex 的 `-c key=value` 参数值转义。
 *
 * Codex CLI 没有 `--mcp-config` 这类「传文件」的口子，MCP 服务器只能靠 `-c` 内联 TOML 片段挂载，
 * 所以每个值都必须转义正确——**错一个字符，整条 `-c` 参数解析失败**，
 * 表现是「MCP 没挂上」或「引擎起不来」，从现象很难反推回转义问题。
 *
 * 从 [CodexBridge] 抽出来（原是 private 方法）以便单测：这是三个引擎参数拼接里最容易出错的一环。
 */
object CliToml {

    /** 转成 TOML 双引号字符串：反斜杠、双引号、换行、回车都要转义。 */
    fun string(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
}
