---
name: upstream-repo-url
description: "官方上游仓库 https://github.com/jieapi/AiCode（永久），含孤立快照合并方法与迁移号冲突注意"
---
# 官方上游仓库地址（用户 2026-10-23 提供，永久生效，不再询问）

- **上游 URL：https://github.com/jieapi/AiCode**
- 合并注意（历史坑，来自 ai-config/notes/handoff-2026-10-17-feedback-batch-final.md）：
  - 官方快照是孤立根（无共同祖先），合并方式：`git fetch <url>` 后用 `git commit-tree <tree> -p <锚点>` 重挂；当前锚点 539c9225（dev 历史内，可直接 `git diff 539c9225 <新快照tree>`）。
  - 迁移号冲突：官方占 60/61，我方 annotations=62、session_team=63、SCHEMA_VERSION=63+（开工前核对 assets/migrations 最大号）。
  - 热点人工合并文件：MainActivity / StatefulAgentWorkflow / SystemPromptProvider 等。
- 网络：直连 GitHub 被 TLS 干扰，fetch 走 gh-proxy 等镜像或开加速器。
- 用途：合并前先做快照 diff 分析（重点盯 ContextCompactor/压缩链路、Store、Workflow），列冲突面再定合并策略。