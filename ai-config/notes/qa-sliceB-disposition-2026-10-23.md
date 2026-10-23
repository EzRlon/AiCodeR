# qa 报告处置记录：切片 A 修复 + 切片 B（2026-10-23）

> 报告来源：`qa` 子代理对「A 的修复 + B 的脚本页」未提交改动的独立复审（结论：无 P0、无阻断；建议修后提交）。
> 上一轮台账：`qa-sliceA-disposition-2026-10-23.md`。交接：`handoff-2026-10-23-scripts-sliceB.md`。

## 一、已修

| 编号 | 问题 | 处置 |
|---|---|---|
| P1-1 | 远程 SSH 模式下「新建脚本」未被拒绝（写宿主目录、编辑器开远端路径 → 建了看不到也打不开） | `ScriptsViewModel.create` 前置 `unavailableReason()` 守卫（置 `CREATE_FAILED`、不落盘）；`SettingsScreen` 在不可用环境下点「+」改弹原因说明（而不是静默无反应） |
| P2-1 | 运行脚本时在主线程写 `.index.json`（性能纪律） | `ScriptRunner.recordUnlessNotReady` 改为 `suspend` 并固定 `withContext(Dispatchers.IO)`；同步与流式两条路径共用 |
| P2-2 | 流式路径被取消/异常时也记「上次运行」，与同步路径语义不一致 | `onCompletion { cause -> if (cause == null) ... }`：只在正常跑完时记录 |
| P2-4 | 列表加载中会闪现空态「还没有脚本」 | `ScriptsSection` 的 `when` 增加 `state.loading -> Unit` 分支 |
| P2-5 | `.sh` 固定用 `bash`，在「rootfs 已装但未 provision」窗口会 127 失败 | `ScriptRunner.target` 对 `.sh` 改问 `commandEngine.defaultShell()`（provision 前 `/bin/sh`、之后 `/bin/bash`）；`ScriptRepository.SHELL_INTERPRETER` 常量收敛这一约定 |

顺带清理：`ScriptRunner.Target` 去掉未被任何调用方使用的 `interpreter` 字段（脚本页与工具展示仍用 `ScriptInfo.interpreter`）。

新增/更新的回归测试：
- `ScriptRunnerTest.target_usesEngineDefaultShellForShellScripts`（锁 P2-5）。
- `RunScriptToolTest.streamingRun_whenStreamAborts_keepsShownOutputAndRecordsNothing`（锁 P2-2）。
- `ScriptsViewModelTest.run_whenScriptVanished_reportsFailureAndClearsRunState`、
  `refresh_dropsRunStateOfScriptsDeletedOutsideThePage`、
  `remoteSshMode_refusesCreateWithoutWritingToDisk`（锁 P1-1 与运行失败/陈旧状态清理）。
- `ScriptsViewModelTest.awaitUntil` 超时由 5s 提到 10s（低配机器余量）。

## 二、记录不改（附理由）

1. **P2-3 滑开态点卡片内「运行」键不收回、直接运行**：子节点的 `clickable` 在命中路径上先消费 tap，父层「滑开时点击收回」不触发。
   修它要动全局共享组件 `core/ui/SwipeToDeleteRow`（暴露 revealed 状态或改手势分层），影响所有使用该组件的页面；
   影响有限（用户主动点了运行键，结果符合预期，只是行没收回），故记录待排期。
2. **O-1 `runs` 键与入参名潜在不一致**：现 UI 始终传全名（`script.name`），不触发；即使触发也会被 `refresh` 的现存名过滤自愈。
3. **O-2 `away` 档位 + `run_script=AUTO` 使「固化」在该档位下被静默放行**：既有跨工具问题（同 `manageMcp`），
   已在 `qa-sliceA-disposition-2026-10-23.md` §三-2 记录，需单独评估后统一处理。
4. **O-3 `delete` 的文件删除在锁外**：并发 `recordRun` 最多留下孤儿索引项（`list()` 只对现存文件出条目），无功能影响。
5. **O-4 进入脚本页刷新两次**（VM `init` + 内容分支 `LaunchedEffect(Unit)`）：量级可接受；
   与 `PromptsViewModel`/`MemoryViewModel` 的既有写法一致，不为省一次 `list()` 破坏一致性。
6. **O-5 `ToolSection` 内层 `verticalScroll` 嵌在脚本页外层 `verticalScroll` 内**：内层优先滚动，可接受（真机看视觉）。
7. **O-6 脚本正文有两条写路径**（`ScriptRepository.save` 与编辑器直写 `writeFile`）：编辑器自带指纹冲突检测、页面回来即重读，暂无破面。
8. **O-7 同帧双击「运行」可并发两次**（无 in-flight 守卫）：低危，量级为多跑一次脚本；如需可在 VM 用 `runs[name].running` 早退。
9. **O-8 运行期无流式进度**（脚本页走同步 `run`，聊天里 `run_script` 走 `stream`）：设计选择（页面要的是「输出内联 + 折叠」而非实时滚屏）；如需实时可后续切 `stream` + `live=true`。

## 三、仍需真机/构建验证（延续台账）

1. 脚本页真机闭环：新建（四种后缀）→ 编辑器改正文保存 → 描述/「上次运行」更新 → 运行看内联输出与折叠 → 左滑删除。
2. 内置示例脚本删除后重启是否重新释放。
3. 大屏两栏下 `ScriptEditor`（`CodeEditorScreen`）排版与未保存提示。
4. 备份导出/导入往返后 `scripts/` 与 `.index.json` 展示。
5. 未 provision 容器里跑 `.sh` 的实际形态（P2-5 修后应为 `/bin/sh` 正常执行，而非 127）。
6. 正式 R8 包前由 `optimizer` 复核 keep/混淆面（脚本页首次复用 `ToolSection`、新增 VM/Section/Tool）。
