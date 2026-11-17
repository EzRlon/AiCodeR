# qa 报告处置记录：用户脚本切片 A（2026-10-23）

> 报告来源：`qa` 子代理对切片 A 未提交改动的独立审查（结论：需修后提交；无 P0、P1×2）。
> 本文件是主代理的逐条处置台账，配套交接：`handoff-2026-10-23-scripts-sliceA.md`。

## 一、P1（已修）

### P1-1 远程 SSH 模式下结构性地半坏 → 已修：显式拒绝，不留静默半坏
- 现象：`ScriptRepository` 用宿主 `java.io.File`（`filesDir/aicode/scripts`），`containerPath` 又硬编码
  `WorkspacePathMapper.AICODE_ROOT = "/root/.aicode"`（`ScriptRepository.kt:43`），而命令经
  `CommandEngine` 在远端执行 → 目录与执行环境不是同一份；远程非 root 用户 home 也指错。
- 处置：新增 `ScriptRepository.unavailableReason()`（远程 SSH 模式返回可读原因），
  `RunScriptTool.execute`/`executeStream` 入口据此返回 `UNSUPPORTED_EXECUTION_MODE`，不再「固化成功但跑不起来」。
- **未做（排队，见 §三-1）**：把目录读写改走 `FileAccessProvider` + `PathHomeResolver` 以真正支持远程
  （同目录既有范式 `ProviderDashboardRunner` 即如此）。理由：那是对今天新增文件的 IO 模型重写
  （原子写、可执行位、索引写都要重新决策），需自己一轮设计+审查，不适合和切片 B/C 混在同一个批次里赶。

### P1-2 无人值守下 `run_script` 绕过 DELETE 预授权类别 → 已修
- 现象：`ScheduledAuthCategoryMapping.judge` 的 DELETE 判定以 `commandText` 非空为前提（`:63-68`），
  而 `run_script` 的 args 只有脚本名（引擎取不到命令行）→ 任务预授权 EXECUTE 即静默放行，
  脚本内的 `rm` 不触发 DELETE 要求。
- 处置：`judge` 增加 `toolName` 参数，`run_script` 的执行动作（capabilities 含 `EXECUTE_COMMANDS`）
  一律额外要求 `DELETE`（`ScheduledAuthCategoryMapping.kt`）；调用点传 `toolName`
  （`ToolPermissionPolicyEngine.kt:219`）。列脚本（`READ_AGENT_CONFIG`）不受影响。
- 回归测试：`ScheduledAuthCategoryMappingTest.runScript_alwaysRequiresDelete`、
  `runScript_listAction_doesNotRequireExecuteOrDelete`。

## 二、P2（本批顺带修）

| # | 问题 | 处置 |
|---|---|---|
| 4 | `save()` 临时名固定 `.<name>.tmp`，并发互踩；异常时残留 | 临时名加 `System.nanoTime()` 后缀，`finally` 清理（`ScriptRepository.kt` save） |
| 5 | `.index.json` 读改写无锁，并行运行丢「上次运行」 | `recordRun`/`readIndex` 加 `@Synchronized` |
| 6 | `containerPath` 无校验的公共 API | 加 `require(isValidName(name))` |
| 7 | `.sh` 用 `sh`，shebang 被忽略 → bashism 脚本失败 | 映射改 `.sh → bash`（容器 provision 后即默认 shell；同目录范式 `ProviderDashboardRunner` 亦用 bash） |
| 8 | 容器未就绪被误报「退出码 未知（可能超时）」 | 退出码为 null 且 `notReadyHint()` 非空时返回该引导（Success），与 `Bash` 语义一致 |
| 9 | 流式路径缺「输出已截断」提示 | 用 `BoundedOutput.truncated` 补同一口径的提示 |
| 10 | PLAN 模式提示词未同步 `run_script` | `prompts/agent/plan-mode.md` 禁止清单加「运行与固化」、可用清单加「不传参数列脚本」 |
| 11 | 用户文档「底线」表述未含脚本面 | `docs-site/docs/guide/permissions.md` 增一段说明（静态判定只覆盖命令行本身；定时任务跑脚本额外要求 DELETE） |
| 3（部分） | `readonly_demo` 档案未含 `summon_team` | 该档案加 `summon_team = DENY`（与 `task`/`manageMcp` 的「防止任何改动」语义一致） |

回归测试：`ScriptRepositoryTest.save_leavesNoTemporaryFileBehind`、
`containerPath_rejectsNameThatCouldEscapedDirectory`、`unavailableReason_isNullLocallyAndSetOnRemoteSsh`；
`RunScriptToolTest.runAction_whenContainerNotReady_returnsGuidanceInsteadOfTimeoutError`、
`remoteSshMode_refusesEveryActionWithoutTouchingEngineOrDisk`；既有断言随 `.sh→bash` 同步更新。

