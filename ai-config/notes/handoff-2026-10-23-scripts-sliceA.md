# 交接：用户脚本切片 A（2026-10-23，上下文 10M 时的暂停点）

> 队列与顺序见 `deferred-queue-2026-10-23.md`（本次新写，未 commit）。
> 上一份 `handoff-2026-10-23-final.md` 记录的是 v95 发版批次（已 commit、未 push），本文件记录其后的新工作。

## 一、当前状态一句话

用户脚本**切片 A（`run_script` 工具 + `~/.aicode/scripts/` 固化目录）代码已全部落地、未 commit、未 push**；
**编译已通过、新增单测 20 条全绿**（见 §五与 §七）；切片 B/C 未开工；qa 审查尚未派发。

仓库：`~/workspace/AiCode`，分支 `dev`，`origin/dev` 停在 `7d18b01`（推送前务必确认用户意图）。
本批前的 12 个提交（v95 批次）已本地完成，工作区当时为 clean。

## 二、切片 A 已完成改动（全部未提交）

**新增**
- `app/src/main/java/com/aicode/feature/agent/domain/script/ScriptRepository.kt`：脚本清单/固化/读取/运行索引。
  管理宿主 `<filesDir>/aicode/scripts`（容器内经 bind 即 `/root/.aicode/scripts`）；支持 `.sh`→`sh`、`.py`→`python3`、`.js`/`.mjs`→`node`；
  名称字符集校验（`[A-Za-z0-9][A-Za-z0-9._-]{0,63}` + 受支持后缀）杜绝路径穿越；`save()` 先写临时文件再改名；
  运行结果记在目录内 `.index.json`（列表展示「上次运行」）；描述取正文首行注释（跳过 shebang 与编码声明）。
- `app/src/main/java/com/aicode/feature/agent/domain/tool/script/RunScriptTool.kt`：`run_script` 工具，三态参数——
  不给参数=列脚本；只给 `name`=容器内运行并返回输出/退出码；`name`+`content`=固化（不执行）。
  实现 `StreamingAgentTool`（运行时长脚本逐行可见过程），命令拼装为 `sh "/root/.aicode/scripts/x.sh"`。
- 单测：`app/src/test/java/com/aicode/feature/agent/domain/script/ScriptRepositoryTest.kt`、
  `app/src/test/java/com/aicode/feature/agent/domain/tool/script/RunScriptToolTest.kt`（JUnit4 + mockk，含穿越名拒绝、退出码语义、命令拼装断言）。

**修改**
- `app/src/main/java/com/aicode/di/AgentModule.kt`：注入并 `register("run_script", ...)`。
- `app/src/main/java/com/aicode/feature/agent/domain/permission/ToolPermissionCatalog.kt`：新增 `run_script`（TERMINAL/ASK）与
  **`summon_team`（AGENT/AUTO）**——后者是上一批次遗漏的目录项，导致该工具实际会弹授权（`AUTO_APPROVE` 短路要求 `tierOf != ASK`），本次一并补上；
  `away` 档案加 `run_script`=AUTO，`readonly_demo` 加 `run_script`=DENY；顺手删掉过时的「22 项」计数注释。
- `app/src/main/res/values/strings.xml`、`values-en/strings.xml`：`perm_tool_run_script(_desc)`、`perm_tool_summon_team(_desc)`。
- `app/src/main/java/com/aicode/feature/backup/data/BackupManagerImpl.kt`：`AI_ASSET_DIRS` 加 `"scripts"`（备份/迁移白名单，导入侧 `isAllowedAicodeAsset` 复用同一列表）。
- 能力感知四处：`app/src/main/assets/prompts/65-capabilities.md`、`app/src/main/assets/prompts/60-tools-and-paths.md`、
  `docs-site/docs/guide/capabilities.md`（构建期 syncAiDocs 同步进 assets/docs）。

## 三、关键设计决策（下一会话不要推翻重做，除非有新的依据）

