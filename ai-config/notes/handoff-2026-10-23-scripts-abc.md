# 交接（2026-10-23 暂停点）：用户脚本 切片 A 修复 + B + C

> 本文件是**当前权威交接**。细读顺序：本文件 → `handoff-2026-10-23-scripts-sliceB.md`（B 落地细节 + C 计划原文）
> → `handoff-2026-10-23-scripts-sliceA.md`（A 的设计决策与边界）→ 两份 qa 处置台账（见 §三）。
> 队列与顺序：`deferred-queue-2026-10-23.md`。用户脚本规划原案：`user-scripts-plan.md`。

## 一、当前状态一句话

用户脚本**切片 A（`run_script` 工具 + `ScriptRepository`/`ScriptRunner`）与切片 B（设置 → 脚本页 UI）已过两轮 qa 并逐条处置**；
**切片 C（定时任务「运行脚本」动作）已过 qa 审查（BATCH，无 P0/P1，6 项 P2）并逐条处置完毕**
（台账：`qa-sliceC-disposition-2026-10-23.md`），处置后 `:app:compileArmsoloReleaseKotlin` + 相关单测 57 用例 0 失败。
**终态（本批收口）**：已提交 6 条（`3754b66` A、`32b2b80` B、`9051d0b` C、`f3c03c0` 交付物页口径修正、`061eb2b` 备注、`0e0b162` 发版前双审处置）；
发版前双审（qa 含 R8 维度 + 性能/功耗）均无 P0/P1、12 项 P2 全处置；
**正式 R8 包 versionCode 113 已构建交付**（`~/workspace/许愿码-正式版-20261023-vCode113-用户脚本+交付物修正+双审处置.apk`，SHA-256 `4a2adb50…`，签名与 v95 同钥匙可覆盖升级），**待真机验收**；**未 push**；
全量单测 1338 用例 0 失败（1 跳过）。台账：`qa-prerelease-review-2026-10-23.md`、`perf-prerelease-review-2026-10-23.md`。

- 仓库：`~/workspace/AiCode`（注意不是 `~/workspace`），分支 `dev`，本地 HEAD `2ce313c`，`origin/dev` 停在 `7d18b01`。
- 工作区状态：22 个已跟踪文件被改 + 若干新增（`git status --short` 全览）；无他人改动。
- 本次会话最后一次验证（`--no-build-cache`）：`:app:compileArmsoloReleaseKotlin` 成功；
  过滤 `agent.domain.script.*`、`agent.domain.tool.script.*`、`schedule.*`、`ScriptsViewModelTest` 共
  **57 用例 0 失败**（`ScriptRepositoryTest` 14、`ScriptRunnerTest` 3、`RunScriptToolTest` 13、
  `ScheduledAuthCategoryMappingTest` 13、`ScheduledTaskSerializationTest` 5、`ScriptsViewModelTest` 9）。

## 二、下一步（按顺序）

1. ~~**派 `qa` 复审切片 C**（BATCH 范围）~~ **已完成**（无 P0/P1，6 项 P2 全部当批修复，见第三份台账）。原范围＝C 改动：`feature/schedule/domain/{ScheduledTask,TaskScheduling,ScheduledTaskWorker,ScheduledTaskNotifier(new),ScheduledAiTaskWorker}.kt`、
   `feature/schedule/presentation/ScheduledTasksViewModel.kt`、
   `feature/schedule/presentation/component/{ScheduledTaskEditorScreen,ScheduledTasksSection}.kt`、
   `SettingsScreen.kt`（任务编辑器分支）、中英 `strings.xml` 的 `schedule_*` 新增键、能力文案两处。
   已知设计决策：SCRIPT 型任务是**用户显式创建**的，等价于 COMMAND 型，**不经过**权限引擎预授权判定
   （P1-2 的 DELETE 收紧只针对「AI 型任务运行中让 AI 调 `run_script`」）；成功静默、失败必报。
   需 qa 重点核：`isAiType` 语义修正是否覆盖所有分派点、Worker 的 SCRIPT 分支异常/取消处理、
   通知抽取（`ScheduledTaskNotifier`）是否与 AI 型原行为一致、编辑器类型选择/脚本候选在「编辑既有任务」与
   「远程模式无可选脚本」下的表现、`saveFromEditor` 新参数的字段保留逻辑。
2. ~~**处置 qa 报告**~~ **已完成**：台账 `qa-sliceC-disposition-2026-10-23.md`（C-1 卡片显示失败输出；C-2 Worker 远程模式短路 + `ScriptRunner.unavailableReason()`；
   C-3 `saveFromEditor` 的 `type` 语义写入 KDoc；C-4 `target()` 纳入 try；C-5 编辑器新增 `scriptsUnavailableReason` 并禁用 SCRIPT 芯片；C-6 页头文案补脚本）。
3. ~~**请用户裁决 commit**~~ **已完成**：用户选「按切片分三条提交」，已提交 `3754b66`/`32b2b80`/C 一条；（如需出正式包，先按项目纪律：先 `qa` + `optimizer` 双审再 `assembleArmsoloRelease`；
   注意 versionCode 撞号——`gitCommitCount() + 1` 与 v95 的关系，见队列笔记勘误）。
4. **真机验收**（§五清单）。

## 三、已完成（勿重做）

- **切片 A 的 qa 处置**：远程模式显式拒绝；无人值守跑脚本额外要求预授权 `DELETE`；
  `.sh` 解释器、临时文件、索引并发、`containerPath` 校验、容器未就绪提示、流式截断提示、
  PLAN 提示词、`readonly_demo` 加 `summon_team=DENY`。台账：`qa-sliceA-disposition-2026-10-23.md`。
