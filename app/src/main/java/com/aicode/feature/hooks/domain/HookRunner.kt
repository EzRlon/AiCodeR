package com.aicode.feature.hooks.domain

import com.aicode.core.util.FileLogger
import com.aicode.core.util.shellQuote
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.hooks.data.HooksRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具钩子执行器：在工具调用前后运行用户配置的脚本（`~/.aicode/hooks.json`）。
 *
 * - 仅本地 PRoot 模式生效（远程模式直接跳过，返回空结果）；
 * - PreToolUse：任一脚本非零退出即[否决][HookOutcome.blocked]该次工具调用，输出作为原因；
 * - PostToolUse：输出附加到工具结果文本末尾；
 * - 脚本经容器内 shell 解释执行（.sh/.py/.js 自动选择解释器），环境变量注入
 *   `AICODE_HOOK_EVENT / AICODE_TOOL_NAME / AICODE_TOOL_ARGS`。
 *
 * 安全边界：hooks.json 位于 app 私有目录经 bind 进容器（`/root/.aicode/hooks.json`）。
 * 该文件的写入在权限引擎中被标为「不可记忆的单次授权」（ToolPermissionPolicyEngine），
 * 叠加本处执行前的灾难命令粗检，构成信任边界；钩子执行本身不再逐次弹窗。
 */
@Singleton
class HookRunner @Inject constructor(
    private val hooksRepository: HooksRepository,
    private val commandEngine: CommandEngine,
    private val executionModeHolder: ExecutionModeHolder
) {
    private companion object {
        const val TAG = "HookRunner"
        /** best-effort 粗检：对 script 字段字符串的常见灾难性删除构造做拒绝（不读脚本文件内容）。 */
        val CATASTROPHIC = Regex("rm\\s+(-[-a-zA-Z]+\\s+)*(/|/\\*|~|~/|~\\/\\*|\\\$HOME/?|/root/?|/root/\\*)(\\s|$|;)")
    }

    /**
     * 运行指定事件的全部匹配钩子。
     * @param event [HooksConfig.EVENT_PRE_TOOL_USE] 或 [HooksConfig.EVENT_POST_TOOL_USE]
     * @param toolName 工具名（如 Bash/editFile）
     * @param argsJson 工具参数 JSON（注入环境变量供脚本判断，超过 4096 字符截断）
     */
    suspend fun run(event: String, toolName: String, argsJson: String): HookOutcome {
        if (!isLocalMode()) return HookOutcome()
        val defs = hooksRepository.current().hooks[event].orEmpty()
            .filter { matches(it.matcher, toolName) }
        if (defs.isEmpty()) return HookOutcome()
        val messages = StringBuilder()
        for (def in defs) {
            val result = runSingle(event, def, toolName, argsJson)
            if (result.blocked) {
                return HookOutcome(blocked = true, message = result.message)
            }
            result.message?.takeIf { it.isNotBlank() }?.let {
                if (messages.isNotEmpty()) messages.append('\n')
                messages.append(it)
            }
        }
        return HookOutcome(blocked = false, message = messages.takeIf { it.isNotEmpty() }?.toString())
    }

    private suspend fun runSingle(
        event: String,
        def: HookDefinition,
        toolName: String,
        argsJson: String
    ): HookOutcome {
        val scriptPath = def.script.trim()
        if (scriptPath.isEmpty()) {
            FileLogger.w(TAG, "钩子 script 为空，已跳过")
            return HookOutcome(blocked = event == HooksConfig.EVENT_PRE_TOOL_USE, message = "钩子 script 为空，已跳过")
        }
        if (CATASTROPHIC.containsMatchIn(scriptPath)) {
            FileLogger.w(TAG, "钩子脚本被安全防护拒绝: $scriptPath")
            return HookOutcome(blocked = event == HooksConfig.EVENT_PRE_TOOL_USE, message = "钩子脚本被安全防护拒绝")
        }
        val envPrefix = buildString {
            append("export AICODE_HOOK_EVENT=").append(shellQuote(event)).append("; ")
            append("export AICODE_TOOL_NAME=").append(shellQuote(toolName)).append("; ")
            append("export AICODE_TOOL_ARGS=").append(shellQuote(argsJson.take(4096))).append("; ")
        }
        val command = envPrefix + buildExecCommand(scriptPath)
        val result = commandEngine.runCommandSyncWithExit(
            command = command,
            projectPath = null,
            timeoutMs = def.timeoutMs.coerceIn(1_000L, 60_000L)
        )
        val output = result.output.trim().take(4_000)
        // exitCode 为 null（超时/容器未就绪）按「通过」处理（fail-open）：守卫型钩子不应因
        // 基础设施故障挂起整轮任务；需要 fail-closed 的检查请显式在脚本内处理。
        val failed = (result.exitCode ?: 0) != 0
        FileLogger.i(TAG, "钩子 $event [$toolName] script=$scriptPath exit=${result.exitCode}")
        return HookOutcome(
            blocked = failed && event == HooksConfig.EVENT_PRE_TOOL_USE,
            message = output.takeIf { it.isNotEmpty() }?.let {
                if (failed && event == HooksConfig.EVENT_PRE_TOOL_USE) "钩子否决（$scriptPath）：\n$it" else it
            }
        )
    }

    /** 按后缀选择解释器；无已知后缀时原样执行（script 字段本身可为完整 shell 命令）。 */
    private fun buildExecCommand(scriptPath: String): String = when {
        scriptPath.endsWith(".py") -> "python3 $scriptPath"
        scriptPath.endsWith(".js") -> "node $scriptPath"
        scriptPath.endsWith(".sh") -> "sh $scriptPath"
        else -> scriptPath
    }

    private fun matches(matcher: String, toolName: String): Boolean {
        val m = matcher.trim()
        if (m.isEmpty() || m == "*") return true
        return m.split('|').any { it.trim().equals(toolName, ignoreCase = true) }
    }

    private fun isLocalMode(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.LOCAL_PROOT
}
