# 项目改动账本（Change Ledger）实施计划

> 生成：2026-11-15。用户已批准：新建 `feature/changelog`、记忆图谱加第 4 个 tab。
> 用途：长期项目里"提过的反馈/改动，哪些改好了、哪些没做"再也翻不出来——本功能给项目一份**跨会话的改动账本**。

## 一、痛点（用户原话归纳）

- 长期项目里，反馈与改动累积多了，**完全不记得哪些改好了、哪些没做**；
- 需要两份视图：一份给用户看（**极致压缩 + 大白话**）、一份给 agent 看（**结构化，能接续工作**）。

## 二、调研结论（GitHub API 实测，2026-11-15）

| 项目 | 可借鉴 |
| --- | --- |
| `Dhananjay625/dev-workflow` | `## Current state` 块（WORKING/BROKEN/NEXT STEP/OPEN DECISIONS/DO NOT）+ append-only Entries（`file:line — before→after — why — verified how`） |
| `illodev/workfile` | "review ≠ done，done 需真跑过的证据"；单一事实源、双视图派生 |
| `baptistebriel/ink` | 痛点陈述与本项目一致：会话间丢上下文、没有 why 的痕迹 |
| `npryce/adr-tools` | ADR 范式：上下文/选项/决定/后果 |
| `orhun/git-cliff` | Conventional Commits → changelog（本项目已有 commit 规范，kind 可直接对齐） |

三条采纳的硬原则：① **单一事实源、双视图渲染**（不做两份记录，否则必不同步）；② **"改完了"必须有证据**（跑过的命令/测试，merge 不算）；③ **有一个"当前状态"块**，让零记忆的新会话能独立接续。

> 学术侧（arxiv / Semantic Scholar / WebSearch / WebFetch）在本容器被环境拦截或限流，**未能核实**，故不引用。

## 三、设计

### 3.1 入口：记忆图谱第 4 个 tab
```
Clock 结论 | MessageSquare 会话 | Share2 图谱 | CheckSquare 账本 ← 新增
```
复用既有 `FloatingTabBar` + `FloatingTabItem`；`MEMORY_GRAPH_TAB_COUNT` 3→4。

### 3.2 内容：一页双视图（同一份数据的两种渲染）

```
┌ 当前状态块（可选折叠）────────────────────────┐
│ ✅ 已验：…  ⚠️ 已知坏：…  ▶️ 下一步：…  ⛔ 别碰：…│
├ 改动账目列表（按状态/类型筛选）────────────────┤
│ [待复验] 思考档位审计落库   feat  11-15  ✅已验 │
│ [已关闭] 会话列表分组折叠   feat  11-14  ✅已验 │
│ [待确认] 官网双语           docs  —      ⏳待做 │
├ 统计（图表组合）─────────────────────────────┤
│ 环形：已验/待验/待做   柱状：按类型   计数：阶段│
└──────────────────────────────────────────────┘
```

- **用户视图**（默认）：`humanSummary` 一行大白话；
- **Agent 视图**（展开/复制）：`agentDetail`（why + before→after）+ `evidence` + `files` 结构化四段。

### 3.3 数据模型（新表 change_ledger，与 fix_ledger 同构）

| 列 | 说明 |
| --- | --- |
| id / workspacePath | 主键 / 所属工作区（账本按项目隔离） |
| title | 一行标题 |
| kind | feat/fix/refactor/docs/chore/perf/test（对齐 Conventional Commits） |
| status | **复用 AnnotationStatus** 状态机（PENDING→SENT→FIXING→REVIEW→CLOSED） |
| humanSummary | 大白话（用户视图） |
| agentDetail | why + before→after（Agent 视图） |
| evidence | 怎么验的（命令/测试名）——"done 需证据" |
| filesJson | 涉改文件 |
| sessionId / sourceAnnotationId | 回链会话 / 标注 |
| createdAt / updatedAt / verifiedAt | 时间 |

### 3.4 Agent 侧通道
仿 `FixLedgerTool` 新增 `changelog` 工具：AI 完成改动当轮落笔（改了哪些文件/为什么/怎么验的）。

## 四、改动清单

1. 迁移 77：新建 `change_ledger` 表（`SCHEMA_VERSION` 76→77）
2. `feature/changelog/`：entity / dao / repository / domain model / ViewModel
3. `MemoryGraphScreen.kt`：第 4 个 tab + 账本页
4. `feature/changelog/domain/tool/ChangelogTool.kt`：agent 写入口
5. strings 中英双语
6. docs-site 同步（能力地图 + 使用文档）

## 五、验证

冒烟编译 → 单测 → `check_migrations.py` → detekt；UI 改动需真机验证。
