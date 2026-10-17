package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.domain.team.TeamRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 多数 Source 维护内容缓存，避免重复读取与格式化；静态基线片段除外，每次读盘以保证编辑即时生效。
 *
 * 片段分两类：
 * - 静态基线：`prompts/` 顶层 `<NN>-<名称>.md`，可被 `prompts.custom/` 按数字身份覆盖或新增；
 * - 按需叶子：`prompts/agent/` 下的无数字片段（模式提醒、子代理基线、压缩/标题提示词），按精确同名覆盖。
 *
 * `prompts.custom/` 存在 [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 时，主代理提示词只由自定义数字片段组成，
 * 不再注入任何内置来源；此时用 `{{AICODE_*}}` 变量按需取回动态内容。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val promptFragmentCatalog: PromptFragmentCatalog,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val teamRepository: TeamRepository,
    private val executionModeHolder: ExecutionModeHolder
) {
    // 抽象独立的 Source
    interface PromptSource {
        fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        // 每次都重新读盘：未编辑时字符串一致，KV Cache 照常命中；编辑后立即生效，无需重启。
        // 技能/记忆等运行期内容由末尾的 99-runtime-context.md 片段用 {{AICODE_*}} 变量承载。
        override fun build(ctx: AgentContext): String = promptFragmentCatalog.renderStatic(ctx.projectRoot)
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空内容用 "" 占位以区分"未缓存"。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val skills = try { skillRepository.listSkills() } catch (e: Exception) { return null }
            if (skills.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "（无描述）" } }" }
            val content = list
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含模式切换、结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * 可用子代理清单（仅注入主代理）：让 AI 知道有哪些自定义 agent 可派发。
     * 会话级缓存，避免每轮扫盘导致 system prompt 抖动打断 KV 缓存。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val entries = try {
                agentDefinitionRepository.listEnabled()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描子代理定义失败: ${e.message}", e)
                return null
            }
            if (entries.isEmpty()) {
                cachedByKey[key] = ""
                return null
            }

            val list = entries.joinToString("\n") { entry ->
                "- ${entry.definition.name}: ${entry.definition.description.ifBlank { "（无描述）" }}"
            }
            val content = list
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 编队团长指令：会话召唤了编队时注入（团长提示词 + 成员名单）；子代理路径不注入。 */
    private inner class TeamLeaderSource : PromptSource {
        override fun build(ctx: AgentContext): String {
            val team = teamRepository.resolve(ctx.teamName) ?: return ""
            val memberLines = team.members.joinToString("\n") { memberName ->
                val desc = agentDefinitionRepository.findIncludingDisabled(memberName)?.description.orEmpty()
                if (desc.isNotBlank()) "- $memberName: $desc" else "- $memberName"
            }
            return buildString {
                append("## 编队：").append(team.name).append('\n')
                append(team.prompt)
                if (memberLines.isNotBlank()) {
                    append("\n\n你的成员（用 task 工具按 agent 名派发）：\n").append(memberLines)
                }
            }
        }
    }

    private inner class ProjectRuleSource {
        @Volatile private var cached: ProjectRules? = null
        private var lastSignature: String = ""

        /**
         * 同时读取全局规则（`~/.aicode`）与工作区规则（项目根目录）：各自 `AGENTS.md` 优先、`CLAUDE.md` 兜底。
         * 任一来源为空则该项为 null；两处文件签名（路径 + mtime）未变时复用缓存。
         */
        fun build(ctx: AgentContext): ProjectRules? {
            val global = resolveRuleFile(containerInstaller.aicodeDir)
            val project = ctx.projectRoot.takeIf { it.isNotBlank() }?.let { resolveRuleFile(File(it)) }
            if (global == null && project == null) return null

            val signature = buildString {
                append(global?.first?.absolutePath).append(':').append(global?.first?.lastModified() ?: 0)
                append('|')
                append(project?.first?.absolutePath).append(':').append(project?.first?.lastModified() ?: 0)
            }
            if (signature == lastSignature && cached != null) return cached

            val rules = ProjectRules(
                global = global?.first?.let { readRule(it) },
                project = project?.first?.let { readRule(it) }
            )
            cached = rules
            lastSignature = signature
            return rules
        }

        private fun resolveRuleFile(dir: File): Pair<File, String>? {
            val agents = File(dir, AGENTS_FILE)
            if (agents.isFile && agents.canRead()) return agents to AGENTS_FILE
            val claude = File(dir, CLAUDE_FILE)
            if (claude.isFile && claude.canRead()) return claude to CLAUDE_FILE
            return null
        }

        private fun readRule(file: File): String? {
            val text = try { file.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            return if (text.length > MAX_AGENTS_CHARS) {
                (text.take(MAX_AGENTS_CHARS) + "\n…（规则过长，已截断）").trim()
            } else {
                text.trim()
            }
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override fun build(ctx: AgentContext): String =
            if (ctx.projectRoot.isNotBlank()) "~/workspace" else "（未选择工作区）"
    }

    private inner class EnvironmentSource : PromptSource {
        override fun build(ctx: AgentContext): String =
            when (executionModeHolder.currentMode()) {
                ExecutionMode.LOCAL_PROOT ->
                    if (executionModeHolder.isSelfImproveMode()) "自我改进模式"
                    else "本地容器（PRoot）"
                ExecutionMode.REMOTE_SSH -> "远程 SSH 服务器"
            }
    }

    /** 自我改进模式指令：仅开关开启时注入（主会话路径；子代理不参与自我改进）。 */
    private inner class SelfImproveInstructionsSource : PromptSource {
        override fun build(ctx: AgentContext): String =
            if (executionModeHolder.isSelfImproveMode()) SELF_IMPROVE_INSTRUCTIONS else ""
    }

    /** 记忆清单拆分为全局/项目两组纯列表，供 `{{AICODE_MEMORY_GLOBAL}}` / `{{AICODE_MEMORY_PROJECT}}` 变量使用。 */
    private data class MemoryLists(val global: String?, val project: String?)

    /** 项目规则正文拆分为全局（`~/.aicode`）与工作区两组，供 `{{AICODE_PROJECT_RULES_GLOBAL}}` / `{{AICODE_PROJECT_RULES_PROJECT}}` 变量使用。 */
    private data class ProjectRules(val global: String?, val project: String?)

    private inner class MemoryListSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只读一次盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空组用 null 字段表示。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, MemoryLists>()

        fun build(ctx: AgentContext): MemoryLists {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            cachedByKey[key]?.let { return it }
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) {
                return MemoryLists(null, null)
            }
            val global = memories.filter { it.scope == MemoryScope.GLOBAL }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" } }" }
            val project = memories.filter { it.scope == MemoryScope.PROJECT }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" } }" }
            val result = MemoryLists(global, project)
            cachedByKey[key] = result
            trimIfNeeded()
            return result
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。 */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val memoryListSource = MemoryListSource()

    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val environmentSource = EnvironmentSource()
    private val selfImproveInstructionsSource = SelfImproveInstructionsSource()
    private val teamLeaderSource = TeamLeaderSource()

    private val customDir: File
        get() = File(containerInstaller.aicodeDir, "prompts.custom")

    /** 自定义目录顶层数字片段（数字身份 → 文件），进程内只扫一次（重启 App 才刷新）。 */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        if (PromptFragmentResolver.isBuiltinDisabled(customDir)) {
            return buildCustomOnly(agentContext)
        }

        // 1. 获取各个 Source 的基线快照。
        val rawStatic = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memories = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        val workspaceContent = workspaceSource.build(agentContext)
        val environmentContent = environmentSource.build(agentContext)
        val selfImproveContent = selfImproveInstructionsSource.build(agentContext)

        // 2. 变量就地展开：`{{AICODE_*}}` 替换为纯数据；未写变量的片段原样保留。
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memories.global,
            memories.project,
            subAgentsContent,
            projectRules?.global,
            projectRules?.project,
            workspaceContent,
            environmentContent,
            currentDate(),
            selfImprove = selfImproveContent,
            modelContext = modelContextText(agentContext)
        )

        // 4. 压缩空清单展开留下的多余空行；稳定基线在前、动态内容集中在末尾片段，均利于 KV Cache。
        // 编队团长指令附加在末尾：会话级内容、变更少，对前缀 KV Cache 影响最小。
        val teamContent = teamLeaderSource.build(agentContext)
        val withTeam = if (teamContent.isNullOrBlank()) staticContent else "$staticContent\n\n$teamContent"
        return collapseBlankLines(withTeam)
    }

    /**
     * [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 生效时：只输出 `prompts.custom/` 顶层的数字片段，
     * 不注入任何内置来源；动态内容仅通过 `{{AICODE_*}}` 变量按需取回。
     */
    private fun buildCustomOnly(ctx: AgentContext): String {
        val content = promptFragmentCatalog.renderCustomOnly(ctx.projectRoot)
        if (content.isEmpty()) {
            FileLogger.w(
                TAG,
                "已启用 ${PromptFragmentResolver.DISABLE_BUILTIN_FILE}，但 $customDir 下没有 <两位数字>-<名称>.md 片段，系统提示词为空"
            )
            return ""
        }
        val memories = memoryListSource.build(ctx)
        val projectRules = projectRuleSource.build(ctx)
        val rendered = renderVariables(
            content,
            activeSkillsSource.build(ctx),
            memories.global,
            memories.project,
            subAgentListSource.build(ctx),
            projectRules?.global,
            projectRules?.project,
            workspaceSource.build(ctx),
            environmentSource.build(ctx),
            currentDate(),
            selfImprove = selfImproveInstructionsSource.build(ctx),
            modelContext = modelContextText(ctx)
        )
        val teamContent = teamLeaderSource.build(ctx)
        return if (teamContent.isNullOrBlank()) rendered else "$rendered\n\n$teamContent"
    }

    /**
     * 按子代理定义组装提示词：只注入 [AgentDefinition.inject] 列出的片段，再接 agent 自己的提示词。
     * 不注入可用子代理清单（子代理不能嵌套派发）。定义正文里的 `{{AICODE_*}}` 变量同样会展开。
     */
    private fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String = buildString {
        if (InjectPart.MAIN_RULES in definition.inject) {
            append(staticRuleSource.build(agentContext))
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext))
            append("\n\n")
        }

        val memories = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        append(
            renderVariables(
                definition.prompt,
                activeSkillsSource.build(agentContext),
                memories.global,
                memories.project,
                subAgentListSource.build(agentContext),
                projectRules?.global,
                projectRules?.project,
                workspaceSource.build(agentContext),
                environmentSource.build(agentContext),
                currentDate(),
                modelContext = modelContextText(agentContext)
            )
        )

        if (InjectPart.SKILLS in definition.inject) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject) {
            listOfNotNull(memories.global, memories.project).takeIf { it.isNotEmpty() }?.let { groups ->
                append("\n\n")
                append(groups.joinToString("\n\n"))
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject) {
            val rules = projectRuleSource.build(agentContext)
            listOfNotNull(rules?.global, rules?.project).takeIf { it.isNotEmpty() }?.let { parts ->
                append("\n\n")
                append(parts.joinToString("\n\n"))
            }
        }

        append("\n\n")
        append(
            renderVariables(
                resolvePrompt(SUBAGENT_CONTEXT_FILE),
                null,
                null,
                null,
                null,
                null,
                null,
                workspaceSource.build(agentContext),
                environmentSource.build(agentContext),
                currentDate(),
                definition.name,
                modelContext = modelContextText(agentContext)
            )
        )
    }

    /** 把片段里的 `{{AICODE_*}}` 占位符替换为真实内容；未出现的占位符保持原样，不影响 `{{INSTRUCTION}}` 等其它占位符。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memoryGlobal: String?,
        memoryProject: String?,
        subAgents: String?,
        projectRulesGlobal: String?,
        projectRulesProject: String?,
        workspace: String,
        environment: String,
        date: String,
        subAgentName: String = "",
        selfImprove: String? = null,
        modelContext: String = ""
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_GLOBAL_VAR, memoryGlobal.orEmpty())
        out = out.replace(MEMORY_PROJECT_VAR, memoryProject.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_GLOBAL_VAR, projectRulesGlobal.orEmpty())
        out = out.replace(PROJECT_RULES_PROJECT_VAR, projectRulesProject.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRulesProject.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(ENVIRONMENT_VAR, environment)
        out = out.replace(DATE_VAR, date)
        out = out.replace(SELF_IMPROVE_VAR, selfImprove.orEmpty())
        out = out.replace(SUBAGENT_NAME_VAR, subAgentName)
        out = out.replace(MODEL_CONTEXT_VAR, modelContext.ifBlank { "未知" })
        return out
    }

    /** 当前模型及其上下文窗口（运行时注入；窗口在会话内稳定，不随时变动，避免打断 KV Cache）。 */
    private fun modelContextText(ctx: AgentContext): String {
        val name = ctx.modelName?.takeIf { it.isNotBlank() } ?: return ""
        if (ctx.modelContextTokens <= 0) return name
        val window = if (ctx.modelContextTokens % 1000 == 0) "${ctx.modelContextTokens / 1000}K"
        else ctx.modelContextTokens.toString()
        return "$name（上下文窗口 $window tokens）"
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /** 把连续 3 个以上换行压成 2 个：变量展开后空清单会留下额外空行。 */
    private fun collapseBlankLines(text: String): String =
        text.trim().replace(Regex("\\n{3,}"), "\n\n")

    /**
     * 按优先级解析单个提示词片段：
     * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
     * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
     * 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
     *
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
     */
    fun resolvePrompt(name: String): String {
        PromptFragmentResolver.parseNumber(name)
            ?.let { number -> readFileOrNull(customFragmentsByNumber[number])?.let { return it } }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.aicodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val SUBAGENT_CONTEXT_FILE = "agent/subagent-context.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 内置静态基线：数字身份 → 规范文件名，决定默认拼接顺序。 */
        // 片段里可用的运行期变量，渲染时替换为真实内容
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_GLOBAL_VAR = "{{AICODE_MEMORY_GLOBAL}}"
        const val MEMORY_PROJECT_VAR = "{{AICODE_MEMORY_PROJECT}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val PROJECT_RULES_GLOBAL_VAR = "{{AICODE_PROJECT_RULES_GLOBAL}}"
        const val PROJECT_RULES_PROJECT_VAR = "{{AICODE_PROJECT_RULES_PROJECT}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val ENVIRONMENT_VAR = "{{AICODE_ENVIRONMENT}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
        const val SUBAGENT_NAME_VAR = "{{AICODE_SUBAGENT_NAME}}"
        const val SELF_IMPROVE_VAR = "{{AICODE_SELF_IMPROVE}}"
        const val MODEL_CONTEXT_VAR = "{{AICODE_MODEL_CONTEXT}}"

        /** 自我改进模式指令正文；仅开关开启时经 [SELF_IMPROVE_VAR] 注入主会话。 */
        const val SELF_IMPROVE_INSTRUCTIONS =
            "## 自我改进模式\n" +
                "你正处于自我改进模式。利用这次机会反思表现并把改进沉淀下来：\n" +
                "1. 技能：在 ~/.aicode/skills/<name>/SKILL.md 新建或更新技能（frontmatter 含 name/description，可附脚本）。改进应解决实际问题、避免重复现有功能。\n" +
                "2. 提示词：在 ~/.aicode/prompts.custom/ 新建数字片段（如 00-<名称>.md）或改进现有片段，让常见任务更高效。\n" +
                "安全约束：绝不得修改应用源码（app/src/main/java、app/src/main/res）、构建配置、工作区文件；不确定时默认禁止。\n" +
                "步骤：a) 反思最近交互的得失；b) 选一个具体改进项；c) 落盘；d) 在回复中用一两句说明改动与目的。\n" +
                "目标不是一次大改，而是持续的小改进——改进保存在 ~/.aicode/，软件升级时会被继承。"
    }
}