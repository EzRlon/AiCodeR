---
name: user-scripts-plan
description: 用户脚本系统规划（脚本页＋商店脚本 tab＋定时任务跑脚本），笔记在 ai-config/notes/user-scripts-plan.md，方案待用户确认
---
# 用户脚本系统（2026-10-22 立项，方案待用户确认后实施）

- 规划笔记：`AiCode/ai-config/notes/user-scripts-plan.md`。
- 一期（本体内置）：脚本页（增删改运行，~/.aicode/scripts/ 随备份走）＋固化脚本概念＋run_script AI 工具＋定时任务「运行脚本」动作；安全走权限引擎＋守夜守护。
- 二期（商店脚本 tab）：第四个 tab 完全对齐现有框架——StoreCatalogCache 加 SCRIPT、翻译管线加 SCRIPT 类型、marketplace.json 源＋gh-proxy；安装必须过安全层 InstallScanService（依赖安全层批次一先落，脚本属「进容器的可执行文本」高风险）。
- 插件调用：脚本跑容器内天然可调 CLI/MCP 服务端（rea/multillm 等）；「脚本＋AI」组合动作复用定时任务模型链路。
- 明确不做：本体进程内脚本宿主；一期不做市场。
- 排队位置：与商店安全层、超长上下文四合一、REA 合规层、标注中心真融合同队列，按用户放行顺序执行。