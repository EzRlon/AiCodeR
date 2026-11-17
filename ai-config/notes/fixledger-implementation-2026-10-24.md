# 修复账本 · 实现记录（队列第 5 项，2026-10-24）

> 状态：已完成（实现 + 编译 + qa 无 P0/P1 + P2 全处置）。

## 本日同批推进的引擎桥接（队列第 7 项，进行中）

- A 数据层：chat_sessions.engine 列（迁移 70 幂等轨 + 审计 SQL + 备份 DTO）、SCHEMA_VERSION=70。
- B 域层：feature/agent/domain/engine/（AgentEngines、PermissionBridgeServer、ClaudeCodeBridge、内置 Node 桥接脚本）。资产落 /root/.aicode/bridge/（独立于 rootfs）。
- C 集成层：AIAgentViewModel 按会话引擎分流事件流、createSession 默认引擎、运行中禁切引擎；ScheduledAgentViewModelProvider 手动构造点同步。
- 待办：D UI 层（模型弹窗两段式+pager、输入栏胶囊、会话角标、设置默认引擎、顶栏按钮移除）→ E 能力登记+qa。

## 落地内容

- 新包 `feature/fixledger/`：domain/model（FixLedgerItem，复用 AnnotationStatus 状态机）+ data/local（FixLedgerEntity/Dao）+ FixLedgerRepository + FixLedgerViewModel + component/FixLedgerScreen（镜像标注中心的列表/状态胶囊/详情弹窗/SwipeToDeleteRow 模式）。
- 迁移 69：`Migration68To69` 走 Kotlin 幂等轨（CREATE TABLE IF NOT EXISTS），审计 SQL `assets/migrations/69_add_fix_ledger.sql` 留存；SCHEMA_VERSION=69；AgentDatabase 注册实体与 DAO；AgentModule 提供 DAO；schemas/69.json 已随编译导出。
- 入口衔接：标注中心顶部新增全宽「修复账本」卡片（第三张卡）→ `composable("fixledger")`；标注详情页新增「转入修复账本」（同一标注只转一次、状态沿用标注当前状态）。
- 能力感知：docs-site/docs/guide/capabilities.md + prompts/65-capabilities.md 已登记。
- 字符串：values（中）+ values-en 各 11 条。

## qa 复核结论（无 P0/P1，可放行）与 P2 处置

| 发现 | 处置 |
|---|---|
| P2-1 转入 check-then-act 非原子，连点可重复转入 | 已修：详情弹窗转入按钮 one-shot（首次点按后禁用） |
| P2-2 Toast 持组合期 Activity context | 已修：改 applicationContext |
| P2-3 全限定默认参数/LocalContext 未走 import | 已修：补 import |
| P2-4 values-en 段末多空行 | 已修：删 |
| P2-5（qa 笔误更正）字符串实际双语各 11 条 | 无需处置 |

迁移三方一致性（DDL/Entity/69.json 逐字符比对）、双建库路径、Room 正确性、DI、性能功耗纪律、能力登记均由 qa 独立核过通过。

P2 处置后编译：`:app:compileArmsoloReleaseKotlin` 通过（2026-10-24）。真机升级路径（68→69）随最终包验收。
