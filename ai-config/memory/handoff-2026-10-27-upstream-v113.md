---
name: handoff-2026-10-27-upstream-v113
description: 交接（2026-10-27 完结）：上游 v1.13 合并 A/B/C/D/E1 全部提交（E1=0b66afa），E2/F 经用户确认移除，合并完结
---
# 交接（2026-10-27 完结）：上游 v1.13 合并 A→E1 全部完成，E2/F 已移除

## 一句话
上游 jieapi/AiCode `v1.13.0-rc1` 采纳清单五个批次全部提交：A+B+C（749f810）、D（5d6a690）、E1（0b66afa，思考档位 400 白名单学习 + Gemini/Anthropic 关闭思考语义修正）。**E2 与 F 经用户 2026-10-27 拍板从计划中移除，上游合并工作完结。**

## 必读
- E1 详情：`~/workspace/AiCode/ai-config/notes/handoff-2026-10-27-batch-e1-reasoning.md`
- 验证基线/硬约束/未采纳清单：`ai-config/notes/handoff-2026-10-27-upstream-v113-batch-d.md`（已加完结节）

## 沿用事实
- 项目根是 `~/workspace/AiCode`（不是 `~/workspace`）；分支 dev；dex 64779/65536（余 757）
- 上游源码 `/tmp/up113/AiCode-1.13.0-rc1`（容器重启会丢，用 gh-proxy.com 前缀重取）
- **后续上游思考系统的零散修复按 E1 的摘取方式逐个处理**（轻量、独立、低 dex 成本），不再整体替换
- E1 遗留待排期微优化 3 条（optimizer P2），见批次笔记「待排期」节
- 构建一律 `sh tools/build-with-lock.sh <任务>`，正式包加 `--no-configuration-cache`；detekt 脚本多文件用 `:` 分隔
- 工作区另一会话的笔记改动（6 已跟踪 + 11 未跟踪）不要提交/回滚；可提交范围 = `app/src/**` + `ai-config/notes/build-times.csv` + 本批自己的笔记
- E1 正式包已产出待真机验收（重点：Gemini/Anthropic 思考强度「关闭」行为、不支持档位的自学习）