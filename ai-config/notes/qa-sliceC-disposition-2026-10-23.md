# 切片 C（定时任务「运行脚本」）qa 处置台账（2026-10-23）

> 审查方：`qa` 子代理（只读、未自跑 Gradle），范围＝切片 C 全量改动（`feature/schedule/*`、`SettingsScreen` 任务编辑器分支、
> 中英 `strings.xml` 的 `schedule_*`/`scripts_*`、`65-capabilities.md` 与 docs-site 两页）。
> 审查结论：**建议放行（BATCH），无 P0、无 P1，6 项 P2**；重点核查项 1~6 全部「已核无问题」（`isAiType` 语义、
> Worker 取消/异常、通知抽取逐字段与原实现一致、编辑器回填与字段保留、i18n 键集与占位符、能力感知文案）。
> 本台账＝主会话逐条处置结果（纪律：报告不得无响应归档）。

## 一、处置一览

| 编号 | qa 定级 | 处置 | 落点 |
|---|---|---|---|
| C-1 | P2 | **修复** | `ScheduledTasksSection.kt`（`LastRunText`） |
| C-2 | P2 | **修复** | `ScheduledTaskWorker.kt`、`ScriptRunner.kt` |
| C-3 | P2 | **修复（文档）** | `ScheduledTasksViewModel.kt` KDoc |
| C-4 | P2 | **修复** | `ScheduledTaskWorker.kt` |
| C-5 | P2 | **修复** | `ScheduledTaskEditorScreen.kt`、`SettingsScreen.kt` |
| C-6 | P2 | **修复** | 中英 `strings.xml`（`schedule_page_hint*`） |

六项全部当批处置，无排期项。

## 二、逐条处置

### C-1 脚本任务失败原因不可视 → 已修

- 现象：`ScheduledTask.lastOutput` 全库无 UI 读取（`LastRunText` 只用 `lastExitCode`），且脚本型任务没有可回看的运行会话，
  失败通知只写「<任务名> 失败」——脚本被删/容器未就绪时，用户看得到「失败」却看不到为什么。
- 处置：`app/src/main/java/com/aicode/feature/schedule/presentation/component/ScheduledTasksSection.kt` 的 `LastRunText`
  在「非 AI 型 + `lastExitCode != null && != 0` + 输出非空」时，追加一行 `lastOutput`（`colorScheme.error`，`maxLines = 2`）。
- 范围说明：条件是 `!task.isAiType`（即命令型与脚本型），因为这两类都没有运行会话可回看；AI 型维持原样、行为不变（qa 已确认通知抽取无回归，此处不动）。
- 备选方案（未采纳）：改共用通知文案 `schedule_notify_failed` 追加摘要——会同时改动 AI 型文案，回归面更大；且通知划掉后信息即丢。
  卡片上留痕可事后回看，故取此案。

### C-2 远程 SSH 模式未短路 → 已修

- 现象：`runScript` 不查 `ScriptRepository.unavailableReason()`，用户先在本地建脚本任务、再切远程模式，会把「解释器 + 本地脚本路径」
  发到远端执行，得到 127/文件不存在这类误导性报错，而不是「远程模式不支持脚本」。
- 处置：
  - `ScriptRunner.kt`：新增 `fun unavailableReason(): String?`（透传 `scriptRepository.unavailableReason()`），与既有的
    `notReadyHint()` 同为「脚本执行环境」的前置查询口，避免给 Worker 再加一个仓库依赖。
  - `ScheduledTaskWorker.kt` `runScript`：在解析脚本前 `scriptRunner.unavailableReason()?.let { return Outcome(FAILED_EXIT, it) }`，
    失败输出即原因原文（配合 C-1 在卡片上可见、并走失败通知）。
- 副作用：远程模式下这类任务每次触发都会报一次失败通知——符合「失败必报」；这是用户自建任务在环境不匹配时的真实失败。

### C-3 `saveFromEditor` 的 `type` 在既有任务分支被丢弃 → 已修（文档化）

- 现象：`existing.copy(...)` 不写 `type`，接口语义看似「传了但无效」；当前 UI 在编辑既有任务时隐藏类型选择，实际不可达。
- 处置：**不改行为**（既有任务类型不可变是有意设计：类型决定 Worker 分派与权限语义，中途改动会让已排队的请求落到另一种 Worker），
  在 `ScheduledTasksViewModel.saveFromEditor` KDoc 写明「`type` 只在新建时生效；既有任务类型不可改；未在编辑器暴露的字段一律保留原值」。
- 记录：若将来要放开「编辑时改类型」，需一并处理三件事——重排调度（`TaskScheduling.cancel` + `schedule`）、清理另一类型的专属字段
  （`command` / `scriptName` / `prompt` / `planText`）、并复核 `isAiType` 相关的守卫。本次不做。

### C-4 `scriptRunner.target()` 在 try 之外 → 已修

- 现象：`target()` 内部的 `containerPath()` 有 `require(isValidName)`、`defaultShell()` 也可能抛；虽经核实**实际不可达**
  （`findScript` 先过 `NAME_REGEX`，返回的 `File.name` 必为合法名），但异常会越过兜底冒泡出 `doWork`。
