package com.aicode.feature.store.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 统一模型（UI 层使用的商店条目） ──────────────────────────────────

/** 商店条目类型：技能 / MCP 服务器 / 专家团 / 引擎插件。 */
enum class StoreKind { SKILL, MCP, EXPERT, PLUGIN }

/**
 * 引擎插件可安装的目标引擎。三个引擎的插件机制互不兼容：
 * Claude Code 与 Codex 都走 marketplace 源，但源格式不同；OpenCode 无 marketplace，只能装 npm 包。
 */
enum class EngineTarget(val label: String, val cli: String) {
    DEEPSEEK("DeepSeek", "dsh"),
    CLAUDE_CODE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    OPENCODE("OpenCode", "opencode")
}

/**
 * 引擎插件条目。
 *
 * [installRef] 是传给对应引擎 CLI 的插件标识：
 * - Claude Code / Codex：`<plugin>@<marketplace>`（如 `formatter@acme-tools`）
 * - OpenCode：npm 包名或 `github:org/repo`
 */
data class PluginStoreItem(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val engines: List<EngineTarget>,
    val installRef: String,
    /** 安装所需的 marketplace 源；OpenCode 为 null。 */
    val marketplaceSource: String? = null,
    /** marketplace 显示名，安装时以 `plugin@marketplace` 形式引用。 */
    val marketplaceName: String? = null
)

/**
 * 内置专家团条目：成套团队（团队定义 + 成员专家），一键安装到 ~/.aicode/teams 与 ~/.aicode/agents。
 * members 对应团队定义的成员子代理名，安装时一并补齐其 agent 定义。
 */
@Serializable
data class TeamStoreItem(
    val fileName: String,
    val name: String,
    val description: String,
    val members: List<String>,
    val installed: Boolean,
    /** 在线市场条目（非内置 assets）。 */
    val online: Boolean = false,
    /** 在线条目的插件目录（安装时据此下载成员定义）。 */
    val pluginBase: String? = null,
    /** 定义文件自带的中文展示名（内置团队），避免离线时退回英文标识。 */
    val displayName: String? = null
)

/**
 * 统一商店条目：两个源（claudemarket.ai 技能 / 官方 MCP Registry）映射到同一形态，
 * 源缺字段时对应 UI 元素自动隐藏。
 */
data class StoreItem(
    /** 源内唯一 id（技能=slug，MCP=registry name）。 */
    val id: String,
    val kind: StoreKind,
    val name: String,
    val description: String = "",
    val author: String = "",
    val category: String = "",
    val popularity: Long = 0,
    /** 源站详情页链接（浏览器打开）。 */
    val detailUrl: String = "",
    val version: String = "",
    val license: String = "",
    /** MCP：远程地址（streamable-http / sse 型可直接配置启用）。 */
    val remoteUrl: String? = null,
    val transportType: String = "",
    /** MCP：是否仅提供 stdio（npm/docker）包（v1 不支持一键安装）。 */
    val stdioOnly: Boolean = false,
    /** 来源商店源 id；多源聚合时用于展示与安装确认。 */
    val sourceId: String = "claudemarket",
    /** 来源展示名。 */
    val sourceName: String = "ClaudeMarket",
    /** 来源是否可信；不可信源安装前需二次确认。 */
    val sourceTrusted: Boolean = true
)

// ── claudemarket.ai DTO ────────────────────────────────────────────

@Serializable
data class CmSkillsResponse(
    val count: Int = 0,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val results: List<CmSkill> = emptyList()
)

@Serializable
data class CmSkill(
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val source: String = "",
    val tags: List<String> = emptyList(),
    val author: String = "",
    val version: String = "",
    val license: String = "",
    val url: String = ""
)

@Serializable
data class CmSkillDetail(
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val author: String = "",
    val version: String = "",
    val license: String = "",
    val docsUrl: String = "",
    /** 技能本体的仓库地址（github.com/{owner}/{repo}/tree/{branch}/{path} 或 .../blob/...）。 */
    val repoUrl: String = "",
    val url: String = ""
)

@Serializable
data class CmSearchResponse(
    val query: String = "",
    val type: String = "",
    val count: Int = 0,
    val results: List<CmSearchItem> = emptyList()
)

@Serializable
data class CmSearchItem(
    val type: String = "",
    val name: String = "",
    val description: String = "",
    val url: String = "",
    val category: String = "",
    val popularity: Long = 0
)

// ── 官方 MCP Registry DTO ──────────────────────────────────────────

@Serializable
data class McpRegistryResponse(
    val servers: List<McpRegistryEntry> = emptyList(),
    val metadata: McpRegistryMetadata? = null
)

@Serializable
data class McpRegistryMetadata(
    @SerialName("next_cursor") val nextCursor: String? = null,
    val count: Int = 0
)

@Serializable
data class McpRegistryEntry(
    val server: McpRegistryServer = McpRegistryServer()
)

@Serializable
data class McpRegistryServer(
    val name: String = "",
    val description: String = "",
    val title: String = "",
    val version: String = "",
    val remotes: List<McpRemote> = emptyList(),
    val repository: McpRepository? = null
)

@Serializable
data class McpRemote(
    val type: String = "",
    val url: String = ""
)

@Serializable
data class McpRepository(
    val url: String = "",
    val source: String = ""
)

// ── 映射到统一模型 ────────────────────────────────────────────────

fun CmSkill.toStoreItem(): StoreItem = StoreItem(
    id = slug,
    kind = StoreKind.SKILL,
    name = name,
    description = description,
    author = author,
    category = category,
    detailUrl = url,
    version = version,
    license = license
)

fun McpRegistryServer.toStoreItem(): StoreItem {
    // 优先 streamable-http / sse 远程型（可直接配置）；否则标记 stdio-only
    val remote = remotes.firstOrNull { it.type == "streamable-http" } ?: remotes.firstOrNull()
    return StoreItem(
        id = name,
        kind = StoreKind.MCP,
        name = title.ifBlank { name },
        description = description,
        author = repository?.url?.substringAfter("github.com/", "")?.substringBefore("/").orEmpty(),
        category = "",
        detailUrl = repository?.url.orEmpty(),
        version = version,
        remoteUrl = remote?.url?.takeIf { it.isNotBlank() },
        transportType = remote?.type.orEmpty(),
        stdioOnly = remote == null
    )
}

/** MCP Registry 的 nextCursor 解码（响应 metadata）；无则 null。 */
fun McpRegistryResponse.nextCursorOrNull(): String? = metadata?.nextCursor?.takeIf { it.isNotBlank() }
