---
name: handoff-2026-10-26-batch6-7-done
description: 交接（2026-10-26 终态）：增强计划批次0-7完成（HEAD c2a9dab，156提交），子代理失效真因已修复，vCode157 已交付；工作区有另一会话未提交的记忆图谱改造
---
# 交接（2026-10-26 终态，交付 vCode157）

## 一句话状态
增强计划批次 0–7 全部完成并本地提交（**未 push**）。子代理失效真因已查明并修复，vCode157 包已交付待真机验收。工作区有**另一会话未提交的记忆图谱改造**（18:00 后写入），勿动、勿提交。

## 提交链（HEAD = c2a9dab，提交数 156）
| 批次 | 提交 | 内容 |
|---|---|---|
| 0 | bdee912 | 导入会话 |
| 1 | 3521bb5 | 构建锁/回滚脚本/A-B#4/daemon 复核 |
| 2 | 7945e72 | 任务书模板/工具准入/报告 catalog/意图三层制 |
| 3 | d20bafe | 失败埋点 + 评测集（迁移 72） |
| 4 | 3b3ede3 | 压缩保真 |
| 5 | 1bf9244 | 模型探测 + 路由表 + 预算感知 |
| 6 | 2ae14a2 | 任务账本 TaskLedgerTool + 项目知识库 ProjectNotesSource + 画像归纳提示词 |
| 7 | dbd9772 | 正式包多维自检脚本 tools/release-selfcheck.sh |
| 修 | 09ca2a6 | taskLedger 表格读写两处缺陷 |
| 修 | 5f662ab | 自检脚本 set -e 加固 + 审查处置记录 |
| 修 | 65a2d1d | 子代理卡死可观测（list 报 runningSeconds）+ 提示词处置指引 + AGENTS 纪律 |
| 修 | fca946b | **子代理失效真因**：事件收集协程异常隔离 + tryEmit 返回值检查 |
| 修 | c2a9dab | 子代理启动三处 early return 不释放活跃位（qa 独立发现）+ 排查笔记定案 |

## 交付
`~/workspace/许愿码-正式版-20261026-vCode157-子代理失效修复.apk`（21.3 MB，17:56 构建）
- versionCode 157 == 提交数 156 + 1 ✅
- versionName 记录 `9fcb2b7`（`--amend` 前的旧哈希）：**`git diff 9fcb2b7 c2a9dab -- app/src/` 为空**，包里代码 = 当前 HEAD，只有笔记 md 有差异
- 签名 SHA-256 `5ec1c22a...` 与之前一致（可覆盖安装）
- 自检 FAIL 0 / WARN 2
- 包内混入另一会话**早前**未提交的 `engine_switch_busy` 字符串（已用 `apkanalyzer resources value` 从包内实测确认）；**不含**记忆图谱（18:00 后才写，包 17:56 已出）

## 子代理失效真因（本次核心产出）
见记忆 `subagent-silent-stall-discipline` 与 `ai-config/notes/subagent-stall-rootcause-2026-10-26.md`。
三处静默失败：①事件收集协程无异常隔离（单次异常永久终止收集器，进程内一次后所有派发失效，直到 VM 重建）②`tryEmit` 返回值被丢弃 ③三处 early return 不释放活跃位。
**判别铁证**：`task(action="list")` 的 `updatedAt == createdAt`（0 秒）表示工作流从未启动。修复后实测连派三文件任务全部成功。

## 未验证 / 待排期
- **全部功能待真机验收**（批次 0–7 均未上真机）
- dex 引用数 64469/65536（余量 1067）：存量问题，再加大依赖会构建失败
- 自检脚本 keep 核对覆盖不到 `* implements`/`* extends` 通配规则（23 条）
- TaskLedgerTool 无单测（纯逻辑，适合补 JUnit）
- `optimizer` 子代理在本环境**未启用**（可派发清单只有 Explore、qa；内置 8 个定义在 `assets/agents/`，需在设置里启用）

## 关键坑
1. Kotlin 块注释支持嵌套：KDoc 里写 `notes/*.md` 的 `/*` 会吞掉后面整个类，报成一片 companion 常量 Unresolved。详见记忆 `kotlin-nested-block-comment-pitfall`。
2. **`--amend` 会改哈希**：包 versionName 记录旧哈希时，先 `git diff <旧哈希> HEAD -- app/src/` 确认代码一致，不要因哈希不匹配就误判「包对应代码没提交」。
3. **多会话共用工作区**：出包前 `git status` 看清哪些改动不是自己的。确认某改动是否在包里可用 `apkanalyzer resources value --type string --name <名> <apk>` 实测。

## 纪律提醒
- 构建走 `sh tools/build-with-lock.sh <任务>`（锁+计时，musl 前缀已内置）
- 正式包必须 `--no-configuration-cache`，可带 build cache
- **交付前跑 `sh tools/release-selfcheck.sh <apk>`**，有 FAIL 不得交付
- versionCode = `git 提交数 + 1`（谁出包都一样，不会撞号）：156 提交 → 157 包；记忆图谱提交后 157 提交 → 158 包