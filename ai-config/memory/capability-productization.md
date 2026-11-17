---
name: capability-productization
description: 能力下沉规划：代码智能四件套内置 App＋七层累积机制，笔记在 ai-config/notes/capability-productization-plan.md
---
# 能力下沉规划（2026-10-22 立项，随未来版本排期）

- 规划笔记：`AiCode/ai-config/notes/capability-productization-plan.md`。
- 核心：把本轮验证过的环境级增强下沉为 App 内置——代码智能四件套（ctags/repo-map/callchain/ast-grep＋code-intel 技能）→ assets/skills/code-intel ＋ scenarios/codeintel.sh（幂等：apk add ctags/gcompat＋npm ast-grep gnu 版）＋ tools 脚本随 App 释放＋能力感知两件套。
- 越用越聪明七层累积：项目结构（索引）/历史（recall·FTS）/决策（memory·记忆图谱）/行为（内置规则）/词汇（翻译缓存）/审查（qa 处置沉淀·self-improve）/工具预算（MCP enabledTools 白名单）——每层只增不删、有验证门槛。
- 排期：A 下沉近期批次（与商店安全层并行/紧随）；B（商店缓存/极黑/专家团 SOP/审查路由）已入代码库随发版带入；C 各已立项批次按用户放行执行。