# App 被 LMK 后的孤儿 CLI 冷启动回收（设计）

> 2026-11-15 晚批次。关联 `engine-process-duplication-2026-11-15.md`。风险等级：高（会发信号杀进程）。

## 一、问题

`EngineProcessRegistry` 只在**进程内**登记 CLI。App 主进程被 LMK / 被系统杀掉时来不及执行
`finally`，`proot → claude/codex/opencode` 整棵树被 init 收养后继续跑：继续烧 token、继续改工作区，
下次打开 App 又起一份，形成「两份同一会话并行施工」（实测见关联笔记）。

## 二、目标与边界

- **只回收本地 PRoot 引擎进程**（SSH 远程模式不经本地 proot，不在此列）。
- **绝不误杀**：宁可漏杀，不可错杀。三重校验：
  1. PID 存在且 `/proc/<pid>/stat` 的 starttime（第 22 字段）与登记值完全一致 → 排除 PID 复用；
  2. `/proc/<pid>/cmdline` 首参包含 `libproot`（App 的 proot 以 jniLib 形式存在于 nativeLibraryDir）；
  3. 记录带 **App 进程 runId**（每次进程启动生成 UUID）；冷启动只回收 runId 不同的记录，
     当前进程刚登记的记录一律跳过，避免「回收协程」与「新回合 spawn」竞态误杀。
- 不做常驻扫描、不做定时任务：只在 `Application.onCreate` 后台执行一次，符合性能/功耗纪律。

## 三、落盘格式

`filesDir/engine-processes.json`（原子替换写，同 `SessionRunStateStore`）：

```json
{ "<sessionId>": { "pid": 12345, "startTicks": 987654, "engine": "claude",
                   "runId": "<uuid>", "createdAt": 1730000000000 } }
```

- spawn 成功后同步写一条（register 本身已在 `Dispatchers.IO` 内）；
- 回合正常收尾 / 主动停止时，异步删一条（避免主线程 IO）；
- 用文件而非 Room：状态量极小，避免迁移。

## 四、冷启动回收流程

1. `AIEditorApp.onCreate`（崩溃子进程直接 return，不执行）→ `appScope.launch(IO)` 调
   `EngineProcessRegistry.reclaimOrphansOnColdStart()`（AtomicBoolean 保证只跑一次）。
2. 读全量记录；对每条：
   - runId == 当前进程 → 跳过；
   - `/proc/<pid>` 不存在或状态 `Z` → `already-exited`；
   - starttime 不一致 → `pid-reused`，不杀；
   - cmdline 不像 proot → `not-proot`，不杀；
   - 否则收集以该 pid 为根的**在活后代**（扫描全 `/proc` 建 ppid 树）。
3. 先 SIGTERM **后代（叶→根）**，再 SIGTERM 根（proot 带 `--kill-on-exit`，正常退出会级联清理）；
   等待 800ms 宽限；对**第一轮快照中的 PID**逐个复核 `startTicks` 未变且非僵尸后才 SIGKILL。
   不在宽限后重新扫树：根进程退出后 PID 可能被复用，按新树 SIGKILL 会误杀（qa P1-1）。
4. 只删除 runId != 当前 的记录，保留并发新写入的记录；结果写 FileLogger。

信号通道：`android.os.Process.sendSignal(pid, 15/9)`（API 24+，minSdk 26）。

## 五、可测试性

`ProcessProbe` 接口封装 `/proc` 读取与发信号，生产实现 `ProcFsProbe`；
`EngineOrphanReclaimer` 纯逻辑（注入假 probe + 0 宽限）可单测：
PID 复用不杀、非 proot 不杀、已退出不杀、当前 runId 跳过、后代先于根、宽限后 SIGKILL。
`EngineProcessFile` 用临时目录测往返、损坏文件降级为空。

## 六、已知取舍

- 若 proot 被 SIGKILL，`--kill-on-exit` 不执行，后代可能残留：所以先杀后代、再杀根。
- 宽限后如果根进程退出且后代被 `--kill-on-exit` 清理，则无事可做；若后代被 init 收养，
  因无法安全证明同源（PID 树已断），按「宁可漏杀」放弃。
- 极短窗口内 App 被再次杀死，记录可能未落盘（漏杀）；下次冷启动若 pid 还在则仍会被回收。
- 不做「跳过太新的记录」的时间阈值：runId 已能精确区分来源，时间阈值反而会漏杀。
- `NOT_PROOT`（可能只是 /proc 瞬时读取失败）保留记录待下次重试；回收过程抛异常时整批记录保留。
- **PID 认领方式**：Android 公共 SDK 的 `Process` 没有 `pid()`，反射不可靠（qa P0-1）。改为扫
  `/proc` 找本进程的 proot 子进程，按各桥注入的 `PERMISSION_BRIDGE_SESSION=<sessionId>` 精确匹配；
  匹配不到时退化为「尚未认领、启动时间最新的 proot 子进程」。该路径待真机冒烟验证。
- **每会话仅一条记录**：同会话若已存在两份历史 proot，只能回收最后登记的那条。register 前会
  同步补跑一次冷启动回收（qa P2-6），把「覆盖旧记录」的窗口压到最小；彻底解决需改为按 PID 列表。
- **宽限期新 fork 的后代不在快照内**：为避免「根退出 + PID 复用」误杀，宽限后不再重扫进程树；
  这类后代只能等下次冷启动。属「宁可漏杀」的刻意取舍（qa P2-7）。

## 七、QA 处置（2026-11-15 20:5x）

- P0-1 PID 认领：公共 SDK 无 `Process.pid()`，删除反射；改扫 `/proc` 找本进程直接子进程，
  先按 `PERMISSION_BRIDGE_SESSION` 精确匹配，匹配不到时要求「未认领 + cmdline program 位等于引擎名」，
  仍拿不到返回 null（不落盘）。**不再回退到已认领的旧 PID**（否则会记错 pid 导致漏杀）。
- P0-2 `liveTree` 根进程漏发信号：根已放回快照首元素；根不在存活表时返回空并标记 `already-exited`。
- P0-3 测试同步：`retainWhere` 双参、根信号顺序、宽限期 PID 复用、`parseProcStat` 用例。
- P1-4 认领不到 PID 时不再无条件删除落盘记录，避免抹掉接替回合的新记录。
- P2-6 `register` 开头同步回收**同会话**旧记录（`reclaimPreviousForSession`）：无旧记录零开销，
  有旧记录时最多等一次宽限，同时避免把全量回收的 sleep 压到首个回合（optimizer P1-1）。
  仍未彻底消灭「Application 异步回收已 CAS 但尚未 load」的极小窗口；根治需按 pid 多条存储，记录待排期。
- 验证：`:app:compileUniversalDebugKotlin`、`:app:hiltJavaCompileUniversalDebug` BUILD SUCCESSFUL；
  `:app:testUniversalDebugUnitTest --tests com.aicode.feature.agent.domain.engine.*` 强制重跑 0 失败
  （新增 `EngineOrphanReclaimerTest` 9 例 + `EngineProcessFileTest` 5 例）。

## 八、Optimizer 处置（2026-11-15 21:0x）

- 无 P0；P1-1（register 同步全量回收拖慢首回合）已按建议改为只回收同会话旧记录。
- 其余 P1/P2 记入 `ai-config/notes/engine-process-followups-2026-11-15.md` 待排期，
  不在发版临界期强改。
- **真机冒烟必查**：引擎回合启动后 `filesDir/engine-processes.json` 是否出现记录（`resolveChildPid`
  依赖同 uid 读 `/proc/<pid>/environ`，本容器无法验证 Android 的 hidepid/SELinux 行为）。
