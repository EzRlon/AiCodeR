---
name: engine-native-first-principle
description: 引擎适配总原则（用户拍板 2026-10-25）：CLI 引擎原生体系优先，App 只桥接引擎没有的专属能力
---
# 引擎适配总原则：引擎原生体系优先（用户拍板 2026-10-25）

用户原话大意：三家 CLI（Claude Code / Codex / OpenCode）体量大、自有体系更强、迭代更快，能自己带的就用它们自己的；App 不重复造、不移植本体那套去覆盖。

## 执行口径

1. **不移植本体工作流**：专家团、子代理编队、PLAN/goal 等编排系统不给 CLI 引擎桥接，等引擎原生多智能体机制成熟后用它们的（OpenCode 原生有 agents 机制，Codex 在演进）。
2. **工具/沙箱/权限/会话历史**：全用引擎原生实现；App 授权中心不为 CLI 引擎拦截命令（Codex workspace-write、OpenCode --auto 已按此落地）。
3. **桥接只做 App 专属、引擎没有的数据与能力**：记忆（App 记忆库）、标注/修复账本（App Room 数据）、技能库内容（App 技能目录，只读）、App 规则文件（兜底——Codex 原生读 AGENTS.md、Claude 原生读 CLAUDE.md，桥的 rules_read 仅作补充）。
4. **能力全域打通（待办）按原生扩展点落地**：用户在 App 配的 MCP 服务器/脚本/商店能力，映射进引擎自己的扩展体系（如生成到 codex config.toml / opencode.json 的 MCP 配置），而不是全部经 aicode_bridge 私有通道；只有 App 内部数据（账本等）继续走 kind:data 通道。
5. 引擎卡与文档表述保持「引擎原生能力 + App 补充」的口径，不夸大桥接能力。

## 关联

- 引擎桥接台账：ai-config/notes/engine-bridge-phase2-disposition-2026-10-25.md、engine-bridge-phase3-codex-disposition-2026-10-25.md、engine-bridge-phase3-opencode-disposition-2026-10-25.md
- 队列来源：handoff-2026-10-25-final.md §四（三期引擎适配 + 引擎二期剩余：recall 进桥、能力全域打通）