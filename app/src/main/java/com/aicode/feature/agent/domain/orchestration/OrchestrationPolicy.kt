package com.aicode.feature.agent.domain.orchestration

/**
 * 外部 CLI 引擎（Claude Code / Codex / OpenCode）的主 Agent 编排守则。
 *
 * 为什么要有这一段：三个引擎各有自己的默认行为，App 此前只注入了语言与项目知识，
 * 主 Agent 是否派活、是否汇报、是否留证据完全靠引擎自觉。这里用一段短提示把它们
 * 收敛到同一套「分层干活 + 任务板 + 结构化上报 + 证据验收」的纪律上。
 *
 * 刻意不写死「主 Agent 不许干活」：问答与小修直接做最快；多文件与并行任务才派发。
 * 文本保持精简（约 1KB），每回合随系统提示注入，避免成为 token 负担。
 */
object OrchestrationPolicy {

    val BASE: String = """
## 主 Agent 编排守则（AiCodeR）
你是本会话的主 Agent，最终对用户交付负责。按任务规模分档，不要为了派发而派发：
- 问答、单文件小改、方案讨论：直接做。
- 多文件功能、重构、跨模块改动：先定计划与验收标准，自己保留集成、审核与用户沟通；把可独立的部分派给子代理（Task / agent），一次交代完整：目标、已知上下文、边界、期望产出、验证标准。
- 大范围并行、多角度调研或评审：建任务板，按依赖派发，不要重复派同一件事。

任务板（共享白板）：用 `orchestration_board` 工具维护任务，action = list / create / claim / update / read。
- 开工前先 list，看已有任务与状态，不要重做别人已认领的活。
- 认领后置 doing；等用户决策置 waiting（needs 写清要用户决定什么）；缺权限、缺工具、被外部条件阻塞置 blocked（needs 写清缺什么）；完成置 done，evidence 必须写命令/测试/文件等可核对的证据，空写会被拒绝。
- 一个任务只由一名 owner 负责；需要多人时拆成多个有依赖的任务。
- 不要删任务、不要跳步；状态与证据是给用户和接手者看的。

进度与沟通：只在关键节点、需要用户决策、完成或失败时汇报；不要逐条转述子代理日志。子代理只回最后一段结论，需要细节就在派活时写进期望产出。
审核：重要改动派独立审查（qa 或只读子代理），不要自审自签收；未验证项如实标注。
权限不足：不要绕过。把 blocked + needs 写进任务板，向用户申请一次性授权，或改派工具更全的角色。
用户中途反馈优先：以用户最新意见调整计划与任务板，不要机械执行旧计划。
""".trim()

    /**
     * 会话绑定了专家团时追加的团长指令：团队定义正文 + 成员 + 生命周期 + 门禁。
     * 与内置引擎的 TeamLeaderSource 同源，保证两套引擎下「团长」是同一个角色。
     */
    fun teamSection(
        teamName: String,
        teamPrompt: String,
        members: List<Pair<String, String>>,
        lifecycle: List<String>,
        gates: List<String>,
        /** 当前引擎是否支持按名字派发自定义成员（Codex 不支持，只能按分工作参考）。 */
        dispatchable: Boolean = true,
        /** 未映射到引擎的成员名：列出来供分工参考，但标注不可直接派发。 */
        unmapped: Set<String> = emptySet()
    ): String = buildString {
        append("## 专家团：").append(teamName).append('\n')
        append(teamPrompt.trim())
        if (members.isNotEmpty()) {
            if (dispatchable) {
                append("\n\n你的成员（用 Task / agent 按名字派发）：\n")
            } else {
                append("\n\n成员分工（本引擎不支持自定义成员派发，以下仅作分工参考；由你按角色串行完成或使用引擎自带协作能力）：\n")
            }
            members.forEach { (name, desc) ->
                append("- ").append(name)
                if (desc.isNotBlank()) append(": ").append(desc.lineSequence().first().take(120))
                if (unmapped.any { it.equals(name, ignoreCase = true) }) {
                    append("（引擎侧未映射，无法直接派发）")
                }
                append('\n')
            }
        }
        if (lifecycle.isNotEmpty()) {
            append("\n## 生命周期（按序推进）\n")
            lifecycle.forEachIndexed { index, node -> append(index + 1).append(". ").append(node).append('\n') }
            append("按节点顺序推进，每节点完成再进下一节点；用户可随时要求跳过或调整顺序。\n")
        }
        if (gates.isNotEmpty()) {
            append("\n## 验收门禁\n")
            gates.forEach { gate -> append("- ").append(gate).append('\n') }
            append("门禁未满足前不要宣称完成。\n")
        }
    }.trim()
}
