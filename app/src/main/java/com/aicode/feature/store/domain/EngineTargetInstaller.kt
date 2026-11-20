package com.aicode.feature.store.domain

import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外部引擎（Claude Code / Codex / OpenCode）侧的商店安装通道：
 * 经 [CommandEngine] 在容器内落盘到各引擎官方配置目录，与内置安装互不影响。
 * 所有写盘经 base64 编码传输，避免 shell 转义与多行内容问题。
 */
@Singleton
class EngineTargetInstaller @Inject constructor(
    private val engine: CommandEngine
) {
    private companion object {
        const val TAG = "EngineTargetInstaller"
        const val WRITE_TIMEOUT_MS = 20_000L
        const val CLAUDE_SKILLS_DIR = "/root/.claude/skills"
        const val CLAUDE_CONFIG = "/root/.claude.json"
        const val CLAUDE_AGENTS_DIR = "/root/.claude/agents"
        const val CODEX_CONFIG = "/root/.codex/config.toml"
        const val OPENCODE_CONFIG = "/root/.config/opencode/opencode.json"
    }

    /** 该条目能否装到 [target]：可装返回 null，否则返回给用户看的原因。 */
    fun unsupportedReason(item: StoreItem, target: EngineTarget): String? {
        if (target == EngineTarget.CLAUDE_CODE) return null
        return when (item.kind) {
            StoreKind.SKILL -> "${target.label} 官方暂不支持技能文件，请在内置引擎安装"
            StoreKind.MCP -> null
            StoreKind.EXPERT -> "${target.label} 官方暂不支持专家团定义，请在内置引擎安装"
            StoreKind.PLUGIN -> null
        }
    }

    /** 把技能文件写到 Claude 官方技能目录（~/.claude/skills/name/）。 */
    suspend fun installSkill(plan: SkillInstallPlan): String {
        val name = sanitizeName(plan.item.id)
        val root = "$CLAUDE_SKILLS_DIR/$name"
        for ((path, bytes) in plan.files) {
            val rel = path.trim('/').replace("..", "")
            if (rel.isEmpty()) continue
            write("$root/$rel", bytes.decodeToString())
        }
        FileLogger.i(TAG, "已安装技能到 Claude Code: $name（${plan.files.size} 个文件）")
        return name
    }

    /** 把远程 MCP 写入 [target] 的官方配置文件（合并写入，不覆盖既有条目）。 */
    suspend fun installMcp(item: StoreItem, target: EngineTarget): String {
        val url = item.remoteUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("该服务器仅提供本地包（npm/docker），暂不支持一键安装")
        val name = sanitizeName(item.id)
        when (target) {
            EngineTarget.CLAUDE_CODE -> {
                val existing = read(CLAUDE_CONFIG)
                val root = if (existing.isNullOrBlank()) JSONObject() else JSONObject(existing)
                val servers = root.optJSONObject("mcpServers") ?: JSONObject()
                servers.put(name, JSONObject().put("type", "http").put("url", url))
                root.put("mcpServers", servers)
                write(CLAUDE_CONFIG, root.toString(2))
            }
            EngineTarget.CODEX -> {
                val existing = read(CODEX_CONFIG) ?: ""
                val section = "[mcp_servers.$name]\nurl = \"${tomlEscape(url)}\"\n"
                val updated = removeTomlSection(existing, "mcp_servers.$name") + "\n" + section
                write(CODEX_CONFIG, updated)
            }
            EngineTarget.OPENCODE -> {
                val existing = read(OPENCODE_CONFIG)
                val root = if (existing.isNullOrBlank()) JSONObject() else JSONObject(existing)
                val mcp = root.optJSONObject("mcp") ?: JSONObject()
                mcp.put(
                    name,
                    JSONObject().put("type", "remote").put("url", url).put("enabled", true)
                )
                root.put("mcp", mcp)
                write(OPENCODE_CONFIG, root.toString(2))
            }
        }
        FileLogger.i(TAG, "已安装 MCP 到 ${target.label}: $name -> $url")
        return name
    }

    /** 把专家团成员定义写到 Claude 官方 agents 目录（每个成员一个 .md 文件）。 */
    suspend fun installTeam(teamName: String, agents: List<Pair<String, String>>): String {
        for ((fileName, body) in agents) {
            val safe = fileName.substringAfterLast('/').replace("..", "")
            if (safe.isBlank()) continue
            write("$CLAUDE_AGENTS_DIR/$safe", body)
        }
        FileLogger.i(TAG, "已安装专家团到 Claude Code: $teamName（${agents.size} 个成员）")
        return teamName
    }

    private suspend fun read(path: String): String? {
        val result = engine.runCommandSyncWithExit("cat $path", timeoutMs = WRITE_TIMEOUT_MS)
        return result.output.takeIf { result.exitCode == 0 }
    }

    private suspend fun write(path: String, content: String): Boolean {
        val dir = path.substringBeforeLast('/')
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "mkdir -p $dir && echo $b64 | base64 -d > $path"
        val result = engine.runCommandSyncWithExit(command, timeoutMs = WRITE_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw IllegalStateException(result.output.lineSequence().lastOrNull()?.take(200) ?: "未知错误")
        }
        return true
    }

    /** TOML 文件中删除既有同名 section（含其键值行），避免重复条目。 */
    private fun removeTomlSection(text: String, section: String): String {
        val lines = text.lines().toMutableList()
        val header = "[$section]"
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim() == header) {
                lines.removeAt(i)
                while (i < lines.size && !lines[i].trim().startsWith("[")) {
                    lines.removeAt(i)
                }
                break
            }
            i++
        }
        return lines.joinToString("\n").trimEnd('\n')
    }

    private fun tomlEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun sanitizeName(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(64)
            .ifBlank { "unnamed" }
}
