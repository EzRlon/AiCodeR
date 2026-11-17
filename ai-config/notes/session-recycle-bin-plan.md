# 会话回收站（30 天软删）设计方案（2026-10-23 立项）

> 依据：只读勘察报告（子代理，逐文件核对 AgentDatabase 全部 13 个 @Entity + 删除链路 + 存储清理 + 备份 + 调度）。
> 队列位置：`deferred-queue-2026-10-23.md` 第 4 项（中件，需设计）。
> 决策权：产品方向决策由主代理按「合理·科学·最优」定稿并记录（用户 2026-10-23 授权）。

## 一、现状事实（已核实，带落点）

- 会话是 **Room 实体** `chat_sessions`（`ChatSessionEntity.kt:12`），字段无任何删除标记；**全部删除都是硬删**。
- `SCHEMA_VERSION = 67`；迁移目录 `app/src/main/assets/migrations/{version}_{desc}.sql`，但 **62~67 实际由 `IdempotentMigrations.kt` 的 Kotlin 幂等迁移执行**，SQL 文件仅作审计（`MigrationLoader.kt:loadMigrations` 里 `removeAll{ 62,63,64,65,67 }` + `add(...)`）。
- 删除链路：`AIAgentViewModel.deleteSession/deleteSessions` → `SessionUseCase.deleteSession`（按 `parentId` 逐层收集子代理会话 → `agentMessageDao.deleteBySession` + `chatSessionDao.delete`）→ 附 `checkpointManager.clearSessionCheckpoints` + 内存态清理（取消 job、清状态、切换当前会话）。
- **孤儿检测恰好是回收站的安全垫**：`StorageCleanupAdvisor` 用 `chatSessionDao.getAllOnce()`（无过滤）作会话基准，
  行还在 → 检查点/ai-logs 不算孤儿、不会被误清。
- 但**多处查询会穿过软删**（软删后仍可见，形同没删）：`getRootSessionsByWorkspace`、`searchInWorkspace`、`recall*`、
  `messagesWithAttachments`（交付物页）、`sessionStorageUsage`（存储页 top5）、`sessionActivity`（记忆图谱）。
- 既有缺陷（顺带记录）：删会话**未清** `todo_items` / `llm_call_records` / `permission_audit`（悬空行）。

## 二、方案定稿（一期）

### 1. 迁移

- `chat_sessions` 增 `deletedAt INTEGER`（可空，null = 正常；非 null = 入回收站时间戳）。
- 新增 `Migration67To68`（走 `addColumnIfMissing` 幂等补列）+ 审计文件 `68_add_session_trash.sql`；
  `MigrationLoader` 加 `68` 的 removeAll 与 `add(Migration67To68())`；`SCHEMA_VERSION → 68`。
- 号位风险：**68 是否被上游占用在本仓库不可核实**；若日后合并上游发现撞号，按既有「我方私有迁移重编号到官方未用号」处理（`MigrationLoader` 的 removeAll 列表与 `IdempotentMigrations` 类名同步改）。

### 2. 语义（一期只做「删除即入回收站」）

- **删除会话 = 软删**：给该会话及其全部子代理会话（按 `parentId` 级联）打同一个 `deletedAt`；消息、检查点、日志**全部保留**（保证「恢复」完整）。
  内存态清理（取消运行中 job、清状态、切当前会话、通知父会话子代理结束）**照旧执行**——否则侧栏/悬浮球/运行态会残留。
- **恢复**：清空该组会话的 `deletedAt`（整组恢复，避免子会话变孤儿）。
- **彻底删除**：硬删（会话行 + 消息 + 检查点 + **todo_items**，并额外清理该会话的 `llm_call_records`）。
- **撤销窗口**：一期不做 Snackbar undo（回收站本身就是时间窗），二期再评估。

### 3. 可见性过滤（关键，漏一处等于没删）

- 会话列表类：`getRootSessionsByWorkspace` / `getRootSessionsByWorkspaceOnce` / `getAllSessionsByWorkspace(Once)` / `getLatestRootSessionByWorkspace` / `getSubSessionsByParent(Once)` 一律加 `deletedAt IS NULL`。
- 穿透查询：`searchInWorkspace`、`recall*`、`messagesWithAttachments`、`sessionStorageUsage`、`sessionActivity` 按其 join 的会话表补 `AND s.deletedAt IS NULL`。
- **故意不加过滤**：`getAllOnce`（孤儿检测基准，保留整行才安全）、备份导出（见 §4）。

### 4. 备份

- 导出**包含**回收站会话（保真，`deletedAt` 随 `ChatSessionDto` 一起走，新增可空字段向后兼容）；导入后 `deletedAt` 保留 → 30 天继续计时。
- 旧备份导入 → `deletedAt = null`（视为正常会话）。备份 schemaVersion 门禁不变（改到 68 后旧 App 打不开新备份，属预期）。

### 5. 30 天清理（功耗纪律优先：不新增唤醒源）

