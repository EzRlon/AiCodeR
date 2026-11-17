package com.aicode.feature.plugin.domain

import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.Json

/** 清单解析结果。失败时带回**全部**问题，而不是只报第一条——安装前一次性说清。 */
sealed interface PluginManifestResult {
    data class Valid(val manifest: PluginManifest) : PluginManifestResult
    data class Invalid(val errors: List<String>) : PluginManifestResult
}

/**
 * `plugin.json` 的解析与校验。纯函数、不做任何 IO，便于单测。
 *
 * 校验口径偏严：清单是插件与宿主之间唯一的契约，写错的地方在安装前报出来，
 * 比装完运行时莫名其妙失败要好。
 */
object PluginManifestParser {

    /** 宿主当前支持的插件 API 版本。 */
    const val SUPPORTED_API_VERSION = 1

    const val FILE_NAME = "plugin.json"

    private const val MAX_ID_LENGTH = 64
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)+$")

    /** 允许释放内容的容器根；插件不得往这之外写。 */
    private val ALLOWED_TARGET_ROOTS = listOf("~/.aicode/", "~/workspace/")

    private val MCP_TOOL_NAME = Regex("^[A-Za-z0-9_.-]+$")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(text: String): PluginManifestResult {
        val manifest = runCatching { json.decodeFromString<PluginManifest>(text) }
            .getOrElse { e ->
                return PluginManifestResult.Invalid(listOf("清单不是合法 JSON: ${e.message}"))
            }

        val errors = buildList {
            validateIdentity(manifest, this)
            validateCapabilities(manifest.capabilities, this)
            validateSlots(manifest.slots, this)
        }
        return if (errors.isEmpty()) {
            PluginManifestResult.Valid(manifest)
        } else {
            PluginManifestResult.Invalid(errors)
        }
    }

    /**
     * 从插件目录读一份清单；读不到或解析失败都归为 Invalid。
     * 路径经 [FileAccessProvider] 访问，本地与远程 SSH 同一套逻辑。
     */
    fun parseDirectory(fileAccess: FileAccessProvider, pluginDir: String): PluginManifestResult {
        val file = pluginDir.trimEnd('/') + "/" + FILE_NAME
        if (!fileAccess.isFile(file)) return PluginManifestResult.Invalid(listOf("缺少 $FILE_NAME"))
        return runCatching { parse(fileAccess.readFile(file)) }
            .getOrElse { e ->
                PluginManifestResult.Invalid(listOf("读取 $FILE_NAME 失败: ${e.message}"))
            }
    }

    private fun validateIdentity(m: PluginManifest, errors: MutableList<String>) {
        if (m.id.isBlank() || m.id.length > MAX_ID_LENGTH || !ID_PATTERN.matches(m.id)) {
            errors += "id 非法（需为反向域名小写形式，如 com.aicode.advisor）: ${m.id}"
        }
        if (m.name.isBlank()) errors += "name 不能为空"
        if (m.version.isBlank()) errors += "version 不能为空"
        if (m.apiVersion != SUPPORTED_API_VERSION) {
            errors += "插件要求宿主 API v${m.apiVersion}，当前支持 v$SUPPORTED_API_VERSION"
        }
        m.permissions.filter { PluginPermission.fromId(it) == null }
            .forEach { errors += "未知权限: $it" }
        if (m.permissions.distinct().size != m.permissions.size) errors += "permissions 存在重复项"
    }

    private fun validateCapabilities(c: PluginCapabilities, errors: MutableList<String>) {
        c.assets.forEach { asset ->
            if (!isSafeRelativePath(asset.from)) {
                errors += "assets.from 必须是插件目录内的相对路径: ${asset.from}"
            }
            if (ALLOWED_TARGET_ROOTS.none { asset.to.startsWith(it) }) {
                errors += "assets.to 必须落在 ~/.aicode/ 或 ~/workspace/ 之内: ${asset.to}"
            }
        }

        c.provision?.let { p ->
            if (p.scenario.isBlank()) errors += "provision.scenario 不能为空"
            if (p.script.isBlank()) {
                errors += "provision.script 不能为空（安装脚本必须在插件包内）"
            } else if (!isSafeRelativePath(p.script)) {
                errors += "provision.script 必须是相对路径: ${p.script}"
            }
            p.marker?.let {
                if (ALLOWED_TARGET_ROOTS.none { root -> it.startsWith(root) }) {
                    errors += "provision.marker 必须落在 ~/.aicode/ 或 ~/workspace/ 之内: $it"
                }
            }
        }

        val seenServers = mutableSetOf<String>()
        c.mcp.forEach { mcp ->
            if (mcp.name.isBlank()) errors += "mcp.name 不能为空"
            if (!seenServers.add(mcp.name)) errors += "mcp 名称重复: ${mcp.name}"
            mcp.enabledTools.filterNot { MCP_TOOL_NAME.matches(it) }
                .forEach { errors += "mcp.enabledTools 含非法工具名: $it（${mcp.name}）" }
            when (mcp.transport) {
                McpSpec.TRANSPORT_STDIO -> if (mcp.command.isNullOrBlank()) {
                    errors += "stdio 型 MCP 必须提供 command: ${mcp.name}"
                }
                McpSpec.TRANSPORT_HTTP -> if (mcp.url.isNullOrBlank()) {
                    errors += "http 型 MCP 必须提供 url: ${mcp.name}"
                }
                else -> errors += "不支持的 MCP transport: ${mcp.transport}"
            }
        }

        c.hooks.forEach { hook ->
            if (hook.event.isBlank()) errors += "hook.event 不能为空"
            if (!isSafeRelativePath(hook.script)) {
                errors += "hook.script 必须是插件目录内的相对路径: ${hook.script}"
            }
            if (hook.timeoutMs <= 0) errors += "hook.timeoutMs 必须为正数: ${hook.script}"
        }

        (c.skills + c.agents + c.teams + c.scripts + c.prompts)
            .filterNot(::isSafeRelativePath)
            .forEach { errors += "内容目录必须是插件目录内的相对路径: $it" }
    }

    private fun validateSlots(slots: List<PluginSlotSpec>, errors: MutableList<String>) {
        slots.forEach { spec ->
            if (PluginSlot.fromId(spec.slot) == null) {
                errors += "未知插槽: ${spec.slot}"
            }
            val position = SlotPosition.fromId(spec.position)
            if (position == null) {
                errors += "未知插槽位置: ${spec.position}"
                return@forEach
            }
            // append 天然排在末尾，带锚点属于自相矛盾的声明
            if (position == SlotPosition.APPEND && !spec.anchor.isNullOrBlank()) {
                errors += "position=append 时不应再指定 anchor: ${spec.slot}"
            }
            if (position != SlotPosition.APPEND && spec.anchor.isNullOrBlank()) {
                errors += "position=${spec.position} 必须指定 anchor: ${spec.slot}"
            }
        }
    }

    /** 相对路径且不含 `..` 段：挡住「插件 zip 解压把文件写到插件目录之外」。 */
    internal fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("~")) return false
        if (path.contains('\\')) return false
        return path.split('/').none { it == ".." }
    }
}
