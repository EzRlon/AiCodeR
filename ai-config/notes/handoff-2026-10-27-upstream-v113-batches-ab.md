# 交接：上游 v1.13 合并批次 A+B（2026-10-27）

## 一句话状态

上游 v1.13.0-rc1 评审完成并已落地**前两批**合并（**全部未提交**，工作区 29 文件改动 +619 −164）。编译与单测全绿。下一会话可直接从「批次 C 候选」继续，或先复核后提交。

## 仓库与基线

- 项目根：`~/workspace/AiCode`（注意：不是 `~/workspace`，后者是另一个 git 仓库）
- 分支：`dev`，HEAD `c92a0c2`（158 提交）
- 上游：`https://github.com/jieapi/AiCode`，本次评审 tag `v1.13.0-rc1`
- 我方上次合并的上游基线：`539c922`（上游 2026-10-17 快照）
- **网络坑**：GitHub 直连被 TLS 重置。取上游元数据/源码走 `https://gh-proxy.com/https://...` 前缀
  - release 元数据：`https://gh-proxy.com/https://api.github.com/repos/jieapi/AiCode/releases/tags/v1.13.0-rc1`
  - 源码包：`https://gh-proxy.com/https://github.com/jieapi/AiCode/archive/refs/tags/v1.13.0-rc1.tar.gz`
  - 本地已解压：`/tmp/up113/AiCode-1.13.0-rc1`（我方基线解压在 `/tmp/old539`，容器重启会丢，需要时重新 `git archive 539c922 | tar -x -C /tmp/old539`）

## 评审结论存档

完整采纳/舍弃清单在 `ai-config/notes/upstream-v1.13-review-2026-10-26.md`（**未跟踪，未提交**）。

## 批次 A 已完成（子代理链路 + 文件/安全）

| 文件 | 改动 |
|---|---|
| `SubAgentEventBus.kt` | 新增 `slotLock` + `reservedSlots`；`tryReserveSlots`/`releaseSlots` 让「检查+占位」原子化；新增 `DELETED` 事件类型；活跃集合读写全部持锁 |
| `TaskTool.kt` | `createSubagent` 改原子占位 + `finally` 归还名额；`del` 改发 `DELETED` |
| `AIAgentViewModel.kt` | 新增 `DELETED` 分支 + `clearSubAgentRuntimeState`；`spawnSubAgentWorkflow` 补异常/取消兜底释放名额并通知父会话 |
| `ChatSession.kt` | `AgentMode.permissiveness` + `limitedBy` |
| `SessionUseCase.kt` | `resolveChildMode`：子代理模式只能收不能放（PLAN 下无法派 `mode: build` 绕只读） |
| `EditFileTool.kt` | `findLineEndingTolerant` 逐字符迭代扫描容忍 LF/CRLF，替换文本沿用原换行格式 |
| `BackupViewModel.kt` + strings | 空口令禁止上传云端（中英双语文案） |
| `FtpServerManager.kt` | 随机口令 + `KeystoreCipher` 加密存储，识别旧 `123456` |
| `BrowserManager.kt` | 混合内容降为 `MIXED_CONTENT_COMPATIBILITY_MODE` |
| `BrowserTool.kt` | 超时先判、`CancellationException` 重抛 |

## 批次 B 已完成（审批串号 + terminal + 思考档位）

