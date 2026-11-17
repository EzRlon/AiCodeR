package com.aicode.feature.settings.domain.feedback

/**
 * 反馈附带的日志片段脱敏。
 *
 * 日志会公开到 GitHub Issue，常见的密钥形态必须在这里抹掉：API Key（`sk-...`）、
 * `key/token/secret/password=...` 赋值、`Authorization: Bearer ...`。脱敏是「尽力而为」，
 * 页面上仍提示用户自行检查，不作为唯一防线。
 */
object LogExcerptSanitizer {

    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_-]{8,}""")
    private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    /**
     * 赋值型密钥。这里**不**包含 `authorization`：否则 `Authorization: Bearer ***` 里的
     * `Bearer` 会被它二次吞掉，反而把脱敏标记破坏掉；Authorization 头由 [BEARER] 负责。
     */
    private val SECRET_ASSIGN = Regex(
        // 键名右侧允许一个引号：日志里的 JSON 形态是 `"token":"abc"`，没有这一步会漏掉
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|secret|password|passwd)\b"?(\s*[:=]\s*)("?)([^\s"',;]{4,})"""
    )

    /** HTTP Basic 认证头。 */
    private val BASIC_AUTH = Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]{8,}""")

    /**
     * @param maxLines 只保留末尾若干行（日志越新越有用）
     * @param maxChars 字符上限，避免把整个日志文件塞进反馈
     */
    fun sanitize(raw: String, maxLines: Int = 200, maxChars: Int = 6000): String {
        // 先按字符粗截尾部（留 4 倍余量），再切行/跑正则：日志文件可达数 MB，
        // 一次性物化全部行并在全文上跑五次正则纯属浪费（用户只会看到尾部片段）。
        val tail = if (raw.length > maxChars * 4) raw.takeLast(maxChars * 4) else raw
        var text = tail.lineSequence().toList().takeLast(maxLines).joinToString("\n")
        if (text.length > maxChars) text = text.takeLast(maxChars)
        text = text
            .replace(SK_KEY, "sk-***")
            .replace(BEARER, "Bearer ***")
            .replace(BASIC_AUTH, "Basic ***")
            .replace(SECRET_ASSIGN) { match ->
                // 保留键名与分隔符，只抹掉值；保留开引号便于阅读
                "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}***"
            }
        return text.trim()
    }
}
