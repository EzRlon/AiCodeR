---
name: handoff-2026-10-23-scripts
description: 交接（2026-10-23 暂停点）：用户脚本切片 A 修复+B+C 全部未 commit；开工前必读 ai-config/notes/handoff-2026-10-23-scripts-abc.md
---
# 交接（2026-10-23 暂停点）：用户脚本切片 A 修复 + B + C

**开工前必读：`ai-config/notes/handoff-2026-10-23-scripts-abc.md`**（权威，含下一步、验收清单、构建命令）。

一句话状态：
- 切片 A（`run_script` 工具 + `ScriptRepository`/`ScriptRunner`）与切片 B（设置 → 脚本页 UI）已过两轮 `qa` 并逐条处置（台账 `qa-sliceA-disposition-2026-10-23.md`、`qa-sliceB-disposition-2026-10-23.md`）。
- 切片 C（定时任务「运行脚本」动作）代码刚落地，编译成功、57 个相关单测全绿，**尚未经 qa 审查**。
- **全部改动未 commit、未 push**（项目纪律：commit 需用户裁决）。仓库 `~/workspace/AiCode`，分支 `dev`，本地 HEAD `2ce313c`，`origin/dev` 在 `7d18b01`。

下一步顺序：①派 `qa` 复审切片 C（BATCH）→ ②逐条处置并写第三份台账 → ③请用户裁决 commit / 是否出正式包（出包前按纪律先 qa + optimizer，并核对 versionCode 撞号）→ ④真机验收。

关键订正（免重查）：
- 定时任务持久化是 **DataStore 的 JSON 字符串**（`ScheduledTaskRepository`，`ignoreUnknownKeys`），**新增任务类型/字段不需要 Room 迁移**。
- `ScheduledTask.isAiType` 语义＝仅 `AI_TASK`/`AI_PLAN`（原先 `type != COMMAND` 会把新增的 `SCRIPT` 误判为 AI 型）；Worker 分派按它走。
- `.sh` 脚本执行问引擎 `defaultShell()`（provision 前 `/bin/sh`、之后 `/bin/bash`）。