| 文件 | 改动 |
|---|---|
| `PlanApprovalManager.kt` | 单槽改多槽 `LinkedHashMap`；`PlanApprovalRequest` 带 `id`；新增 `resolve(id, choice)`；回滚 Scope 加 `SupervisorJob` + `runCatching` |
| `AgentApprovalNotificationManager.kt` | `PendingApproval` 加 `requestId`；PendingIntent 的 `data` 带 kind/requestId/choice |
| `AgentApprovalActionReceiver.kt` | 有 id 精确 resolve，无 id 回退「最早待决」 |
| `AIAgentViewModel.kt` | 审批聚合补 `pendingRequest` 并把 requestId 传给通知 |
| `BackgroundTerminalTools.kt` | `read` 支持 `filter` 正则按行筛选；`close` 支持 `tab_ids` 批量（兼容数组与逗号分隔） |
| `AIProvider.kt` + 三适配器 | 新增 `reasoningLevels` |
| `OpenAIAdapter.kt` | 有声明档位时按声明透传/就近降级（`max`/`xhigh` 不再被压成 high）；无声明时保留原保守策略 |
| `GeminiAdapter.kt` | 关闭思考（budget 0）不再带 `includeThoughts`（原会直接 400） |
| `StatefulAgentWorkflow.kt` | 每次请求按元数据刷新档位 |

## 验证结果（均已跑过）

- `sh tools/build-with-lock.sh :app:compileArmsoloReleaseKotlin` → **BUILD SUCCESSFUL**（3m3s，仅既有 FloatBallOverlay 弃用警告）
- 单测 **全绿**：
  - `SessionUseCaseModeTest`（新增）5/5
  - `BackgroundTerminalToolsParsingTest`（新增）7/7
  - `ContextCompactorTest` 19/19

## 顺带修掉的既有破损

`ContextCompactorTest.kt` 因记忆图谱批次（`24fc67d`）给 `ContextCompactor` 加了 `sessionUseCase`/`graphRepository` 两个构造参数而未同步，导致整个单测模块编译失败。只补了两个 mock 参数，未改测试逻辑。

## 批次 C 已完成（2026-10-27 续做）

| 文件 | 改动 |
|---|---|
| `SubAgentDigester.kt`（新增） | 纯函数从子代理消息提炼「结论摘要 + 改动文件」；跳过空正文/压缩摘要行，toolArgs 截断时退化为字面提取 |
| `AgentNotificationCenter.kt` | `PendingNotification` 增 `resultExcerpt`/`changedFiles` |
| `AgentNotificationFormatter.kt` | 子代理通知输出 `<changed-files>`/`<result-excerpt>`；有摘要时 hint 改为「通常无需再读」 |
| `AIAgentViewModel.kt` | `enqueueSubAgentNotification` 在 COMPLETED 时于 IO 线程提炼摘要（失败/终止不抽正文），传给 `notifyParentSubAgentFinished` 新参数 |
| `AgentDefinition.kt` | 新增 `allowsTool`/`effectiveMode`/`capabilitySummary`；`filterToolNames` 改为复用同一判定，并默认保留 `messageParent`（白名单未列也不裁，否则提示词教模型调不存在的工具） |
| `AgentDefinitionRepository.kt` | 新增 `definitionsVersion`，save/delete/setDisabled 后递增 |
| `SystemPromptProvider.kt` | 子代理清单每行加「文件读取/文件修改/执行命令」能力标注；`SourceCacheKey` 带 mode + 定义版本（顺带修「改定义要开新会话才生效」） |
| `ChatSessionDao.kt` + `SessionUseCase.kt` | 空会话查询下沉 SQL（`NOT EXISTS` 子查询 + `deletedAt IS NULL`），不再全量读根会话逐条查消息 |
| `ShellCommandParser.kt` | `Analysis` 增 `blockers` 记录触发构造；补命令替换括号深度跟踪，避免 `$(...)` 内括号被误判为子 shell |
| `ToolPermissionPolicyEngine.kt` | 拦截/询问原因回显具体构造（`unanalyzableReason`），AI 不必整条反复重试 |
| `SettingsScreen.kt` | `rememberSaveableStateHolder` + `SaveableStateProvider` 按分区名接住滚动位置，详情页 key 带条目身份 |

**验证**：`:app:compileArmsoloReleaseKotlin` BUILD SUCCESSFUL；单测全绿（150 用例，0 失败）——`SubAgentDigesterTest` 10/10（新增）、`AgentDefinitionTest` 22/22、`ShellCommandParserTest` 15/15、`ToolPermissionPolicyEngineTest` 72/72、`SessionUseCaseModeTest` 5/5、`BackgroundTerminalToolsParsingTest` 7/7、`ContextCompactorTest` 19/19。detekt 扫改动文件 0 发现。

