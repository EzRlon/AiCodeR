---
name: handoff-pending
description: 待接手的工作（2026-10-12）：另一并行会话已做/已规划但未完成的三项——记忆图谱P1、笔记模式P1、交接提醒方案；含文件清单、未验证状态与接手步骤
---
# 待接手的工作（来自并行会话，2026-10-12）

> **2026-10-12 用户确认：原并行会话已停止，以下全部工作由主施工会话接手完成（不再区分归属）。**

本文件是**跨会话交接单**：由「AiCode 增强版」主会话留给正在施工的「C2 标注账本」会话。
约定：**先干完手头的施工**，再把下面三项接手做完。所有改动都在 `~/workspace/AiCode` 仓库内。

## 一、已完成、但「未编译验证 + 未真机测试」的两项 P1

### 1. 记忆图谱 P1（纯读模型版，无落库）
> **2026-10-13 进展：已随增强版批次编译通过（BUILD SUCCESSFUL）；真机验收仍待做（见记忆 release-pending-verification）。**
- 新增：`feature/memorygraph/presentation/MemoryGraphScreen.kt`——用现有会话数据把「项目→会话→子代理」画成可点选的 2D 图，含「仅执行中」过滤、当前会话高亮、运行中转圈、点节点切会话。
- 修改：
  - `feature/agent/presentation/component/ChatHeaderComponents.kt`（顶栏加 Share2「记忆图谱」按钮）
  - `feature/agent/presentation/component/AIChatPanel.kt`（透传 `onNavigateToMemoryGraph`）
  - `MainActivity.kt`（新增 `memorygraph` 路由；用 sessions/subSessionsByParent/agentStates 渲染，点击调 `selectSession`）
  - `res/values/strings.xml`、`values-en/strings.xml`（`memory_graph_*` 6 条）
- 待办：编译验证；P2（3D 视图/markmap 脑图/AI 扫描模块）、P3（冲突提示/全部重划分/权限门）。见路线图记忆 `roadmap-final` 的 G 段。

### 2. 笔记模式 P1（Vditor WYSIWYG，离线）
> **2026-10-13 进展：已随增强版批次编译通过（BUILD SUCCESSFUL）；真机验收（输入/软键盘/相对图片）仍待做。**
- 新增：`app/src/main/assets/notes/`——Vditor 3.11.2 离线包（约 5.8M，裁掉 mathjax/mermaid/graphviz 等）、`note.html`（初始化 Vditor，cdn 指向本地 assets）、`LICENSE-Vditor-MIT.txt`。
- 新增：`feature/note/presentation/NoteEditorViewModel.kt`（注入模板、防抖写盘、`/local/` 资源加载器）、`feature/note/presentation/component/NoteEditorWebView.kt`（WebView + `AiCodeNote` JS 桥）。
- 修改：`feature/editor/presentation/CodeEditorScreen.kt`（md 文件顶栏加「笔记模式」按钮，与源码/预览三态互斥；退出笔记时 `viewModel.load(path)` 重载）、`strings.xml`（`editor_note_mode`）。
- 待办：编译验证；真机测 WebView 输入/软键盘/相对图片；P2 脑图、P3 导出 PDF（PrintManager）/图片。

## 二、已批准方案、尚未开工

### 3. 交接提醒：上下文过半软提醒 + 一键交接开新会话（带开关）
> **2026-10-13：已实施完成（GeneralSettingsRepository/BackupSnapshot/ContextCompactor.buildHandoffSummary/AgentWorkflow.handoffInputBudget+buildHandoffSummary/StatefulAgentWorkflow/AIAgentViewModel（HandoffPrompt+maybePromptHandoff+confirmHandoff+dismissHandoff）/HandoffPromptPanel/AIChatPanel/GeneralSettingsSection+SettingsViewModel+SettingsScreen/strings），随批次编译验证中。**
需求：上下文占用过半时，在回合结束（空闲）点停下提示用户，确认后生成「接手摘要」并新建会话继续。带总开关。
要点（文件级）：
- `feature/settings/data/repository/GeneralSettingsRepository.kt`：加 `handoffPromptEnabledFlow`（bool 默认 true）+ `handoffPromptPercentFlow`（int 默认 50，1..100）+ setter/snapshot/restore。
- `feature/backup/domain/BackupSnapshot.kt` + `BackupManagerImpl.kt`：两项新设置纳入备份（带默认值）。
- `feature/settings/presentation/SettingsViewModel.kt` + `component/GeneralSettingsSection.kt`：新增「交接提醒」开关 + 阈值输入（挨着「自动压缩阈值」）。
- `feature/agent/domain/workflow/ContextCompactor.kt`：新增 `buildHandoffSummary(messages, aiProvider, sessionId)`——复用分块摘要逻辑生成接手摘要（不改动当前会话历史、不落库）。
- `feature/agent/presentation/AIAgentViewModel.kt`：在 `executeAgentRequestStream` 的 `finally`（`flushPendingNotifications` 之后、`isOwnJob` 且非错误/取消）评估占用（`lastInputTokens / effectiveInputBudget`）；达标且本会话未忽略则置 `handoffPrompt` 状态。新增 `confirmHandoff()`（生成摘要 → `SessionUseCase.newSessionEntity` 建「（接手）旧标题」会话 → 落 `UserMessage(CONTEXT_COMPACTION_MARKER)+AssistantMessage(summary)` → `selectSession`）与忽略/本会话静默。注意：AIAgentViewModel 目前**未**注入 contextCompactor/modelMetadataService，需补，或把「占用计算/摘要生成」下沉到 `AgentWorkflow`/`StatefulAgentWorkflow`（后者已有这些依赖与 provider 构造 `getEffectiveProvider`/`resolveProviderConfig`）。
- `feature/agent/presentation/component/`：新增交接提示卡片，挂在输入区上方。
- 无 DB 迁移。触发严格限定在空闲点，不打断工具循环；与 90% 自动压缩并存。

## 三、共享文件与所有权约定
- **迁移编号（2026-10-13 上游合并后重编号）**：官方 60/61 就位（checkpoint 指纹/连接排序），我方 annotations=62、session_team=63，`SCHEMA_VERSION=63`；后续迁移**从 64 起**。
- 本轮会话改过的**共享热点文件**（接手时先看有无冲突）：`strings.xml`（中英）、`MainActivity.kt`、`AIChatPanel.kt`、`ChatHeaderComponents.kt`、`CodeEditorScreen.kt`、`AGENTS.md`。
- 本轮会话**未**触碰 `feature/annotation/**`、`AgentDatabase.kt`、`assets/migrations/`。

## 四、接手步骤
1. 先把手头施工收尾，确认构建恢复绿色：`cd ~/workspace/AiCode && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so /root/tools/gradle-8.11.1/bin/gradle :app:assembleUniversalDebug`。
2. 编译验证上面两项 P1；报错则修复（关注共享热点文件是否被并发覆盖）。
3. 派 `qa` 子代理独立审查（AGENTS.md 强制）。
4. 实施功能 3（交接提醒）。
5. 真机验收、双语 token 检查按 `roadmap-final` 的验收标准执行。