- **切片 B**：`ScriptsViewModel`（列表/运行/新建/删除/陈旧运行状态清理）、`ScriptsSection`（卡片 + 内联输出复用聊天 `ToolSection` + 新建弹窗）、
  `SettingsScreen` 分区接线、编辑正文复用 `CodeEditorScreen`、`ScriptRepository.delete()`、中英文案、能力感知。
- **切片 B 的 qa 处置**：远程模式禁止新建（否则「建了看不到也打不开」）；`recordRun` 移入 IO 线程；
  流式被取消/异常不再记「上次运行」；加载中不闪空态；`.sh` 改用引擎 `defaultShell()`（未 provision 时 `/bin/sh`）。
  台账：`qa-sliceB-disposition-2026-10-23.md`。
- **切片 C**：`ScheduledTaskType.SCRIPT` + `ScheduledTask.scriptName`；`isAiType` 修正为仅 AI_TASK/AI_PLAN；
  `TaskScheduling` 改按 `!isAiType` 分派；`ScheduledTaskWorker` 增加脚本分支（经 `ScriptRunner`，记录输出/退出码，
  失败才通知）；通知逻辑抽成 `ScheduledTaskNotifier` 并被 AI 型 Worker 复用；任务编辑器新增类型选择与脚本候选；
  列表加脚本徽标与脚本名；`saveFromEditor` 新增 `scriptName`/`type`。
  **订正**：任务存 DataStore 的 JSON（非 Room 表），**新增任务类型不需要 DB 迁移**。

## 四、未验证 / 已知边界（如实）

- 三切片**全部未真机验证**（UI、运行输出折叠、左滑删除、授权弹窗文案、脚本任务无人值守触发与通知）。
- 本轮 qa 处置新增 4 个真机验收点（见 `qa-sliceC-disposition-2026-10-23.md` §四）：卡片失败输出可见、远程模式 SCRIPT 芯片禁用与原因提示、页头文案、远程模式脚本任务失败原因文案。
- 远程 SSH 模式下脚本功能整体不可用（设计如此）；真正支持需改用 `FileAccessProvider` + `PathHomeResolver`（台账 §三-1 排期）。
- 未 provision 的容器里跑 `.sh` 现走 `/bin/sh`（P2-5 修后行为，未实测）。
- 记录待排期（均见台账，本批未改）：`away` 档位压过 `NON_REMEMBERABLE` 的既有顺序问题、失败路径绕过
  `ToolOutputStore`、`recall` 未收录 catalog、`SwipeToDeleteRow` 滑开态点行内按钮不收回、进入脚本页刷新两次。
- 切片 C 的 Worker 无单测覆盖（WorkManager + Hilt 装配，项目惯例不测 Worker）；其正确性依赖真机与 qa 静态审查。

## 五、真机验收清单（建议顺序）

1. 脚本页：新建（`.sh`/`.py`/`.js`/`.mjs` 各一个）→ 编辑器改正文保存 → 列表描述与「上次运行」更新 → 点运行看内联输出与长输出折叠 → 左滑删除（含确认弹窗）。
2. 内置示例脚本（`assets/aicode/scripts/demo_balance.py`、`demo_subscription.py`）删除后重启是否重新释放。
3. 容器终端里确认脚本可见（`/root/.aicode/scripts/`）并能手动执行；`run_script` 工具三态（列/固化/运行）与授权弹窗文案。
4. 定时任务：新建时选「运行脚本」→ 选脚本 → 跑一次，确认成功静默、失败弹通知、输出截断入库、列表「上次运行」更新；ONCE 型跑完自动禁用。
5. 备份导出/导入往返后 `scripts/` 与 `.index.json` 的展示。
6. 大屏两栏下脚本编辑器排版/返回/未保存提示。

## 六、环境与命令（勿与其它 Gradle 构建并行）

```sh
cd ~/workspace/AiCode
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so \
  ./gradlew :app:compileArmsoloReleaseKotlin :app:testArmsoloReleaseUnitTest \
  --tests "com.aicode.feature.agent.domain.script.*" --tests "com.aicode.feature.agent.domain.tool.script.*" \
  --tests "com.aicode.feature.schedule.*" --tests "com.aicode.feature.settings.presentation.ScriptsViewModelTest" \
  --no-build-cache
```
- 正式包：`assembleArmsoloRelease`（R8），产物 `app/build/outputs/apk/armsolo/release/`。
- 容器内无 `ripgrep`（`search` 工具不可用）：用 `grep -rn`（busybox 不支持 `--include`）；**别对 `app/src/main/assets` 全库 grep**（min.js 会淹没输出）。
- 单测里对 `CommandEngine` 的 mock 需要同时桩 `notReadyHint()` 与 `defaultShell()`（严格 mock 未桩即抛）。

## 七、本批新增/关键文件

- 域：`app/src/main/java/com/aicode/feature/agent/domain/script/{ScriptRepository,ScriptRunner}.kt`、
  `.../agent/domain/tool/script/RunScriptTool.kt`、`.../schedule/domain/ScheduledTaskNotifier.kt`
- 表现层：`app/src/main/java/com/aicode/feature/settings/presentation/ScriptsViewModel.kt`、
  `.../settings/presentation/component/ScriptsSection.kt`、`SettingsScreen.kt`（脚本分区与编辑器接线）
- 测试：`app/src/test/java/com/aicode/feature/agent/domain/script/{ScriptRepositoryTest,ScriptRunnerTest}.kt`、
  `.../agent/domain/tool/script/RunScriptToolTest.kt`、`.../settings/presentation/ScriptsViewModelTest.kt`、
  `.../schedule/domain/{ScheduledAuthCategoryMappingTest,ScheduledTaskSerializationTest}.kt`
