---
name: handoff-next-session
description: 新会话一键接手（2026-10-14）：当前安全节点 30ebde3、队列下一项=定时任务 v1、纪律/环境/替代审查方案与设计稿索引；自包含，直接复制文末指令给新会话即可
---
# 新会话一键接手（2026-10-14）

> 供新会话直接接手 `~/workspace/AiCode`（dev 分支）继续施工。本文件自包含；配合项目记忆 `handoff-2026-10-13` 使用。

## 一、当前状态

- **安全节点：`55395ee`**（App 图标定稿采用新版，2026-10-15）。前序：`45c44c1`（在线商店 v1）、`c56d6c9`（图标过渡版）、`7ac7811`（界面标注 v2）、`5e47210`（定时任务 v1）、`30ebde3`（守夜 v1 第一刀）。
- **工作区干净**；debug 编译绿；单测 **1205 项 0 失败**；**R8 Release+Beta 全量验证通过**（全部批次代码，37min）；**最终 debug 稳定槽验收包已构建并发送用户**（1.7.0-dev / `55395ee` / com.aicode.debug）。
- 已完成批次：消息类型 → 授权中心 → 能力雷达 → 守夜 v1 → 定时任务 v1 → 界面标注 v2 → 在线商店 v1 → **App 图标（新版定稿 `55395ee`）**。

## 二、队列状态

1. **验收中**：用户真机验收（清单 `release-pending-verification.md` 已 19 项）——等待反馈。
2. **守夜守护续刀**（穿插可选）：v1 第二刀=锚点/守夜报告/急停（`unattended-security-plan.md` §八）。
3. ~~大块~~：本版不做（用户 2026-10-15 决定推迟）。

**流程提醒**：验收包为 debug 稳定槽（com.aicode.debug，debug 签名）；Release/Beta 的 R8 为构建验证（无签名密钥不装机）。图标设计源：`~/.aicode/assets/app-icon-final-robot.png`（母版）/ `icon-source-gpt-3candidates.png`（原始三案）。

## 三、纪律（必须遵守）

- **统一批次标准（2026-10-15 优化，用户确认「按照最优方式」）**：
  - 每批必需：debug 编译（`assembleUniversalDebug`）+ 单测（基线 **1193**）——错误隔离底线；有错修复后**只复验一次**，不反复跑
  - 审查（qa 子代理/外部模型；都不可用时深度自查并如实记录）→ 双语/主题检查 → `git commit`（本地 dev，不 push）
  - **R8（Release+Beta）不再每批跑**：攒到本版功能全部完工/交付验证时**统一跑一次**；纯资源任务（不碰 Kotlin）零编译
- **能力感知纪律**（AGENTS.md）：新功能必须登记 `AiCode/docs-site/docs/guide/capabilities.md`（能力地图）+ 影响 AI 行为时同步 `app/src/main/assets/prompts/65-capabilities.md`。
- **决策代选授权**：评审/反馈的未拍板决策点按「合理·科学·最优」代选并落档，用户可推翻；不可逆操作先问。
- **验收节奏**：不逐批打扰用户、不发 APK；全部批次完工后一次性验收（清单在 `~/workspace/.aicode/notes/release-pending-verification.md`，已累计 15 项功能与真机用例）。
- **用户消息「批准所有执行指令」**：常规操作直接执行，不弹抉择；流程上不要停下来征求批准。

## 四、环境与工具（易踩坑）

- 编译命令（原样使用）：
  `cd ~/workspace/AiCode && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew :app:assembleUniversalDebug`
- 单测：`... :app:testUniversalDebugUnitTest`（约 2-10 分钟）；R8 验证：`... :app:assembleUniversalRelease` + `... :app:assembleUniversalBeta`（**各约 20-25 分钟**，串行跑；按 §三 新节奏改为攒批统一跑）。
- **容器无 ripgrep**：搜索用 `python3 /root/tmp/ksearch.py "正则" 目录`（第二参数必须是目录，不传单文件）；busybox grep 不支持 `--include`。
- **qa 子代理不稳定**：2026-10-14 三次接单超时（引擎侧），替代方案=**multi-llm MCP 外部模型交叉审查**（deepseek 通道可用；把它对被审查批次的 diff 打包成 md 发过去）+ 对报告的疑点逐条以代码核实后再采纳。multi-llm 其他通道（gemini/claude/chatgpt/grok）常掉线，慎用。
- 耗时命令用 `terminal(action="start", notify=true)`，不轮询。
- R8 与 debug 构建同时发起会排队等锁（安全但慢），避免并发。

## 五、关键锚点（本会话沉淀）

- 权限引擎：`feature/agent/domain/permission/ToolPermissionPolicyEngine.kt`（档位/守夜插点都在此，改动前通读）；`ShellCommandParser.kt`（collectRmTargets 守夜变体）。
- 审计：`PermissionAuditRecorder.kt`（枚举 ALLOW_ONCE/ALWAYS/DENY_USER/DENY_SYSTEM/ALLOW_WATCH/WATCH_TIMEOUT，保留 200 条，迁移 65）。
- 守夜：`WatchModeSettingsRepository`（开关）；`ToolPermissionManager`（10 分钟超时+竞态防护）；`PlanApprovalManager`/`BudgetApprovalManager`/`AskUserQuestionManager`（守夜分支）。
- 设置页：`PermissionsSettingsSection.kt`（四区 + 守夜开关）。
- 迁移：当前 `SCHEMA_VERSION=65`，下一个=66（幂等模式见 `IdempotentMigrations.kt`）。
- editFile 教训：改动前必须先 readFile 拿最新原文（跨会话/跨文件格式混淆会导致连续失配）。

## 六、一键指令（复制给新会话）

```
【继续施工 · 2026-10-15 交接】
先读 ~/.aicode/notes/handoff-next-session.md（自包含）与项目记忆 handoff-2026-10-13，
然后 cd ~/workspace/AiCode && git log --oneline -3 && git status --short 核对状态
（期望：HEAD=5e47210，工作区干净）。
任务：按队列继续推进——下一项 = 界面标注 v2（设计稿 ~/.aicode/notes/live-annotation-plan.md，
基础件在 ~/.aicode/notes/v2-staging/）。
纪律：统一批次标准（编译→单测→独立审查→双语→提交），能力感知两件套同步，
决策代选继续生效，全部完工后统一真机验收（清单 release-pending-verification.md）。
环境注意：multi-llm 浏览器通道与 qa 子代理不稳定时（本批实录：deepseek/qwen 页面级故障、
qa 无产出）→ 先自查（对照审查清单逐条核实）+ 多通道轮试，审查顺延如实记录；
编译命令与搜索工具见交接文档 §四。
```
