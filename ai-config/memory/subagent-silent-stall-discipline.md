---
name: subagent-silent-stall-discipline
description: 子代理静默失效真因（定案）：事件收集协程无异常隔离+tryEmit丢弃+early return不释放活跃位；判别铁证是 updatedAt==createdAt 表示工作流从未启动
---
# 子代理静默失效：真因是事件链三处静默失败（2026-10-26 定案）

## 判别铁证（不要用「等多久没反应」判断）
`task(action="list")` 的 `createdAt` / `updatedAt`：
- **`updatedAt == createdAt`（0 秒）→ 会话行从未更新过 → 工作流根本没启动**
- 有真实活动（几十秒到几分钟）→ 在跑

依据：`executeAgentRequestStream` 一启动就会 `persist(USER)` + `touch()`，`touch` 是 `UPDATE chat_sessions SET updatedAt`。所以只要启动过，updatedAt 必变。

`task(action="read")` 返回「（子代理尚未回复）请求内容：<prompt>」时，那是回退到用户消息的分支（`TaskTool.kt:372`），说明一条助手消息都没有。

## 真因（三处，均已修复）
子代理启动链：`TaskTool.emit(SPAWNED)` → `SubAgentEventBus.tryEmit` → `AIAgentViewModel` 的 collect → `spawnSubAgentWorkflow` → `executeAgentRequestStream`。三处都能让事件静默消失，**无日志无报错**，表现一致：子会话建成、UI 显示 running、工作流永不启动。

1. **事件收集协程无异常隔离**（`AIAgentViewModel.kt:1728`）：`collect` 块裸奔，任何一处抛异常会让整个收集协程**永久终止**，之后所有 SPAWNED 无人消费 → 子代理全部静默失效，App 其余功能完全正常，直到 ViewModel 重建才恢复。这是「一轮全失败、过一阵又全正常」的解释。
2. **`tryEmit` 返回值被丢弃**（`SubAgentEventBus.kt:116`）：SharedFlow 缓冲满时返回 false，事件被丢。
3. **三处 early return 不释放活跃位**（`spawnSubAgentWorkflow`）：「父会话不存在」「子会话已删」正常返回但活跃位已占 → 永久占坑，isFull 到顶后派发全被拒。第三处「同会话已有活跃 job」**刻意不 release**（会话本就在跑）。

修复提交：fca946b（1+2）、9fcb2b7（3）、65a2d1d（可观测性）。

## 方法论教训
- **同一形态任务先败后成 → 怀疑进程内状态污染，不是任务规模**。本次因此推翻了自己「长回合+重负载」的错误结论。
- 前两次结论（2026-10-19 的"流式卡死"、本次首版"长回合+构建并发"）都是错的：都被「等很久无输出」这个表象误导，没有用结构化证据。
- 派子代理后判活：先 `list` 看 `updatedAt` 是否变化（旧包看 `updatedAt` 是否 == `createdAt`），别干等。