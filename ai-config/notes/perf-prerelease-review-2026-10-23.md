# 发版前性能/功耗审查处置台账（2026-10-23）

> 报告来源：性能与功耗审查子代理（承担本项目 `optimizer` 角色；本环境未注册 `optimizer` 命名子代理，改用独立上下文的通用子代理 + 同一份审查口径）。
> 审查对象：`2ce313c..061eb2b` 共 5 条提交 + 发版前 qa 处置的未提交改动。
> **结论：无性能/功耗回退，本批相对上个已发布版本是净改善（交付物页去掉了「进页递归遍历整个工作区」的 IO 大头）；无 P0、无 P1，6 项 P2，建议出正式包。**
> 本文件为主会话逐条处置结果（纪律：报告不得无响应归档）。

## 一、处置一览

| 编号 | 定级 | 内容 | 处置 |
|---|---|---|---|
| P2-1 | P2 | 脚本清单重复读盘：首次进页 `init` + `LaunchedEffect` 两次；从编辑器返回 `onBack` + `LaunchedEffect` 两次 | **修（去重）** |
| P2-2 | P2 | 打开定时任务编辑器无条件建 `ScriptsViewModel`（一次 `list()` 读盘）；`scripts.map{}` 每次重组重算 | **修（remember）+ 记录** |
| P2-3 | P2 | 称 `containerPath()` 带 `mkdirs()` 副作用、在组合期执行 | **核实为误报** |
| P2-4 | P2 | `runs` 输出驻留至离开设置页（单条 ≤ 约 40k 字符，无条数上限） | **记录（可接受）** |
| P2-5 | P2 | 定时任务卡片每次重组对 ≤4000 字符的 `lastOutput` 做 `trim()` 拷贝 | **修（remember + 仅失败行）** |
| P2-6 | P2 | `SCRIPT_TIMEOUT_MS` 10 分钟恰等于 WorkManager 对普通 Worker 的执行上限 → 挂死脚本可能被系统停掉，既不落库也不通知 | **修（降到 5 分钟）** |

## 二、逐条处置

### P2-1 清单重复读盘 → 已修

- 处置：`ScriptsViewModel.refresh()` 加 job 去重（`refreshJob?.isActive == true` 早退），与 `AIAgentViewModel.refreshDeliverables` 同一写法。
- 为何不去掉 `onBack` 的 refresh：它是「编辑器写完盘后重读」的语义保证；去重后重叠调用被合并，既保证新鲜度又不多读。
- 收益：每次进页 / 每次返回由 2 次 `list()`（一次索引读 + 逐文件读首行注释）降为 1 次。

### P2-2 编辑器打开时的清单读取与 map 重算 → 部分修 + 记录

- 已修：候选脚本名列表用 `remember(scriptsState.scripts)` 缓存，清单不变时保持同一实例，避免编辑器子树因参数变化多余重组。
- 记录不改：「打开编辑器即建 `ScriptsViewModel`」会带来一次 `list()` 读盘。理由：类型选择在编辑器内部（新建任务默认 COMMAND，可切 SCRIPT），提前到外部按类型判断会把状态判断拆到两处；且该读盘有界（索引 ~60B/脚本 + 每脚本读至多 20 行）、在 IO 线程、且 VM 在 settings 路由作用域内本就会为「脚本页 / 定时任务编辑器」两处共用而不重复创建。

### P2-3 `containerPath()` 组合期 mkdir → 核实为误报（不改）

- 核实：`ScriptRepository.containerPath(name)` 只做 `require(isValidName)` + 字符串拼接 `"$CONTAINER_SCRIPTS_DIR/$name"`
  （`CONTAINER_SCRIPTS_DIR` 是常量），**不调用 `dir()`**；带 `mkdirs()` 的 `dir()` 只被 `list()`/`save()`/`resolve()` 等读写路径使用。
  故组合期没有文件系统 syscall。已复核 `SettingsScreen` 里 `path = scriptsViewModel.containerPath(name)` 的调用链。

### P2-4 `runs` 输出驻留 → 记录（可接受）

- 依据：单条输出受引擎限幅（head 20k + tail 20k），`refresh()` 会清掉已消失脚本的条目、删除时立刻移除、离开设置页随 VM 销毁释放；无「永远关不掉的旧输出」。
- 不做收紧：进一步按条数/总量裁剪收益有限，且会影响用户回看最近一次输出（本批刚为此加了「失败输出可见」）。记为观察项。

### P2-5 卡片失败输出重算 → 已修

- 处置：`LastRunText` 里把 `trim()` 结果用 `remember(task.lastOutput)` 缓存，并只在「非 AI 型 + 失败」分支内计算（成功行不再拷贝）。

### P2-6 脚本超时等于 WorkManager 上限 → 已修（降到 5 分钟）

- 现象：`SCRIPT_TIMEOUT_MS = 10 分钟` 恰好等于 WorkManager 对普通（非前台）Worker 的执行上限，
  挂死的脚本更可能先被系统停掉 Worker（取消协程），那一次既不落「上次运行」也不发失败通知——
  而本项目对脚本型任务的口径是「失败必报」。
- 处置：降到 `5 分钟`（与命令型的 `COMMAND_TIMEOUT_MS` 一致），并写明理由，
  让引擎 watchdog 收尾（落库 + 失败通知），行为可预期。
- 影响：单次脚本执行上限从 10 分钟收紧到 5 分钟；无人值守场景下「跑不完要被叫停并告知」比「静默消失」更符合设计。

## 三、审查确认无问题的面（采纳）

- **无新增唤醒源**：未新增 WorkManager 请求、前台服务、常驻监听、定时器或持续动画；脚本型「成功静默」实际减少通知。
- **无新依赖、无新权限**：`gradle`/`AndroidManifest`/`proguard` 本批均未改动；`assets` 增量仅 3 个 prompts md（示例脚本在基线已存在）。
- **调度频率未变**：`TaskScheduling` 仅把 `type == COMMAND` 换成 `!task.isAiType`，周期/延迟/约束逐字未变。
- **线程纪律**：新增链路的磁盘 IO 全在 IO 线程（界面侧 `withContext(Dispatchers.IO)`、工具侧经 `StatefulAgentWorkflow` 的 IO 上下文、DataStore 自带 IO 调度）。
- **交付物页修正净收益**：删掉了 `listFilesRecursive(CONTAINER_ROOT, 8)` 全工作区递归 + 每命中项父目录回查；留存路径（DAO 查询 300 条、有界）开销可接受。

## 四、自验

- `:app:compileArmsoloReleaseKotlin`：**通过**；全量单测结果见发版构建前的最终一次运行记录。
- 既有偶发（非本批，延续记录）：`SyncEngineIgnoreTest.upload_skips_custom_ignored_paths`（`UncaughtExceptionsBeforeTest`），隔离复跑通过。

## 五、未覆盖（如实）

- 真机量化：脚本页进页/返回的实际耗时与掉帧、`AnimatedContent` 过渡中的刷新可感知性。
- P2-6 的竞态结论需真机用挂死脚本复现验证（WorkManager 停 Worker 与引擎 watchdog 谁先到）。
- 大规模脚本（数百）场景下 `.index.json` 与逐文件读的放大程度，仅按代码推算为线性。
- R8 维度不在本审查范围（由发版前 qa 专项覆盖）。