**审查发现并已修的问题**：
- `SubAgentDigester` 原先只按 `path` 读改动文件，而 `generateImage` 的参数名是 `output_path`——会漏报图片产物。改为按工具映射参数名，并补了回归用例。
- `TerminalSessionTool` 类注释里 `close` 重复描述两行（旧文残留），已合并。

**审查结论（主线程自查，替代失效的 qa 子代理）**：槽位占位/归还成对、DELETED 与 STOPPED 不重复通知、权限只能收不能放（父 mode 异常不静默提权）、CRLF 容忍保留原格式、审批多槽按 id 精确 resolve、思考档位按声明透传且 Gemini budget=0 不带 includeThoughts、拦截原因回显构造且命令替换内括号不误判为子 shell、SQL 下沉语义等价（含 `deletedAt IS NULL`）、摘要提炼只在 COMPLETED 且走 IO 线程。均无 P0/P1 问题。

**dex 余量（正式包实测，批次 A+B+C 全部已含）**：`classes.dex` 64705 / 65536，余 **831**（本批 A+B+C 合计仅 +34 引用）。仍逼近上限，下一个大批次（多选组件 + LazyColumn）前必须先评估拆 dex 或裁依赖。

## 批次 C 剩余候选

1. 列表多选批量操作 + LazyColumn 改造（上游 `ListSelection.kt` 274 行，我方技能/MCP 列表仍是 `Column+forEach`）——改动面大，建议独立批次

## 明确未采纳（勿再评估）

提示词通用化重写、免责声明/隐私门控、供应商预设增删、`api.official.json` 全量刷新、应用内更新、文件树「当前容器」根节点、容器镜像锁定模式、压缩阈值 90→85（我方已 70%）、gradle 堆 7168m、Alpine rootfs 升级、远程终端 1 秒轮询退出标记、MCP OAuth。理由逐条见评审笔记。

## 硬约束

1. **迁移号**：上游 v1.13 到 63（`add_llm_call_reasoning_wire`），我方到 73，**63 号已被 `add_session_team` 占用**。若采纳思考能力全量方案必须重编号到 74 并同步 `SCHEMA_VERSION`。
2. **dex 余量**：64469/65536（余 1067）。批次 C 一次全上会爆，必须分批并每批核 dex 数。
3. **构建**：一律 `sh tools/build-with-lock.sh <任务>`（锁 + musl 前缀已内置）。正式包必须 `--no-configuration-cache`。
4. **多会话共用工作区**：`git status` 里有**另一会话未提交**的改动，**不要提交、不要回滚**：
   - `ai-config/notes/devenv-one-tap-disposition-2026-10-24.md` 等 6 个已跟踪笔记的修改
   - `ai-config/notes/` 下 10 个未跟踪笔记（deepseek-harness-borrowing / engine-bridge-phase2-disposition / enhancement-program / handoff-2026-10-24/25/26 / kimi-review / review-disposition / review-package）
   - 本批可提交范围 = `app/src/**`（含 2 个新测试文件）+ `ai-config/notes/build-times.csv` + 本交接与评审笔记
5. **思考能力完整子系统**（`ReasoningResolver` + `ReasoningCapabilityTables` + 400 文案学习 + UI 档位选择器，6 新文件）本批**没有**整块搬，只摘了两条独立修复。要全量替换需单独立项出方案。
6. **子代理实测教训**：本会话派 3 个 Explore 子代理全部长时间无产出（`updatedAt` 持续刷新但从不回复），已停止。若再遇，判别铁证是 `updatedAt == createdAt` 或长期无 `lastOutput`；此时不要空等，改为自己核查。

## 编译耗时参考

Kotlin 编译约 3–8 分钟（增量/全量），单测约 1.5–3 分钟。用 `terminal` + `notify=true` 等完成通知，不要轮询。
