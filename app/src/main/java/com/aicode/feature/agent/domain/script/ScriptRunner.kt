package com.aicode.feature.agent.domain.script

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * 脚本执行入口：把脚本名解析成容器内运行命令并执行，跑完记录「上次运行」。
 *
 * `run_script` 工具与「设置 → 脚本」页共用这一份实现——解释器选择、路径拼装、工作目录、
 * 超时与运行记录只有一处，避免两个入口各写一套后走样。
 */
@Singleton
class ScriptRunner @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val scriptRepository: ScriptRepository
) {

    /** 已解析的脚本：名称与容器内运行命令。 */
    data class Target(val scriptName: String, val command: String)

    /**
     * 按名解析脚本并拼出运行命令；找不到脚本、或不支持的后缀返回 null。
     * 名称已过字符集校验，容器路径不含引号与空格，双引号包裹即可安全交给 shell。
     *
     * `.sh` 用引擎的默认 shell：provision 装好基地包（含 bash）之前它是 `/bin/sh`，
     * 直接写 bash 只会拿到 127（bash: not found），装好之后即 bash。
     */
    fun target(name: String): Target? {
        val file = scriptRepository.resolve(name) ?: return null
        val interpreter = ScriptRepository.resolveInterpreter(file.name) ?: return null
        val executable = if (interpreter == ScriptRepository.SHELL_INTERPRETER) {
            commandEngine.defaultShell()
        } else {
            interpreter
        }
        return Target(file.name, "$executable \"${scriptRepository.containerPath(file.name)}\"")
    }

    /** 在当前工作区目录内同步执行并记录「上次运行」。 */
    suspend fun run(target: Target, timeoutMs: Long): CommandResult {
        val result = commandEngine.runCommandSyncWithExit(
            target.command,
            workspaceRepository.currentPath(),
            timeoutMs
        )
        recordUnlessNotReady(target, result.exitCode)
        return result
    }

    /** 流式执行（逐行 emit 过程输出）。 */
    fun stream(target: Target, timeoutMs: Long): Flow<CommandEvent> {
        var exitCode: Int? = null
        return commandEngine.runCommandStream(target.command, workspaceRepository.currentPath(), timeoutMs)
            .onEach { event -> if (event is CommandEvent.Exit) exitCode = event.code }
            // 只在正常跑完时记「上次运行」：被取消/异常中断时没有可信的退出码，与同步路径一致。
            .onCompletion { cause -> if (cause == null) recordUnlessNotReady(target, exitCode) }
    }

    /** 容器未就绪的引导文案；非 null 表示命令没有真正执行。 */
    fun notReadyHint(): String? = commandEngine.notReadyHint()

    /** 当前执行环境不支持用户脚本的原因；非 null 时脚本解析与执行都不成立。 */
    fun unavailableReason(): String? = scriptRepository.unavailableReason()

    /**
     * 写一次「上次运行」。容器未就绪时引擎把引导文案当输出返回，这不算一次运行，不记录；
     * 索引是磁盘读写，固定放 IO 线程（调用方可能在主线程触发运行）。
     */
    private suspend fun recordUnlessNotReady(target: Target, exitCode: Int?) {
        if (exitCode == null && notReadyHint() != null) return
        withContext(Dispatchers.IO) { scriptRepository.recordRun(target.scriptName, exitCode) }
    }
}
