---
name: self-improve-and-teams-order
description: 排期决定（2026-10-12 用户确认）：先做「自我改进模式」再做「专家团/编队」；含两者要点、开工前待核实点与安全提示
---
# 「自我改进模式」与「专家团」排期决定（2026-10-12 用户确认）

用户要求两项一起纳入、共同迭代进步，顺序由 AI 判断。

## 决定：先「自我改进模式」，后「专家团/编队」
理由：
1. 自我改进**更小、更独立、更低风险**——纯 DataStore 偏好键 + `ExecutionModeHolder` 轻量标志 + `SystemPromptProvider` 注入指令 + 设置页开关，几乎不碰核心链路。
2. 自我改进产出的产物（`~/.aicode/skills/` 技能、`~/.aicode/prompts/` 提示词片段）正是**专家团成员定义可复用的资产**——先有它，专家团才踩在更好的基础上。
3. 专家团**体量大**：新数据层（TeamDefinition/Parser/Source/Repository）+ 会话字段 `teamName` + Room **迁移 61**（`61_add_session_team.sql`）+ `TeamPickerSheet` + 设置页编队管理 + 预置 `assets/teams`，还需与人争迁移号，适合单独一批做。

## 自我改进模式要点（详见项目记忆 `self-improve-mode-plan`，另一会话拟定）
- 目标：AI 反思并更新自己的技能/提示词，写入 `~/.aicode/`（跨版本遗传）；硬约束：不许改应用源码/资源/配置/工作区；APK 增量 < 1.05 KB。
- 落点：`GeneralSettingsRepository`（`self_improve_mode` 偏好键）、`ExecutionModeHolder`（`_selfImprove` 标志，不动 ExecutionMode 枚举）、`SystemPromptProvider`（环境文案 + 新增指令来源）、设置页开关。
- **开工前要对真实代码核实的点**：①方案写 `~/.aicode/prompts.custom/`，实际目录是 `~/.aicode/prompts/`；②设置 UI 写「XML/Compose」，本项目是纯 Compose；③`SystemPromptProvider` 的 `EnvironmentSource`/`renderVariables` 内部结构需对着代码核。
- 安全：注入的指令必须明确禁止改应用文件；建议同时评估「AI 写 `~/.aicode/skills/` 是否要经权限引擎」——若它走了 Write 工具，需确认该路径的可写性与是否加不可记忆授权门槛（参考 hooks.json 的做法）。

## 专家团/编队要点（详见项目记忆 `roadmap-final` 的 D3 段）
- `teams/<name>.md` = 团长提示词 + 成员名单；会话级一键召唤；团长由**主代理**充当（子代理不可嵌套）；复用现成 `task` 派发引擎。
- 落点：存储层（镜像子代理栈 `feature/agent/domain/subagent`）+ 团长片段 `assets/prompts/agent/team-lead.md` + 会话字段 `teamName` 与迁移 `61_add_session_team.sql` + 聊天区 `TeamPickerSheet` + 设置页编队管理 + 预置 `assets/teams`。D4 编队看板为远期。

## 排期位置
- 与路线图批次的关系：这两项**不在**第 4 批（第 4 批 = C3 沙箱 + E3 记忆管理页 + E4 目标托管 + E5 定时任务）。E3/E5 已完成提交（`4a8f2e2`）；E4 由用户另开新会话做（落点见记忆 `e4-goal-hosting-plan`）；C3 排第 4 批最后。
- 建议作为**第 5 批**：自我改进模式 → 专家团（D3）→（原批次的 G 记忆图谱 P1 可与其一并考虑，注意迁移号协调）。