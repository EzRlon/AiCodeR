package com.aicode.feature.agent.domain.team

/** 专家团定义的来源作用域：全局（跨项目共享）或项目级（仅当前工作区生效，可 git 追踪）。 */
enum class TeamDefinitionScope { GLOBAL, PROJECT }

/**
 * 一个「专家团·专家团」定义，来自 `teams/<name>.md` 的 frontmatter + 正文。
 * 召唤后主代理充当团长（子代理不可嵌套派发），按 [prompt] 指挥 [members] 中的子代理分工。
 *
 * @param name 唯一标识（与文件名一致），会话通过它引用专家团
 * @param description 何时该用这个专家团，供选择器展示
 * @param members 成员的子代理名列表（对应 `~/.aicode/agents` / 项目级 agents 中的定义）
 * @param lifecycle 可选生命周期节点（按序）：团长按它引导分派顺序，如「需求澄清、方案、实现、验证、交付」；
 *   空则团长按通用纪律自主判断
 * @param gates 可选门禁说明（按序）：节点必须满足的验收条件，如「验证：qa 报告存在且无未处理失败项」；
 *   空则仅沿用系统级验收纪律
 * @param prompt 团长提示词（正文），召唤后注入主代理系统提示词
 * @param filePath 定义文件的容器路径，供设置页展示
 */
data class TeamDefinition(
    val name: String,
    val description: String,
    val members: List<String>,
    val prompt: String,
    val lifecycle: List<String> = emptyList(),
    val gates: List<String> = emptyList(),
    val filePath: String? = null
)

/** 一个生效的专家团条目：定义本体 + 来源作用域，供 UI 标注「全局/项目」。 */
data class TeamDefinitionEntry(
    val definition: TeamDefinition,
    val scope: TeamDefinitionScope
)
