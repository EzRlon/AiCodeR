# 交接：用户脚本 切片 A 修复 + 切片 B（2026-10-23 会话中）

> 上一份：`handoff-2026-10-23-scripts-sliceA.md`（切片 A 落地时的状态与设计决策，仍有效）。
> 队列与顺序：`deferred-queue-2026-10-23.md`。qa 处置台账：`qa-sliceA-disposition-2026-10-23.md`。

## 一、当前状态一句话

切片 A（`run_script` + `ScriptRepository`）**已通过 qa 审查并按报告逐条处置**；切片 B（设置 → 脚本页 UI）**代码已落地、编译与 47 个相关单测全绿**，正在等第二轮 qa（A 修复 + B 新代码）报告；切片 C 未开工（落点已摸清，见 §四）。**全部未 commit、未 push**。

仓库：`~/workspace/AiCode`，分支 `dev`；`origin/dev` 停在 `7d18b01`（推送前务必确认用户意图）。

## 二、切片 A 的 qa 处置（已落地，勿重做）

- P1-1 远程 SSH 模式半坏 → **显式拒绝**：`ScriptRepository.unavailableReason()` +
  `RunScriptTool` 三态动作返回 `UNSUPPORTED_EXECUTION_MODE`。
- P1-2 无人值守跑脚本绕过 DELETE 预授权 → `ScheduledAuthCategoryMapping.judge` 增 `toolName` 参数，
  `run_script` 执行动作额外要求 `DELETE`（`ToolPermissionPolicyEngine.kt` 调用点已传 toolName）。
- P2 顺带修：`.sh` 解释器 `sh`→`bash`、临时文件唯一名+`finally` 清理、索引读写 `@Synchronized`、
  `containerPath` 名称校验、容器未就绪返回引导而非「可能超时」、流式截断提示口径、
  PLAN 提示词（`prompts/agent/plan-mode.md`）、`readonly_demo` 档案加 `summon_team=DENY`、
  `docs-site/docs/guide/permissions.md` 增脚本面说明。
- 记录待排期（未改）：远程真正支持（改用 `FileAccessProvider`+`PathHomeResolver`）、
  away 档位压过 `NON_REMEMBERABLE` 的既有顺序问题、失败路径绕过 `ToolOutputStore`、
  `recall` 未收录 catalog。详见台账 §三。

## 三、切片 B 已落地内容

新增：
- `app/src/main/java/com/aicode/feature/agent/domain/script/ScriptRunner.kt`：**脚本执行的唯一实现**
  （`target()` 解析+拼命令、`run()` 同步执行并记录上次运行、`stream()` 流式并在 `onCompletion` 记录、
  `notReadyHint()`）。`run_script` 工具与脚本页共用，避免两处走样。
- `app/src/main/java/com/aicode/feature/settings/presentation/ScriptsViewModel.kt`：脚本页状态
  （清单 + 每脚本运行状态 `runs`；`refresh/create/run/delete`；`pendingOpen` 串起「先写文件再开编辑器」；
  `refresh` 会丢弃已不在清单里的 `runs`，规避「删除后运行收尾把旧输出写回」的竞态）。
- `app/src/main/java/com/aicode/feature/settings/presentation/component/ScriptsSection.kt`：
  提示卡 + 脚本卡（名称/描述/解释器·上次运行/行内运行键/左滑删除）+ 运行输出（复用聊天 `ToolSection`，自带长输出折叠）
  + `ScriptNewDialog`（只收名称，后缀定语言）。
- 单测：`ScriptRunnerTest`、`ScriptsViewModelTest`。

修改：
- `RunScriptTool.kt`：改为注入 `ScriptRunner`（不再自己拼命令/调引擎）。
- `SettingsScreen.kt`：`SettingsSection.Scripts/ScriptEditor`、`depth()`、`parentSection`、顶栏「+」、
  内容分支与两个弹窗、`SettingsMenu` 菜单行（AI 配置组、图标 `FeatherIcons.Code`）、
  脚本正文编辑器走 `CodeEditorScreen(path = scriptsViewModel.containerPath(name))`。
- `ScriptRepository.kt`：新增 `delete()`（并清索引记录）。
- `strings.xml` / `values-en/strings.xml`：`settings_scripts` + `scripts_*`。
- 能力感知：`65-capabilities.md`（脚本页入口）、`docs-site/docs/guide/capabilities.md`（同上）。

验证：`:app:compileArmsoloReleaseKotlin` 通过；`testArmsoloReleaseUnitTest` 过滤
`agent.domain.script.*`、`agent.domain.tool.script.*`、`ScheduledAuthCategoryMappingTest`、
`ScriptsViewModelTest` 共 47 例全绿（`--no-build-cache`）。

