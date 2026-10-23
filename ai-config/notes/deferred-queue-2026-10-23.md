# 顺延项执行队列（2026-10-23 排定）

> 用户指令：按推荐顺序顺延排队。顺序来源＝ `handoff-2026-10-23-final.md` §四（顺延项）
> 与 `overnight-batch-delivery-2026-10-23.md` §七，按「依赖关系 → 风险/规模由小到大」重排。
> 纪律：每批走审查路由（L2 → qa；L3 → qa＋optimizer；正式编译前双审＋R8 维度）；
> 本地 commit 不 push；无人值守路径必须复核守夜（scheduledRun）静默放行分支。

## 队列

| 序 | 项 | 依赖 | 规模 | 状态 |
|---|---|---|---|---|
| 1 | 脚本系统切片 A：`run_script` 工具（列脚本/按名运行/返回输出）＋ `~/.aicode/scripts/` 固化目录 | 无 | 小（工具＋目录＋能力登记） | 代码完成；qa 已审（无 P0、P1×2 已修，见 `qa-sliceA-disposition-2026-10-23.md`）；待 commit/真机验收 |
| 2 | 脚本系统切片 B：脚本页 UI（列表卡：名称/语言/描述/上次运行/来源；新建、编辑复用文件编辑器、运行（输出内联、长输出折叠）、删除） | 1 | 中 | 代码已落地（`ScriptsSection` + `ScriptsViewModel` + `SettingsScreen` 接线），待编译与 qa |
| 3 | 脚本系统切片 C：定时任务新增「运行脚本」动作（跑完通知策略：成功静默/失败必报、输出截断入库） | 1 | 中 | 代码已落地待验；**无需 Room 迁移**——任务存 DataStore 的 JSON（`ScheduledTaskRepository`，`ignoreUnknownKeys`），加字段/枚举值即兼容 |
| 4 | 会话回收站（30 天软删） | 需设计：`chat_sessions` 软删迁移、与存储推荐清理/孤儿检测的交互 | 中 | 待设计 |
| 5 | 修复账本 | 现状未核实，先设计 | 中 | 待设计 |
| 6 | 大屏双/三栏布局 | 已核实为真缺口（`isExpandedWidth` 全库仅 MainActivity:549 一处使用，聊天主界面大屏仍单栏） | 中 | 待设计 |
| 7 | CLI 引擎桥接一期（Claude Code） | 设计已定稿（见 handoff-2026-10-23-overnight-batch 第 12 条，含 UI 布局约束：引擎选择收编进模型选择弹窗、顶栏「智能体」按钮移除时机） | 大 | 待开工（工程量大） |
| 8 | 战略大件：标注中心真融合、编队消息投递语义、UI 自动化一期（只读）、记忆图谱 v2 | 各自设计先行 | 大 | 逐项立项 |

## 已排除（核实已具备，不入队）

- 消息排队：`_queuedRequests` 队列＋`processNextInQueue` 三重守卫（暂停会话/命令占用/活跃 job），带重排/撤销/插队 API。
- 文件冲突检测：编辑器 `loadedFingerprint` 比对＋差异弹窗（覆盖/放弃/取消）。

## 勘误与注意

- 脚本系统切片 A 曾在交接笔记 §二/§四 被记为「已做」，实测未做（无 `tool/script/` 目录、全库无 `run_script`）；A/B/C 均从零开始。**2026-10-23 进展**：A 代码+qа 审查+修复完成；B 代码落地待验；C 未开工。切片 A 的 I/O 层仍用宿主 `java.io.File`，远程 SSH 模式已改为显式拒绝（真正支持排期见 `qa-sliceA-disposition-2026-10-23.md` §三-1）。
- 出正式包前先处理 versionCode（先 commit 或把 `gitCommitCount() + 1` 调为 +2），避免与 v95 撞号。
- 脚本系统规划原文见 `user-scripts-plan.md`（含二期商店脚本 tab、明确不做的边界：脚本一律容器内执行，不引入本体 JS 引擎）。
