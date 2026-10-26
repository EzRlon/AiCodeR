package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.schedule.domain.AuthCategoryVerdict
import com.aicode.feature.schedule.domain.ScheduledAiRunState
import com.aicode.feature.schedule.domain.ScheduledAuthCategoryMapping
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 评估顺序（Bash）：
 *   1) 任一段命中 DENY 规则 → DENY（即便命令不可静态判定，DENY 也对已解析的段生效；可覆盖内置白名单）；
 *   2) 命令不可静态判定（含命令替换/分组/绝对路径重定向等）→ ASK，且不可记忆；
 *   3) 所有段都命中内置安全白名单（ls/git status 等只读命令，见 [BuiltInSafeCommands]）→ ALLOW；
 *   4) 所有段都命中已记忆的 ALLOW 规则 → ALLOW；
 *   5) 否则 → ASK，可记忆前缀为各段的「程序名」或「程序名+子命令」（对 git/npm 等子命令分发器，
 *      记 `git pull` 而非 `git`，使不同子命令各自独立授权）。
 * 非 shell 的 ASK 工具按整工具（pattern=`*`）匹配。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：无论内置白名单或已记忆规则，一律 ASK 且不可记忆；
 * AUTO 模式也不自动放行（除非已开启「禁用安全拦截」）。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val toolPermissionTiers: ToolPermissionTierRepository,
    private val watchModeSettings: WatchModeSettingsRepository,
    private val scheduledAiRunState: ScheduledAiRunState
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆"

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"

        /** 计划模式切换工具（守夜：申请/退出为只读方向，直接放行）。 */
        const val PLAN_MODE_TOOL = "planMode"

        /** MCP 管理工具（守夜：配置类操作，拒绝 + 记录）。 */
        const val MANAGE_MCP_TOOL = "manageMcp"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * 浏览器工具：navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab 为写操作（PLAN 拦截），
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs 为只读（PLAN 放行）。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /**
         * 提权参数：非 AUTO 模式下，命令因内置安全防护（灾难性 rm）被拒时，
         * 可在调用时置为 true 重试，把硬拒绝降级为一次性用户授权。
         */
        const val ELEVATE_ARG = "elevate"

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "安全防护：禁止执行高危删除操作（根目录删除）"
        const val REASON_RM_RELATIVE = "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
        const val REASON_RM_HOME = "安全防护：禁止执行高危删除操作（用户目录删除）"
        const val REASON_RM_WORKSPACE = "安全防护：禁止执行高危删除操作（工作区根目录删除）"
        const val REASON_RM_TMP = "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
        const val REASON_RM_SYSTEM_DIR = "安全防护：禁止执行高危删除操作（系统关键目录删除）"

        /** 相对/通配类删除目标（归一化后判定）：整体删除当前目录或任意内容。 */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** 根级通配（`/et*`、`/usr*` 等）可能展开为受保护的系统目录，直接拦截。 */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** 工作区根目录的删除目标（归一化后判定）；工作区的子目录不在此列。 */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** 受保护的系统关键目录：命中其本身或其任意子路径即拦截（`/tmp`、工作区子树单独处理）。 */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        const val REASON_UNANALYZABLE_DESTRUCTIVE =
            "安全防护：命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，且疑似破坏性操作，无法确认安全"

        /**
         * 不可静态判定时的拦截原因：附带具体触发构造。
         * 只说「无法静态分析」时 AI 不知道是 `$(...)` 还是绝对路径重定向，只能整条反复重试。
         */
        internal fun unanalyzableReason(analysis: ShellCommandParser.Analysis): String {
            val constructs = analysis.blockers.joinToString("、").ifEmpty { "未知构造" }
            return "$REASON_UNANALYZABLE_DESTRUCTIVE（触发构造：$constructs）"
        }

        /** 疑似破坏性程序（仅在命令不可静态判定时用于保守拦截）。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null,
        /** 守夜模式下的自动放行原因；非空表示「放行 + 记录」（工作流据此写授权审计）。 */
        val watchAutoAllow: String? = null
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()

        // 工具档位（工具授权中心的「禁止」）：用户显式设置，压过 PLAN/AUTO 模式与一切内置规则，直接拒绝。
        val tier = toolPermissionTiers.tierOf(toolName)
        if (tier == ToolPermissionTier.DENY) {
            return EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "该工具已被用户在「设置 → 工具授权」中设为「禁止」。如确需使用，请先调整授权设置。"
            )
        }

        // 守夜守护：无人值守语义（见 unattended-security-plan §L2）。
        // 定时 AI 任务运行中同样视为无人值守：超档挂起走兜底超时，不无限等待。
        val activeRun = scheduledAiRunState.current()
        val watchMode = watchModeSettings.isArmed() || activeRun != null
        if (watchMode) {
            // 宿主高危与配置类工具：拒绝 + 记录（记录由工作流的系统拒绝打点承担）。
            if (toolName == SHIZUKU_TOOL || toolName == MANAGE_MCP_TOOL) {
                return EvalResult(
                    Verdict.DENY,
                    emptyList(),
                    denyReason = "守夜模式：用户不在场，「$toolName」属高危/配置类操作，已自动拒绝并记录。请改用容器内的等效安全方式，或留待用户在场时执行。"
                )
            }
            // 计划模式切换：只读方向的操作，直接放行。
            if (toolName == PLAN_MODE_TOOL) {
                return EvalResult(Verdict.ALLOW, emptyList())
            }
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "当前处于 PLAN（计划）模式，系统物理沙盒已禁止修改系统状态或执行写操作。请在计划模式下仅调用只读工具探索代码，不要尝试修改文件或执行命令。")
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.AUTO) {
            // AUTO 模式放行所有权限；灾难性 rm 防护（根目录/系统目录删除）默认保留，
            // 可在「工具授权」设置中关闭（禁用安全拦截）。遭遇拦截时仍可凭 elevate 参数提权重试。
            val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
            if (!safetyDisabled && isShellTool(toolName, args)) {
                val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                if (command != null) {
                    val analysis = ShellCommandParser.analyze(command)
                    checkCatastrophicRm(analysis.segments)?.let { return elevationOrDeny(it, args, watchMode) }
                    // 命令替换/子 shell/绝对路径重定向等无法静态判定的构造，删除目标不可知；
                    // 若同时疑似破坏性，宁可拦下（可提权），避免绕过安全防护。
                    if (!analysis.analyzable && looksDestructive(command)) {
                        return elevationOrDeny(unanalyzableReason(analysis), args, watchMode)
                    }
                }
            }
            // Shizuku 直接操作宿主系统，AUTO 下也不自动放行，仍需逐次确认（除非已开启「禁用安全拦截」）。
            if (toolName == SHIZUKU_TOOL && !safetyDisabled) {
                return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
            }
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 定时 AI 任务运行中：预授权类别内的操作直接放行 + 记录；超出类别继续走主链
        // （ASK → 通知授权通道 / 守夜兜底超时），落实超档三级分级的「可逆盒内放行、越界引导」。
        // 位于 AUTO 之后：AUTO 任务由上方分支全放行；PLAN 任务保持只读拦截，不被预授权越过。
        // Shizuku/MCP 的守夜拒绝与上方灾难性 rm 检查已先行，不会被本分支覆盖。
        if (activeRun != null && mode == com.aicode.feature.agent.domain.model.AgentMode.BUILD) {
            // C 类核心配置写入（hooks/mcp/agents/teams）：守夜「永不自动放行」，预授权不覆盖，
            // 落到主链评估（hooks 守夜拒绝；其余 ASK 后走兜底超时）。
            val protectedWrite = targetsProtectedConfig(toolName, args)
            if (!protectedWrite) {
                if (isShellTool(toolName, args)) {
                    val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                    if (command != null) {
                        val analysis = ShellCommandParser.analyze(command)
                        checkCatastrophicRm(analysis.segments)?.let { return elevationOrDeny(it, args, watchMode) }
                        if (!analysis.analyzable && looksDestructive(command)) {
                            return elevationOrDeny(unanalyzableReason(analysis), args, watchMode)
                        }
                    }
                }
                val commandText = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.contentOrNull
                when (val verdict = ScheduledAuthCategoryMapping.judge(
                    capabilities, commandText, activeRun.authorizedCategories, toolName
                )) {
                    is AuthCategoryVerdict.Covered -> return EvalResult(
                        Verdict.ALLOW,
                        emptyList(),
                        watchAutoAllow = "定时任务「${activeRun.taskName}」预授权放行（${verdict.reason}）"
                    )
                    is AuthCategoryVerdict.NotCovered,
                    AuthCategoryVerdict.NotApplicable -> Unit
                }
            }
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // task 只读动作（read/list）：不放行 DENY 规则，其余直接自动放行（不弹窗）。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) {
                val rules = rulesRepo.loadEffectiveForCurrentProject().filter { it.toolName == toolName }
                val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
                if (whole.any { it.decision == PermissionDecision.DENY }) {
                    return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
                }
                return EvalResult(Verdict.ALLOW, emptyList())
            }
        }

        val rules = rulesRepo.loadEffectiveForCurrentProject().filter { it.toolName == toolName }
        return if (isShellTool(toolName, args)) {
            evaluateShell(
                rules,
                args,
                forceAsk = toolName == SHIZUKU_TOOL,
                tier = tier,
                watchMode = watchMode,
                scheduledRun = activeRun != null
            )
        } else if (targetsHooksConfig(toolName, args)) {
            if (watchMode) {
                // 守夜：hooks.json 写入直接拒绝 + 记录（脚本会在每次工具调用时静默执行，无人值守不放开）。
                EvalResult(
                    Verdict.DENY,
                    emptyList(),
                    denyReason = "守夜模式：hooks.json 会在每次工具调用时静默执行脚本，属高危配置，已自动拒绝。请留待用户在场时修改。"
                )
            } else {
                // 工具钩子配置写入视为高危：hooks.json 里的脚本会在每次工具调用时零授权静默执行，
                // 等同 Agent 配置修改——永不可记忆，仅单次放行（防「始终允许 writeFile」后经提示注入获得静默命令执行）
                EvalResult(
                    Verdict.ASK,
                    rememberablePatterns = emptyList(),
                    rememberDisabledReason = "该文件配置工具钩子（脚本会在每次工具调用时静默执行），仅支持单次放行"
                )
            }
        } else {
            evaluateGeneric(rules, capabilities, tier = tier, scheduledRun = activeRun != null)
        }
    }

    /** 写入目标是否为工具钩子配置（全局 ~/.aicode/hooks.json，容器内 /root/.aicode/hooks.json）。 */
    private fun targetsHooksConfig(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName != "writeFile" && toolName != "editFile") return false
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return false
        return path == "/root/.aicode/hooks.json" || path.endsWith("/.aicode/hooks.json") || path == ".aicode/hooks.json"
    }

    /**
     * 预授权不可覆盖的守夜「永不自动放行」配置路径（hooks.json / mcp.json / agents / teams）。
     * 命中时预授权直接跳过，让主链评估（hooks 守夜拒绝；其余 ASK 后走兜底超时）接住。
     */
    private fun targetsProtectedConfig(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName != "writeFile" && toolName != "editFile") return false
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return false
        val p = path.replace('\\', '/')
        if (p.endsWith("/.aicode/hooks.json") || p == ".aicode/hooks.json") return true
        if (p.endsWith("/.aicode/mcp.json") || p == ".aicode/mcp.json") return true
        return p.contains("/.aicode/agents/") || p.startsWith(".aicode/agents/") ||
            p.contains("/.aicode/teams/") || p.startsWith(".aicode/teams/")
    }

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // 只读探索工具在 PLAN 模式下永远安全
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** 调用是否携带提权参数（`elevate: true`）。 */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * 该命令是否疑似破坏性（仅在命令不可静态判定时使用）：含输出重定向、`-delete`
     * 或常见破坏性程序名。宁可多问，不误放。
     */
    private fun looksDestructive(command: String): Boolean =
        command.contains('>') || command.contains("-delete") || DESTRUCTIVE_PROGRAM.containsMatchIn(command)

    /**
     * 灾难性删除的裁决：携带提权参数时降级为一次性用户授权（ASK），否则硬拒绝（DENY）
     * 并在原因里提示可提权重试。AUTO 与非 AUTO 模式共用。提权仅对本次调用生效，不可记忆。
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>, watchMode: Boolean = false): EvalResult =
        if (!watchMode && isElevationRequested(args)) {
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "高危操作提权确认",
                rememberDisabledReason = "命令命中内置安全防护，提权仅支持单次放行，不可记忆"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = if (watchMode) {
                    "$catastrophicReason。守夜模式下该操作永不放行（提权亦不可用），请改用安全方式：可逆操作（如先 mv 到临时目录）或工作区内明确路径。"
                } else {
                    "$catastrophicReason。如确需执行，可在调用时加 `$ELEVATE_ARG: true` 重试，系统将向用户请求授权。"
                }
            )
        }

    /** 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。 */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    /** 工具档位查询（工作流/UI 用）：显式设置优先，否则目录默认档位。 */
    suspend fun tierOf(toolName: String): ToolPermissionTier = toolPermissionTiers.tierOf(toolName)

    private fun evaluateGeneric(rules: List<PermissionRule>, capabilities: Set<ToolCapability>, tier: ToolPermissionTier, scheduledRun: Boolean = false): EvalResult {
        val whole = rules.filter { it.pattern == PermissionRule.WHOLE_TOOL }
        if (whole.any { it.decision == PermissionDecision.DENY }) return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具被项目权限规则策略禁止执行")
        // 定时 AI 任务运行中：历史记忆的整工具放行与档位兜底不生效（预授权清单为任务级权威，未覆盖项走通知授权）
        if (!scheduledRun && whole.any { it.decision == PermissionDecision.ALLOW }) return EvalResult(Verdict.ALLOW, emptyList())
        // 工具档位「自动允许」：用户显式设置，作兜底放行（在 DENY 与已记忆规则之后）。
        if (!scheduledRun && tier == ToolPermissionTier.AUTO) return EvalResult(Verdict.ALLOW, emptyList())
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
            )
        }
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** 单个删除目标的裁决：命中受保护范围返回对应原因，否则 null。 */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // 工作区子目录（构建产物等）属正常操作，置于系统目录判定之前放行
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * 词法路径归一化：把开头的 `~` / `$HOME` / `${HOME}` 展开为容器家目录，折叠重复 `/`，
     * 解析 `.` 与 `..`。纯字符串处理、不访问文件系统，故无法覆盖 `$(...)` 等运行时才可知的路径。
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    /**
     * 守夜专用：判断删除目标是否全部安全（null=全部安全；否则返回首个不安全原因）。
     * 安全范围 = 工作区（含相对路径——命令 cwd 即工作区）与容器 /tmp；`.git` 内部除外；
     * 未展开变量（`$DIR` 等）、find -exec 等不确定目标一律不安全。
     */
    private fun watchRmRejectReason(targets: List<String>): String? {
        for (raw in targets) {
            if (raw == ShellCommandParser.FIND_EXEC_MARKER) {
                return "命令通过 find -exec/-delete 删除文件，目标无法明确核对"
            }
            val normalized = normalizePath(raw)
            if (normalized.contains('$')) {
                return "删除目标含未展开的变量（${raw}），实际路径不可确认（空变量可能展开为根路径）"
            }
            if (!isWatchSafeRmTarget(normalized)) {
                return "删除目标「${raw}」不在工作区或临时目录内"
            }
        }
        return null
    }

    /** 归一化后的路径是否在守夜安全范围（工作区子路径 / 容器临时目录；不含 .git 内部）。 */
    private fun isWatchSafeRmTarget(normalized: String): Boolean {
        val p = normalized
        if (p.isEmpty() || p == "." || p.startsWith("..")) return false
        if (p == ".git" || p.startsWith(".git/") || p.contains("/.git/") || p.endsWith("/.git")) return false
        if (p.startsWith("/root/workspace/") || p.startsWith("workspace/")) return true
        if (p.startsWith("/tmp/")) return true
        // 其余相对路径：命令 cwd 即工作区，视为工作区内。
        return !p.startsWith("/")
    }

    private fun evaluateShell(
        rules: List<PermissionRule>,
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false,
        tier: ToolPermissionTier = ToolPermissionTier.ASK,
        watchMode: Boolean = false,
        /** 定时 AI 任务运行中：删除动作不自动放行（预授权类别未覆盖时走通知授权），保持「删除类单独放行」语义。 */
        scheduledRun: Boolean = false
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)
        val allow = rules.filter { it.decision == PermissionDecision.ALLOW }
        val deny = rules.filter { it.decision == PermissionDecision.DENY }

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录。
        //    携带提权参数时降级为一次性用户授权（守夜下永不放行）。
        val catastrophicReason = checkCatastrophicRm(analysis.segments)
        if (catastrophicReason != null) {
            return elevationOrDeny(catastrophicReason, args, watchMode)
        }

        // 1) DENY 优先（含对内置安全白名单的覆盖）：任一段命中 DENY 即拒。
        if (analysis.segments.any { seg -> deny.any { ShellCommandParser.matches(it.pattern, seg) } }) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "该命令被项目权限规则策略禁止执行")
        }

        // 2) Shizuku 高危工具：无论内置白名单或已记忆规则，一律弹窗且不可记忆。
        if (forceAsk) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 3) 不可静态判定 → 守夜分支（破坏性拒绝+引导 / 非破坏性放行+记录）；否则必须弹窗、不可记忆（内置白名单也不适用）。
        //    定时 AI 任务运行中，预授权清单是唯一权威：不可静态判定的命令不得借守夜分支放行
        //    （否则任意 shell 都能绕过清单），落到 ASK 走通知授权/兑底超时。
        if (!analysis.analyzable) {
            if (watchMode) {
                return if (looksDestructive(command)) {
                    EvalResult(
                        Verdict.DENY,
                        emptyList(),
                        denyReason = "守夜模式：命令包含命令替换/子 shell/绝对路径重定向等无法静态判定的构造且疑似破坏性，已自动拒绝（触发构造：${analysis.blockers.joinToString("、")}）。请改写为不含这些构造、路径明确可分析的简单命令后重试。"
                    )
                } else if (scheduledRun) {
                    EvalResult(
                        Verdict.ASK,
                        emptyList(),
                        rememberDisabledReason = "定时任务运行中：命令无法静态判定，不在预授权清单内，已转通知授权"
                    )
                } else {
                    EvalResult(
                        Verdict.ALLOW,
                        emptyList(),
                        watchAutoAllow = "守夜：命令不可静态判定但未见破坏性构造，放行并记录"
                    )
                }
            }
            return EvalResult(
                Verdict.ASK,
                emptyList(),
                // 把触发构造写进弹窗说明：用户与 AI 都能看出这条命令为什么没法自动判定。
                rememberDisabledReason = "命令含无法静态判定的构造（${analysis.blockers.joinToString("、").ifEmpty { "未知构造" }}），只能单次授权、不可记忆"
            )
        }

        // 3.5) 守夜：删除动作分级（本层 + 嵌套 shell / find 变体）。
        //      目标全在工作区或容器临时目录 → 放行 + 记录；否则（含未展开变量、find -exec、越界路径）拒绝 + 引导。
        //      定时 AI 任务运行时：删除必须由预授权 DELETE 类别覆盖（上方已判定），未覆盖则弹通知授权（不静默放行）。
        if (watchMode) {
            val rmTargets = ShellCommandParser.collectRmTargets(analysis.segments)
            if (rmTargets.isNotEmpty()) {
                val unsafeReason = watchRmRejectReason(rmTargets)
                return if (unsafeReason == null) {
                    if (scheduledRun) {
                        EvalResult(
                            Verdict.ASK,
                            rememberablePatterns = emptyList(),
                            rememberDisabledReason = "定时任务运行中：删除操作不在预授权范围内，请从通知一键批准，或等待 10 分钟自动拒绝"
                        )
                    } else {
                        EvalResult(
                            Verdict.ALLOW,
                            emptyList(),
                            watchAutoAllow = "守夜：删除目标均位于工作区或临时目录，放行并记录"
                        )
                    }
                } else {
                    EvalResult(
                        Verdict.DENY,
                        emptyList(),
                        denyReason = "守夜模式：$unsafeReason。请改用工作区内明确路径（可先 cd 到目标目录用相对路径），或改用可逆方式（如先 mv 到临时目录）。"
                    )
                }
            }
        }

        // 4) 内置安全白名单：每段都命中安全前缀（ls/git status 等）→ 自动放行，不弹窗。
        if (analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 5) 已记忆的 ALLOW：每段都命中 → 放行。
        // 对 rm 命令进行精细化校验：避免存量或宽泛的 "rm"/"rm -rf" 无目标规则放行高风险删除
        // 定时 AI 任务运行中（scheduledRun）：不以历史记忆静默放行——未覆盖项一律走通知授权（预授权清单为任务级权威）。
        val allAllowed = analysis.segments.isNotEmpty() &&
            analysis.segments.all { seg ->
                val rmInfo = ShellCommandParser.parseRmInfo(seg)
                allow.any { rule ->
                    if (!ShellCommandParser.matches(rule.pattern, seg)) {
                        false
                    } else if (rmInfo.isRm) {
                        val ruleRmInfo = ShellCommandParser.parseRmInfo(rule.pattern.split(Regex("\\s+")))
                        ruleRmInfo.targetPaths.isNotEmpty() && (!rmInfo.isRecursive || ruleRmInfo.isRecursive)
                    } else {
                        true
                    }
                }
            }
        if (!scheduledRun && allAllowed) return EvalResult(Verdict.ALLOW, emptyList())

        // 6) 否则弹窗，可记忆前缀（子命令分发器记 程序名+子命令）。
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        if (hasRmUnrememberable) {
            if (watchMode) {
                // 守夜：删除缺少明确目标或结构异常（已在前置分级之外）。
                return EvalResult(
                    Verdict.DENY,
                    emptyList(),
                    denyReason = "守夜模式：删除操作缺少明确目标或结构异常，已自动拒绝。请给出明确、可分析的目标路径。"
                )
            }
            return EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
            )
        }

        // 工具档位「自动允许」：用户显式设置，兜底放行（内置防护/Shizuku/不可静态判定/rm 高危均在前，仍保留）。
        // 定时 AI 任务运行中：任务预授权清单优先——档位兜底不生效（未覆盖项走通知授权）。
        if (!scheduledRun && tier == ToolPermissionTier.AUTO) return EvalResult(Verdict.ALLOW, emptyList())

        val rememberable = analysis.segments.mapNotNull { ShellCommandParser.rememberablePrefix(it) }.distinct()
        return EvalResult(Verdict.ASK, rememberable)
    }
}