## 四、切片 C（代码已落地，待验证；原计划如下，实现与计划一致）

目标：定时任务新增「运行脚本」动作 + 跑完通知策略（成功静默 / 失败必报、输出截断入库）。

**重要订正**：任务持久化是 DataStore 里的 **JSON 字符串**（`ScheduledTaskRepository`，
`ignoreUnknownKeys`），**不是 Room 表 → 新增任务类型不需要 DB 迁移**（队列笔记里「DB 变更」的判断有误）。

改动清单：
1. `feature/schedule/domain/ScheduledTask.kt`：`ScheduledTaskType` 加 `SCRIPT`；`ScheduledTask` 加
   `scriptName: String? = null`（`@Serializable`，缺字段回落默认值，旧数据兼容）。
2. `feature/schedule/domain/TaskScheduling.kt`：三个 mode 分支里把 `task.type == COMMAND` 的判断
   改为 `!task.isAiType`（COMMAND 与 SCRIPT 都走 `ScheduledTaskWorker`）。
3. `feature/schedule/domain/ScheduledTaskWorker.kt`：按 `type` 分支——COMMAND 现行为不变；
   SCRIPT 用 `ScriptRunner` 解析并执行（`target(scriptName)` 为空 → `exitCode=1` + 明确文案），
   写回 `lastRunAt/lastExitCode/lastOutput`（输出截断 `MAX_OUTPUT_CHARS`），**仅失败时通知**
   （复用 `ScheduledAiTaskWorker.notifyCompletion` 的渠道 `agent_complete` + `schedule_notify_failed` 文案）。
4. `feature/schedule/presentation/ScheduledTasksViewModel.kt`：`saveFromEditor` 增 `type`/`scriptName` 参数。
5. `feature/schedule/presentation/component/ScheduledTaskEditorScreen.kt`：新建任务时可选类型
   （容器命令 / 运行脚本，FilterChip），选脚本时展示脚本下拉（候选来自 `ScriptRepository.list()`）；
   编辑既有任务类型只读。
6. `.../ScheduledTasksSection.kt`：`TaskTypeBadge` 加 `SCRIPT` 标签；副标题显示脚本名。
7. `strings.xml` 双份：`schedule_badge_script`、`schedule_field_type`、`schedule_type_command`、
   `schedule_type_script`、`schedule_field_script`、`schedule_no_scripts`、`schedule_script_missing` 等。
8. 注意：SCRIPT 型任务由用户在界面显式创建，等价于 COMMAND 型（用户明确指定要跑什么），
   **不经过**权限引擎的预授权判定；P1-2 的 DELETE 收紧针对的是「AI 型任务运行中让 AI 调 `run_script`」这条路径。

### C 实际落地补充

- 通知逻辑抽成 `ScheduledTaskNotifier`（新文件，渠道 `agent_complete`），AI 型 Worker 改为复用（删掉其内部重复实现）。
- 编辑器类型选择只在**新建**时出现（容器命令 / 运行脚本）；编辑既有任务类型固定。脚本候选来自 `ScriptsViewModel.state`（与「设置 → 脚本」同一份清单）。
- `ScheduledTask.isAiType` 语义修正为「仅 AI_TASK / AI_PLAN」——原先的 `type != COMMAND` 会把新加的 SCRIPT 误当 AI 型，导致路由到 AI Worker。`TaskScheduling` 分派改按 `!isAiType`。
- 模型兼容测试扩到 `ScheduledTaskSerializationTest`（脚本名往返、旧数据缺字段回默认、`isAiType` 真值表）。

## 五、验证命令（勿与其它 Gradle 构建并行）

```sh
cd ~/workspace/AiCode
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so \
  ./gradlew :app:compileArmsoloReleaseKotlin :app:testArmsoloReleaseUnitTest \
  --tests "com.aicode.feature.agent.domain.script.*" --tests "com.aicode.feature.agent.domain.tool.script.*" \
  --tests "com.aicode.feature.schedule.domain.ScheduledAuthCategoryMappingTest" \
  --tests "com.aicode.feature.settings.presentation.ScriptsViewModelTest" --no-build-cache
```
正式包前按项目纪律另跑 `assembleArmsoloRelease`（R8）+ qa/optimizer 双审（本批为 L3，optimizer 排在 C 落地后的正式编译前）。

## 六、未验证（如实标注）

- 切片 B 全部 UI 未经真机验证（列表/新建/编辑器/运行时输出折叠/左滑删除/授权弹窗文案）。
- `.sh` 走 bash 后，**未 provision 的容器**上跑脚本会失败（预期行为，未实测报错文案）。
- 定时任务（切片 C）未开工。
- 远程 SSH 模式下脚本功能整体不可用（设计如此，原因见台账 §三-1）。
