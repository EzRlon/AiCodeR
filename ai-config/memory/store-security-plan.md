---
name: store-security-plan
description: 在线商店安全层三批次规划已立项待放行，笔记在 ai-config/notes/store-marketplace-security-plan.md
---
# 在线商店安全层计划（已立项待放行）

- 规划笔记：`AiCode/ai-config/notes/store-marketplace-security-plan.md`（2026-10-22 立项）。
- 用户指示：**只规划不开工**，方案设计完善后经用户确认再开始。
- 三批次：①安装安全层＋禁忌扫描（InstallScanService，PASS/WARN/BLOCK 三级，纯本地规则引擎，特征库 assets JSON 可热更）②版本记录与更新检测（installed-registry.json＋contentHash 比对＋一键更新＋.bak 回退）③整包插件安装＋精选推荐位（中期）。
- 明确不做：自建托管市场、语义向量检索。
- 前置：在线商店加载优化批次（缓存+翻译迁移）qa 审查收尾后叠加，不冲突。
- 验收：恶意样本集命中率 100%＋官方样本误报 <10%＋更新回退链路通。