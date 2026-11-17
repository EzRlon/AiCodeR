package com.aicode.feature.agent.domain.tool.ledger

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 项目任务账本工具 `taskLedger`（3.1）：把 handoff/队列从「笔记里的自由文本」升级为结构化、可推进的状态表。
 *
 * 存储：`<项目>/.aicode/notes/task-ledger.md`（项目级，可 git 跟踪；与记忆/笔记同一套文件约定）。
 * 为什么用文件而不是建表：账本要能被新会话直接读到、能随备份与 git 走、能被 grep 检索；
 * 建表还得加迁移，而账本的读写频率极低（一批任务写几次）。
 *
 * 状态机：queued（排队）→ doing（进行中）→ done（完成，待验收）→ accepted（验收通过）。
 * 只允许前进，不允许跳回（与修复账本 ledger_advance 同一纪律：状态推进是事实记录，不是心愿单）。
 */
class TaskLedgerTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TaskLedgerTool"
        const val LEDGER_RELATIVE = "/.aicode/notes/task-ledger.md"
        val HEADER = """
            |# 项目任务账本
            |
            |> 结构化任务状态表，由 AI 维护（工具 `taskLedger`）。状态只前进：queued → doing → done → accepted。
            |> 新会话开工先读本文件，按 doing/queued 顺序接续，不要重做 done/accepted 项。
            |
            || 状态 | 任务 | 备注 |
            ||---|---|---|
            """.trimMargin()
    }

    override val name = "taskLedger"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
            "read", "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }

    override val description =
        "项目任务账本（跨会话任务状态）：列出/新增/推进任务状态。长期项目用它在会话之间交接进度，" +
            "新会话开工先 list 看 doing/queued 接续，完成后 advance 推进；不要用它记一次性小事。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：list=列全部任务；read=读原始账本正文；add=新增任务（默认 queued）；advance=推进状态",
            enum = listOf("list", "read", "add", "advance"),
            required = true
        ),
        "task" to ToolParameter(
            name = "task",
            type = ParameterType.STRING,
            description = "任务标题（add 必填；advance 时用于匹配已有任务，需与账本里的标题一致或为其唯一子串）",
            required = false
        ),
        "note" to ToolParameter(
            name = "note",
            type = ParameterType.STRING,
            description = "备注：add 时为背景/验收标准；advance 时为推进理由（如提交号、验证结果）",
            required = false
        ),
        "status" to ToolParameter(
            name = "status",
            type = ParameterType.STRING,
            description = "目标状态（advance 用）：doing=开始做；done=完成待验收；accepted=验收通过。只能前进不能回退",
            enum = listOf("doing", "done", "accepted"),
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")
        val projectRoot = context.projectRoot.trimEnd('/')
        if (projectRoot.isBlank()) return ToolResult.Error("缺少工作区，无法读写任务账本", "NO_WORKSPACE")
        val path = projectRoot + LEDGER_RELATIVE
        return runCatching {
            when (action) {
                "read" -> ToolResult.Success(JsonPrimitive(readOrInit(path)))
                "list" -> listTasks(path)
                "add" -> addTask(path, args)
                "advance" -> advanceTask(path, args)
                else -> ToolResult.Error("未知 action：$action，支持 list / read / add / advance", "INVALID_ARGS")
            }
        }.getOrElse { e ->
            FileLogger.e(TAG, "任务账本操作失败: $action", e)
            ToolResult.Error("任务账本操作失败：${e.message}", "LEDGER_FAILED")
        }
    }

    private fun readOrInit(path: String): String {
        if (!fileAccess.isFile(path)) return HEADER
        return fileAccess.readFile(path)
    }

    private fun listTasks(path: String): ToolResult {
        val tasks = parseRows(readOrInit(path))
        return ToolResult.Success(
            buildJsonObject {
                put("count", tasks.size)
                put("path", path)
                put("tasks", buildJsonArray {
                    tasks.forEach { row ->
                        addJsonObject {
                            put("status", row.status)
                            put("task", row.task)
                            put("note", row.note)
                        }
                    }
                })
            }
        )
    }

    private fun addTask(path: String, args: Map<String, JsonElement>): ToolResult {
        val task = cellText(args["task"])
        if (task.isNullOrBlank()) return ToolResult.Error("参数无效：task 不能为空", "INVALID_ARGS")
        val note = cellText(args["note"]).orEmpty()
        val current = readOrInit(path)
        val rows = parseRows(current)
        if (rows.any { it.task.equals(task, ignoreCase = true) }) {
            return ToolResult.Error("账本已有同名任务：$task（用 advance 推进，不要重复新增）", "DUPLICATE_TASK")
        }
        val updated = current.trimEnd() + "\n" + renderRow(LedgerRow(STATUS_QUEUED, task, note)) + "\n"
        fileAccess.writeFile(path, updated, overwrite = true)
        return ToolResult.Success(
            buildJsonObject {
                put("task", task)
                put("status", STATUS_QUEUED)
                put("path", path)
            }
        )
    }

    private fun advanceTask(path: String, args: Map<String, JsonElement>): ToolResult {
        val target = cellText(args["task"])
        if (target.isNullOrBlank()) return ToolResult.Error("参数无效：task 不能为空", "INVALID_ARGS")
        val status = (args["status"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("参数无效：status 不能为空（doing/done/accepted）", "INVALID_ARGS")
        val statusRank = STATUS_ORDER.indexOf(status)
        if (statusRank < 0) return ToolResult.Error("未知状态：$status（doing/done/accepted）", "INVALID_ARGS")

        val current = readOrInit(path)
        val rows = parseRows(current)
        val matches = rows.filter { it.task.contains(target, ignoreCase = true) }
        if (matches.isEmpty()) return ToolResult.Error("账本中找不到任务：$target", "TASK_NOT_FOUND")
        if (matches.size > 1) {
            return ToolResult.Error(
                "匹配到多个任务（${matches.joinToString("、") { it.task }}），请给出更完整的标题",
                "AMBIGUOUS_TASK"
            )
        }
        val row = matches.single()
        val currentRank = STATUS_ORDER.indexOf(row.status)
        if (currentRank < 0) return ToolResult.Error("账本里该任务的状态无法识别：${row.status}", "INVALID_STATUS")
        if (statusRank <= currentRank) {
            return ToolResult.Error(
                "状态只能前进：${row.status} → $status 不允许（当前已是 ${row.status}）",
                "STATUS_NOT_ADVANCE"
            )
        }
        val note = cellText(args["note"])
            ?.let { if (row.note.isBlank()) it else "${row.note}；$it" }
            ?: row.note
        val updatedRow = row.copy(status = status, note = note)
        // 按行号替换而不是按整行文本相等：账本可能被外部编辑过（行尾空白、手工改备注），
        // 文本相等匹配会静默不命中——工具报成功但文件没变，下次 list 仍是旧状态。
        val lines = current.lines().toMutableList()
        val index = lines.indexOfFirst { parseRow(it) == row }
        if (index < 0) return ToolResult.Error("账本行定位失败，请用 read 查看原始正文后重试", "ROW_NOT_LOCATED")
        lines[index] = renderRow(updatedRow)
        fileAccess.writeFile(path, lines.joinToString("\n"), overwrite = true)
        return ToolResult.Success(
            buildJsonObject {
                put("task", row.task)
                put("from", row.status)
                put("to", status)
                put("path", path)
            }
        )
    }

    private data class LedgerRow(val status: String, val task: String, val note: String)

    /** 单元格文本：`|` 与换行会切断表格列（parseRows 按 `|` 分列），统一换成全角与空格。 */
    private fun cellText(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull
            ?.replace("|", "｜")
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.trim()

    private fun parseRow(line: String): LedgerRow? {
        if (!line.trimStart().startsWith("|")) return null
        val cols = line.trim().removePrefix("|").removeSuffix("|").split("|").map(String::trim)
        if (cols.size < 2 || cols[0] !in STATUS_ORDER) return null
        return LedgerRow(cols[0], cols[1], cols.getOrElse(2) { "" })
    }

    private fun parseRows(content: String): List<LedgerRow> =
        content.lineSequence().mapNotNull(::parseRow).toList()

    private fun renderRow(row: LedgerRow) = "| ${row.status} | ${row.task} | ${row.note} |"

    private val STATUS_QUEUED = "queued"
    private val STATUS_ORDER = listOf(STATUS_QUEUED, "doing", "done", "accepted")
}
