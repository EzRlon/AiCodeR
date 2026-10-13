package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 编队定义来源：一个目录下的 `*.md`，每个文件一个编队。 */
interface TeamDefinitionSource {
    fun listDefinitions(): List<TeamDefinition>
}

/** 目录扫描：只取顶层 `*.md`；目录不存在或异常时降级为空列表并记日志，不向上抛。 */
internal object TeamDirectoryScanner {
    private const val TAG = "TeamDirectoryScanner"

    fun scan(provider: FileAccessProvider, root: String): List<TeamDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { entry -> TeamDefinitionParser.parse(provider, "$base/${entry.name}") }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描编队定义目录失败: $root", e)
        emptyList()
    }
}