1. **权限映射按动作细化**（`effectiveCapabilities`）：列脚本=`{READ_AGENT_CONFIG}`（引擎自动放行）、运行=`{EXECUTE_COMMANDS}`、
   固化=`{MODIFY_AGENT_CONFIG}`（属 `NON_REMEMBERABLE_CAPABILITIES`，永不可记忆；定时任务的 `ScheduledAuthCategoryMapping` 对
   MODIFY_AGENT_CONFIG 一律不覆盖 → 无人值守下**不可能**静默固化新脚本）。兜底 `capabilities = {EXECUTE_COMMANDS}`。
2. **脚本体不做静态分析（已知边界）**：权限引擎的灾难性 rm/不可静态判定防护只覆盖 `SHELL_TOOLS = {"Bash","Shizuku"}` 与 terminal；
   `run_script` 传的是脚本名，引擎取不到命令行。这与「Bash 里执行 `sh /root/.aicode/scripts/x.sh`」等价，
   且同目录已有既有范式（`ProviderDashboardRunner.runScript`，`feature/settings/domain/service/ProviderDashboardRunner.kt:661`）。
   补偿控制＝固化需 MODIFY_AGENT_CONFIG（不可记忆、不被预授权覆盖）+ 脚本在用户可见目录。若 qa 认为不够，可选加固：
   运行 `.sh` 前把脚本体交给 `ShellCommandParser` 分析（会引入与 Bash 不一致的语义，需权衡）。
3. **不重复造轮子**：输出限幅＋全文落盘由工作流统一做（`ToolOutputStore.process`，`StatefulAgentWorkflow.kt:1237/1563`），
   工具只返回 `ToolResult.Success(JsonPrimitive(output))`；流式路径自限量用 `BoundedOutput`（与 `ExecuteCommandTool` 一致）。
4. **脚本目录本来就是既有的**：`ContainerInstaller.extractScripts`（`ContainerInstaller.kt:193`）启动时把 `assets/aicode/scripts/*`
   释放到 `<filesDir>/aicode/scripts`，**仅当文件不存在时写入**（用户改动保留）；远程模式另有 `uploadBuiltinFiles("scripts", ...)`。
   故无需新增绑定或释放逻辑。

## 四、下一步（按队列顺序）

1. ~~确认切片 A 验证~~ **已完成**：编译通过（末次增量复核 `UP-TO-DATE`，确认含最后两处编辑）、单测 20 条全绿。
2. **派 `qa`（BATCH 范围）**：审查范围＝本批（工具层/权限面/备份白名单/能力文案），要求带 `文件:行号`；报告须逐条处置。
3. **等用户裁决是否 commit**（项目纪律：不擅自 commit；本批为开发批次，非发版批次）。
4. **切片 B**：脚本页 UI（列表卡：名称/语言/描述/上次运行；新建、编辑复用文件编辑器、运行（输出内联、长输出折叠）、删除）。
   `ScriptRepository` 已具备 list/read/save/resolve，缺 `delete`（B 再加，勿提前加）。
5. **切片 C**：定时任务「运行脚本」动作 + 任务动作枚举迁移（DB 变更）+ 跑完通知策略（成功静默/失败必报、输出截断入库）；
   无人值守沿用守夜纪律（预授权 EXECUTE 类别覆盖，未覆盖走通知授权/兜底超时）。

## 五、环境与验证命令

```sh
cd ~/workspace/AiCode
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so \
  ./gradlew :app:compileArmsoloReleaseKotlin :app:testArmsoloReleaseUnitTest \
  --tests "com.aicode.feature.agent.domain.script.*" --tests "com.aicode.feature.agent.domain.tool.script.*" --no-build-cache
```
- 本次构建在 terminal 标签 `term-26`（新会话可能看不到该标签，直接重跑上面的命令即可，增量构建很快）。
- 构建纪律：不与其它 Gradle 构建并行（KSP 缓存污染坑，见记忆 `gradle-parallel-build-ksp-cache-pitfall`）；正式包另需 `assembleArmsoloRelease`。
- 容器内无 `ripgrep`，`search` 工具不可用；用 `grep -rn`（busybox 不支持 `--include`）或 `list`+`readFile`。

