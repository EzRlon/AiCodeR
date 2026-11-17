# 能力全域打通 · 批三（引擎特有开关 + 默认引擎扩展）（2026-10-25）实施台账

设计依据：`engine-capability-sync-design.md` §分期批三、§映射表「引擎特有选项」行。
前序：批一 `engine-capability-sync-batch1-disposition-2026-10-25.md`（MCP 映射 + 图片/附件）。

## 一、交付内容

### A. Codex 沙箱档位开关（引擎卡小开关）

- 新增 `EngineOptionsRepository`（DataStore `engine_options_prefs`）：`codex_sandbox_full_access`，默认 `false`。
- `CodexBridge` 起进程前冷读开关，`--sandbox` 取值：`false` → `workspace-write`（既有行为），`true` → `danger-full-access`。
- 引擎卡文案：「放开沙箱（可读写工作区外）」。

### B. OpenCode `--auto` 开关（引擎卡小开关）

- 同一仓储：`opencode_auto_approve`，默认 `true`（保持既有行为）。
- `OpenCodeBridge` 仅在开关为真时向 args 加入 `--auto`。
- 引擎卡文案：「自动批准权限请求」；既有 hint 已改指向该开关。

### C. 新会话默认引擎扩展到 Codex / OpenCode

- `DefaultModelsSection` 默认引擎组新增 Codex、OpenCode 两行（底层 `setDefaultEngine` 与 `createSession` 读 `getDefaultEngine` 本就通用，无需改逻辑）。

### D. 连贯与文案

- 开关经 `AIChatPanel → ChatInputBar → ModelIconButton → ModelSheet → EnginePage → EngineCard` 贯通；`EngineCard` 由单 Row 改为 Column + 可选 `optionRow`，新增 `EngineOptionToggle`（复用 `AppSwitch`）。
- 字串：`values/strings.xml`、`values-en/strings.xml` 各新增 4 条（两开关标题 + 两默认引擎副标题），并改 `engine_codex_hint` / `engine_opencode_hint`。
- 能力感知件：`app/src/main/assets/prompts/65-capabilities.md`、`docs-site/docs/guide/capabilities.md` 已登记开关与新会话默认引擎扩展。
- 依赖注入链同步：`AIAgentViewModel` 构造新增 `EngineOptionsRepository`，`ScheduledAgentViewModelProvider`（手工位置参数装配 VM）同步。

## 二、默认值不改变既有行为（关键自查）

| 开关 | 默认 | 默认下的 args | 与改动前是否一致 |
|---|---|---|---|
| Codex 沙箱 | 关（false） | `--sandbox workspace-write` | 一致 |
| OpenCode 自动批准 | 开（true） | 含 `--auto` | 一致 |

## 三、验证

- 编译：`:app:compileArmsoloReleaseKotlin` 通过（首轮失败= `ScheduledAgentViewModelProvider` 位置参数未同步，已修）。审查处置后再编译一次复核（开关行 toggleable/semantics + 两条 hint 文案）。
- detekt：触达 9 文件 0 findings，处置后再扫 `ChatModelSheet.kt` 亦 0 findings。
- 字串键集：zh/en 各 2703 条，差集为空。
- qa（子代理）：**结论可合入，无 P0/P1**。已核无误项：开关语义方向与默认值（默认下 args 与改动前逐字一致）、EngineCard 内 AppSwitch 点击不连带触发卡片选择、参数贯通无漏传与默认值错位、ScheduledAgentViewModelProvider 位置参数对齐（全库仅此一处构造 VM）、DataStore 名唯一且 corruptionHandler 用法一致、冷读不在主线程、默认引擎常量闭合、双语键集对齐（2703/2703）。6 条 P2 处置：
  - qa-P2-1 OpenCode 关闭 `--auto` 后无人应答的权限请求可能拖死回合（最坏 30min 空闲看门狗才中止）→ **当批缓解**：`engine_opencode_hint` 补「关闭后未授权的工具调用会被拒绝、回合可能不完整」；真机验收项已列。
  - qa-P2-2 开关对未安装/未登录引擎同样可操作 → 记录不改（会静默落库、装好后生效，非 bug）。
  - qa-P2-3 全局开关会一并作用于无人值守定时任务 → **当批缓解**：`engine_codex_hint` 补「全局生效、含定时任务」。
  - qa-P2-4 UI 层默认值与持久化默认值重合，未来新增调用点漏传会显示错误态 → 记录不改（当前唯一调用点均传真值，依赖参数可选以保持既有风格）。
  - qa-P2-5 选项行文字不可点、开关缺可访问名 → **当批修复**：整行改 `Modifier.toggleable`（Role.Switch）+ `semantics(mergeDescendants = true)`，AppSwitch 退为纯视觉（`onCheckedChange = null`）防空双触发。
  - qa-P2-6 提交卫生（工作区混入他会话笔记）→ 本批提交只纳入批三文件与批三台账。
- optimizer（子代理）：整体风险低，**无 P0/P1**；确认无常驻轮询/定时器/持续动画/后台唤醒，新 DataStore 与既有 30+ repository 同构。5 条 P2 全部记录不修复（理由如下）：
  - opt-P2-1 两个新状态在 AIChatPanel 根作用域读取，开关变化会重跑面板体 → 不构成回归（面板本就随 streamingText 每 token 重组，重活有 `remember` 护栏）；日后若优化，下沉收集到 EnginePage。
  - opt-P2-2 冷读最坏每回合 2 次（resume 缺失重试路径）→ DataStore 已内存缓存，收益趋近零，不加缓存。
  - opt-P2-3 DefaultModelsSection 默认引擎四行样板（可改描述符循环）→ 与 EnginePage 惯例一致的整洁度建议，记 P2 待排期。
  - opt-P2-4 新增第 33 个独立 DataStore 文件 → 与既有惯例一致，待日后 DataStore 收敛一并并入。
  - opt-P2-5 同一 DataStore 两次 `stateIn`（可 combine）、`optionRow` 匿名 lambda 使卡片不可跳过 → 影响趋零（sheet 内仅 3 卡），不改。

## 四、提交

`e273f70`（本地，未 push）：批三代码 12 文件 + 能力感知件 2 文件 + 本台账；未纳入他会话笔记。

## 五、待真机验收（并入下一包）

1. 引擎页 Codex 卡开关打开后，Codex 会话内命令可读写工作区外（如写 `/root/xxx`）；关闭时越界写被拒。
2. OpenCode 卡开关关闭后，未授权工具请求被拒绝（回合可能因此不完整），行为可预期、不长时间挂起。
3. 设置 → 默认模型页把新会话默认引擎设为 Codex / OpenCode，新建会话直接以该引擎开始。
4. 开关切换后不必重启 App，下一次发送即生效；切换过程中卡片点击选引擎不受影响。
