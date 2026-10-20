package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

/**
 * 解析单个专家团定义文件（`teams/<name>.md`）：YAML frontmatter 为配置，正文为团长提示词。
 * 与 [com.aicode.feature.agent.domain.subagent.AgentDefinitionParser] 同一套 frontmatter 约定。
 */
object TeamDefinitionParser {
    private const val TAG = "TeamDefinitionParser"
    private const val MAX_DESC_CHARS = 500

    /** 正文为空视为非法定义（专家团必须有团长提示词），返回 null。定义文件经 [provider] 以容器路径读取。 */
    fun parse(provider: FileAccessProvider, filePath: String): TeamDefinition? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse {
            FileLogger.w(TAG, "读取专家团定义失败: $filePath", it)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)
        val prompt = body.trim()
        if (prompt.isEmpty()) return null

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')

        return TeamDefinition(
            name = name,
            description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS),
            members = stringList(frontmatter["members"]),
            prompt = prompt,
            filePath = filePath
        )
    }

    /** 同时接受 YAML 列表与逗号分隔字符串两种写法。 */
    private fun stringList(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析专家团定义 YAML 失败", e)
            emptyMap()
        }
        return map to rest
    }
}