- **不新增 WorkManager**：清理只在两个低频、用户已触发的时机做——① 冷启动对账（`AIAgentViewModel` init / 应用启动后台协程，一次索引查询 + 偶尔删除）；
  ② 进入回收站页时懒清理。二者都幂等、可取消、失败不阻塞。
- 逾期判定：`deletedAt <= now - 30 天` → 走「彻底删除」同一实现（含 todo/检查点清理）。

### 6. UI（遵循 UI 纪律：统一主题 token、功能分区清晰）

- 入口：侧栏「会话」tab 顶部右侧一个回收站图标（**仅当回收站非空时显示**，避免噪声），点击进入回收站页。
- 回收站页：列表（标题 + 「删除于 X · 剩余 Y 天」），行操作「恢复」「彻底删除」（带确认）；页头「清空回收站」（带确认）。
- 空态文案 + 中英双份。

## 二之补、实现中新增的决策（本批落地时定，理由随附）

- **彻底删除不动 `llm_call_records` / `permission_audit`**：二者是用量与授权审计记录，按会话删会破坏历史统计，且本身有界（授权记录滚动保留 200 条）。删会话时只清会话行 + 消息 + `todo_items`（后者是既有缺口，本次顺手补上）。
- **哪些入口走「彻底删除」而不是回收站**（语义必须区分，否则要么回收站堆垃圾、要么腾不出空间）：
  1. 存储页「聊天记录」勾选删除（`StorageViewModel.deleteChatSessions`）——目的是腾空间，软删保留消息等于没删；
  2. 子代理会话结束后的清理（`TaskTool`）——任务期产物，进回收站只会堆一堆没人要的子会话；
  3. 冷启动自动回收空会话（`SessionUseCase.recycleEmptySessions`）——空会话无内容可恢复；
  4. 删除整个工作区（`deleteSessionsByWorkspace`）——保留原有硬删语义；并改为遍历「含回收站」的会话列表，否则软删会话的消息会变成永不清理的孤儿。
- **UI 一期用抽屉内弹层**（会话列表顶部入口 → 回收站弹层：列表 + 单条恢复/彻底删除 + 一键清空），不做独立页面；独立页面（含搜索、批量）留二期。理由：入口改动仅限 `ChatDrawer` 会话列表，不动抽屉的 tab 骑线按钮布局契约。
- **剩余天数是展示层计算**（`ChatDrawer` 侧用同一 30 天常量算），不落库、不参与判定；判定一律以 `deletedAt <= now - 30d` 为准。

## 三、不做/边界

- 附件（`.aicode/attachments`）按工作区存放、与会话无映射：一期**不由回收站管理**（维持现状，走工作区产物清理）；建立会话↔附件映射属二期。
- 精确 30 天：以「≥30 天且在下次触发时清除」为近似，不做定时精确清理。
- 远程 SSH 模式：会话与 DB 都在本机，不受脚本那类执行模式限制。

## 三之补、落地清单（本批已实现）

- 迁移：`Migration67To68`（`addColumnIfMissing`）+ `68_add_session_trash.sql` 审计 + `MigrationLoader` 接管 + `SCHEMA_VERSION=68` + Room 导出 `app/schemas/.../68.json`。
- 实体/域模型：`ChatSessionEntity.deletedAt` ↔ `ChatSession.deletedAt`（`toDomain`/`fromDomain` 双向映射）。
- DAO：软删/恢复/逾期查询/计数/批量硬删；会话列表类 6 个查询 + 消息侧 10 处（含 FTS 搜索、recall 系列、交付物页、存储用量 top-N、记忆图谱）补 `deletedAt IS NULL`。
- UseCase：`deleteSession`（软删整组）/ `restoreSession` / `purgeSession` / `purgeExpiredTrash` / `purgeTrashOfWorkspace` / `collectSessionGroup`（**含回收站**的子会话下钻）。
- ViewModel：`trashedSessions` 流 + `restoreSession`/`purgeSession`/`emptyTrash` + `forgetSessions`（清检查点与内存态）；冷启动做一次逾期清理（无新增周期任务/唤醒源）。
- 接线：`MainActivity` 收集回收站流与四个回调；`ChatDrawerContent` → `SessionListTab` → `SessionListContent`。
- 测试：`SessionUseCaseWorkspaceDeletionTest` 适配新构造 + 新增 4 个用例（软删打标记不删行 / 恢复清标记 / 彻底删清消息待办行 / 逾期逐组清理）。

## 四、风险与验收

- 风险：漏给某个 `JOIN chat_sessions` 的查询补过滤 → 软删数据仍可见（搜索/召回/交付物/图谱）。**实现后必须逐个新增查询点复核**。
- 风险：Toast/Snackbar 语义、批量删除与「删除工作区」路径的语义变化（后者软删整个工作区的会话）。
- 验收（真机）：删除会话 → 侧栏消失、交付物页/搜索/召回/图谱不再出现 → 回收站可见可恢复 → 彻底删除后检查点与 todo 一并清理 → 改系统时间或直接构造 30 天前数据验证清理。
- 本地可验证项：编译 + 既有单测；**Room DAO 行为本地无法单测**（Robolectric 在 aarch64/musl 不可用），DAO 断言依赖真机。
