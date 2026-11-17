# qa 处置台账：第 8 项三件（记忆图谱 v2 / 编队投递 / fixledger 回写）（2026-10-24）

> qa 结论：无 P0；P1×5 + 边缘 P1×1；全部当批修复。编译两轮后通过（BUILD SUCCESSFUL）。

| 级别 | 发现 | 处置 |
|---|---|---|
| P1-1 | fixledger 未收录 ToolPermissionCatalog → 回退 ASK，每次调用弹窗，破坏修复闭环 | **已修**：目录 AGENT 组登记 AUTO 档（perm_tool_fixledger 双语字符串） |
| P1-2 | 投递重投后 deadline 不刷新，重投窗口塌缩到 0–30 秒 | **已修**：新增 extendDeadline，重投路径刷新 120s 时限 |
| P1-3 | 重投正文被截断为 120 字 | **已修**：Tracked 保留完整正文（content），预览截断语义取消 |
| P1-4 | 图谱搜索清空后假空态（hits 空列表盖住全量节点） | **已修**：查询 <2 字符置 hits=null 退回全量 |
| P1-5 | deliveryTracker.onExpired 持 VM，onCleared 未清 → 单例泄漏 + 提醒失灵 | **已修**：handler 提为类属性 + ownsDeliveryHandler 标记，onCleared 按惯例清理 |
| P1-edge | 用户未手动转入修复账本时回写必断链（NOT_ADVANCEABLE） | **已修**：advanceLinked 无条目时从标注自动补建（带标注当前状态），标注侧仅在落后时前进（防倒拉） |
| P2 | Tracked 字段非 volatile/sweep 竞态、子→父超时 nudge 循环风险、提醒丢失无留痕、图谱同步逐会话全量读消息、argsBlob 误连边、同步不清理已删会话节点、LIKE 未转义、SegmentedTabs currentPage、注释/格式琐项 | **记录待排期**（ai-config/notes 本文件即台账；不阻塞发版；图谱同步成本优化与按 toolName 投影查询列入后续） |

## 通过项（qa 独立核实）

迁移 71 三方一致（71.json 逐项比对）、双建库路径、唯一索引 NULL 语义、BFS 无环、投递追踪骨架与功耗纪律（有未决才 sweep）、advanceLinked 状态推进 guard、子代理工具过滤不误漏、能力双语登记。

## 待验证边界

- 真机迁移 68→71、重投时序、UI 手感、0.55px 外框可见性——随最终包真机验收。