## 六、已核实的事实（免重查）

- `ContainerInstaller.aicodeDir = File(context.filesDir, "aicode")`（`ContainerInstaller.kt:360`），经 `-b <宿主>:/root/.aicode`
  绑进容器（`LinuxContainerEngine.kt:752`）——**宿主与容器是同一份目录**，App 写的脚本容器立即可见；`WorkspacePathMapper.AICODE_ROOT = "/root/.aicode"`。
- 权限引擎：入口 `ToolPermissionPolicyEngine.evaluate`（`:149`）；`scheduledRun` 下历史记忆与档位兜底一律失效（`:397`、`:610` 等）；
  定时预授权映射 `ScheduledAuthCategoryMapping.judge`（EXECUTE_COMMANDS→EXECUTE 类别）；兜底超时 10 分钟自动拒绝（`ToolPermissionManager.kt:71`）。
- `AUTO_APPROVE` 短路条件：`permissionPolicy == AUTO_APPROVE && tierOf(toolName) != ASK`（`StatefulAgentWorkflow.kt:1768`）——
  未登记进 `ToolPermissionCatalog` 的工具默认档位是 ASK，会退化成弹窗（这就是 `summon_team` 之前的实际行为）。

## 六点五、补记（2026-10-23 qa 审查后的修正，覆盖上文两处表述）

- §三-1 把「固化需不可记忆的 MODIFY_AGENT_CONFIG」当作补偿控制：在 **scheduledRun 下成立**
  （`ToolPermissionPolicyEngine.kt:396` 被 `!scheduledRun` 挡住），但在 **away 档位/手动 AUTO 下不成立**
  （`tier == AUTO → ALLOW` 排在 `NON_REMEMBERABLE` 判定之前）。属既有跨工具问题，已记录待排期。
- §七「远程 SSH 模式下的脚本执行未验证」→ 已改为**显式拒绝**：`ScriptRepository.unavailableReason()`
  在远程模式返回可读原因，`RunScriptTool` 三态动作均返回 `UNSUPPORTED_EXECUTION_MODE`。
  远程真正支持（改用 `FileAccessProvider` + `PathHomeResolver`）排期在案。
- 另修：无人值守跑脚本额外要求预授权 DELETE 类别（`ScheduledAuthCategoryMapping.judge` 增 `toolName` 参数）；
  `.sh` 解释器由 `sh` 改 `bash`；临时文件/索引并发、`containerPath` 校验、容器未就绪提示、流式截断提示、
  PLAN 模式提示词、`readonly_demo` 档案。逐条处置见 `qa-sliceA-disposition-2026-10-23.md`。

## 七、未做/未验证（如实标注）

- 切片 A 验证结论（已确认，2026-10-23）：`:app:compileArmsoloReleaseKotlin` 通过；
  `:app:testArmsoloReleaseUnitTest`（过滤到两个新测试类）20 条全绿——`ScriptRepositoryTest` tests=10 failures=0 errors=0、
  `RunScriptToolTest` tests=10 failures=0 errors=0（报告在 `app/build/test-results/testArmsoloReleaseUnitTest/`）。
  构建期既有告警未处理（`ToolPermissionManager.kt:71` opt-in、`FloatBallOverlay.kt:249` deprecated override、
  `StoreInstallScanner.kt:60` 与 `VncOverlayPrefsRepository.kt:33` 注解目标），均非本批引入。
- 未真机验证：脚本固化→运行→退出码→清单「上次运行」展示，以及授权弹窗文案。
- `.index.json` 是否随备份包往返（备份递归打包目录，理论上包含隐藏文件）**未实测**。
- 远程 SSH 模式下的脚本执行未验证（宿主 `filesDir/aicode/scripts` 与远端 `~/.aicode/scripts` 是两份，靠 `uploadBuiltinFiles` 补内置脚本；用户固化的脚本**不会**自动同步到远端——切片 B/C 若要支持远程，需要单独的同步策略）。
