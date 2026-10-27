---
name: devenv-one-tap-setup
description: 开发环境一键搭建已交付（b3153ee 待真机验收）；enqueueAgentRequest 必须显式传 projectRoot 的坑
---
# 开发环境一键搭建（2026-10-24 交付）

- 已提交 `b3153ee`（未 push），台账在 `ai-config/notes/devenv-one-tap-disposition-2026-10-24.md`，待真机验收（清单在台账）。
- 入口：设置 → 运行环境组「开发环境」；`DevEnvironmentsSection.kt` = 静态场景目录（10 场景，新增场景只加一条 DevEnvScenario + 字符串资源）。
- 链路：`AIAgentViewModel.startSessionWithRequest(request, title)` = `ensureCurrentSession(ws, title?)`（从 newSession 抽取，空会话复用逻辑等价重构）+ `enqueueAgentRequest(request, projectRoot = ws, targetSessionId = sid)`。
- 踩坑（qa 抓出，已修）：**enqueueAgentRequest 必须显式传 projectRoot**——默认 "" 会让 CLI 引擎（Claude Code）跳过工作区绑定、系统提示词按「未选择工作区」渲染、项目级记忆/规则不加载；全仓其它调用方都显式传了，新增调用方必须跟上。
- 设计要点：安装由 AI 在会话里执行（提示词写明本地 Alpine apk / 远程按发行版检测），不写死脚本；会话标题用场景名。