## 三、记录待排期（本批不改，附理由）

1. **远程模式真正支持脚本**（P1-1 的完整解）：`ScriptRepository` 改用 `FileAccessProvider` +
   `PathHomeResolver.aicodeRoot()`，同时切片 B 的编辑器（本就走 `FileAccessProvider`）自然对齐。
   注意随之要重新决策：SFTP 下的原子写（`rename` 不允许覆盖，`FileAccessProvider.rename` 目标存在即抛）、
   可执行位（`FileAccessProvider` 无 chmod，但「解释器 + 路径」调用不依赖它）、索引写并发。
2. **`away` 档位/手动 AUTO 压过 `NON_REMEMBERABLE`**（P2-2）：`ToolPermissionPolicyEngine.kt:396` 的
   `tier == AUTO → ALLOW` 排在 `:397` 的 `NON_REMEMBERABLE` 判定之前，故 away 档位下「固化脚本」不弹窗。
   既有问题（同样作用于 `manageMcp`），改动会影响别的工具行为，需单独评估后统一处理。
   影响：交接中「固化需不可记忆的 MODIFY_AGENT_CONFIG」这一补偿控制在 away 档位下不成立；
   scheduledRun 下结论仍成立（`:396` 被 `!scheduledRun` 挡住）。
3. **失败路径绕过 `ToolOutputStore`**（P2-1）：非零退出返回 `ToolResult.Error`，`ToolOutputStore.process`
   对 Error 原样返回 → 不限幅、不落盘。实际风险低（引擎 `BoundedOutput` 已限幅到约 40k，与
   `MAX_INLINE_CHARS` 同量级）。要修得改「非零退出读作失败」的对外语义，单独排期。
4. **`recall` 已注册但未收录 catalog**（P2-14，既有缺陷，非本批）：注册 26 / 目录 25，
   授权中心看不到它。需单独确认其应属分组与档位后补登记。
5. **`list()` 逐文件读前 20 行**（P2-15）：脚本数量大时是 O(n) 文件 IO。切片 B 的脚本页若频繁刷新，
   再考虑加短 TTL 缓存。
6. **备份包会带上 `.index.json`**（P2-12，非缺陷）：导入后「上次运行」时间跟着迁移，无害；
   `MAX_AICODE_ENTRY_BYTES` 64MB/条目、无总量上限，与 keys 独立段无冲突。

## 四、qa 认定的「无问题但值得记录」

- 命令注入/路径穿越不可达：`findScript` 用目录名比对而非 `File(dir, 用户输入)` 构造路径；名称字符集 +
  固定解释器映射使命令拼装不可注入。
- 权限面三者自洽（policy=ASK + catalog TERMINAL/ASK + `effectiveCapabilities`）；因 policy 非
  `AUTO_APPROVE`，`StatefulAgentWorkflow` 的 AUTO_APPROVE 短路对 `run_script` 不生效。
- 三态分发与双路径语义一致；`recordRun` 不重复；`CancellationException` 透传；
  `resolveTimeoutMs` 与 `ExecuteCommandTool` 一致；`firstCommentDescription` 的 `return@use` 合法；
  `setExecutable` 在宿主不抛异常且非必需（解释器 + 路径调用）。
- 能力感知三处文案与实现一致；`syncAiDocs` 覆盖 `docs-site/docs/**/*.md` → assets；
  DI 无漏改构造点；catalog 消费者全部数据驱动，无硬编码计数需同步。

## 五、测试补强建议（未做，随切片 B/C 一并考虑）

- `resolveTimeoutMs` 边界（非数字 / 0 / 负数 / 超上限）。
- 流式路径：非零退出 → Error、异常兜底 → Success、保存与列表委派、脚本未找到、`Exit(null)` 超时语义。
- 名称注入变体（含 `"`、`$`、反引号）作为回归护栏。
- 描述 100 字符截断与「注释在更靠后行」的扫描行为。

## 六、仍需真机/构建验证（主代理后续执行）

1. 固化 → 容器终端可见 → `run_script` 运行 → 退出码 → 清单「上次运行」展示；三态授权弹窗文案。
2. `.sh` 现在走 bash：确认 provision 完成后的容器里 `bash` 可用、带 bashism 的脚本可跑。
3. 备份导出/导入往返后 `.index.json` 与清单展示。
4. 定时任务 + 仅预授权 EXECUTE 时，含 `rm` 的脚本应落 ASK（不再静默放行）。
