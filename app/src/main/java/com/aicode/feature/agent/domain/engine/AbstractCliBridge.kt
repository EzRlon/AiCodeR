package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.cliagent.CliAgentCatalog
import com.aicode.feature.agent.domain.cliagent.CliAgentManager
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.flow.first

/**
 * 三个 CLI 引擎桥接（Claude Code / Codex / OpenCode）的公共骨架。
 *
 * 抽取的是**逐字相同**的部分，避免三处各改一遍时漏改：
 * - 桥接目录 / 容器工作区路径 / 数据桥脚本路径（容器内路径约定）
 * - 运行时容器档案解析、CLI 可用性探测（带 5 分钟缓存）
 * - 可用性/认证提示两份 @Volatile 缓存字段
 *
 * 不抽取的部分（三份实现确实不同，强行合并会引入分支复杂度）：
 * `authHint`（判断条件各异）、`classifyEarlyExit`（错误特征词不同）、
 * `parseLine` / `runTurnOnce`（各 CLI 的 JSON 事件流协议完全不同）、
 * `writeBridgeAssets`（资产内容与数量不同）。
 */
abstract class AbstractCliBridge(
    protected val cliAgentManager: CliAgentManager,
    protected val pathHomeResolver: PathHomeResolver,
    protected val containerSettingsRepository: ContainerSettingsRepository
) {
    /** 子类声明自己对应的 CLI 可执行文件名（如 "claude" / "codex" / "opencode"）。 */
    protected abstract val cliCommand: String

    /** 容器内桥接资产目录：/root/.aicode 独立于 rootfs（升级不丢），且是 node 可用的绝对路径。 */
    protected fun bridgeDir(): String =
        pathHomeResolver.aicodeRoot().trimEnd('/') + BRIDGE_DIR_SUFFIX

    /** 容器内的工作区路径：CLI 参数与桥接环境变量都必须是容器视角路径。 */
    protected fun containerWorkspacePath(): String =
        pathHomeResolver.home().trimEnd('/') + "/workspace"

    /** App 注入到各 CLI 的 MCP 桥脚本（memory / skill / ledger 等工具的实现）。 */
    protected fun dataBridgeScript(): String = bridgeDir() + "/aicode-bridge.mjs"

    /** 当前生效的容器档案（远程 SSH 模式下由子类自行判断返回值的用途）。 */
    protected suspend fun resolveRuntimeProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 远程 SSH 模式不走本地 CLI 桥接。 */
    protected suspend fun isRemote(): Boolean =
        resolveRuntimeProfile().mode == ExecutionMode.REMOTE_SSH

    /**
     * CLI 是否可用（容器内已安装）。结果缓存 5 分钟，避免每次打开会话都探测一次容器。
     * 远程 SSH 模式恒为 false——桥接只在本地容器内运行。
     */
    suspend fun isAvailable(): Boolean {
        if (isRemote()) return false
        cachedAvailable?.let { cached ->
            if (System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) return cached
        }
        val agent = CliAgentCatalog.ALL.firstOrNull { it.command == cliCommand } ?: return false
        val available = cliAgentManager.detectVersion(agent) != null
        cachedAvailable = available
        cachedAtMs = System.currentTimeMillis()
        return available
    }

    // ── 可用性 / 认证提示缓存（各子类的 authHint 实现共用）──

    @Volatile
    protected var cachedAvailable: Boolean? = null

    @Volatile
    protected var cachedAtMs: Long = 0

    @Volatile
    protected var cachedAuthHint: String? = null

    @Volatile
    protected var cachedAuthAtMs: Long = 0

    /** 认证提示缓存是否仍然新鲜（5 分钟内）。 */
    protected fun authHintCacheFresh(): Boolean =
        System.currentTimeMillis() - cachedAuthAtMs < CACHE_TTL_MS

    protected fun rememberAuthHint(hint: String?) {
        cachedAuthHint = hint
        cachedAuthAtMs = System.currentTimeMillis()
    }

    protected companion object {
        const val BRIDGE_DIR_SUFFIX = "/.aicode/bridge"
        const val CACHE_TTL_MS = 5 * 60_000L
    }
}
