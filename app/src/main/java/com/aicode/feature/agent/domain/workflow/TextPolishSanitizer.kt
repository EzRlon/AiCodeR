package com.aicode.feature.agent.domain.workflow

/**
 * 把模型返回的「润色结果」清理成可以直接放回输入框的纯文本。
 *
 * 模型即使被要求「只输出改写后的文本」，仍常见三种装饰：整体包一层 ``` 代码围栏、
 * 加「润色后：」之类的前缀、或者给整段加英文引号。这些直接塞进输入框会显得很傻，
 * 所以统一在这里剥掉。
 *
 * 只做**外层装饰**的清理，不动正文：正文里的引号、代码块、列表都必须原样保留。
 */
internal fun sanitizePolishedText(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null

    // 整体被 ``` 包住（可能带语言标识）：去掉围栏，保留内部内容。
    if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
        val firstNewline = text.indexOf('\n')
        if (firstNewline in 1 until text.length - 3) {
            text = text.substring(firstNewline + 1, text.length - 3).trim()
        }
    }

    // 常见前缀装饰。
    for (prefix in POLISH_PREFIXES) {
        if (text.startsWith(prefix)) {
            text = text.removePrefix(prefix).trim()
            break
        }
    }

    // 整段被引号包住（模型有时把结果当字符串输出）。
    if (text.length >= 2) {
        val first = text.first()
        val last = text.last()
        if ((first == '"' && last == '"') || (first == '“' && last == '”')) {
            text = text.substring(1, text.length - 1).trim()
        }
    }

    return text.ifBlank { null }
}

private val POLISH_PREFIXES = listOf(
    "润色后：", "润色后:", "润色结果：", "润色结果:",
    "改写后：", "改写后:", "修改后：", "修改后:",
    "Polished:", "Polished：", "Here is the polished version:", "Rewritten:"
)
