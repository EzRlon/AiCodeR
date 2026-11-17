---
name: handoff-capability-radar
description: 交接单（2026-10-14）：能力雷达 P0 完工待并入本批更新——AiCode 仓库 4 个未提交文件清单、验证状态、并入动作、真机验收项
---
# 交接单：能力雷达（AI 能力自感知 P0）→ 主施工会话

> **状态：✅ 已并入提交 `08f37e2`（2026-10-14，随「工具授权中心 v1」批次）**。AGENTS.md 待并入指针已更新。以下内容保留作历史追溯。
> 设计背景与本机制说明：`.aicode/notes/capability-awareness-plan.md`；工作区 `AGENTS.md` 已新增「能力感知（新功能交付纪律）」（对主会话即刻生效）。

## 一、待并入提交的文件（AiCode 仓库内，4 个）

| 文件 | 状态 | 说明 |
| --- | --- | --- |
| `AiCode/docs-site/docs/guide/capabilities.md` | 新增 | 能力地图：五域能力 + 组合菜谱 6 条 + 收录口径 |
| `AiCode/app/src/main/assets/prompts/65-capabilities.md` | 新增 | 能力雷达提示词片段：每轮注入的场景速查（编号 65，位于 60/70 之间） |
| `AiCode/docs-site/.vitepress/config.ts` | 修改 :107 | 侧栏「入门」组新增「能力地图（场景速查）」入口 |
| `AiCode/docs-site/docs/guide/overview.md` | 修改 :5 | 功能总览页新增能力地图引导句 |

并入动作：随本批 `git add` + 提交即可。建议提交信息（符合仓库 commit-msg 钩子规范）：
`feat(agent): 能力雷达 P0——能力地图 / 65 场景速查片段 / 能力感知交付纪律`

## 二、工作区根文件（非 git 仓库，无需提交，已生效）

- `AGENTS.md`：新增「能力感知（新功能交付纪律）」节——新功能必须带 AI 感知件（地图登记 + 如需则 65 更新），qa 审查覆盖「AI 能答出该功能」。
- `.aicode/notes/capability-awareness-plan.md`：设计 / 分期（P1＝注册表机制化、P2＝公告与英文）/ 维护指引。

## 三、验证状态（已完成）

- 构建 `:app:assembleUniversalDebug` 成功；APK 内 `assets/prompts/65-capabilities.md`、`assets/docs/guide/capabilities.md` 与源码**逐字节一致**（syncAiDocs 链路正常）。
- 独立 qa 子代理审查：链接 42/42 有效、注入链路（编号解析 → 释放 → 渲染）逐环节核实、文档站构建、片段相关单测全部通过；1 blocking（远程管理描述错误）+ 6 条建议——**已全部修复并重建复验**（详见设计笔记「验收进展」节）。
- 未验证：真机与全新会话行为（见下）。

## 四、真机验收项（建议并入 `release-pending-verification.md` 统一清单）

升级新包后，**开一个新会话**测试：

1. 问「你都能干什么」→ 应给出结构化能力概览（而非仅列工具名）。
2. 说「帮我每天检查构建警告」→ 应主动提「设置 → 定时任务」。
3. 检查 `~/.aicode/docs/guide/capabilities.md` 已随版本释放存在。

## 五、注意事项

- 若本批同时改动 `docs-site/**` 或 `assets/prompts/**`：提交前先 `git status` 对齐；本批仅动上述 4 个文件，无共享热点（未触 strings.xml / MainActivity / AIChatPanel 等）。
- 并入提交后：请更新 `AGENTS.md`「会话交接」节中的本条待并入指针（移除或标记完成）。
