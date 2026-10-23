# 交付物页口径修正（2026-10-23）

## 用户反馈

左侧栏「交付物」看起来在扫全盘手机的 APK 软件；期望这里是「AI 开发过程中交付的档案与安装包」。

## 现状（已核实）

`AIAgentViewModel.loadDeliverables()` 有两个数据源：

1. 会话附件：`AgentMessageDao.messagesWithAttachments(workspacePath)`（按当前工作区过滤、时间倒序）——
   这一支正是「AI 发进会话的交付物」；
2. 工作区盲扫：`fileAccess.listFilesRecursive("~/workspace", 8)` 递归找 `.apk/.apks/.xapk`
   （只排除了 `.git`、`node_modules`）。

**实证（本容器 `~/workspace`）**：第 2 支一次捞出 **15 个 APK** —— 各变体构建产物
（`app/build/outputs/apk/{universal,armsolo,x86solo}/{debug,release,beta}`）、`archive/` 里的历史包、
以及往批留下的交付包。它们与「AI 这次交付了什么」无关。

**口径矛盾**：抽屉自身的描述串 `drawer_tab_deliverables_desc` 早已写明「**AI 交付的**安装包与文件：
跨会话聚合、按时间与类型分组」——盲扫与该口径直接冲突（用户看到的正是这个矛盾）。

## 处置

- 删除工作区盲扫，交付物**只认会话附件**：交付事件＝「AI 把它发进了会话」。
- 依据：工作区里存在同后缀文件不等于交付（构建中间产物、历史归档、往批的包都在里面）；
  工作区产物另有去处——「设置 → 存储 → 工作区产物」（`StorageUsageScanner`，带 `MAX_ARTIFACT_FILES` 上限），
  能力不丢失、职责不重叠。
- 连带更新：`docs-site/docs/guide/capabilities.md` 的交付物条目（去掉「+ 工作区 APK」）、
  中英 `deliverables_empty` 文案（说明来源是「AI 在会话里发给你的」）。

## 边界（如实）

- AI 构建了 APK 但**没有发进会话**（例如只在回复文字里说了路径），该包不会出现在交付物页——
  设计如此，可在「存储 → 工作区产物」或文件页看到。
- 会话附件的口径与去重逻辑未动（`putIfAbsent` 以 `containerPath` 去重）。

## 自验

- `:app:compileArmsoloReleaseKotlin`：**通过**。
- 全量单测 `:app:testArmsoloReleaseUnitTest`：**1337 用例，1 失败（1 跳过）**，失败的是
  `com.aicode.feature.workspace.domain.remote.SyncEngineIgnoreTest.upload_skips_custom_ignored_paths`，
  报 `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`（别的用例泄漏的异常在测试开始前被检出）——
  **与本次改动无关**（交付物/VM 与远端同步引擎无交集）。
- 隔离复跑该类：**BUILD SUCCESSFUL（6/6 通过）** → 判定为**顺序相关的既有偶发**，非本次引入。
  同一套件在本次改动前的全量运行（term-38）为 1337 全绿，可作对照。
- 该偶发项引入发版前必查清单：交付包构建前的全量单测若再命中，按「泄漏协程异常的用例定位」处理。

## 审查（L2 → qa BATCH）

- 结论：**可提交，无 P0 / 无 P1**；无悬空引用、无回归、无其他功能受影响。
- 已核重点：删除后无悬空引用与死代码（`fileAccess` 仍被 16 处使用；`listFilesRecursive`/`CONTAINER_ROOT` 他处引用不受影响）；
  留存数据源确为「AI 交付」——SQL 为
  `WHERE s.workspacePath = :workspacePath AND m.role != 'USER' AND m.attachmentsJson IS NOT NULL AND != '[]' ORDER BY m.timestamp DESC LIMIT 300`，
  涵盖 `sendFile` 卡片（TOOL 行）与模型生图（ASSISTANT 行），用户上传的 USER 行被正确排除；
  去重键 `containerPath` 保证 LazyColumn key 唯一；附件对应文件已删时菜单各动作走 Toast 提示、不崩；
  存储页 `StorageUsageScanner` 独立扫产物不受影响。
- 报告缺陷 2 项（均 P2，陈旧注释）：`ChatDrawer.kt:322` 参数注释、`ChatDrawer.kt:1900` 函数 doc 仍写「会话附件 + 工作区安装包」→ **已一并修**。
- 额外对齐：英文 tab 描述 `drawer_tab_deliverables_desc` 由 “produced by AI” 改为 “delivered by AI”，与中文「AI 交付的」及实现口径一致。
