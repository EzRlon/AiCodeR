package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.hooks.domain.HookDefinition
import com.aicode.feature.hooks.domain.HooksConfig
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 宿主共享配置（MCP 服务表 / 钩子表）的读写口。
 *
 * 抽成接口不是为了将来换实现，而是为了让 [PluginResourceReleaser] 的装配与摘除能脱离
 * Hilt 依赖图单测——撞名跳过、账本记账、摘除不误删这些行为只有测过才敢说对。
 */
interface PluginHostConfig {

    suspend fun mcpServers(): List<McpServerConfig>

    suspend fun setMcpServers(servers: List<McpServerConfig>)

    fun hooks(): Map<String, List<HookDefinition>>

    fun setHooks(hooks: Map<String, List<HookDefinition>>)
}

/** 生产实现：MCP 走 [McpConfigRepository]（全局层），钩子走 `~/.aicode/hooks.json`。 */
class DefaultPluginHostConfig(
    private val mcpConfig: McpConfigRepository,
    private val fileAccess: FileAccessProvider
) : PluginHostConfig {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun mcpServers(): List<McpServerConfig> = mcpConfig.getGlobalServers()

    override suspend fun setMcpServers(servers: List<McpServerConfig>) =
        mcpConfig.setGlobalServers(servers)

    override fun hooks(): Map<String, List<HookDefinition>> = runCatching {
        if (!fileAccess.exists(HOOKS_PATH)) {
            emptyMap()
        } else {
            json.decodeFromString<HooksConfig>(fileAccess.readFile(HOOKS_PATH)).hooks
        }
    }.getOrElse { emptyMap() }

    override fun setHooks(hooks: Map<String, List<HookDefinition>>) {
        fileAccess.writeFile(HOOKS_PATH, json.encodeToString(HooksConfig(hooks)))
    }

    private companion object {
        const val HOOKS_PATH = "~/.aicode/hooks.json"
    }
}
