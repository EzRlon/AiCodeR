# 发版前 qa 专项复查处置台账（2026-10-23）

> 报告来源：`qa` 子代理对 `2ce313c..061eb2b`（用户脚本三切片 + 交付物口径修正 + 笔记）的跨批/发版前复查，
> 含 R8/混淆/资源收缩维度。**结论：建议直接构建正式 R8 包——无 P0、无阻断性 P1，6 项 P2。**
> 本文件为主会话逐条处置结果（纪律：报告不得无响应归档）。审查方式：只读，未自跑 Gradle。

## 一、处置一览

| 编号 | 定级 | 内容 | 处置 |
|---|---|---|---|
| Q1 | P2 | `Bash` 跑同一脚本时命令行同样看不到脚本体，DELETE 预授权只覆盖 `run_script` | **修（文档）** |
| Q2 | P2 | 任务列表单 JSON 串：未知枚举值→整串解码失败→列表被清空并在下次写回永久丢失 | **修（代码）** |
| Q3 | P2 | `ScriptsViewModel.run` 的 `runner.target()` 在 `runCatching` 之外 | **修（代码）** |
| Q4 | P2 | 两处陈旧注释/文档（`PermissionsSettingsSection`「22 项」、AGENTS.md 提交列表） | **修（文案）** |
| Q5 | P2 | `list()` 会把名称不合规（如带空格）的文件列成卡片，但按名解析必失败 | **修（代码）+ 测试** |
| Q6 | P2 | versionCode/versionName 现状、队列笔记记的 110 已过时 | **记录 + 更新笔记** |

六项全部当批处置，无排期项。

## 二、逐条处置

### Q1 等价 `Bash` 路径的 DELETE 缝隙 → 已修（文档）

- 现象：本批为「无人值守下 AI 调 `run_script`」额外要求预授权 `DELETE`（切片 A P1-2 的处置），
  但用 `Bash` 执行同一脚本文件时，命令行是 `bash "/root/.aicode/scripts/x.sh"`，静态判定看不到脚本体 →
  仅凭 `EXECUTE` 即放行。属**既有**缝隙（`bash <file>` 藏 `rm` 早已存在），非本批引入。
- 处置：**不改行为**（改判定语义会影响所有 `Bash` 调用），在 `docs-site/docs/guide/permissions.md`
  的该段补一句：这层收紧只针对 `run_script` 工具调用；用 `Bash` 跑同一脚本时只能按 `EXECUTE` 判定，
  要看住脚本内的删除动作应为任务勾选 `DELETE` 类别。避免读者把 P1-2 误读为闭环。

### Q2 任务列表解码脆性 → 已修（代码）

- 现象：`ScheduledTaskRepository` 的 `Json { ignoreUnknownKeys = true }` 未设 `coerceInputValues`；
  整份任务列表是**单个 JSON 字符串**，任一条目出现未知枚举值即整串解码抛异常 → 被 `getOrDefault(emptyList())`
  吞成空表 → 下一次 `upsert` 写回即把全部任务永久清空。本批新增枚举值 `SCRIPT` 放大了该形态的风险面
  （正常升级不可达：versionCode 单调，降级安装不被允许；仅侧载旧包/手工注回新 JSON 时可达）。
- 处置：
  - 加 `coerceInputValues = true`（未知枚举值回落默认值，不再整串失败）；
  - 两处解码点（`tasksFlow`、私有 `decode()`）补 `FileLogger.w` 告警，失败不再静默；
  - 类 KDoc 写明「三个开关都要在」的原因（单串结构 → 一条失败即全丢）。
- 取舍说明：`coerceInputValues` 会把未知 `type` 落成 `COMMAND`（降级场景下 SCRIPT→COMMAND 是有损的），
  但相比「全部任务消失且被写回抹掉」，保留其余字段、让用户能看见并修正，是更安全的一侧。

### Q3 `ScriptsViewModel.run` 解析未兜底 → 已修（代码）

- 处置：与切片 C 的 C-4（worker 侧）对齐，把 `runner.target(name)` 纳入 `runCatching`：
  `runCatching { runner.target(name) }.getOrNull()`，异常不再冒泡出 `viewModelScope`。
