# qa 处置台账：会话回收站（c77805a）复审（2026-10-24）

> 首派子代理跨会话失效，本批重派只读 qa（范围 git show --stat c77805a，六项重点）。
> 审查结论：无 P0；1 条 P1；5 条 P2；六项重点五项通过、备份 DTO 一项不通过。

## 六项重点结论（qa 原文核实）

1. 软删可见性封口：通过（会话列表 6 查询、FTS 3 处、LIKE 召回 3 处、上下文检索、记忆图谱、存储 top-N、交付物页均带 deletedAt IS NULL）
2. 迁移 67→68 双路径：通过（SQL 审计文件与幂等 Kotlin 轨一致；SCHEMA_VERSION=68 与 68.json 一致；全新建库走 Room 自动建表）
3. 软删/恢复/彻底删/清空/逾期链路 + forgetSessions：通过（四处内存态清理齐备；软删刻意不清检查点正确）
4. 语义分流四处入口：通过（存储页/TaskTool/空会话回收→purgeSession；工作区整删→硬删且防孤儿消息）
5. UI 入口/空态/剩余天数/批量冲突：通过（附 2 条 P2）
6. 备份 DTO deletedAt：**不通过（P1-1）**

## 发现处置（P1 + P2 全部当批修复）

| 级别 | 发现（文件:行号） | 处置 |
|---|---|---|
| P1-1 | ChatSessionDto 无 deletedAt，软删会话经备份导入后「复活」（BackupSnapshot.kt:222、BackupManagerImpl.kt:967/973） | **已修**：DTO 加 `deletedAt: Long? = null`，toDto/toEntity 双向映射；旧备份缺字段回退 null，向后兼容 |
| P2-1 | 回收站逐条列出子代理会话，整组语义下重复误导（ChatSessionDao.kt:49） | **已修**：列表与计数查询加 `parentId IS NULL`；`getTrashedSessionsByWorkspaceOnce`（清空/逾期清理用）保持全量 |
| P2-2 | trashedSessions 用 Eagerly 订阅，VM 一建就挂 DB 流（AIAgentViewModel.kt:3491） | **已修**：改 `WhileSubscribed(5_000)` |
| P2-3 | 剩余不足 1 天显示「剩余 0 天」；SimpleDateFormat 每次重组新建（ChatDrawer.kt:1170） | **已修**：天数向上取整（剩几小时显示 1 天，逾期才显示 0）；format 包 `remember(deletedAt)` |
| P2-4 | 工作区整删不清理待办，与 purgeSession 新口径不一致（SessionUseCase.kt:136） | **已修**：deleteSessionsByWorkspace 逐会话补 `todoItemDao.deleteBySession` |
| P2-5 | 批量选择进行中回收站入口仍可点开（ChatDrawer.kt:925） | **已修**：`selectedSessionIds` 非空时隐藏入口行 |

## 验证

- P1-1 与 5 条 P2 处置后：`:app:compileArmsoloReleaseKotlin` 编译验证通过（BUILD SUCCESSFUL，2026-10-24）。改动未提交，随返回键守护一并待用户裁决。
- qa 未独立复跑全量单测（按纪律只读）；「1342 用例 0 失败」沿用提交时的声明。
- 未真机验证：回收站全链路（含本次 P2 修复后的列表口径）随 versionCode 117 正式包一并真机验收。

## 备注

- 子代理首派后约 1.5 小时未交报告，发催办指令后立即收束输出；后续派发同类审查建议在 prompt 里就写明「逐项结论先行、超时即交中期报告」。