- 处置：把 `target()` 解析移入 `try`（`ScheduledTaskWorker.runScript`），异常走既有的 `catch (e: Exception)` → 记 `FileLogger.e` + 失败输出；
  `CancellationException` 仍先重抛（保持原取消语义）。
- 判读：纯防御性收紧，未改变任何可达路径的行为。

### C-5 远程模式下「运行脚本」芯片可点选 → 已修

- 现象：远程模式（`availableScripts` 为空）下 SCRIPT 芯片仍可点，选中后只显示「还没有脚本：先到设置→脚本新建」（在远程模式下同样做不到），
  保存被 `canSave` 挡住，形成没有说明的死路。
- 处置：
  - `ScheduledTaskEditorScreen.kt`：新增参数 `scriptsUnavailableReason: String? = null`；SCRIPT 芯片 `enabled = scriptsUnavailableReason == null`；
    SCRIPT 分支优先展示该原因（替代 `schedule_no_scripts`），不再把人引向「去脚本页新建」。
  - `SettingsScreen.kt`：把 `scriptsState.unavailableReason` 传入编辑器。
- 说明：只在「环境不支持」时生效（远程 SSH 模式）；脚本清单加载中的空窗仍是原提示，不受影响。`canSave` 的非法状态拦截保持不变。

### C-6 定时任务页头文案未提脚本 → 已修

- 处置：中英 `schedule_page_hint_title` / `schedule_page_hint` 补「或运行脚本 / 脚本任务成功静默、失败通知」。
  - zh：`自动执行命令或脚本` / `到点在容器里执行指定命令或运行一个脚本，结果可逐条查看（脚本任务成功静默、失败通知）。受系统调度，触发时间可能略有延迟。`
  - en：`Run commands or scripts automatically` / `Runs the given command or a script inside the container on schedule; each run's result can be reviewed. Script tasks stay silent on success and notify on failure. Trigger time may be slightly delayed by the system.`

## 三、自验

- 命令（`--no-build-cache`，未与其它 Gradle 并行）：
  `./gradlew :app:compileArmsoloReleaseKotlin :app:testArmsoloReleaseUnitTest --tests "com.aicode.feature.agent.domain.script.*" --tests "com.aicode.feature.agent.domain.tool.script.*" --tests "com.aicode.feature.schedule.*" --tests "com.aicode.feature.settings.presentation.ScriptsViewModelTest"`
- 结果：**BUILD SUCCESSFUL（12m49s，退出码 0）**；`testArmsoloReleaseUnitTest` 相关 6 个测试类 **57 用例 0 失败 0 错误 0 跳过**
  （`ScriptRepositoryTest` 14、`ScriptRunnerTest` 3、`RunScriptToolTest` 13、`ScheduledAuthCategoryMappingTest` 13、
  `ScheduledTaskSerializationTest` 5、`ScriptsViewModelTest` 9），与交接笔记基线一致。编译告警均为既有（未随本轮新增）。
  - 注：本轮改动含 2 处 UI（卡片失败输出、编辑器 SCRIPT 芯片禁用）与 1 处 `ScriptRunner` 方法新增，仅编译期可验证，界面表现归入真机验收。
- 静态复核（主会话独立于 qa 做的交叉核对）：
  - `isAiType` 全库调用点 5 处（定义 + `TaskScheduling`×3 + `ScheduledAiTaskWorker` 守卫 + `saveFromEditor`），SCRIPT 归类均正确；
  - `ScheduledTaskType` 其他引用仅创建点（`AIAgentViewModel`）、编辑器、列表徽标，无遗漏分派点；
  - 中英 `schedule_*` 键集各 84 个完全一致，新增键占位符（`%1$s`）两侧一致（脚本比对：0 处不匹配）；`scripts_*` 20 个键亦对称。

## 四、本次处置新增的真机验收点（并入交接 §五）

1. 命令/脚本型任务失败后，卡片「上次运行」下方应出现红色失败输出（最多两行），成功时不出现。
2. 远程 SSH 模式下「新建任务 → 类型选择」中「运行脚本」芯片应为不可点；已存在的脚本型任务进编辑器应显示「远程 SSH 模式下暂不支持用户脚本」。
3. 定时任务页顶部说明应已含「或运行脚本」。
4. 远程 SSH 模式下让一个已存在的脚本型任务触发（或临时把触发时间调近）：失败通知与卡片输出应是「远程 SSH 模式下暂不支持用户脚本…」，而不是 ssh/127 类报错。

## 五、遗留（未随本批处置，与 qa 报告一致）

- Worker 仍无单测（项目惯例：WorkManager + Hilt 装配不测），SCRIPT 分支的正确性依赖真机与本次静态审查。
- `MAX_OUTPUT_CHARS = 4000` 截断不附「已截断」标记（`CommandResult.outputTruncated` 未用）——C-1 修后失败输出可见，
  但超长失败输出仍会被静默截断；与命令型同源问题，随「输出落盘」批次一并处理。
- 三切片全部未真机验证（UI、运行输出折叠、左滑删除、授权弹窗、脚本任务无人值守触发与通知）。