- 可达性：极低（`findScript` 先过 `NAME_REGEX`），属防御性收紧。

### Q4 陈旧注释/文档 → 已修（文案）

- `PermissionsSettingsSection.kt`：「22 项长列表」→「条目较多的长列表」（本批已把 catalog 的计数去掉）。
- `AGENTS.md` 交接段：提交列表更新为五条（含 `f3c03c0`/`061eb2b`）、下一步改为「性能审查 → 正式包 → 真机验收」。

### Q5 `list()` 混入不可运行文件 → 已修（代码）+ 测试

- 处置：`list()` 的过滤由 `!it.name.startsWith(".")` 改为 `isValidName(it.name)`
  （字符集 + 受支持后缀一次到位；隐藏文件仍被字符集规则排除），KDoc 写明理由。
- 回归护栏：`ScriptRepositoryTest.list_skipsFilesWithInvalidNames`（`my script.sh` 不进列表）。

### Q6 发布元数据 → 记录 + 更新笔记

- `versionCode` = `git rev-list --count HEAD`(111) + 1 = **112**，高于已交付的 95（`output-metadata.json` 实测），**无撞号**。
- `versionName` 将由 `git describe --tags` 解析为 `2.0.0-aicoder.dev+milestone/vnc-overlay-108-g061eb2b`（含 `/`）——
  与已交付并真机验收通过的 v95 同形态，仅显示面，实测无害。
- 已更新 `deferred-queue-2026-10-23.md` 里过时的「versionCode 110」。

## 三、qa 的 R8 维度结论（采纳，未做额外动作）

- `run_script` 走**字面量字符串注册**（`AgentModule` → `ToolRegistry` 的 `Map` 直查），全库无 `Class.forName`/反射 → 无混淆/裁剪破坏面。
- `@HiltWorker` 装配走注入的 `Map<String, Provider<WorkerAssistedFactory>>`（无反射、无类名拼接），
  且 `ScheduledTaskWorker` 与已随 v95 发布的 `ScheduledAiTaskWorker` 注解形态一致 → 无需新增 keep。
- `@Serializable`（`ScriptIndex`/`ScheduledTask`）由既有 `-keep …$$serializer` 等规则覆盖；
  `ScheduledTaskType` 新增枚举值走编译器生成的 enum serializer（无反射）。
- 资源收缩：本批新增 33 个字符串全部有 Kotlin 引用；`proguard-rules.pro` **无需新增/推翻任何规则**。
- `assets/` 不受 `shrinkResources` 影响（实证解包 v95 R8 包仍含 `assets/aicode/scripts/demo_*.py`）。
- i18n：中英键集各 2591、差集为空、占位符不匹配 0 处。

## 四、自验（本批处置后）

- `:app:compileArmsoloReleaseKotlin`：**通过**。
- 本批相关单测（脚本域 + 定时任务 + 脚本页 VM）：**58 用例 0 失败**（含新增 `list_skipsFilesWithInvalidNames`），共 6 个测试类。
- 全量单测：本次改动前 1337 用例（1 例既有偶发见下）；发版构建前会再跑一次全量。
- 命令：`./gradlew :app:compileArmsoloReleaseKotlin :app:testArmsoloReleaseUnitTest --tests "com.aicode.feature.agent.domain.script.*" --tests "…tool.script.*" --tests "com.aicode.feature.schedule.*" --tests "…ScriptsViewModelTest" --no-build-cache`（退出码 0，10m32s）。
- 既有偶发（非本批）：`SyncEngineIgnoreTest.upload_skips_custom_ignored_paths` 报 `UncaughtExceptionsBeforeTest`，隔离复跑通过；发版前全量若再命中，按「定位泄漏协程异常的用例」处理。

## 五、仍未覆盖（如实）

- R8 实跑由 `assembleArmsoloRelease` 收口（本文件不代替真实构建结论）。
- 真机：脚本页全链路、定时任务脚本选择与失败通知、交付物页修正后的表现、内置示例脚本重释放、大屏两栏编辑器。
- `ScheduledTaskWorker` 无单测（项目惯例）。
- 既有已记录项（非本批）：`away` 档位压过「不可记忆」、`recall` 未录入 catalog、`list()` 逐文件读前 20 行的 O(n) IO。
