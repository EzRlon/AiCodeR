package com.aicode.feature.plugin.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.plugin.data.PluginStateStore
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 清理「旧版随包内置插件」留下的残留。
 *
 * 背景：顾问团 / 分析台曾由 App 在启动时把清单释放进 `~/.aicode/plugins`（旧清单用
 * `builtinScenario` 指向宿主内置场景），默认停用。现在两者改为独立插件包，装、卸、启停
 * 全部交给插件体系，App 不再随包释放任何插件目录。但老设备升级上来后：
 *
 * 1. `~/.aicode/plugins/com.aicode.{multillm,rea}` 里还留着旧清单——新解析器要求
 *    `provision.script`，旧清单没有，于是插件页凭空多出两条「目录没能加载」的报错；
 * 2. 旧流程启用插件时直写的 `multi-llm` / `rea` MCP 条目还留在全局配置里，MCP 页残留。
 *
 * 因此启动时做一次**保守的**清理：
 * - 只删「受管 id + 目录里只有 plugin.json + 清单含 `builtinScenario` 标记」的目录，
 *   用户自己塞进插件目录的任何文件都会让整个目录跳过；
 * - 只摘「没有可用的同名插件目录」时、且参数与旧内置预设完全吻合的 MCP 条目，
 *   用户自建的 MCP 服务（哪怕同名但命令不同）不受影响。
 *
 * 幂等：清理完成后条件不再成立，后续启动是空操作。
 */
object LegacyPluginCleanup {

    private const val TAG = "LegacyPluginCleanup"
    private const val ROOT = "~/.aicode/plugins"
    private const val MANIFEST = "plugin.json"

    /** 旧版内置清单的独有字段：新格式一律用 `provision.script`，不会出现它。 */
    private const val LEGACY_BUILTIN_MARK = "\"builtinScenario\""

    private data class Managed(val pluginId: String, val matches: (McpServerConfig) -> Boolean)

    private val MANAGED = listOf(
        Managed(PluginIds.MULTILLM) { it.isLegacyMultiLlmPreset() },
        Managed(PluginIds.REA) { it.isLegacyReaPreset() }
    )

    /**
     * 返回本次清掉的插件 id，供日志与测试断言。
     *
     * [pluginsRoot] 仅测试注入临时目录用；生产一律走默认的容器路径。
     */
    suspend fun run(
        fileAccess: FileAccessProvider,
        mcpConfig: McpConfigRepository,
        pluginsRoot: String = ROOT
    ): List<String> {
        val stateStore = PluginStateStore(fileAccess, pluginsRoot)
        val removed = mutableListOf<String>()

        MANAGED.forEach { managed ->
            val dir = "$pluginsRoot/${managed.pluginId}"
            val deleted = runCatching { removeLegacyDir(fileAccess, dir) }
                .onFailure { FileLogger.w(TAG, "清理旧内置插件目录失败: $dir", it) }
                .getOrDefault(false)
            if (deleted) {
                removed += managed.pluginId
                runCatching { stateStore.remove(managed.pluginId) }
                FileLogger.i(TAG, "已清理旧版内置插件目录: $dir")
            }
        }

        val servers = mcpConfig.getGlobalServers()
        val kept = servers.filterNot { server ->
            MANAGED.any { managed ->
                managed.matches(server) &&
                    !hasUsablePluginDir(fileAccess, pluginsRoot, managed.pluginId)
            }
        }
        if (kept.size != servers.size) {
            runCatching { mcpConfig.setGlobalServers(kept) }
                .onFailure { FileLogger.w(TAG, "清理旧内置插件的 MCP 残留失败", it) }
                .onSuccess {
                    FileLogger.i(
                        TAG,
                        "已清理旧版内置插件 MCP 残留: " +
                            servers.map { it.name }.subtract(kept.map { it.name }.toSet()).joinToString()
                    )
                }
        }
        return removed
    }

    /**
     * 只删「目录里仅有 plugin.json、且清单是旧内置格式」的目录。
     * 任何额外文件都视为用户内容，整目录跳过，宁可留着让用户手动处理也不误删。
     */
    private fun removeLegacyDir(fileAccess: FileAccessProvider, dir: String): Boolean {
        if (!fileAccess.exists(dir) || !fileAccess.isDirectory(dir)) return false
        val entries = fileAccess.listFiles(dir)
        if (entries.isEmpty() || entries.any { it.name != MANIFEST }) return false
        val manifest = fileAccess.readFile("$dir/$MANIFEST")
        if (!manifest.contains(LEGACY_BUILTIN_MARK)) return false
        fileAccess.deleteRecursively(dir)
        return true
    }

    /** 目录是否是「当前版本可用的插件」：存在清单且不是旧内置格式。 */
    private fun hasUsablePluginDir(
        fileAccess: FileAccessProvider,
        pluginsRoot: String,
        pluginId: String
    ): Boolean =
        runCatching {
            val manifestPath = "$pluginsRoot/$pluginId/$MANIFEST"
            fileAccess.exists(manifestPath) &&
                !fileAccess.readFile(manifestPath).contains(LEGACY_BUILTIN_MARK)
        }.getOrDefault(false)

    /** 旧版 multi-llm 预设：python3 跑 `~/.aicode/multi-llm/server.py`。 */
    private fun McpServerConfig.isLegacyMultiLlmPreset(): Boolean =
        name == "multi-llm" &&
            command == "python3" &&
            args.any { it.endsWith("/.aicode/multi-llm/server.py") }

    /** 旧版 REA 预设：npx 跑 `rea-agents@*` 的 mcp 子命令。 */
    private fun McpServerConfig.isLegacyReaPreset(): Boolean =
        name == "rea" &&
            command == "npx" &&
            args.any { it.startsWith("rea-agents@") } &&
            args.lastOrNull() == "mcp"
}
