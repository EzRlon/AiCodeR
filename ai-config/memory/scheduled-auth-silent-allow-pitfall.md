---
name: scheduled-auth-silent-allow-pitfall
description: 坑：无人值守任务下权限引擎的 4 处静默放行会绕过预授权清单（已修复+回归测试；新增放行分支时必须检查 scheduledRun）
---
# 无人值守任务的权限静默放行源（2026-10-15，随定时任务 v1 修复验证）

## 坑
权限引擎有多个「静默放行」分支，会让定时 AI 任务的**预授权清单形同虚设**（清单没勾的操作被静默放行，用户以为任务受限但其实没有）：

1. `evaluateShell` 第 5 步「已记忆 ALLOW 规则」——用户在交互会话里「始终允许 git pull」，定时任务没勾 EXECUTE 时该命令仍被放行
2. `evaluateShell` 末尾「工具档位 AUTO 兜底」——授权中心把 Bash 设「自动允许」，任务清单未覆盖时仍被放行
3. `evaluateGeneric` 的整工具记忆放行 + 档位兜底——同一问题（writeFile 等非 shell 工具）
4. `evaluateShell` 3.5「工作区删除守夜放行」——守夜默认放行工作区内删除，与「删除类单独勾选」冲突

## 修法（已随 5e47210 落地）
- `evaluateShell` / `evaluateGeneric` 加 `scheduledRun: Boolean` 参数（= `ScheduledAiRunState.current() != null`）
- 上述 4 处静默放行分别加 `!scheduledRun` 条件：定时任务运行中，未覆盖的操作一律落 ASK（通知授权，10 分钟兜底超时）
- 保留的放行：内置只读白名单（ls/git status）、灾难 rm 的 DENY 语义、C 类配置拦截
- 回归保护：`ToolPermissionPolicyEngineTest` 有 5 个 `scheduledRun_*` 用例 + 1 个非任务场景回归用例

## 教训
**任何「静默放行」分支都要检查是否需受任务级预授权清单约束**——将来新增放行分支（白名单、记忆、档位、守夜语义等）时，必须同步考虑 `scheduledRun` 场景。写测试时优先构造「有历史记忆/档位/守夜放行」的组合场景，能露出这类冲突。