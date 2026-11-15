package com.aicode.feature.agent.domain.engine

/**
 * 解析 Codex 用户 `~/.codex/config.toml` 里的 `developer_instructions`。
 *
 * Codex 的 `-c` 是覆盖语义：直接下发 App 指令会把用户自定义指令整段替换掉。因此只有能可靠
 * 解析成「单行基本字符串」时才做拼接；格式复杂（多行串 / 字面量串 / 非法转义 / 数组等）一律
 * 标记为不可解析，由调用方跳过本回合注入——宁可少注入，也不毁用户配置。
 *
 * 抽成纯函数便于单测。上一版直接写在桥里，踩了两个真 bug：
 * 1. 只要 config.toml 出现该词（哪怕只是注释）且键不存在，就被当成「无法解析」→ 编排/语言/知识注入全跳过；
 * 2. 多行串 `"""..."""` 被单行正则解析成空串 → 反而用 App 指令覆盖了用户指令。
 */
internal object CodexUserInstructions {

    /** present 表示配置里出现过该键；text 为可安全解析的值（解析不了为 null）。 */
    data class Existing(val present: Boolean, val text: String?)

    /** 只认行首（可带缩进）的非注释键赋值，避免把 `# developer_instructions = ...` 当成真键。 */
    private val ASSIGNMENT = Regex("""(?m)^[ \t]*developer_instructions[ \t]*=""")

    /** 单行基本字符串：双引号包裹，允许转义，尾部可带注释。 */
    private val SINGLE_LINE = Regex("""^"((?:[^"\\]|\\.)*)"[ \t]*(?:#.*)?$""")

    fun parse(content: String): Existing {
        val match = ASSIGNMENT.find(content) ?: return Existing(present = false, text = null)
        // TOML 单行字符串不能跨行；先截到本行末尾再判断，多行串会以 """ / ''' 开头
        val start = match.range.last + 1
        val lineEnd = content.indexOfAny(charArrayOf('\n', '\r'), start)
            .let { if (it < 0) content.length else it }
        val value = content.substring(start, lineEnd).trim()

        if (value.startsWith("\"\"\"") || value.startsWith("'''")) return Existing(true, null)
        val single = SINGLE_LINE.find(value) ?: return Existing(true, null)
        val decoded = decodeBasicString(single.groupValues[1]) ?: return Existing(true, null)
        return Existing(true, decoded)
    }

    /** 按 TOML 基本串规则解码转义；出现非法转义返回 null（交给调用方走「跳过注入」）。 */
    private fun decodeBasicString(raw: String): String? {
        if (raw.indexOf('\\') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            i++
            if (i >= raw.length) return null
            when (raw[i]) {
                'b' -> out.append('\b')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'f' -> out.append('\u000C')
                'r' -> out.append('\r')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                'u' -> {
                    val cp = readHex(raw, i + 1, 4) ?: return null
                    out.append(cp.toChar())
                    i += 4
                }
                'U' -> {
                    val cp = readHex(raw, i + 1, 8) ?: return null
                    if (cp > 0x10FFFF) return null
                    out.appendCodePoint(cp)
                    i += 8
                }
                else -> return null
            }
            i++
        }
        return out.toString()
    }

    private fun readHex(source: String, start: Int, length: Int): Int? {
        if (start + length > source.length) return null
        return source.substring(start, start + length).toIntOrNull(16)
    }
}
