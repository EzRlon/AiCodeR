package com.aicode.feature.agent.domain.engine

import com.aicode.feature.agent.domain.model.AgentMode

/**
 * 会话工作模式（[AgentMode]）→ 各 CLI 引擎原生模式参数的映射。
 *
 * 对外只保留 BUILD/PLAN/AUTO 一个语义轴（与内置引擎共用同一套 UI 与权限模型），
 * 引擎原生模式是实现细节，多对一投影到这里。三个引擎的原生集合互不相同
 * （Claude 6 档 / Codex 沙箱 3 档 / OpenCode 7 个 agent），直接暴露会让同一个 App
 * 出现两套语义，且部分档位（compaction/summary/title）与"工作模式"无关。
 *
 * 纯函数、无 IO，映射表由 [EngineModeMappingTest] 逐格钉住。
 */
object EngineModeMapping {

    /** Claude `--permission-mode`。BUILD 用 manual：仍走权限桥逐次确认，与改造前行为一致。 */
    fun claudePermissionMode(mode: AgentMode): String = when (mode) {
        AgentMode.BUILD -> "manual"
        AgentMode.PLAN -> "plan"
        AgentMode.AUTO -> "bypassPermissions"
    }

    /**
     * Claude `--permission-prompts`。默认值是 host（由 --permission-prompt-tool 的权限桥应答）。
     * PLAN/AUTO 下不该有人被问：传 none 使无人应答的请求直接拒绝，而不是挂起等 5 分钟超时。
     */
    fun claudePermissionPrompts(mode: AgentMode): String = when (mode) {
        AgentMode.BUILD -> "host"
        AgentMode.PLAN, AgentMode.AUTO -> "none"
    }

    /** 原生权限模式 → 额外环境变量。容器内 CLI 以 root 运行，claude 对 bypassPermissions 直接秒退。 */
    fun claudeExtraEnv(nativePermissionMode: String): Map<String, String> =
        if (nativePermissionMode == "bypassPermissions") mapOf("IS_SANDBOX" to "1") else emptyMap()

    /**
     * Codex `-s/--sandbox`。
     *
     * 注意：`codex exec` 没有 `-a/--ask-for-approval`（实测报 unexpected argument），
     * 所以 exec 模式下沙箱是唯一杠杆。BUILD 因此是「工作区内自动执行、区外被挡」，
     * 不像内置引擎那样每次弹窗——这是引擎限制，需在文档与模式说明条里讲清楚。
     */
    fun codexSandbox(mode: AgentMode): String = when (mode) {
        AgentMode.BUILD -> "workspace-write"
        AgentMode.PLAN -> "read-only"
        AgentMode.AUTO -> "danger-full-access"
    }

    /** OpenCode `--agent`。PLAN 用引擎自带的只读 plan agent；其余用默认 build agent。 */
    fun openCodeAgent(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> "plan"
        else -> null
    }

    /** OpenCode `--auto`：只有 AUTO 全放行。 */
    fun openCodeAuto(mode: AgentMode): Boolean = mode == AgentMode.AUTO
}
