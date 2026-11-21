package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.model.AgentMode

/**
 * 引擎原生工作模式目录。
 *
 * 每个引擎有自己的一套档位，界面直接列出该引擎的原生档位（引擎有多少种就显示多少种），
 * 而不是把三档语义压扁后再投影回去。[AgentMode] 仍是内部语义轴（权限门禁、子代理继承、
 * 计划工具、定时任务都依赖它），由 [semantic] 从原生档位派生，避免多出第四条并行语义。
 */
object EngineModeCatalog {

    /** 一个引擎原生档位：id 即传给 CLI 的原生取值。 */
    data class Entry(
        val id: String,
        /** 语义投影：决定权限门禁、PLAN 拦截、子代理继承等内部行为。 */
        val semantic: AgentMode,
        /** 是否为该引擎的默认档位（切换引擎或旧会话无记录时落到这里）。 */
        val isDefault: Boolean = false
    )

    /** 本体内置工作流：语义轴自身即档位。 */
    val BUILTIN: List<Entry> = listOf(
        Entry(AgentMode.BUILD.name, AgentMode.BUILD, isDefault = true),
        Entry(AgentMode.PLAN.name, AgentMode.PLAN),
        Entry(AgentMode.AUTO.name, AgentMode.AUTO)
    )

    /** Claude Code `--permission-mode` 的六个原生取值。 */
    val CLAUDE_CODE: List<Entry> = listOf(
        Entry("manual", AgentMode.BUILD, isDefault = true),
        Entry("acceptEdits", AgentMode.BUILD),
        Entry("plan", AgentMode.PLAN),
        Entry("auto", AgentMode.AUTO),
        Entry("dontAsk", AgentMode.BUILD),
        Entry("bypassPermissions", AgentMode.AUTO)
    )

    /** Codex `-s/--sandbox` 的三个原生取值。 */
    val CODEX: List<Entry> = listOf(
        Entry("read-only", AgentMode.PLAN),
        Entry("workspace-write", AgentMode.BUILD, isDefault = true),
        Entry("danger-full-access", AgentMode.AUTO)
    )

    /**
     * OpenCode 没有独立的档位枚举：`--agent build|plan` 决定身份，`--auto` 是独立的放行开关，
     * 二者组合出四种可选项，id 用「agent 或 agent+auto」表示。
     */
    val OPENCODE: List<Entry> = listOf(
        Entry("build", AgentMode.BUILD, isDefault = true),
        Entry("build+auto", AgentMode.AUTO),
        Entry("plan", AgentMode.PLAN),
        // plan+auto 仍是 plan 只读身份（--agent plan），语义轴必须跟身份走，不能因 --auto 误判为可写
        Entry("plan+auto", AgentMode.PLAN)
    )

    /**
     * DeepSeek Harness（dsh headless）`--permission-mode` 的原生 preset。
     * 0.2.2-rc.x 实测：known: read-only, workspace-write, danger-full-access（与 Codex 的
     * 只读沙箱同型），PLAN 语义可硬承载。
     */
    val DEEPSEEK: List<Entry> = listOf(
        Entry("read-only", AgentMode.PLAN),
        Entry("workspace-write", AgentMode.BUILD, isDefault = true),
        Entry("danger-full-access", AgentMode.AUTO)
    )

    /** 按引擎标识取原生档位清单；null / 未知引擎 = 本体内置工作流。 */
    fun entriesFor(engine: String?): List<Entry> = when (engine) {
        AgentEngines.CLAUDE_CODE -> CLAUDE_CODE
        AgentEngines.CODEX -> CODEX
        AgentEngines.OPENCODE -> OPENCODE
        AgentEngines.DEEPSEEK -> DEEPSEEK
        else -> BUILTIN
    }

    /** 该引擎的默认档位 id。 */
    fun defaultModeId(engine: String?): String =
        entriesFor(engine).first { it.isDefault }.id

    /**
     * 把会话里存的档位 id 归一到当前引擎的合法档位。
     * 引擎被切换过（旧档位属于另一个引擎）或记录缺失时回落到默认档，不越权。
     */
    fun normalize(engine: String?, modeId: String?): String {
        val entries = entriesFor(engine)
        return if (entries.any { it.id == modeId }) modeId!! else defaultModeId(engine)
    }

    /** 原生档位 → 语义档位；非法 id 按该引擎默认档处理。 */
    fun semantic(engine: String?, modeId: String?): AgentMode {
        val entries = entriesFor(engine)
        val id = normalize(engine, modeId)
        return entries.first { it.id == id }.semantic
    }

    /** OpenCode 档位是否带 `--auto`。 */
    fun openCodeAuto(modeId: String?): Boolean = modeId?.endsWith("+auto") == true

    /** OpenCode 档位对应的 `--agent` 取值；`plan+auto` 仍是 plan 身份。 */
    fun openCodeAgent(modeId: String?): String? = when {
        modeId == null -> null
        modeId.startsWith("build") -> "build"
        modeId.startsWith("plan") -> "plan"
        else -> null
    }
}
