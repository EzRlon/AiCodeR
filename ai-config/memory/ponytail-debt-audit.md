---
name: ponytail-debt-audit
description: Ponytail 技能已装（5 个）；全库屎山审计清单在 ai-config/notes/debt-audit-2026-10-27.md（P0×5/P1×12/P2×14，仅标记未开工）；含可复用的审计方法论与子代理踩坑
---
# Ponytail 技能与全库屎山审计（2026-10-27）

## 已安装技能（全局 ~/.aicode/skills/）
来自 DietrichGebert/ponytail（MIT，GitHub 热门项目），装 5 个：
- `ponytail`：懒资深工程师模式，最小改动，四档 lite/full/ultra/off
- `ponytail-audit`：**整仓质量审计**（本次用的就是这个）
- `ponytail-review`：单次改动/diff 审查
- `ponytail-debt`：扫 `shortcut:` 注释生成债务账本
- `ponytail-help`：用法速查

ponytail 设计要点：规则注入而非事后清理；六维排序 correct > safe > 规模 > tested > fast > lean；每条发现必须带「具体触发场景」否则不许报；优先给删除式改法；最多 20 条。第三方实测（JetBrains 80 组配对任务）：广告称 -54% 代码，实测约 -15% 代码 / -10.3% 成本。

**本项目没有 `shortcut:` 标记**（0 处），所以 ponytail-debt 目前无输出；如要启用债务账本，需在代码里按 `shortcut: <上限>, <升级路径>` 格式标注。

## 审计结果
全量清单在 `ai-config/notes/debt-audit-2026-10-27.md`（453 行，§一~§十；§一~§八 全库基线基于 c92a0c2，§九 增量覆盖到 b72cf74）。开工指引与批次执行纪律在 `ai-config/notes/handoff-2026-10-27-debt-audit.md`。状态：**仅扫描标记，未开工，等用户批准**。

- §一~§八：全库基线（P0×5 / P1×12 / P2×14 + 重复实现 7 组 + 历史 33 条核实 + 巨型文件拆分方案 + 5 批次执行顺序）。
- §九（2026-10-27 11:50 追加）：最近 18 小时增量审计（上游 v1.13 合并 A/B/C/D/E1 + 记忆图谱 P1，约 +5734/-597 行）。增量 P1×4（冷启动全量重释放 2.4MB 内置技能 / 多选脚手架三份重复 / 第 4 份会话清理块 / agent 域层直连 memorygraph data 层）+ 增量 P2×8；另记录本批已修的安全项（防止将来重复报）与两条核对后排除的误报。

## 增量审计要点（18 小时窗口）
- 窗口内最重的功耗问题：`AIEditorApp.kt:317-320` 每次冷启动无条件调用 `extractBuiltinSkills`，对 `assets/skills` 逐技能「整目录删 + 全量释放」；本批新增 6 技能把该 IO 从约 0.2MB 抬到 2.4MB（`ui-ux-pro-max` 独占 2.0MB，其中 1.8MB `data/` 对 AI 无用）。
- 本批已修的安全项（勿重复报）：子代理权限 limitedBy 只能收不能放、审批通知带 requestId、FTP 随机口令+KeystoreCipher、ShellCommandParser 括号深度+blockers、浏览器混合内容降级。
- 排除的误报：`AIProvider.reasoningLevels` 每轮写入不构成并发串档（`getEffectiveProvider` 每轮新建实例）。

## 审计方法论（可复用）
1. 客观基线：`find | wc -l`、detekt 单文件跑（**整目录跑会崩** `AsyncExecutionService.getService must not return null`，必须逐文件）
2. detekt 输出解析：`cyclomatic complexity (mcc)` 与 `cognitive complexity` 行首 `- ` 后跟数字
3. 两路子代理并行，**必须用默认通用子代理，不能用 Explore**（Explore 无 Bash 工具，rg 未装时会卡死）
4. 主线程逐条回源核实子代理结论 —— 本次排除 3 条误报（`isExpandedWidth` 非死代码、`rowIds` 失配不成立、`_expandingPath` 机理需修正为 catch 在下游）
5. BusyBox 环境：无 rg，`grep --include` 有假阴性，Bash 禁用 `$(...)`/子 shell/输出重定向