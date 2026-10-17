package com.aicode.feature.store.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 统一模型（UI 层使用的商店条目） ──────────────────────────────────

/** 商店条目类型：技能 / MCP 服务器。 */
enum class StoreKind { SKILL, MCP, EXPERT }

/**
 * 内置专家团条目（随版本内置的 agents 定义，一键安装到 ~/.aicode/agents）。
 * 外部暂无成熟的专家团商店可接入，商店以本地内置资产为来源。
 */
data class ExpertStoreItem(
    val fileName: String,
    val name: String,
    val description: String,
    val installed: Boolean
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
    val stdioOnly: Boolean = false
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
