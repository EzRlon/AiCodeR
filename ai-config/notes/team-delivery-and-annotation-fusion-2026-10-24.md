# 编队投递语义 + 标注融合 · 实现记录（队列第 8 项，2026-10-24）

## 编队消息投递语义（CCCC 吸收，第一期内存态）

- 新 `SubAgentDeliveryTracker`（domain/subagent，@Singleton 纯内存）：task send / messageParent 的消息带追踪——SENT →（2 分钟）DELIVERED；requireReply（messageParent）转等回复（10 分钟）；目标会话来话/发送方来话均结清。
- 超时：未送达重投一次（按原方向重发事件），仍超时经 `enqueueAgentRequest(isAutoTrigger)` 给发起方注入提醒——不再静默丢消息；扫描协程仅在有未决追踪时存在，结清即取消（无新增常驻轮询）。
- `SubAgentEvent` 加 `messageId` 字段（默认空，生命周期事件不受影响）。
- 不建表的依据（调研）：进程死亡时运行中的子代理工作流本身也终止，重投无意义；跨进程对账留待真实需求。

## 标注中心真融合（第一批：AI 回写闭环）

- 新 `fixledger` 工具（feature/fixledger/domain/tool）：action 参数 annotationId + target(FIXING/REVIEW)；`FixLedgerRepository.advanceLinked` 同时推进修复账本与标注账本（仅前进、禁 CLOSED——复验是用户验收），无条目/不可推进返回可读错误。
- 闭环缺口补齐：汇总发给 AI 的文本每条标注现在带「标注 id + 回写提示」（annotation_summary_line 加 %5$s），AI 修完即可自主回写，不再依赖用户手动推状态。
- 能力登记：capabilities.md + 65-capabilities.md。

## 分期未做（后续）

- 标注中心第二批（FIXING 自动推进的语义化触发）、第三批统一画布（常驻底图/账本内补画）。
- 编队投递的跨进程持久化（message_delivery 表）待真实需求。

## 待办

- [ ] qa 复核（记忆图谱 v2 + 投递语义 + fixledger 工具一批）。
