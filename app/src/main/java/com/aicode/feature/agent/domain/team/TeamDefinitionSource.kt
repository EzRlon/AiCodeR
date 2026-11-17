package com.aicode.feature.agent.domain.team

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 专家团定义来源：一个目录下的 `*.md`，每个文件一个专家团。 */
interface TeamDefinitionSource {
    fun listDefinitions(): List<TeamDefinition>
}

/**
 * 目录扫描：只取顶层 `*.md`；目录不存在或异常时降级为空列表并记日志，不向上抛。
 *
 * 按文件修改时间升序（同时间再按名称），使新安装的定义排在列表末尾——用户在商店装完
 * 立刻打开选择器时，新条目就出现在最下方，而不是按名称插进中间。
 *
 * 已知限制：编辑已有定义会刷新 mtime，该专家团因此移到末尾；远程 SFTP 的 mtime 只到秒，
 * 同一秒内写入的多个文件会回退到名称序。
 */
internal object TeamDirectoryScanner {
    private const val TAG = "TeamDirectoryScanner"

    fun scan(provider: FileAccessProvider, root: String): List<TeamDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .sortedWith(compareBy({ it.lastModified }, { it.name.lowercase() }))
            .mapNotNull { entry -> TeamDefinitionParser.parse(provider, "$base/${entry.name}") }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描专家团定义目录失败: $root", e)
        emptyList()
    }
}
