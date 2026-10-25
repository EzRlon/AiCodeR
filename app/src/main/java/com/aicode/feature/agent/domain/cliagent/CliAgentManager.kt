package com.aicode.feature.agent.domain.cliagent

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 容器内 AI CLI 智能体的安装检测 / 一键安装 / 一键启动。
 *
 * - 检测走 [CommandEngine]（本地 PRoot / 远程 SSH 由 DI 委托层按模式转发），
 *   用 IfReady 变体：容器未初始化时返回 null 引导用户先完成初始化，绝不隐式触发解压。
 * - 安装与启动走 [TerminalSessionProvider.startBackgroundCommand] 开终端标签执行：
 *   进度在标签里可见、失败可重试；启动用 notify=false（命令后 exec shell 保活，
 *   CLI 退出后标签仍是可输入会话），安装用 notify=true（跑完发通知带退出码）。
 */
@Singleton
class CliAgentManager @Inject constructor(
    private val commandEngine: CommandEngine,
    private val terminalSessionProvider: TerminalSessionProvider
) {

    /** 版本探测缓存（agentId → 时间戳+首行版本）：设置页反复进出/三桥 isAvailable 高频探测时避免反复拉起容器进程。 */
    private val versionCache = mutableMapOf<String, Pair<Long, String?>>()

    /** 安装结束后的复检需要绕过缓存；initial 探测走 5 分钟 TTL（对齐桥层 isAvailable 口径）。 */
    suspend fun detectVersion(agent: CliAgent, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            versionCache[agent.id]?.let { (at, v) ->
                if (System.currentTimeMillis() - at < VERSION_TTL_MS) return v
            }
        }
        val result = commandEngine.runCommandSyncIfReady(
            command = agent.detectCommand,
            projectPath = null,
            timeoutMs = DETECT_TIMEOUT_MS
        )
        val version = if (result?.exitCode != 0) {
            null
        } else {
            result.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
        }
        versionCache[agent.id] = System.currentTimeMillis() to version
        return version
    }

    /** 在新终端标签里安装（notify=true：结束后通知）。返回标签 id。须在主线程调用。 */
    suspend fun install(agent: CliAgent): String =
        terminalSessionProvider.startBackgroundCommand(
            command = agent.installCommand,
            title = "安装 ${agent.displayName}",
            notify = true
        )

    /** 在新终端标签里启动 CLI 交互会话（notify=false：退出后 shell 保活）。返回标签 id。须在主线程调用。 */
    suspend fun launch(agent: CliAgent): String =
        terminalSessionProvider.startBackgroundCommand(
            command = agent.launchCommand,
            title = agent.displayName,
            notify = false
        )

    private companion object {
        /** CLI 冷启动打印版本可能要几秒（node 启动），给足余量。 */
        const val DETECT_TIMEOUT_MS = 20_000L

        /** 版本探测缓存 TTL：与桥层 isAvailable 的 5 分钟口径一致。 */
        const val VERSION_TTL_MS = 5 * 60_000L
    }
}
