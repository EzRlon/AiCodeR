---
name: e4-goal-hosting-plan
description: E4「目标托管」落点调研结论（2026-10-12）：可不改数据库实现最小版；复用 todo 工具链/qa 子代理/挂起式询问/现成用量字段；含插入点、风险与分步清单
---
# E4「目标托管」落点（2026-10-12 调研结论，供实施）

目标：用户给高层目标 → AI 用现有 `todo` 工具拆解推进 → **关键收尾用另一个模型做独立验收** + **单次任务 token 预算**超限时暂停询问。

**结论：最小可行版不需要 Room 迁移**（只有「验收结果持久化」才需要，且迁移号有冲突，见风险）。

## 可复用的现成件（已核实，带 文件:行号）
- **目标拆解**：`feature/agent/domain/tool/todo/TodoTool.kt`（快照式 `replaceTodos`，:91-131）+ 表 `todo_items`（`data/local/entity/TodoItemEntity.kt`、`data/local/dao/TodoItemDao.kt`）+ 会话级 `AIAgentViewModel.currentSessionTodoItems`（:192-198）+ UI `presentation/component/TodoDashboardBar.kt`。**零成本复用**。
- **独立验收**：首选**复用 `qa` 子代理**（`assets/agents/qa.md` 就是「只报告不改码、实际跑构建、输出带 文件:行号」；`AgentDefinition` 支持 `model:`/`providerId:` 指定另一个模型）。派发链：`TaskTool.createSubagent` → `SubAgentEventBus` → `AIAgentViewModel.spawnSubAgentWorkflow`（:1160-1190）。注意子代理上限 5、不可嵌套。
  - 轻量替代：`provider.complete()` 单次问答式验收，范式见 `StatefulAgentWorkflow.generateTitle`（约 :1055）/`ContextCompactor.summarize`（:219-250）。
- **验收模型设置**：照抄 `ModelSelectionSettingsRepository` 子类范式（`CompactionModelSettingsRepository.kt` / `TitleModelSettingsRepository.kt`），新建 `VerificationModelSettingsRepository`（**纯 DataStore，无迁移**）。解析回退见 `StatefulAgentWorkflow.resolveTitleFallbackProvider`（约 :1048，仿它加 `resolveVerificationFallbackProvider`），并同步改 `di/AgentModule.kt` 的 `provideAgentWorkflow`（约 :415）。
- **token 预算读数**：明细 `llm_call_records`（`LlmCallRecordDao.kt`）、会话累计 `chat_sessions.totalInputTokens/totalOutputTokens/lastInputTokens`（`ChatSessionDao.addTokenUsage` :78、`updateLastInputTokens` :81；写入点 `AIAgentViewModel.kt:1727/1731/1733`）。**单次任务累计需自己在 workflow/VM 侧累加**（本轮开始清零）。上下文窗口预算已有：`ModelContextPolicy.effectiveInputBudget`（:37-39）、`StatefulAgentWorkflow` 的 `inputBudget`（:533）、超窗 `LlmError("input_budget_exceeded")`（:588-592）。
- **预算超限暂停询问**：复用 park-and-resume 范式——`AskUserQuestionManager.kt`（awaitAnswer :34-46 / resolve :55-60）、`PlanApprovalManager.kt`（:33-50）。UI 宿主：`AIChatPanel.kt` 的 AskUserQuestionPanel（:1676）/ PlanApprovalPanel（:1696）紧贴 ChatInputBar，VM 侧 `pendingUserQuestion`（:959）+ `resolveUserQuestion`（约 :1908）。**无需新造暂停机制**（stopAgent/stopAgentSession/isRunning :968-971/:1936/:1945 已有）。

## 可插入「收尾验收」的位置（三选一）
1. `StatefulAgentWorkflow.executeEvents()` 的 `while` 结束后、`send(AgentEvent.Completed)` 前（:994-996）——最贴合「收尾」，推荐。
2. `reduce` 的 `isFinished = true`（:362/374）——语义最干净但改状态机，风险大。
3. ViewModel 收尾（`executeAgentRequestStream` 的 finally 前，:1616 调用处、:1870-1890 区域）——不动 workflow，MVP 最易。

## 设置/UI 落点
- 「验收模型」→ 加进 `DefaultModelsSection.kt`（与识图/压缩/标题并列，复用 `ModelSelectionSheet` :307）；`SettingsViewModel` 加状态（仿 :537-556）+ init 采集（仿 :756-790）+ setter。
- 「token 预算」→ 加进 `GeneralSettingsSection.kt` 的「模型」组（:74-110，与压缩阈值并列），复用 `NumberInputDialog`（:262）；存储用 `GeneralSettingsRepository` 加 key（仿 `compactionThresholdPercentFlow` :159-172）。

## 风险/坑
1. **迁移号冲突**：61 已被 D3（`61_add_session_team.sql`）与 G（记忆图谱）预定——E4 若要落库验收结果须另排 62+，开工前对号。当前 `SCHEMA_VERSION=63`（2026-10-13 上游合并重编号后）。
2. **tool_calls 配对约束**：assistant(toolCalls=N) 后不能只插一条 tool 消息（OpenAI 400）。验收结论若落库，用**无配对的 TOOL 消息**（参考 CompactionFailed 的落法 `AIAgentViewModel.kt:1677`，回放时 `buildHistory` 会丢弃）或普通 user/assistant 消息。
3. `TodoTool` 是**整表替换**语义：拿它当跨轮持久的「目标」载体有被覆盖风险；若要跨轮稳定 goal，需给会话加字段（需迁移）。
4. i18n：新文案必须中英双语（项目规则）。

## 分步实施清单（无迁移版）
A 验收模型设置：新增 `VerificationModelSettingsRepository` → `StatefulAgentWorkflow` 加 `resolveVerificationFallbackProvider` + 改 `AgentModule` → `SettingsViewModel` 状态/setter → `DefaultModelsSection` 加行 + strings。
B token 预算设置：`GeneralSettingsRepository` 加 `tokenBudgetFlow`/setter（0=不限）→ `SettingsViewModel` → `GeneralSettingsSection` 加行（复用 NumberInputDialog）+ strings。
C 超预算暂停：新增 `BudgetApprovalManager`（照抄 AskUserQuestionManager）+ DI → workflow `CallLlm` 累计本轮 token、超预算 `awaitApproval` → `AIAgentViewModel` 暴露 `pendingBudgetApproval`/`resolveBudgetApproval` → `AIChatPanel` 加面板。
D 收尾验收：workflow 收尾（:994 附近）取本会话 todo + 最近对话 → 用验收模型跑一次独立检查（新增 `assets/prompts/agent/verifier.md`，仿 title-generator）→ 结论以无配对 TOOL 消息落库。
E（可选，需迁移）：验收结果持久化 → `todo_items` 加列或新表 + 迁移 62+ + 备份链路登记。

**需真机验证**：B/C 的预算触发与暂停、D 的验收（真实 provider 调用/子代理并发槽）、设置页新条目 UI。
**构建**：`cd ~/workspace/AiCode && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew :app:assembleUniversalDebug`（`LD_PRELOAD` 在本容器必需）。

## 相关状态（2026-10-12）
- E3 记忆管理页 + E5 定时任务已提交（`4a8f2e2`）。
- C3 沙箱：PoC 第一阶段真机通过（Android 11 / 红米 K40，用 BlackBoxReborn 跑起测试 APK）；目标机就是 Android 11，需集成时用 NDK 编译引擎核（容器未装 NDK）。