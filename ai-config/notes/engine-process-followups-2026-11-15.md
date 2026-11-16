# 引擎进程管理待排期（optimizer / qa 2026-11-15）

> 来源：本批 LMK 孤儿回收的 QA 与 optimizer 审查。P0 已全部当批修复；以下为记录待排期项。

## 优先

1. **`/proc` 扫描成本**（EngineProcessRegistry.kt `resolveChildPid`）：每次 spawn 全表 `allPids` +
   每 pid 一次 `stat`，设备通常 300–800 进程。建议单次快照内缓存 stat；可先尝试
   `/proc/<pid>/task/<tid>/children` 快路径（内核差异需兜底），并避免候选重复 `stat`。
2. **多条记录串行回收**（EngineOrphanReclaimer.kt）：每条独立全表扫描 + 独立 800ms 宽限。
   改为「一次快照 → 全部 SIGTERM → 一次宽限 → 统一 SIGKILL」。
3. **stat 读失败误判**：`stat == null` 当前等同 `ALREADY_EXITED`，记录随即被删。应区分 ENOENT 与
   其它 IOException，后者按 `NOT_PROOT` 一样保留待下次重试。
4. **每会话一条记录**：同会话多份历史 proot 只能回收最后一条；需要按 PID 列表存储。
5. **`claimedPids` 小泄漏**：同会话替换回合时旧 pid 未移除（只覆盖 `sessionPids`）。

## 次要

6. `EngineProcessRegistry.killProcess` 每次 kill 新建裸线程 sleep 1.5s；stop-all 时可复用一个
   scheduled executor。
7. 可测试性：`EngineProcessRegistry` 的 PID 认领/落盘路径依赖真实 Context 与 /proc；
   建议注入 `ProcessProbe` + 目录，认领逻辑抽成纯函数。
8. `ReleaseReadinessChecker` 在 domain 层直接产出 `R.string` 文案并注入 Context；
   `verifyAlias` 每次刷新都 `copyToLocal` 证书（远程模式 = SFTP 往返）且 `keytool` 在 App 内通常
   不可用。建议改为返回资源键/结构化结果、临时文件 finally 删除。
9. 原子 JSON 写实现重复三份（EngineProcessFile / SessionRunStateStore / QueuedRequestStore），
   可抽 `AtomicJsonFile<T>`。
10. 死代码：`NoteEditorWebView` 的 compactToolbar/format bar 分支已无调用方；
    `EngineProcessRegistry.killAll()` 无调用方；`DevKeysSection` 有未用 import。

## 真机冒烟必查

- 引擎回合启动后 `filesDir/engine-processes.json` 是否出现记录（依赖同 uid 读
  `/proc/<pid>/environ`，容器内无法验证 Android hidepid/SELinux 行为）。
- 冷启动首回合 spawn 延迟、多会话并起时 `resolveChildPid` 耗时、低内存机冷启动是否掉帧。
