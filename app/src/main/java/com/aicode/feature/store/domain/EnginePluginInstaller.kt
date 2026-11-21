package com.aicode.feature.store.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.store.data.EngineTarget
import com.aicode.feature.store.data.PluginStoreItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎插件安装：三个引擎的插件机制互不兼容，这里按引擎分别下发各自的 CLI 命令。
 *
 * - Claude Code：marketplace 源 + `plugin install <plugin>@<market> -y`（非 TTY 下不带 `-y` 会被拒绝）
 * - Codex：marketplace 源 + `plugin add <plugin>@<market>`（`add` 自 0.131.0 起才有）
 * - OpenCode：无 marketplace，`plugin add <npm包|github:org/repo>` 直接写 opencode.jsonc
 *
 * 一律调引擎自己的 CLI 而不是手写其插件目录：各引擎的缓存布局是内部实现，
 * 手写等于复刻私有格式，上游改一次就失效。
 *
 * DeepSeek（dsh）的特殊性：`dsh plugin add/remove/ls` 是 profile 内 pnpm 转发，
 * 命令会先 boot profile —— 而 profile boot 强依赖有效 LLM 凭据（无 Key 时直接
 * MISSING_CREDENTIAL/AUTH 退出，pnpm 根本不执行，实测）。因此安装命令带
 * `--profile headless` 且凭据缺失时翻译成「先完成引擎认证」的可操作指引；
 * 商店 MCP 则不依赖凭据（由 DeepSeekBridge 的 --patch 层管理）。
 */
@Singleton
class EnginePluginInstaller @Inject constructor(
    private val engine: CommandEngine
) {
    private companion object {
        const val TAG = "EnginePluginInstaller"
        /** 插件安装要拉远端仓库，比普通命令慢得多。 */
        const val INSTALL_TIMEOUT_MS = 300_000L
    }

    /** 安装结果：成功时 [message] 为引擎输出摘要，失败时抛出带引擎名的异常。 */
    suspend fun install(item: PluginStoreItem, target: EngineTarget) {
        if (target !in item.engines) {
            throw IllegalStateException("${item.name} 不支持 ${target.label}")
        }
        ensureCliReady(target)
        if (!supportsPlugins(target)) {
            throw IllegalStateException(
                "当前 ${target.label} 版本不支持插件命令：请先在「设置 → 模型与引擎」把该引擎升级到最新版"
            )
        }

        when (target) {
            EngineTarget.CLAUDE_CODE -> {
                addClaudeMarketplace(item)
                run(target, "plugin", "install", "${item.installRef}", "-y", "--scope", "user")
            }

            EngineTarget.CODEX -> {
                addCodexMarketplace(item)
                run(target, "plugin", "add", item.installRef)
            }

            EngineTarget.OPENCODE -> {
                // OpenCode 的插件就是 npm 包/Git 仓库，无需 marketplace 步骤
                run(target, "plugin", "add", item.installRef)
            }

            EngineTarget.DEEPSEEK -> runDshPlugin("plugin", "add", item.installRef)
        }
        FileLogger.i(TAG, "已安装插件 ${item.name} → ${target.label}")
    }

    suspend fun uninstall(item: PluginStoreItem, target: EngineTarget) {
        ensureCliReady(target)
        when (target) {
            EngineTarget.CLAUDE_CODE ->
                run(target, "plugin", "uninstall", item.installRef, "-y", "--scope", "user")

            EngineTarget.CODEX ->
                run(target, "plugin", "remove", item.installRef)

            EngineTarget.OPENCODE ->
                run(target, "plugin", "remove", item.installRef)

            EngineTarget.DEEPSEEK ->
                runDshPlugin("plugin", "remove", item.installRef)
        }
        FileLogger.i(TAG, "已卸载插件 ${item.name} ← ${target.label}")
    }

    /** 已安装判定：调引擎自己的 `plugin list` 而不是读目录，避免依赖私有缓存布局。 */
    suspend fun listInstalled(target: EngineTarget): Set<String> {
        val result = when (target) {
            EngineTarget.CLAUDE_CODE -> runRaw(target, "plugin", "list", "--json")
            EngineTarget.CODEX -> runRaw(target, "plugin", "list")
            EngineTarget.OPENCODE -> runRaw(target, "plugin", "list")
            EngineTarget.DEEPSEEK -> runRaw(target, "--profile", "headless", "plugin", "ls")
        }
        if (result.exitCode != 0) return emptySet()
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private suspend fun addClaudeMarketplace(item: PluginStoreItem) {
        val source = item.marketplaceSource ?: return
        // 重复添加同名 marketplace 会报错，失败不阻塞后续 install（已存在即目标状态）
        runRaw(EngineTarget.CLAUDE_CODE, "plugin", "marketplace", "add", source, "--scope", "user")
    }

    private suspend fun addCodexMarketplace(item: PluginStoreItem) {
        val source = item.marketplaceSource ?: return
        runRaw(EngineTarget.CODEX, "plugin", "marketplace", "add", source)
    }

    /** 引擎 CLI 未安装时先报清楚，别让用户看到一串 npm 错误。 */
    private suspend fun ensureCliReady(target: EngineTarget) {
        val probe = engine.runCommandSyncWithExit(
            command = "command -v ${target.cli}",
            timeoutMs = 15_000L
        )
        if (probe.exitCode != 0) {
            throw IllegalStateException(
                "${target.label} 尚未安装：请先在「设置 → 模型与引擎」点该引擎的「安装」"
            )
        }
    }

    /**
     * 插件子命令能力探测：各引擎的插件机制仍在演进，旧版本没有 `plugin` 子命令。
     * 直接调会得到一串难读的参数错误，所以先探 `plugin --help` 给出可执行的提示。
     */
    suspend fun supportsPlugins(target: EngineTarget): Boolean {
        val probe = engine.runCommandSyncWithExit(
            command = "${target.cli} plugin --help",
            timeoutMs = 30_000L
        )
        return probe.exitCode == 0
    }

    private suspend fun run(target: EngineTarget, vararg args: String) {
        val result = runRaw(target, *args)
        if (result.exitCode != 0) {
            val detail = result.output.trim().lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(3)
                .joinToString("；")
            throw IllegalStateException(
                "${target.label} 安装失败（退出码 ${result.exitCode}）" +
                    if (detail.isNotEmpty()) "：$detail" else ""
            )
        }
    }

    /**
     * dsh 的插件子命令：`--profile` 必须紧跟在 `dsh` 后（子命令的 option），boot
     * profile 需要有效凭据，无凭据/无效 Key 时把底层报错翻译成可操作指引。
     */
    private suspend fun runDshPlugin(vararg args: String) {
        try {
            run(EngineTarget.DEEPSEEK, "--profile", "headless", *args)
        } catch (e: IllegalStateException) {
            val m = e.message ?: ""
            throw IllegalStateException(
                if ("MISSING_CREDENTIAL" in m || "AUTH" in m || "api key" in m.lowercase() || "Token is invalid" in m) {
                    "DeepSeek 插件管理需要有效的 DeepSeek 凭据（dsh 启动 profile 时校验）：" +
                        "请先完成引擎认证（设置 → 模型 配置 Key，或容器 ~/.dsh/.env 写入 DEEPSEEK_API_KEY），再安装插件"
                } else {
                    m
                }
            )
        }
    }

    private suspend fun runRaw(target: EngineTarget, vararg args: String): CommandResult =
        engine.runCommandSyncWithExit(
            command = (listOf(target.cli) + args).joinToString(" "),
            timeoutMs = INSTALL_TIMEOUT_MS
        )
}
