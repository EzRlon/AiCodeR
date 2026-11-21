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
    companion object {
        const val TAG = "EngineTargetInstaller"

        /**
         * DeepSeek（dsh）商店 MCP 的专属 patch 文件（profile 层目录；桥回合以第二个
         * --patch 加载，全部条目由本类管理，格式固定「每条目一块」可整块追加）。
         */
        const val DSH_STORE_MCP_PATCH = "/root/.dsh/profiles/headless/aicode-store-mcp.patch.yml"
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
            // 技能文件装到 ~/.claude/skills 后，各引擎经 aicode_bridge 的 skills 工具
            // 统一读取（SKILL_DIRS 含该目录），可跨引擎使用；仅专家团定义是 CC 专有格式。
            StoreKind.SKILL -> null
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
            EngineTarget.DEEPSEEK -> {
                // dsh 的 MCP 走 profile 层 patch：App 管理专属文件（每条目一个 insert 块，
                // 同 id 幂等），由 DeepSeekBridge 作为第二个 --patch 传入回合（patch 的
                // 直接顶层条目不生效，insert 同 id 覆盖已实测）。桥侧保底创建空文件，
                // 此处追加后无需关心 profile 目录是否已初始化。
                val block = buildString {
                    append("- insert:\n")
                    append("  - id: mcp-$name\n")
                    append("    name: '@deepseek-ai/dsh-mcp-client'\n")
                    append("    config:\n")
                    append("      serverName: '$name'\n")
                    append("      transport: streamable-http\n")
                    append("      url: '$url'\n")
                }
                val existing = read(DSH_STORE_MCP_PATCH) ?: ""
                if (existing.contains("- id: mcp-$name")) {
                    FileLogger.i(TAG, "DeepSeek 已存在同名 MCP，跳过: $name")
                    return name
                }
                write(DSH_STORE_MCP_PATCH, existing.trimEnd() + "\n" + block)
            }
        }
        FileLogger.i(TAG, "已安装 MCP 到 ${target.label}: $name -> $url")
        return name
    }

    /**
     * 探测各外部引擎已安装的 MCP 名字集合（与 [installMcp] 的写入位置逐一对齐）。
     * 每个引擎一条 grep 命令，不存在的配置文件按空集处理；返回 per-engine 名字集合。
     */
    suspend fun probeMcpEngines(): Map<EngineTarget, Set<String>> {
        fun names(vararg patterns: String): Set<String> =
            patterns.flatMap { it.split('\n') }.filter { it.isNotBlank() }.toSet()
        val cc = runCatching {
            names(grepNames("$CLAUDE_CONFIG", "\"mcpServers\""))
        }.getOrDefault(emptySet())
        val codex = runCatching {
            names(grepTomlSections(CODEX_CONFIG, "mcp_servers."))
        }.getOrDefault(emptySet())
        val opencode = runCatching {
            names(grepNames(OPENCODE_CONFIG, "\"mcp\""))
        }.getOrDefault(emptySet())
        val dsh = runCatching {
            names(grepDshPatch(DSH_STORE_MCP_PATCH))
        }.getOrDefault(emptySet())
        return mapOf(
            EngineTarget.CLAUDE_CODE to cc,
            EngineTarget.CODEX to codex,
            EngineTarget.OPENCODE to opencode,
            EngineTarget.DEEPSEEK to dsh
        )
    }

    /** grep 出 JSON 里 mcpServers/mcp 对象的键名（粗糙但足够用于已装状态展示）。 */
    private suspend fun grepNames(path: String, section: String): String {
        val result = engine.runCommandSyncWithExit(
            "grep -A 40 '\"$section\"' $path 2>/dev/null | awk -F'\"' '/^[[:space:]]*\"[a-z0-9._-]+\":/ {print \$2}' || true",
            timeoutMs = WRITE_TIMEOUT_MS
        )
        return result.output.takeIf { result.exitCode == 0 } ?: ""
    }

    /** grep 出 TOML 里 mcp_servers.<name> 的 section 名。 */
    private suspend fun grepTomlSections(path: String, prefix: String): String {
        val result = engine.runCommandSyncWithExit(
            "grep -oE '^\\[$prefix[a-z0-9._-]+\\]' $path 2>/dev/null | sed 's/\\[|\\]//g;s/$prefix//g' || true",
            timeoutMs = WRITE_TIMEOUT_MS
        )
        return result.output.takeIf { result.exitCode == 0 } ?: ""
    }

    /** grep 出 DeepSeek store patch 里的 mcp-<name> 条目名。 */
    private suspend fun grepDshPatch(path: String): String {
        val result = engine.runCommandSyncWithExit(
            "grep -oE 'id: mcp-[a-z0-9._-]+' $path 2>/dev/null | sed 's/id: mcp-//g' || true",
            timeoutMs = WRITE_TIMEOUT_MS
        )
        return result.output.takeIf { result.exitCode == 0 } ?: ""
    }

    /** 从 [target] 的官方配置里移除名为 [name] 的 MCP 条目（幂等；条目不存在时静默返回）。 */
    suspend fun uninstallMcp(name: String, target: EngineTarget) {
        when (target) {
            EngineTarget.CLAUDE_CODE -> {
                val existing = read(CLAUDE_CONFIG) ?: return
                val root = JSONObject(existing)
                val servers = root.optJSONObject("mcpServers") ?: return
                if (servers.remove(name) == null) return
                root.put("mcpServers", servers)
                write(CLAUDE_CONFIG, root.toString(2))
            }
            EngineTarget.CODEX -> {
                val existing = read(CODEX_CONFIG) ?: return
                write(CODEX_CONFIG, removeTomlSection(existing, "mcp_servers.$name"))
            }
            EngineTarget.OPENCODE -> {
                val existing = read(OPENCODE_CONFIG) ?: return
                val root = JSONObject(existing)
                val mcp = root.optJSONObject("mcp") ?: return
                if (mcp.remove(name) == null) return
                root.put("mcp", mcp)
                write(OPENCODE_CONFIG, root.toString(2))
            }
            EngineTarget.DEEPSEEK -> {
                val existing = read(DSH_STORE_MCP_PATCH) ?: return
                // patch 块格式固定（- id: mcp-<name> 起到下一块前），按块整体移除
                val marker = "- id: mcp-$name"
                if (!existing.contains(marker)) return
                val blocks = existing.split("\n- ").filterNot { it.trimStart().startsWith("id: mcp-$name") || it.contains(marker) }
                write(DSH_STORE_MCP_PATCH, blocks.joinToString("\n- ").trimEnd() + "\n")
            }
        }
        FileLogger.i(TAG, "已从 ${target.label} 移除 MCP: $name")
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
