# 子代理「全部失效」真因（2026-10-26 定案）

> 前一版笔记 `subagent-stall-rootcause-2026-10-26.md` 的结论**是错的**——那版把原因归为「长回合 + 重负载窗口导致 LLM 流式卡死」。本文是用时间戳证据推翻后重写的定案版。

## 决定性证据：时间戳

`task(action="list")` 的 `createdAt` / `updatedAt` 给出铁证：

| 子代理 | createdAt | updatedAt | 存活 |
|---|---|---|---|
| ed806e6a 审查批次6/7全量 | 13:37:49 | 13:37:49 | **0.0s** |
| 8d594894 脚本+schema复核 | 14:17:28 | 14:17:28 | **0.2s** |
| f52ddf67 性能功耗维度 | 14:25:00 | 14:25:00 | **0.1s** |
| 794f1e59 小范围复核A | 15:22:15 | 15:22:15 | **0.1s** |
| bd8cdc93 Explore探针 | 15:11:40 | 15:11:49 | 9.8s |
| c9b22eef qa链路探针 | 15:13:25 | 15:13:50 | 25.4s |
| 3f6647bc qa禁Bash探针 | 15:15:42 | 15:16:12 | 30.5s |
| a9978cab qa-Bash探针 | 15:18:40 | 15:19:03 | 22.4s |

`updatedAt == createdAt` 意味着**会话行从未被更新过一次**。而 `executeAgentRequestStream` 只要启动就会走 `persist(USER)` + `sessionUseCase.touch()`（`AIAgentViewModel.kt:2357`/`2365`），`touch` 是 `UPDATE chat_sessions SET updatedAt`（`ChatSessionDao.kt:92-93`）。

**结论：失败的子代理从未启动工作流，不是「跑到一半卡死」。** 之前"流式卡死"的判断从一开始就不成立。

## 第二个证据：间歇性，与任务大小无关

同一形态的三文件任务：
- 17:07 派发「二分-读三文件」→ 失败（0.0s）
- 17:15 派发「计时实验-三文件」（同样三个文件、同样问题）→ **成功**（111s）

如果是任务太大/构建并发，同一任务不会先败后成。这是**进程内累积状态的间歇性故障**。

## 真因：事件链上三处静默失败

子代理启动链路：`TaskTool.emit(SPAWNED)` → `SubAgentEventBus.tryEmit` → `AIAgentViewModel` 的 collect → `spawnSubAgentWorkflow` → `executeAgentRequestStream`。

三处都能让事件静默消失，且**全都无日志、无报错**，表现完全一致：子会话建成、UI 显示 running、工作流永不启动。

### 1. 事件收集协程没有异常隔离（`AIAgentViewModel.kt:1728`）
```kotlin
viewModelScope.launch {
    subAgentEventBus.events.collect { event ->   // 整个块裸奔
        when (event.type) { ... }
    }
}
```
`collect` 块里任何一处抛异常，**整个收集协程永久终止**。之后所有 SPAWNED 事件进 SharedFlow 却无人消费——子代理全部静默失效，而 App 其余功能完全正常。这解释了「5 个失败全在 16:47 前、6 个成功全在 16:52 后」：进程内发生过一次异常后收集器就死了，直到 ViewModel 重建（约 16:50 用户操作导致）才恢复。

### 2. `tryEmit` 返回值被丢弃（`SubAgentEventBus.kt:116`）
`MutableSharedFlow(extraBufferCapacity = 64)`，缓冲区满时 `tryEmit` 返回 false，事件被丢。原代码直接 `_events.tryEmit(event)` 不检查。

### 3. 三处提前返回不释放活跃位（`AIAgentViewModel.kt:1832`）
`spawnSubAgentWorkflow` 的「父会话不存在」「子会话已删」两处 early return 正常返回，但 SPAWNED 已在总线里占了活跃位——该会话永久占名额，`isFull` 到顶（5 个）后所有后续派发被拒。

## 修复（提交 fca946b + 9fcb2b7）

1. 事件收集逐事件 `runCatching`，失败记 `FileLogger.e` 并释放活跃位——收集器永不因单个事件死亡
2. `tryEmit` 返回值检查，失败记日志；SPAWNED 投递失败时回滚活跃位与 `startedAt`
3. 两处「不启动」的 early return 补 `release()`；第三处（同会话已有活跃 job）刻意不 release——会话本就在跑，清掉会把正在运行的会话名额错误释放

另（提交 65a2d1d）：`SubAgentEventBus` 记录启动时刻，`task(action="list")` 输出 `runningSeconds`，让「跑了多久」可见——这是定位本次问题所用的工具本身。

## 方法论教训

- **不要用「等多久没反应」判断卡死**，要用 `updatedAt == createdAt` 这类**结构性证据**。前两次我都因为「等了 15–35 分钟没输出」下结论，都是错的。
- **同一形态任务先败后成**，就该怀疑状态污染而非任务规模。我第一次没做这个对照，直接写了错误的根因笔记。
- `task(action="read")` 返回「（子代理尚未回复）请求内容：<prompt>」时，注意那是**回退到用户消息**的分支（`TaskTool.kt:372`），说明一条助手消息都没有——不是"回复慢"。
