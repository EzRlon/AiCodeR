package com.aicode.feature.agent.domain.tool.script

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.script.ScriptRepository
import com.aicode.feature.agent.domain.script.ScriptRunner
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 用户脚本工具：列出 / 固化 / 运行配置目录下的脚本（`~/.aicode/scripts/`，容器内经绑定即
 * `/root/.aicode/scripts/`）。运行在容器内发生，与 `Bash` 同属命令执行面。
 *
 * 参数组合决定动作：
 * - 只给 `name`：在容器内运行该脚本，返回输出与退出码；
 * - 给 `name` + `content`：把一段自动化固化成脚本，**不执行**；
 * - 都不给：列出可用脚本（名称 / 解释器 / 描述 / 上次运行）。
 *
 * 运行与解释器选择由 [ScriptRunner] 承担，与「设置 → 脚本」页共用同一实现。
 */
class RunScriptTool @Inject constructor(
    private val scriptRunner: ScriptRunner,
    private val scriptRepository: ScriptRepository
) : AgentTool(), StreamingAgentTool {

    private companion object {
        const val TAG = "RunScriptTool"

        /** 默认超时（秒），与 [com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool] 对齐。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** 超时上限（秒），与容器命令引擎上限对齐。 */
        const val MAX_TIMEOUT_SECONDS = 1_800L

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    override val name = "run_script"
    override val description = "列出、固化或运行用户脚本（存于 ~/.aicode/scripts/，在容器内执行）。" +
        "只给 name 则运行该脚本并返回输出与退出码；给 name + content 则把一段自动化固化成脚本（不执行）；" +
        "都不给则列出可用脚本。支持 .sh / .py / .js / .mjs（缺解释器需先在容器内装对应场景）；" +
        "脚本首行注释会作为描述展示。"
    override val permissionPolicy = ToolPermissionPolicy.ASK

    /** 兜底能力（运行面）；按动作细化见 [effectiveCapabilities]。 */
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val saving = args["content"]?.jsonPrimitive?.contentOrNull != null
        if (saving) return setOf(ToolCapability.MODIFY_AGENT_CONFIG)
        val running = !args["name"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        return if (running) setOf(ToolCapability.EXECUTE_COMMANDS) else setOf(ToolCapability.READ_AGENT_CONFIG)
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "脚本名（含后缀，如 cleanup.sh；不带后缀时会补全唯一匹配）。运行或固化时必填；缺省则列出可用脚本。",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "脚本正文（与 name 同时给出即固化脚本，覆盖同名文件，不执行）。首行可用注释写明用途，会作为描述展示。",
            required = false
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "运行脚本的最长执行时间（秒），默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    /** 解析 timeout（秒）并钳到合法范围，返回毫秒。 */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val scriptName = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val saving = args["content"]?.jsonPrimitive?.contentOrNull != null
        return when {
            saving -> PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认固化脚本",
                summary = "写入 ${ScriptRepository.CONTAINER_SCRIPTS_DIR}/${scriptName.ifEmpty { "(缺 name)" }}",
                details = "脚本会保存到配置目录（容器内 ${ScriptRepository.CONTAINER_SCRIPTS_DIR}/），本次不执行。",
                argsPreview = argsPreview
            )
            scriptName.isNotEmpty() -> PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认运行脚本",
                summary = "$scriptName（容器内执行）",
                details = "将在当前执行环境中运行 ${ScriptRepository.CONTAINER_SCRIPTS_DIR}/$scriptName 并返回输出与退出码。",
                argsPreview = argsPreview
            )
            else -> PendingToolPermission(
                id = callId,
                toolName = name,
                title = "确认列出脚本",
                summary = "列出 ${ScriptRepository.CONTAINER_SCRIPTS_DIR}/ 下的脚本",
                details = "只读脚本清单，不执行任何脚本。",
                argsPreview = argsPreview
            )
        }
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        scriptRepository.unavailableReason()?.let { return ToolResult.Error(it, "UNSUPPORTED_EXECUTION_MODE") }
        val scriptName = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val content = args["content"]?.jsonPrimitive?.contentOrNull
        return when {
            content != null -> saveScript(scriptName, content)
            scriptName.isEmpty() -> listScripts()
            else -> runScript(scriptName, resolveTimeoutMs(args))
        }
    }

    private fun listScripts(): ToolResult {
        val scripts = scriptRepository.list()
        if (scripts.isEmpty()) {
            return ToolResult.Success(
                JsonPrimitive(
                    "当前没有可用脚本（目录 ~/.aicode/scripts/，容器内 ${ScriptRepository.CONTAINER_SCRIPTS_DIR}/）。" +
                        "可用 run_script 传 name + content 固化一个，支持 .sh / .py / .js / .mjs。"
                )
            )
        }
        val body = scripts.joinToString("\n") { info ->
            buildString {
                append("- ${info.name}（${info.interpreter}）")
                info.description?.let { append("：$it") }
                append(info.lastRunAt?.let { " — 上次运行 ${formatTime(it)}" } ?: " — 尚未运行过")
                info.lastExitCode?.let { append("，退出码 $it") }
            }
        }
        return ToolResult.Success(
            JsonPrimitive("可用脚本 ${scripts.size} 个（容器内运行，目录 ~/.aicode/scripts/）：\n$body")
        )
    }

    private fun saveScript(scriptName: String, content: String): ToolResult {
        if (scriptName.isEmpty()) {
            return ToolResult.Error("固化脚本需要同时给出 name（含后缀，如 cleanup.sh）与 content", "MISSING_NAME")
        }
        return runCatching { scriptRepository.save(scriptName, content) }.fold(
            onSuccess = { file ->
                ToolResult.Success(
                    JsonPrimitive(
                        "已固化脚本 ${file.name}（${content.length} 字符）→ ~/.aicode/scripts/${file.name}；" +
                            "运行：run_script name=${file.name}"
                    )
                )
            },
            onFailure = { e -> ToolResult.Error("固化脚本失败：${e.message}", "SAVE_FAILED") }
        )
    }

    private suspend fun runScript(scriptName: String, timeoutMs: Long): ToolResult {
        val target = scriptRunner.target(scriptName)
            ?: return ToolResult.Error("找不到脚本「$scriptName」。${availableHint()}", "SCRIPT_NOT_FOUND")

        FileLogger.d(TAG, "运行脚本 ${target.scriptName}（timeout=${timeoutMs}ms）")
        val result = try {
            scriptRunner.run(target, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "运行脚本失败: ${target.scriptName}", e)
            return ToolResult.Error("运行脚本失败：${e.message}")
        }

        // 退出码为 null 且容器未就绪：引擎把引导文案当输出返回，这不是错误，
        // 把引导原样给出（与 Bash 工具一致），不要误报成「可能超时」。
        if (result.exitCode == null) {
            scriptRunner.notReadyHint()?.let { hint ->
                FileLogger.i(TAG, "容器未就绪，未执行脚本 ${target.scriptName}")
                return ToolResult.Success(JsonPrimitive(hint))
            }
        }

        val header = "脚本 ${target.scriptName} 退出码 ${result.exitCode ?: "未知（可能超时）"}" +
            if (result.outputTruncated) "（输出已截断）" else ""
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("$header\n${result.output}"))
        } else {
            ToolResult.Error("$header\n${result.output}", "SCRIPT_EXIT_${result.exitCode ?: "UNKNOWN"}")
        }
    }

    private fun availableHint(): String {
        val names = scriptRepository.list().map { it.name }
        return if (names.isEmpty()) {
            "当前没有可用脚本，可传 name + content 固化一个。"
        } else {
            "可用脚本：${names.joinToString("、")}"
        }
    }

    private fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

    /**
     * 流式执行：运行脚本时逐行 emit 过程输出（长脚本在聊天里可见进度），结束 emit 最终结果；
     * 列出与固化没有过程输出，直接复用 [execute]。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        if (scriptRepository.unavailableReason() != null) {
            emit(ToolStreamEvent.Completed(execute(args)))
            return@flow
        }
        val scriptName = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val saving = args["content"]?.jsonPrimitive?.contentOrNull != null
        val target = if (!saving && scriptName.isNotEmpty()) scriptRunner.target(scriptName) else null
        if (target == null) {
            emit(ToolStreamEvent.Completed(execute(args)))
            return@flow
        }

        val timeoutMs = resolveTimeoutMs(args)
        // 限幅累积：喂回模型的最终结果与 Bash 工具一致（超大输出只保留开头 + 结尾）
        val accumulated = BoundedOutput()
        var exitCode: Int? = null
        try {
            FileLogger.d(TAG, "运行脚本(流式) ${target.scriptName}（timeout=${timeoutMs}ms）")
            scriptRunner.stream(target, timeoutMs).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        accumulated.append(event.text)
                        accumulated.append("\n")
                        emit(ToolStreamEvent.Progress(event.text))
                    }
                    is CommandEvent.Exit -> exitCode = event.code
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底：底层流异常时保留已展示的输出，避免模型只看到失败而丢掉此前结果
            FileLogger.e(TAG, "运行脚本(流式) 异常(已保留 ${accumulated.totalChars} 字符): ${target.scriptName}", e)
            val saved = accumulated.build()
            val fallback = if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive("脚本 ${target.scriptName} 执行中断：${e.message}\n$saved"))
            } else {
                ToolResult.Error("运行脚本失败：${e.message}")
            }
            emit(ToolStreamEvent.Completed(fallback))
            return@flow
        }

        val header = "脚本 ${target.scriptName} 退出码 ${exitCode ?: "未知（可能超时）"}" +
            if (accumulated.truncated) "（输出已截断）" else ""
        val result = if (exitCode == 0) {
            ToolResult.Success(JsonPrimitive("$header\n${accumulated.build()}"))
        } else {
            ToolResult.Error("$header\n${accumulated.build()}", "SCRIPT_EXIT_${exitCode ?: "UNKNOWN"}")
        }
        emit(ToolStreamEvent.Completed(result))
    }
}
