# 全库屎山/技术债审计（2026-10-27，Ponytail 方法论）

> 状态：**批次 1~4 已完工；批次 5 可控部分已完工（多选脚手架/域层解耦/清理块归并），巨型文件拆分仍待立项**。提交：`ca9b975` / `e5d167c` / `ec8590c` / `09b7bb1` / `ec1bf4d` / `3445941`。
> 基线提交 `c92a0c2`。审计方法：ponytail-audit（整仓质量审计）+ 仓库自带 detekt + 两路子代理并行深审 + 主线程逐条回源核实。
> 本文件所有行号均为 2026-10-27 实测，代码变动后会漂移。
> **2026-10-27 11:50 更新**：新增 §九「最近 18 小时增量审计」（上游 v1.13 合并 A/B/C/D/E1 + 记忆图谱 P1），原 §九 顺延为 §十。

## 一、客观基线

| 指标 | 数值 |
|---|---|
| 主源码 | 600 个 .kt / 148,491 行 |
| 单测 | 144 个文件 / 19,681 行（覆盖率约 13%） |
| 超 800 行文件 | 38 个（6.3% 文件承载 35.7% 代码） |
| 超 1000 行文件 | 18 个 |
| 超 2000 行文件 | 7 个 |
| 平均文件行数 | 247 行 |
| 模块集中度 | agent 60,255 行 + settings 35,212 行 = 全库 64% |
| settings 分层 | presentation 27,700 行 : (data 4,603 + domain 2,909) = 3.7 : 1 |
| TODO/FIXME/HACK 标记 | **0 处**（债务全隐性，无任何代码内标记） |
| `shortcut:` 债务标记 | **0 处**（未采用 ponytail 债务账本约定） |
| runCatching 使用 | 209 个文件，其中 107 处 `.getOrNull()` 静默吞 |
| 空 catch 块 | 1 处（`BrowserScreen.kt:554`，已排除误报） |

### detekt 复杂度 Top 10（单文件实测）

| 文件 | 行数 | 圈复杂度 | 认知复杂度 |
|---|---|---|---|
| `settings/.../ProviderEditorScreen.kt` | 3259 | 310 | **897** |
| `agent/presentation/AIAgentViewModel.kt` | 4469 | **816** | 666 |
| `agent/.../AIChatPanel.kt` | 2534 | 448 | 648 |
| `agent/domain/workflow/StatefulAgentWorkflow.kt` | 1845 | 378 | **610** |
| `settings/presentation/SettingsViewModel.kt` | 3228 | 396 | 255 |
| `floatball/FloatBallOverlay.kt` | 1467 | 188 | 398 |
| `agent/.../ChatDrawer.kt` | 2281 | 202 | 390 |
| `agent/.../ChatInputBar.kt` | 1799 | 158 | 382 |
| `MainActivity.kt` | 1481 | 169 | 371 |
| `agent/.../AdaptiveCardRenderer.kt` | 1194 | 246 | 193 |

### 巨型单函数（Composable / 状态机）

| 函数 | 跨度 | 行数 |
|---|---|---|
| `AIChatPanel`（Composable） | `AIChatPanel.kt:675-2447` | ~1770 |
| `ProviderEditorScreen`（Composable） | `ProviderEditorScreen.kt:178-1303` | ~1125 |
| `ChatInputBar`（Composable） | `ChatInputBar.kt:175-912` | ~738 |
| `executeEvents`（协程状态机） | `StatefulAgentWorkflow.kt:591-1220` | ~630 |
| `executeAgentRequestStream` | `AIAgentViewModel.kt:2352-2945` | ~603 |
| `SettingsScreen`（Composable） | `SettingsScreen.kt:225-1936` | ~1710 |

### 层间违规（grep 实测）

- **domain → presentation：11 行 / 9 文件**（历史记为 3 处，实际更多）
- **core → feature：11 行 / 2 文件**（`AppProxy.kt:4-9`、`FileChangeHub.kt:5-9`）
- **agent ↔ settings 双向 import**：agent→settings 39 处，settings→agent 40 处（事实循环依赖，两模块无法独立拆分）

### 测试覆盖缺口（重点）

- `StatefulAgentWorkflow`：**零单测**（含纯函数 `reduce` 也无测试）——全 App 最核心状态机
- `executeAgentRequestStream`（603 行执行入口）：**零单测**
- `WorkspaceRepository.deleteWorkspace`（破坏性删除）：**零单测**
- 已排除误报：`isExpandedWidth` 在 `MainActivity.kt:613` 有实际调用，非死代码

---

## 二、P0 必修（5 条，均已回源核实成立）

### 1. 编辑器把 GBK 文件当 UTF-8 读，保存即损坏原文件
`feature/editor/presentation/CodeEditorViewModel.kt:107`、`:181`

> **✅已修 ca9b975**（2026-10-27，批次 1）。`load()` 改走 `FileEncoding.decode` 并存下编码与 BOM；`writeThrough()` 用同一编码写回并补回 BOM；另补 `Charset.strictEncode`，GBK 表示不了的字符改为报错而非静默变 `?`。回归测试 `FileEncodingTest`（8 例）。

- **问题**：`load()` 用 `bytes.toString(Charsets.UTF_8)`，`writeThrough()` 调 `fileAccess.writeFile(path, content)` 默认 UTF-8。而 `feature/editor/domain/FileEncoding.kt` 已完整实现 UTF-8/UTF-16/GBK 检测与「保存用同一编码写回」约定（KDoc 明写「避免 GBK 文件被静默转成 UTF-8 破坏原文件」），**全库零调用点**。打开任一 GBK 中文源文件 → 乱码 → 保存 → 原文件被 UTF-8 覆盖，编码信息永久丢失，无备份（checkpoint 只管 AI 写入）。
- **改法**：`load()` 改 `FileEncoding.decode(bytes)` 并存下 encoding；`writeThrough()` 传 `encoding = detected.charset`。约 6 行，复活已写好的死代码。
- **不修后果**：一次保存毁掉整个文件编码，且无提示无备份。

### 2. 删除用户消息会级联硬删其后全部对话，且无确认弹窗
`feature/agent/presentation/AIAgentViewModel.kt:4442-4452`、`AIChatPanel.kt:2419`、`MessageContextMenuPopup.kt:136-137`

> **✅已修 ca9b975**（2026-10-27，批次 1）。删除前弹二次确认（复用 `BatchDeleteConfirmDialog`），用户消息明确告知会连带删除其后全部对话；切换会话时清空待确认状态，避免跨会话删错。

- **问题**：`deleteMessage` 对 `role == USER` 的消息执行 `agentMessageDao.deleteMessagesAfterTimestamp(...)`（SQL：`timestamp > :cutoff`，见 `AgentMessageDao.kt:87`），即删一条用户消息会静默删掉其后整段对话（AI 回复、工具结果、后续所有轮次）。UI 上 `MessageContextMenuPopup` 的删除项只有红字（`destructive = true`），点击直接回调 `delete()`，**无任何确认**。消息删除是硬删（软删只作用于会话级）。
- **改法**：删除回调加一次确认（复用项目已有 `AlertDialog` 惯例），或拆成「仅删本条」/「删本条及之后」两个明确动作。
- **不修后果**：长会话里删一条自己发错的消息，一次性丢掉后面几十轮 AI 回复与全部工具记录，不可恢复。

### 3. 子代理失败时同时发 FAILED 与 COMPLETED，父代理收到「完成」误报
`feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1106-1107`、`AIAgentViewModel.kt:2809-2844`

- **问题**：`executeEvents` 收尾无条件执行 `state.error?.let { send(AgentEvent.Failed(...)) }` 后紧接 `send(AgentEvent.Completed)`。ViewModel 的 `Failed` 分支发 `SubAgentEventType.FAILED` 并 `archiveSubAgentReport(outcome="failed")`；`Completed` 分支**再发一次** `COMPLETED` 并 `archiveSubAgentReport(outcome="completed")`。而 `SubAgentReportCatalog.kt:45` 是 `overwrite = true` —— 失败的 failed 归档被 completed **覆盖**。父代理据此认为子任务成功。
- **改法**：ViewModel 的 `Completed` 分支加 `if (!failed)` 前置判断（`failed` 变量已在 2809 置位，作用域覆盖整个 collect）；workflow 侧改互斥发送。

> **✅已修 e5d167c**（2026-10-27，批次 2）。两侧都改：workflow 收尾改 Failed/Completed 互斥；ViewModel 的 Completed 分支加 `!failed` 前置判断。
- **不修后果**：任何子代理失败（API 报错、工具崩溃、上下文超限）都被父代理当成功，`task(action="read")` 拿到错误的 completed 归档并误导后续所有会话。

### 4. `extractDocs` 用裸 `deleteRecursively` 删 `~/.aicode/docs`
`feature/agent/domain/container/ContainerInstaller.kt:48`

> **✅已修 ca9b975**（2026-10-27，批次 1）。改 `SafeDelete.deleteTree(destDir)`，与 multi-llm / skills 一致。

- **问题**：同文件 `extractMultiLlm`（`:68`）与 `extractBuiltinSkills`（`:110`）都已改用 `SafeDelete.deleteTree` 并注明「不能用 deleteRecursively，会跟随目录符号链接下钻」，`:117-119` 甚至声明本类不再保留私有实现 —— 但 `extractDocs` 仍留裸 `deleteRecursively`。`docs` 目录在容器内对 AI 可写，AI 建一个 `docs/x -> /root/.aicode/skills` 链接，下次启动全量删除会删掉 skills 整棵树。
- **改法**：第 48 行换 `SafeDelete.deleteTree(destDir)`，一字之差。
- **不修后果**：本项目已有 `delete-recursively-symlink-pitfall` 踩坑记忆，这是同一坑的第四个入口。

### 5. 删除工作区/SAF 文档仍用裸 `deleteRecursively`（用户主动操作即可触发）
`feature/workspace/data/repository/WorkspaceRepository.kt:456`、`feature/workspace/data/provider/WorkspaceDocumentsProvider.kt:159`

- **问题**：`deleteWorkspace` 本地分支 `File(projectsRoot, name).deleteRecursively()`。工作区被 bind mount 到容器 `/root/workspace`（`LinuxContainerEngine.kt:742`），目录内容由 AI 全权写入，且 `WorkspaceRepository.kt:115` 注释明说 filesDir 支持 symlink（npm/pnpm/git 建软链）。AI 或用户脚本在工作区里建一个指向 `/root/.aicode` 的符号链接，用户点「删除工作区」就会跟着链接删出去。对照：`LocalFileAccess.deleteRecursively:199-207` 已做 NOFOLLOW 防护，说明纪律存在但未统一收口。此路径零单测。
- **改法**：两处都换 `SafeDelete.deleteTree`。
- **不修后果**：与 P0-4 同类数据丢失，但触发面更大（删工作区是常规操作）。

> **✅已修 ca9b975**（2026-10-27，批次 1）。两处改 `SafeDelete.deleteTree`；`deleteDocument` 保留原有「删不掉即抛 FileNotFoundException」语义。补单测 `SafeDeleteTest`（5 例，含符号链接不下钻）。

---

## 三、P1 应修（12 条）

### 1. 权限桥 token 复用 sessionId，data 通道绕过权限弹窗
`feature/agent/domain/engine/PermissionBridgeServer.kt:67`、`:134-146`、`ClaudeCodeBridge.kt:218`、`CodexBridge.kt:211`、`OpenCodeBridge.kt:226`

- **问题**：`ensureStarted(token = sessionId)`，三个引擎桥都把 sessionId 当鉴权令牌。而 sessionId 会出现在聊天界面、`session-<id>.log` 日志文件名、容器内 env。本机任意进程（含容器内 AI 脚本、第三方 MCP server）连 `127.0.0.1:<port>` 带该 token 发 `{"kind":"data"}` 即可**无弹窗**调用 `recall_search`（读全部历史会话原文）、`ledger_*`（读标注账本含图片路径）。`expectedTokens` 只加不删（`removeToken` 仅在回合结束调，异常路径漏调即永久有效）。
- **改法**：token 改独立随机 UUID 与会话 id 解耦；data 通道加「仅允许当前活跃会话」判定；`expectedTokens` 在连接结束或 `close()` 时清理。

> **✅已修 e5d167c**（2026-10-27，批次 3）。三桥改用 `newToken()` 随机 UUID，`token→sessionId` 绑定表，data 通道校验会话匹配，`removeToken` 同步清绑定。

### 2. VNC WebView `MIXED_CONTENT_ALWAYS_ALLOW` + JS 桥暴露剪贴板
`feature/vnc/presentation/VncWebViewPanel.kt:76`、`:122-145`

- **问题**：页面本身来自 `appassets.androidplatform.net`（安全），但 ALWAYS_ALLOW 允许 https 页面加载 http 子资源；`VncJsBridge` 暴露 `getAndroidClipboard()`/`pushToAndroid(text)`。noVNC 连用户指定的任意 VNC 服务器，页面一旦被 http 子资源劫持，JS 可静默读写系统剪贴板（含复制的密码）。对照 `BrowserManager.kt:507` 已收紧为 `MIXED_CONTENT_COMPATIBILITY_MODE`，此处不一致。
- **改法**：改 `MIXED_CONTENT_COMPATIBILITY_MODE`；剪贴板桥加一次性令牌（仅用户显式点粘贴时允许一次）。

> **部分修 e5d167c**（2026-10-27，批次 3）。剪贴板读取已加一次性放行令牌（仅用户点粘贴时允许一次）。mixed content **不降级**：实测降为 COMPATIBILITY_MODE 会直接断掉 noVNC 的 `ws://127.0.0.1:6080`（不是偏好问题）；改为封住滥用面——WebView 只放行自身资产域、禁止外部导航，被劫持的页面已无从加载外部子资源。

### 3. 备份导入无事务，中途失败留下「一半新一半旧」
`feature/backup/data/BackupManagerImpl.kt:582`、`:831-856`

> **✅已修 ca9b975**（2026-10-27，批次 1）。`restoreFromTar` 改三阶段：文件类条目流式落地 → 连续 DB 段一个 `withTransaction` → 乱序条目与元数据段（元数据的小表写入单独包事务）；`restoreLegacy` 只把 DB 段包进事务。文件已写盘而后续失败时抛 `PartialRestoreException` 告知已写入文件数。

- **问题**：`restoreFromTar` 逐 tar entry 处理，每条 `restoreJsonl` 分页 `upsertAll`（8 处），每个工作区文件直接 `FileOutputStream(target).use { tar.copyTo(out) }`（`:848`）。全流程无 `withTransaction`（该文件 0 处使用）。导入中途抛异常（磁盘满/tar 损坏/单条 json 解析失败）→ 部分会话已 upsert、部分文件已覆盖写，元数据段还没跑，`onFailure` 只记日志。
- **改法**：DB 段用 `agentDatabase.withTransaction` 包住；文件段先解到临时目录再整体替换，最小版至少加事务 + 明确告知「部分文件已写入」。

### 4. `_expandingPath` 异常路径绕过清除，目录卡死（原报告机理已修正）
`AIAgentViewModel.kt:775`、`:869`

- **问题**：`.onEach { _expandingPath.value = null }.catch { ... }` —— **`catch` 在 `onEach` 下游**，上游抛异常时 `onEach` 被跳过，标记不清。`toggleExpand` 开头 `if (path == _expandingPath.value) return` 于是拒绝该目录再次展开（表现为点了没反应、转圈不动）。切走 Tab 超过 5s（`WhileSubscribed(5_000)`）再切回会重新订阅自愈，故为 P1 而非 P0。
- **改法**：把清除放进 `finally`/超时兜底（`_expandingPath` 置位时同时起 10s 超时协程无条件清空），或 `catch` 提到 `onEach` 之前。

> **✅已修 e5d167c**（2026-10-27，批次 2）。两处都做了：`catch` 分支补清标记 + 置位时起 10s 兜底清除协程。

### 5. 删除/编辑消息无事务、无历史缓存失效
`AIAgentViewModel.kt:4434-4452`

- **问题**：`deleteMessagesAfterTimestamp` + `deleteMessageById` 两条独立执行（无 `withTransaction`）；且 DAO 直调后没调 `invalidateHistory`，而 `MessagePersistenceUseCase` 的 `historyCache` 依赖 Room `InvalidationTracker` 异步回调递增 `dbVersion`（`:37-42`）。下一轮 `buildHistory` 若在回调到达前命中缓存，会把刚删的消息重新发给模型。`updateMessageContent` 同理。
- **改法**：改走 `messagePersistenceUseCase` 用例方法（与 `rewindConversation` 一致，内含 `invalidateHistory`），级联删除包进 `withTransaction`。

> **✅已修 ca9b975**（2026-10-27，与 P0-2 同批）。删除改走 `MessagePersistenceUseCase.deleteMessage`（读角色与级联删除同事务 + 提交后 `invalidateHistory`）；`updateContent` 递增 `dbVersion` 主动失效缓存，不再依赖 InvalidationTracker 的异步回调。

### 6. 交接暂停态纯内存，进程重启即失效
`AIAgentViewModel.kt:334`、`:2380-2382`、`:2992`

- **问题**：`pausedHandoffSessions = mutableMapOf<String, Int>()` 不落盘。进程被杀后原本因上下文超硬阈值暂停的会话恢复为可发送，`enqueueAgentRequest` 拦截失效。同文件 `handoffContinuedSessions`（253）、`dismissedHandoffCounts`（331）、`goalEmptyTurnCounts`（340）同为纯内存，其中 `goalEmptyTurnCounts` 丢失会让空回合熔断从 0 重来。
- **改法**：暂停态与继续抑制集合落盘（`ChatSessionEntity` 加列或 DataStore）。

> **✅已修 e5d167c**（2026-10-27，批次 2）。新增 `HandoffStateRepository`（DataStore + JSON）：暂停态/已继续抑制/暂不计数/goal 空回合计数启动时恢复、变更即落盘。

### 7. `reasoningDurations` / `_contextUsages` 等会话态只增不减，三处清理块重复且漏清
`AIAgentViewModel.kt:567`、`:2719`、`:3940-3963`、`:3965-4007`、`:4008-4066`

- **问题**：`reasoningDurations = mutableStateMapOf<String, Long>()` 只有写入点 2719，**全文件零清除路径**。`_contextUsages` 仅 4106（回滚路径）清，`forgetSessions`/`deleteSessions`/`deleteSession` 三处清理块几乎逐字重复却都漏清 `_contextUsages`、`_keySwitchStates`、`_preparingTools`、`_compactionFailedSessions`、`_reasoningTimings`、`reasoningDurations`、`toolArgsByMsgId`。
- **改法**：抽 `clearSessionRuntimeState(sessionId)`（与 `clearSubAgentRuntimeState` 合并），补齐 7 个字段，三处改调一处；`reasoningDurations` 改为从消息实体读。

> **✅已修 e5d167c**（2026-10-27，批次 4）。三处（`forgetSessions`/`deleteSessions`/`deleteSession`）合并为 `clearSessionRuntimeState`，补齐 keySwitch/preparingTool/compactionFailed/contextUsages/intentDrafts 等原先漏清的字段；`reasoningDurations` 加消息→会话映射以便按会话清理。

### 8. `SettingsScreen` 单函数内 96 个状态收集，任一设置变化整页重组
`feature/settings/presentation/component/SettingsScreen.kt:244-340`

- **问题**：实测 `grep -c collectAsStateWithLifecycle` 全文 110 个，其中 **96 个集中在 `SettingsScreen` 主函数 244-340 这 97 行内**。这些是 Compose snapshot 读取点，任一变化都会让整页重组；内部 `AnimatedContent`（`:609`）承载所有分区页，重组代价按当前分区复杂度放大。
- **改法**：每类状态下沉到对应分区 Composable（`GeneralSettingsSection`/`AboutSection` 等已是独立组件，让它们自己 collect），`SettingsScreen` 只保留导航态。纯机械迁移。

> **不修**（2026-10-27，批次 4 评估）。96 个状态收集属纯机械迁移，但 `SettingsScreen` 已 2300+ 行、内部 `AnimatedContent` 承载全部分区，一次性下沉涉及所有分区组件的参数面，回归面大而收益（重组范围）需真机对比才能量化。归入§七「巨型文件拆分」一并做，不单独动。

### 9. `ChatInputBar` 每次键入同步全文 `TextMeasurer.measure`
`feature/agent/presentation/component/ChatInputBar.kt:321-332`

- **问题**：`remember(value, inputFieldWidthPx, inputDensity, inputTextStyle) { ... textMeasurer.measure(AnnotatedString(value), ...) }` —— `value` 是输入框全文，**每敲一个字**都在组合期同步测量全文高度。长文本（几千字）时压在键入路径上。
- **改法**：改 `derivedStateOf` + 按长度阈值短路，或挪到 `LaunchedEffect(value)` 异步测。

> **✅已修 e5d167c**（2026-10-27，批次 4）。短文本（<120 字符）直接短路，其余走 `derivedStateOf`。

### 10. `AIChatPanel` 收工后仍逐帧跑 1200ms
`feature/agent/presentation/component/AIChatPanel.kt:1395-1413`

- **问题**：`snapshotFlow { ... }` 为 false 时进入 `else` 分支，用 `deadline = now + CALIBRATE_TAIL_MS(1200ms)` 继续 `withFrameNanos { }` 逐帧校准（`:1405-1409`）。这段「收尾兜底」每轮任务结束都无条件触发，60fps 下约 72 帧主线程帧回调。
- **改法**：收尾分支改 `delay(CALIBRATE_TAIL_MS)` 后单次校准。

> **✅已修 e5d167c**（2026-10-27，批次 4）。收尾分支改 `delay(CALIBRATE_TAIL_MS)` 后单次校准，不再逐帧醒 72 次。

### 11. `FileChangeHub` 512 目录上限是全局配额，与注释「单订阅」不符
`core/watch/FileChangeHub.kt:82`（注释）、`:312`（实现）、`:118`（`dirWatches` 全局表）

- **问题**：注释写「**单订阅**可持有 inotify 句柄的目录数上限」，实现是 `dirWatches.size >= MAX_WATCHED_DIRS`，而 `dirWatches` 是跨订阅共享的全局 `ConcurrentHashMap`。一个大仓库的文件树订阅把配额吃满后，**其它订阅**（权限规则、MCP 配置、技能目录）的 `register()` 直接 `return`，静默失去 inotify，只剩 15s 轮询兜底，且日志看不出是别人的订阅占的。
- **改法**：判断改 `myDirs.size >= MAX_WATCHED_DIRS`（用订阅自己的集合）。

> **✅已修 e5d167c**（2026-10-27，批次 4）。改按 `myDirs`（本订阅自己的集合）判定，并更新注释。

### 12. `FileVersionRegistry.clearSession` 定义了但全库零调用
`feature/agent/domain/tool/FileVersionRegistry.kt:89`

- **问题**：grep 实测 `clearSession(` 只在定义处出现。`sessions` 是 `ConcurrentHashMap`，每 sessionId 一桶、每桶最多 512 条。会话删除、`onCleared`、任务结束都没调用。长期使用后内存里堆着所有历史会话的路径指纹表。
- **改法**：会话删除与 `AIAgentViewModel.onCleared` 各加一行 `versions.clearSession(sid)`。

> **✅已修 e5d167c**（2026-10-27，批次 4）。接在 `clearSessionRuntimeState` 里（覆盖会话删除/批量删除/回收站清空三处）。

---

## 四、P2 可选（14 条）

> **批次 4 处置（2026-10-27，`e5d167c` / `ec8590c`）**：1/2/3/4/6/7/9/12/13/14 **✅已修**；5 部分修（SDF 改 ThreadLocal 复用，状态映射合并未做）；8 不修（需新增 DAO 批量查询，收益有限）；10 不修（2s 轮询是文件树的正确性兑底，改 5s 会让外部改动感知变钝，需真机权衡）；11 无需改（`FileLogger.v` 仅在 VERBOSE 等级下写盘，默认等级更高）。

| # | 问题 | 位置 | 改法 |
|---|---|---|---|
| 1 | `FloatBallOverlay` 消息列表 `itemsIndexed` 缺 key+contentType（历史 10-20#7/10-22#3） | `FloatBallOverlay.kt:973` | 补 `key = { _, m -> m.id }`、`contentType = { _, m -> m.role }` |
| 2 | `animateScrollToItem(lastIndex)` 动画期新消息插入越界（历史 10-22#7） | `FloatBallOverlay.kt:1016` | 协程内先重读 `lastIndex` |
| 3 | 思考计时 100ms 一次重组（历史两项均未改） | `StatusBubbles.kt:611` | `delay(100)` → `delay(500)` |
| 4 | 后台同时持两把 Wi-Fi 锁（历史 10-20#6） | `TerminalKeepaliveService.kt:269-275` | 后台只持 HIGH_PERF |
| 5 | `formatTime` 每次重组新建 `SimpleDateFormat` + 两份 when 状态映射（历史 10-22#4/#5，文件已改名） | `AnnotationLedgerPanel.kt:470-471`、`:374-380`、`:404-412` | `remember(ts)` 缓存；抽 `AnnotationStatus.labelRes()` |
| 6 | VNC 5 秒回环探测（历史 10-21 P2#2） | `VncOverlayViewModel.kt:85-96` | 指数退避到 30s 封顶 |
| 7 | 图谱连边 O(会话×记忆)，`Regex` 建在双层循环最内层 + 内层查 DB | `GraphRepository.kt:122-129` | 正则预编译移出循环；`nodeById` 提到循环外 |
| 8 | 图谱 1 跳扩散 N+1 查询 | `GraphRepository.kt:168-176` | 改 `WHERE id IN (...)` 一次查 |
| 9 | `GraphDao` 硬编码列类型与 `GraphNodeType` 枚举两处定义同一规则 | `GraphDao.kt:44` | 改 `type IN (:types)` 传枚举名 |
| 10 | `FileChangeHub` 文件树订阅默认 2s 快照轮询（历史 10-20#2 部分修复） | `FileChangeHub.kt:66`、`:150`；`AIAgentViewModel.kt:763` | 文件树订阅传 `fallbackPoll = false` 或 5s |
| 11 | `MarkdownPreviewWebView` 每次资源请求都 `FileLogger.v`（历史 10-21 P2#4） | `MarkdownPreviewWebView.kt:66` | 包 `if (DIAG)` |
| 12 | `PromptsSection` key lambda 里 `localFragments.take(index).count{}` 每次重组 O(n²)，同一计算写两遍 | `PromptsSection.kt:150-155` | 预计算 rowKey 列表 |
| 13 | `LogSettingsSection` 日志列表 `itemsIndexed(entries)` 缺 key（最多 1200 行） | `LogSettingsSection.kt:371` | 补稳定 key |
| 14 | `FloatBallOverlay` 模型列表 `items(models.size)` 缺 key | `FloatBallOverlay.kt:1432` | 用模型名作 key |

---

## 五、重复实现（含「是否值得合并」判断）

| 组 | 位置 | 结论 |
|---|---|---|
| `SafeDelete` vs `SafeFileDelete` | `core/util/SafeDelete.kt`(51 行) / `SafeFileDelete.kt`(50 行)，9 个调用点分裂 | **值得合并**：给 SafeDelete 补 Boolean 重载，删 SafeFileDelete，净删约 45 行，消除「哪份权威」歧义 |
| 三个 provider adapter 脚手架 | `OpenAIAdapter.kt:39-68` / `AnthropicAdapter.kt:39-63` / `GeminiAdapter.kt:30-52`，各 13-14 个同形 `override var` + 一字不差 `extraHeaders()` | **值得抽基类**（净删约 80 行）；但 `complete`/`completeStream` 三份协议差异真实，**不要**强行合并方法体 |
| `shellQuote` 四份 | `ShellArgs.kt:148` / `GitRepository.kt:803` / `WorkspaceRepository.kt:493` / `HookRunner.kt:77` | **值得合并**（安全相关）：四份转义规则不完全一致，任一份改错即命令注入；下沉 `core/util/ShellQuote.kt` 取 Git 版 —— **✅已修 e5d167c**（内嵌单引号两种写法对 shell 等价，但安全集判定不同，已统一为 Git 版语义；补 `ShellQuoteTest`） |
| `formatBytes` 六份 | `ChatAttachmentUtils.kt:287` / `RemoteBackupDialog.kt:125` / `StorageUsage.kt:86` / `ContainerImageDownloadSection.kt:71` / `KeysViewModel.kt:249` / `ListFilesTool.kt:261` | **前五份值得合并**（小数位/阈值/Locale 三种写法混用）；`ListFilesTool` 的 `humanSize` 保留（`ls -h` 语义不同） |
| `FileAccess` 四件套 | `FileAccessProvider`(200) / `LocalFileAccess`(328) / `RemoteSftpFileAccess`(532) / `DelegatingFileAccess`(80) | **不合并**：`DelegatingFileAccess` 看似纯透传，但需每次调用读 `ExecutionModeHolder.currentMode()` 决定后端，删掉就要在每个调用点判模式 |
| `SimpleDateFormat` 30 处 | 全库 | **不合并**（格式各异），但高频重组路径改 `remember`（见 P2-5） |
| `CostFormat` vs `StorageUsage` | `CostFormat.kt:9-12`（`Locale.getDefault()`）/ `StorageUsage.kt:86`（`Locale.US`） | 统一 `Locale.US`（金额小数点不该跟区域） |

---

## 六、历史项核实（22 条核心 + 11 条补充）

### 屎山/架构 10 条（`pre-release-audit-2026-10-20.md`）：**10 条全部仍存在**

| # | 历史项 | 状态 |
|---|---|---|
| 1 | domain→presentation 反向依赖 | 仍存在，且**扩大到 11 行/9 文件**（历史只算了 3 处，漏了 `ScheduledAiTaskWorker` 直接依赖 VM） |
| 2 | AIAgentViewModel 3847 行 / 45 依赖 | 仍存在且更大：**4469 行 / 52 注入依赖 / 141 个函数** |
| 3 | SettingsViewModel 3190 行 | 仍存在：3228 行 / 56 注入 |
| 4 | BrowserManager domain 层操纵 WebView | 仍存在：2062 行，domain 层 import `android.webkit.*` |
| 5 | StorageUsageScanner 拼展示文案 | 仍存在：`:160/162/217/248/317/398-399` 十余处 `context.getString`；`StorageCleanupAdvisor.kt:84-145` 同型 |
| 6 | FileChangeHub core→feature | 仍存在：`:5-9` |
| 7 | SafeDelete / SafeFileDelete 双实现 | 仍存在：9 个调用点分裂 |
| 8 | 三 adapter 脚手架重复 | 仍存在 |
| 9 | UI 巨石 | 仍存在且 4 个变大：ProviderEditor 3259 / AIChatPanel 2534 / SettingsScreen 2302 / ChatDrawer 2281 / ChatInputBar 1799 |
| 10 | `copyHandoffContent` 静默吞异常 | 仍存在：`:3067-3090` 无 `FileLogger` |

### 性能与功耗 8 条：**5 条仍存在 / 2 条部分修复 / 1 条已修复**

| # | 历史项 | 状态 |
|---|---|---|
| 1 | WakeLock 前台也全回合持有 | **仍存在**：`AIAgentViewModel.kt:2407` `acquireKeepalive()` 无条件调用，无前后台判定 |
| 2 | FileChangeHub 2s 轮询对所有订阅 | **部分修复**：配置类已 15s（`:73`），`watchWorkspace` 默认仍 2s（`:150`） |
| 3 | 流式每 delta 全量 trim + 悬浮球 push | **部分修复**：加了 60ms 节流（`StatefulAgentWorkflow.kt:1146`）、预览 `take(160)`；悬浮球 `updateMessages` 仍逐条 `replace(Regex("\\s+"), " ")`（`:1553-1561`） |
| 4 | LiveNotification 每次 cancel IPC | **已修复**：`:83-88` 仅空/关/前台时 cancel |
| 5 | RemoteSsh 15s 探活 | **部分修复**：未配置态已 60s，正常态仍 15s（`:248`） |
| 6 | 后台双 Wi-Fi 锁 | **仍存在**：`:269-275` |
| 7 | FloatBall itemsIndexed 缺 key | **仍存在**：`:973` |
| 8 | StatusBubbles 100ms | **仍存在**：`:611` |

### qa P2 5 条：1 仍存在 / 2 已修复 / 1 无需改 / 1 仍存在（理论坑）

| # | 历史项 | 状态 |
|---|---|---|
| 1 | Checkpoints 先删 DB 后删文件 | **仍存在**：`CheckpointManager.kt:270-276` |
| 2 | `attachMultiLlmInternal` 读改写非原子 | **仍存在**：`SettingsViewModel.kt:840-853` |
| 3 | `AdaptiveModalBottomSheet` 入场动画吃帧 | **已修复**：改 `Animatable` + `LaunchedEffect`（`:113-118`） |
| 4 | FTS4 + INSERT OR REPLACE 不触发 DELETE 触发器 | **仍存在（理论坑）**：`AgentMessageDao.kt:14-18`，当前无同 id 重插路径 |
| 5 | StorageSection 容器大类文案 | 无需改 |

### 10-22 P2 9 条：4 仍存在 / 3 已修复 / 1 部分修复 / 1 仍存在

| # | 历史项 | 状态 |
|---|---|---|
| 1 | 标注详情弹窗主线程大图解码 | 已修复（当批 P1） |
| 2 | FloatBall 钉住判定在 delay(800) 前 | 已修复（当批 P1） |
| 3 | FloatBall itemsIndexed 缺 key | 仍存在：`:973` |
| 4 | `formatTime` 新建 SDF | 仍存在：`AnnotationLedgerPanel.kt:470-471`（文件已改名） |
| 5 | StatusPill 与 statusLabel 两份 when | 仍存在：`:374-380` 与 `:404-412` |
| 6 | StatusBubbles 100ms | 仍存在：`:611` |
| 7 | FloatBall `animateScrollToItem` 越界 | 仍存在：`:1016` |
| 8 | MainActivity `popBackStack`+`delay(350)` | **已修复**（全文件无 350ms） |
| 9 | BrowserScreen 空 catch | **已修复**：`:140-141` 已补 `FileLogger.e` |

### 商店 P2 2 条：**2 条仍存在**
- 同 tab 双在飞请求陈旧覆盖：`StoreViewModel.kt:481-503` 无代次号
- 翻译管线重复全量跑：`:739-758` 每次 cancel 后全量重跑

**小结**：22 条核心历史项中仍存在 14、部分修复 2、已修复 3、无需改 1；补充 11 条中仍存在 6。**屎山类几乎全数存活，性能类修了一半** —— 说明历史「发版后队列」基本未执行，债务在累积（8 个核心文件相比 10-20 合计 +1300 行）。

---

## 七、巨型文件拆分建议（按职责切，不按行数硬切）

### `AIAgentViewModel.kt`（4469 行 / 52 依赖）→ 切 8 块
- `FileBrowserViewModel`（582-1070，约 490 行）：工作区/文件树/展开集/粘贴冲突，与 agent 主链路无耦合
- `SessionListController`（3940-4070 + 4139-4290，约 250 行）：回收站/删除/重命名/置顶/导入导出（顺带解决 P1-7 的三份重复清理）
- `SubAgentCoordinator`（1848-2110，约 260 行）：spawn/投递/通知/归档
- `HandoffController`（2955-3110，约 155 行）：交接判定/暂停/摘要/继续（P1-6 的落盘在这里做）
- `GoalLoopController`（3647-3800，约 150 行）
- `ChatSearchController`（656-712，约 60 行）
- `TokenUsageQuery`（3810-3893，约 85 行）
- 主体保留：`executeAgentRequestStream`（2352-2945）+ 队列 + 悬浮球镜像；`executeAgentRequestStream` 本身再拆「事件归并」+「状态收尾」两段

### `ProviderEditorScreen.kt`（3259 行）→ 切 5 块
- `ProviderConfigForm`（约 380-1130，750 行）
- `ProviderModelsPage`（约 850-1524，670 行）
- `ProviderKeysPage`（2152-2450，300 行）
- `ProviderDashboardParts`（2503-2905，400 行）
- `ProviderExtrasSheets`（2906-3259，350 行）—— 请求头与脚本参数两个 sheet 结构近乎一致，可合并为一个参数化组件

### `SettingsViewModel.kt`（3228 行）→ 切 6 块
- `ProviderSettingsViewModel`（约 400 行）
- `ContainerSettingsViewModel`（2482-2735，250 行）
- `McpSettingsViewModel`（1502-1683 + 778-853，250 行）
- `SkillAgentSettingsViewModel`（1684-1898，215 行）
- `UpdateCheckViewModel`（1915-2125，210 行）
- `TokenStatsViewModel`（约 400 行）
- init 里 60+ 个 `launch { xxxFlow.collectLatest { _xxx.value = it } }` 纯镜像样板（1050-1490，约 440 行）可大量删除（能直接暴露 repository StateFlow 就别镜像）

### `AIChatPanel.kt`（2534 行）→ 切 5 块
- `ChatItemBuilder.kt`（228-620，390 行纯函数，已有 3 个测试文件覆盖，独立后更好测）
- `ChatScrollController`（1245-1470，225 行，P1-10 在此）
- `ChatStreamingTail`（约 120 行）
- `ChatVoiceInput`（约 70 行）
- `ChatPanels`（1900-2070，170 行）
- 主体保留状态收集 + 骨架 + 悬浮层编排（约 500 行）

### `StatefulAgentWorkflow.kt`（1845 行）→ 切 5 块
- `AgentReducer.kt`（435-581 + 300-440，约 280 行）——**优先做，因为要补单测**
- `StreamAccumulator`（1136-1240，已独立成类）
- `WorkingSetRestore.kt`（约 240-520，280 行）
- `AuxiliaryLlmCalls.kt`（1354-1530，340 行）—— `generateTitle`/`generateCommitMessage`/`runCompletionVerification` 结构近乎相同，可抽 `callAuxiliary()` 收口
- 主体：`executeEvents`（591-1220）按 effect 类型拆 `handleCallLlm`/`handleToolBatch`/`handlePermission`

### `ChatDrawer.kt`（2281 行）→ 切 4 块
- `SessionListTab`（830-1398，570 行）
- `FileBrowserTab`（1399-1927，530 行）
- `DrawerSheets`（1928-2070 + 1100-1195，240 行）
- `DeliverablesTab`（2116-2281，165 行）

### `ChatInputBar.kt`（1799 行，66 行参数 + 60+ 实参）→ 切 5 块
按面板拆：`SlashCommandMenu` / `MentionMenu` / `QueuedRequestsPanel` / `DashboardPanel` / 输入框本体；参数用 `ChatInputBarState` 数据类收口

### `SettingsScreen.kt`（2302 行）→ 切 4 块
- 96 个状态收集下沉到分区组件后本文件缩约 100 行
- `SettingsNavigation`（470-620，150 行）
- `SettingsDialogs`（约 1600-2240，600 行）
- 主体保留 `SettingsMenu`（1952-2238）+ 分区路由

---

## 八、建议执行顺序（等批准）

**批次 1（数据安全，最高优先）**：P0-1 编码损坏、P0-2 消息级联删除、P0-4/P0-5 裸 deleteRecursively、P1-3 备份事务 —— **✅ 已全部完工（`ca9b975`）**
**批次 2（正确性）**：P0-3 子代理误报完成、P1-4 expandingPath、P1-5 历史失效、P1-6 暂停态落盘 —— **✅ 已全部完工（`e5d167c`）**
**批次 3（安全收口）**：P1-1 权限桥 token、P1-2 VNC mixed content（部分修，见 §三）、`shellQuote` 合并 —— **✅ 已完工（`e5d167c`）**
**批次 4（性能功耗）**：P1-7 会话态泄漏、P1-9 输入测量、P1-10 逐帧、P1-11 配额、P1-12、P2 全项、增量 P1-1 —— **✅ 已完工（`e5d167c` / `ec8590c`）**；P1-8 不修（并入批次 5）
**批次 5（架构重构，工程量大）**：层间依赖下沉、巨型文件拆分（建议单独立项、分批做）—— **部分完工（`3445941`）**：多选脚手架收口（增量 P1-2）、agent 域层与 memorygraph data 层解耦（增量 P1-4）、会话清理块第 4 份拷贝归并（增量 P1-3）已完成；**巨型文件拆分与全库层间依赖下沉仍待单独立项**。

**增量项归位（§九，2026-10-27 窗口新引入/恶化）**：
- 增量 P1-1（冷启动全量重释放 2.4MB 技能）→ 归**批次 4（性能功耗）**，改动小、收益直接。
- 增量 P1-2（多选脚手架三份重复）与增量 P1-3（第 4 份清理块）→ 归**批次 5（架构重构）**，与既有 P1-7/P1-8 合并做。
- 增量 P1-4（agent 域层直连 memorygraph data 层）→ 归**批次 5**，与「层间依赖下沉」同批。
- 增量 P2-5～12 → 归**批次 4** 的 P2 全项。

---

## 九、最近 18 小时增量审计（2026-10-27 11:50 窗口）

**时间窗**：2026-10-26 17:50 ～ 2026-10-27 11:50。窗口内提交 9 个（含 4 个纯笔记提交），代码改动约 **+5734/-597 行 / 98 个文件条目**，主题是**上游 v1.13 合并**（批次 A+B+C、D、E1）+ **记忆图谱 P1**。未提交改动只有笔记与新增技能资源（无 .kt）。

窗口内代码提交：

| 提交 | 时间 | 主题 |
|---|---|---|
| `749f810` | 07:20 | 批次 A+B+C：子代理链路/审批串号/思考档位/安全回显 |
| `5d6a690` | 08:32 | 批次 D：列表多选 + LazyColumn 改造 |
| `0b66afa` | 10:53 | 批次 E1：思考档位 400 白名单学习 + 关闭思考语义修正 |
| `24fc67d` | 10-26 20:41 | 记忆图谱 P1：AI 读写回路 |
| `c92a0c2` | 10-26 20:57 | 修记忆图谱 Hilt 循环依赖 |
| `c2a9dab` | 10-26 17:57 | 子代理启动提前返回不释放活跃位 |

### 本批已修复项（记录在案，**不要**再当问题重复报）

- 子代理并发名额改原子占位（`tryReserveSlots`/`releaseSlots` + `slotLock`）——修并行 `task(create)` 双双放行后超额创建。
- 新增 `DELETED` 事件——`task(del)` 后不再给已删会话补通知、烧一轮 LLM。
- **子代理权限只能收不能放**（`AgentMode.permissiveness`/`limitedBy`）——堵住 PLAN 下派 `mode: build` 子代理绕过只读（安全修复，语义正确）。
- 审批通知带 `requestId` + `PendingIntent` data 区分——修「点旧通知误批后来的请求」。
- `ShellCommandParser` 命令替换括号深度跟踪 + `blockers` 回显——已核实**不会降低严格度**（任何 `$(` 仍记为 blocker）。
- FTP 改随机口令 + `KeystoreCipher` 加密，识别旧固定口令 `123456`（含明文遗留的迁移判定，已核实 `decryptString` 对非密文原样返回）。
- 浏览器混合内容降为 `COMPATIBILITY_MODE`；`EditFileTool` 换行容忍（含刻意规避正则栈溢出，设计正确）。

### 增量 P1（4 条，均为本批引入或使既有问题恶化）

**1. 每次冷启动全量重释放 2.4MB 内置技能，IO/功耗被本批放大 ~10 倍**（`AIEditorApp.kt:317-320` + `ContainerInstaller.kt:101-116`）
- **问题**：`extractBuiltinSkills` 对 `assets/skills` 下**每个**技能先 `SafeDelete.deleteTree(dest)` 整目录删除、再递归全量重释放，且每次冷启动无条件执行。本批新增 6 个技能（`brainstorming`/`executing-plans`/`frontend-design`/`ui-ux-pro-max`/`verification-before-completion`/`writing-plans`，合计约 2.2MB，其中 `ui-ux-pro-max` 独占 **2.0MB**，内含 1.8MB `data/`），`assets/skills` 总量从约 0.2MB 涨到 **2.4MB**。触发场景：每次冷启动 → 12 棵目录树删除 + 约 2.4MB 写入 filesDir。
- **改法**：按技能目录的内容 hash 或 App `versionCode` 做「变化才释放」；或只对新增/变更的技能目录删除重建。
- **不修后果**：每次冷启动的闪存写入量与目录删除次数为原来的 10 倍以上，属性能功耗纪律禁止的无谓消耗，且损耗闪存寿命。

**2. 列表多选的页面侧脚手架在三个 Section 逐字复制**（`McpSettingsSection.kt:159-205`、`SkillsSection.kt:76-160`、`SubAgentsSection.kt:80-150`）
- **问题**：共享 UI 已抽进 `core/ui/ListSelection.kt`，但每个页面的状态脚手架被复制三份，且几乎逐字相同：`allKeys`(remember 映射) / `keyToEntry` / `selectedEntries`(remember mapNotNull) / `allSelected`(count 比较) / `LaunchedEffect(allKeys, selection.isActive) { retainOnly; if empty exit }` / `SelectionActionBar` + `if (allSelected) clear() else replaceAll(allKeys)`。
- **改法**：抽 `rememberListSelection(keys: List<String>)` 返回 `(selection, allSelected, selectedCount, toggleAll)`，或做一个 `SelectionScaffold` composable；同时把 `skillSelectionKey`/`subAgentSelectionKey`/`mcpSelectionKey` 收敛成同一个「作用域:名称」构造。
- **不修后果**：改一次要改三处；三处已开始漂移（MCP 的 retainOnly 只作用于搜索结果，另两处作用于全量）。

**3. 会话运行时清理块出现第 4 份拷贝（既有 P1-7 恶化）**（`AIAgentViewModel.kt:1893-1912`）
- **问题**：本批新增 `clearSubAgentRuntimeState(sessionId)`，与 `forgetSessions`（`:3952-3968`）、`deleteSessions`（`:3983-4007`）、`deleteSession`（`:4026-4046`）里的收尾块高度重复（都做 release / cancel job / 清 `_agentStates`/`_streamingTexts`/`_runningTools`/草稿），且四份**清的字段集合各不相同**——原 P1-7 记的「三处漏清 `_contextUsages`/`reasoningDurations` 等 7 个字段」问题在本批没有收敛，反而又多了一份需要同步维护的拷贝。
- **改法**：合并为一个 `clearSessionRuntimeState(sessionId, includeNotifications: Boolean)`，四call一处；字段集合以「全部会话态 map」为唯一基准。
- **不修后果**：新加的运行时状态永远有概率被漏清（漏 4 处中的任意一处都会只增不减）。

**4. agent 域层直连 memorygraph 的 data 层**（`SystemPromptProvider.kt:47`、`ContextCompactor.kt:52`）
- **问题**：本批让 `feature/agent/domain/prompt/SystemPromptProvider` 与 `feature/agent/domain/workflow/ContextCompactor` 注入 `com.aicode.feature.memorygraph.data.GraphRepository`（用全限定名直连，**无接口**）。这跨了 feature 边界，且是「领域层依赖另一模块的数据层」。
- **改法**：抽一个 domain 侧接口（如 `ConclusionReader`，只暴露 `validConclusions`/`extractConclusionsFromSummary`）放 memorygraph 域层，由 data 层实现；或把结论模型下沉 core。
- **不修后果**：agent 核心链路（系统提示词构建、压缩）从此绑死 Room/DAO，无法脱离数据库单测；记忆图谱重构会直接波及 agent 主链路。
- **备注**：`feature/agent/data/local/database/AgentDatabase.kt:27-29` 引用 `memorygraph.data.local.dao/entity` 是既有做法（同库跨模块建表），不计入本批新增。

### 增量 P2（8 条）

**5. `GraphDao.deleteEdgesOfWorkspace` 死代码**（`GraphDao.kt:78`）—— 全库 grep 零调用（`deleteEdgesTouching` 已被 `GraphRepository.kt:312` 使用，`deleteNodeById` 也被用）。改法：删除声明，或接上「同步时清理孤儿边」。

**6. 备份导出图谱两表用全量载入，与既有分页约定不一致**（`BackupManagerImpl.kt:383-389` + `GraphDao.kt:80-86`）—— `graphDao.allNodes()`/`allEdges()` 一次性把整表读进内存；同文件其它表都走 `PAGE_SIZE = 500` + `lastId` 分页。改法：沿用分页约定。不修后果：图谱变大后导出出现内存尖峰。

**7. `MemoryGraphScreen` 内两个相对时间格式化函数**（`:256 formatRelative` vs `:964 relativeTime`）—— 语义不同（「什么时候」vs「多久前」）但同在一文件，且都**每次调用新建 `SimpleDateFormat`**，而 `ConclusionsSection` 是在 `LazyColumn` 列表项里调用的。改法：合并到 `core/util` 的单一时间格式化模块并 `remember`。同型问题另见 `GraphSearchTool.kt:92`（每次 execute 新建）。

**8. `EditFileTool.findLineEndingTolerant` 缺首字符快判，最坏 O(n·m)**（`EditFileTool.kt:322-355`）—— 逐字符扫描在每个起点都重新比对整个模式；大文件（内容 n 大）+ 长 `old_string`（m 大）且**不匹配**时退化为 n×m 次比较。改法：先判 `content[i] == pattern[0]` 再进内层循环。不修后果：极端输入下 tool 长时间占用线程（**无栈溢出风险**，这点相对正则方案是正确取舍）。

> **✅已修 f11b2b7**（2026-10-27）。每个起点先判首字符再进内层；pattern 首字符为换行时不快判（\n 要按 \r\n / \n 两种形态匹配，交由内层处理）。

**9. `AgentDefinition.capabilitySummary` 的能力分组是硬编码三个集合**（`AgentDefinition.kt` companion 的 `READ_TOOLS`/`WRITE_TOOLS`/`EXEC_TOOLS`）—— 判定用 `allowsTool`（与 `filterToolNames` 同源，这点好），但**分组本身**是手写清单；将来新增写类工具若不补进 `WRITE_TOOLS`，摘要会显示「文件修改 否」而实际可写。KDoc 已自认此风险。改法：由工具自身的 `ToolCapability` 推导分组（写能力 → 写组）。不修后果：主代理据摘要把修改任务派给「显示只读」的子代理，白跑一轮。

**10. 圆角魔数两处硬编码**（`SettingsGroupComponents.kt:147-152` 与 `:126`）—— `LazyGroupItem` 与 `SettingsGroup` 各写 `14.dp`；同文件其它地方用 `Radius.lg` 令牌。改法：统一到 theme 令牌。

**11. 6 个新技能资源未入库，且 `ui-ux-pro-max` 的 1.8MB `data/` 对 AI 无用**（`app/src/main/assets/skills/{brainstorming,executing-plans,frontend-design,ui-ux-pro-max,verification-before-completion,writing-plans}`，git status 为 `??`）—— AI 只用得上 `SKILL.md`（16KB），`ui-ux-pro-max/data/`（1.8MB）仅供其 `scripts/` 检索脚本；`scripts/` 139KB 需在容器里跑。改法：入库前裁剪无用 `data/`，或改为按需下载。不修后果：APK 与冷启动释放量白涨（与 P1-1 同源）。

> **✅已入库 72a8716**（2026-10-27）。**原判断有误，已就地更正**：`data/` 不能裁——`scripts/core.py:15` 是 `DATA_DIR = Path(__file__).parent.parent / "data"`，`search.py` 的检索全靠这批 CSV，裁掉技能直接失效。故 `data/` 随包入库（压缩后仅 +391KB，自检实测），体积影响可接受。

**12. `PlanApprovalManager.resolve(choice)` 在锁外读 id（小竞态）**（`PlanApprovalManager.kt:78-81`）—— `pendingApproval.value?.id` 在锁外取，`resolve(id, choice)` 再进锁；两次之间条目可能已被取消并换成新请求，于是 `pending.remove(staleId)` 返回 null → 静默丢弃用户这次点击。触发概率低（需精确交错）。改法：在锁内取最早 entry 并直接完成。

> **✅已修 f11b2b7**（2026-10-27）。取最早条目与移除放进同一持锁区；完成逻辑抽 `completeResolved` 供两个 resolve 共用。

**13. `ConclusionParser` 用 `line.contains(标题)` 判段标题，正文提及即误切段**（`ConclusionParser.kt:41-44`）—— 段落标题判定是「行内含『关键决定与理由』/『教训与坑』/『未完成』」，不是锚定行首的编号标题。模型输出里若有一条正文写到「…之前的关键决定与理由已过期」，会被当成新段标题：前段被提前 `flush()`，后续行被归到错误标签下。影响有限（节点不会丢，`recordConclusion` 按名称幂等，只是标签错位或段落被切碎），故列 P2 而非更高级。改法：标题判定改为锚定行首（如 `NUMBERED_SECTION` 命中且含标题名才算段标题）。

> **✅已修 f11b2b7**（2026-10-27）。改为「剥掉编号与强调符号后行首匹配」；补 `ConclusionParserTest` 3 例（编号标题 / 正文误提不切段 / 粗体与无编号标题）。

### 本窗口核对后**排除**的两条（避免误记）

- `AIProvider.reasoningLevels` 每次请求写入（`StatefulAgentWorkflow.kt:654`）**不构成并发串档**：`getEffectiveProvider`（`:262`）每轮都 `aiProviderFactory.create(...)` 新建独立实例，无跨会话共享。
- `SubAgentEventBus` 的 `reservedSlots` 在 `emit(SPAWNED)` 无条件 -1 而 `release()` 不减（`:139-141`）：当前 SPAWNED **唯一**发射点是 `TaskTool.kt:268`，且该处必先 `tryReserveSlots`，记账自洽；仅当将来新增第二个 SPAWNED 发射点时才会瞬时少计一名额。标为「有条件」，不作为待修项。

### 流程性发现（非代码债）

- `24fc67d` 的验证只跑了 `compileArmsoloReleaseKotlin BUILD SUCCESSFUL`，但**该任务不触发 Hilt 组件生成**，正式包因 `GraphRepository ↔ ConclusionExtractor` 循环依赖构建失败，次日由 `c92a0c2` 修复（改用无注入的 `object ConclusionParser`）。教训已写入该提交信息：**涉及新增/改动 `@Inject` 构造参数时必须跑 `hiltJavaCompile` 或正式包构建**。本窗口内新增的 `@Inject` 类只有 `GraphSearchTool`（已随该循环修复被验证）。

---

### 窗口审计未覆盖（本窗口内）

- `SwipeToDeleteRow.kt`（+84/-46）的图形层改造只看了提交说明，未逐行核实。
- `BackgroundTerminalTools.kt`（+70/-16）的 filter/tab_ids 批量解析、`SubAgentDigester` 调用侧未逐行。
- `SkillsSection`/`SubAgentsSection` 只核实了多选脚手架部分，列表项与分组渲染未逐行。
- `MemoryGraphScreen.kt` 新增的 `ConclusionsSection`（:72-255）与图谱页未逐行审。
- `ReasoningLearning` 的正则白名单解析只做了反例推理（`tool_choice` 误学已被 `NONE` 过滤拦住），未穷举真实 400 文案。
- `749f810` 的 `AgentNotificationFormatter`/`ChatSession`/`ChatSessionDao`（空会话下沉 SQL）改动未逐行。

---

## 十、本次审计未覆盖

- `feature/git/presentation` 各 Tab 未逐行（只审了 `GitRepository` 命令构造与转义）
- `TerminalScreen`(625) / `StoreInstallScanner`(374) / `AnnotationCanvasScreen`(776) / `MemoryGraphScreen`(974) 未读
- `feature/backup` 远端存储实现（WebDav/S3/FTP）未读
- `agent/domain/tool` 其余小工具方法体、MCP 传输层未读
- `MainActivity` 560-1481、`FloatBallOverlay` 1-600 与 700-1467 未逐行
- 单测与代码逐条对应关系未核对（仅按文件名判断）
- `docs-site` 能力地图登记完整性未验证
- 本次未跑构建/单测（只读审计），所有结论均为静态代码核实

---

## 十一、批次 1~4 执行记录（2026-10-27）

**提交**：`ca9b975`（批次 1）→ `e5d167c`（批次 2~4）→ `ec8590c`（批次 4 增量与 P2）→ `507d28f` / `a574a38` / `09b7bb1`（档案标注与 qa 处置）。

**验证**：`:app:compileArmsoloReleaseKotlin` BUILD SUCCESSFUL；`:app:testArmsoloReleaseUnitTest` 全量 1438+ 例通过（新增 `SafeDeleteTest` 5 例、`FileEncodingTest` 9 例、`ShellQuoteTest` 5 例）；detekt 对全部改动文件 0 发现。

**qa / 性能审查处置**（子代理 `optimizer` 未安装，性能项由 qa 兼审）：
- 已修：`HandoffStateRepository.load` 吞 `CancellationException`；`FileEncoding` 暴露枚举共享 BOM 数组；`rm -rf` 缺 `--`；展开兑底协程堆积；会话删除未清落盘的交接状态（本批新增的残留面）。
- 已修（回退）：Wi-Fi 锁改回只持 `HIGH_PERF`——原以为 `LOW_LATENCY` 是超集，但它是前台低延迟语义，息屏后台保活不一定等价，不拿真机验证赌。
- 已改注释：VNC 退避的「跟手感不变」不成立（服务新起最长等 30s 才弹），改为如实描述取舍。

**遗留 P2 待办**（记录不修，真机或后续批次再定）：1. 会话运行时清理对每个被删会话全表扫 `reasoningDurationSessions`，批量删是 O(n²)（当前会话数量级下无感）。
2. `FileChangeHub` 全局 inotify 不再有 512 总闸，多个递归订阅各自可到 512，理论上可能触系统 `max_user_watches`（当前订阅数少）。
3. `extractBuiltinSkills` 版本戳：同 versionCode 下 assets 内容变化不更新；用户删掉某内置技能目录在同版本内不再自动补回（release 语义无碍）。
4. 滚动校准收尾的 1200ms 窗口内 md 异步解析导致的高度跳变要等末尾才纠正。
5. `ScheduledAgentViewModelProvider` 与主 ViewModel 若都调 `HandoffStateRepository.save`，后写会全量覆盖先写（当前仅主 VM 调用）。

**已知偶发测试失败（与本批无关）**：`SyncEngineIgnoreTest.upload_skips_custom_ignored_paths` 在全量跑时偶报
`UncaughtExceptionsBeforeTest`（kotlinx-coroutines-test 的协程泄漏检测），单独跑必过、重跑全量也过。
下批若再遇，先重跑一次再定。

**批次 5（架构重构）未开工**：层间依赖下沉、巨型文件拆分、`SettingsScreen` 状态收集下沉（P1-8）、增量 P1-2/P1-3/P1-4。

---

## 十二、optimizer 发版审查处置（2026-10-27，区间 171a4f5..HEAD）

**审查模式**：BATCH + R8 发布面。**总评：风险中**，无 P0。R8 / 资源收缩 / 主线程三面已核实无风险（技能为纯文本 assets 不入 R8；`isShrinkResources` 只作用于 `res/`；释放走 `Dispatchers.IO`）。

**已闭环（`21763c5` / `4803970`）**：
1. `ui-ux-pro-max/SKILL.md` 11 处脚本路径为 `${CLAUDE_PLUGIN_ROOT}/.claude/skills/...`，AiCode 容器内是死路径 → 改为 `/root/.aicode/skills/ui-ux-pro-max/scripts/search.py`，实测检索正常返回。
2. `writing-plans` / `executing-plans` / `brainstorming` 正文的 `superpowers:` 命名空间与 `docs/superpowers/` 路径在 AiCode 不存在 → 去掉命名空间，并补「AiCode 环境适配」说明（`subagent-driven-development` 与 `using-superpowers` 未随包发行，给出等价替代做法）。
3. 技能 `.sh` 脚本无执行位（assets 不保留权限）→ `extractAssetsRecursive` 复制后对 `.sh` 补 `setExecutable`。
4. 版本戳 `.builtin_version` 原在 `skills/` 内，会随备份（`AI_ASSET_DIRS` 含 skills）带走，同版本恢复时跳过释放 → 移到 skills 同级 `aicode/.builtin_skills_version`。
5. 段标题不识别 `##` / `###`（模型输出 Markdown 标题即丢结论）→ 前缀字符类补 `#`，三份正则共享 `PREFIX` 常量。

**记待排期（不修，均无发版阻断）**：
- 释放 93 个文件后触发一次技能变更广播（`WhileSubscribed` 无订阅者时零开销）。
- 技能清单 6→16 个，系统提示词注入净增约 2.2K 字符（≈800 tokens）/ 新会话；两大项是 `ui-ux-pro-max` 497 与 `build-mcp-server` 471，需瘦身只裁 description。
- `EditFileTool` 快判在 `pattern[0]=='\n'` 时主动跳过，而多行 old_string 占多数 → **实际收益仅限单行 old_string**，注释口径需按此修正（避免后人误判为热路径优化）。
- `extractAssetsRecursive` 用「`assets.open` 抛 IOException 判目录」的异常驱动控制流（本次 25 次异常，可忽略）。
- 商店 `refreshInstalled` 用 `listAllSkills()` 判已安装 → 内置技能被商店视为已安装；同名冲突面从 6 扩到 16（升级时内置版会覆盖同名用户/商店技能）。
- 首次启动并发 8+ 个 `appScope.launch`，IO 峰值约 2.8MB 写入 + 118 条目删除（推断，未实测）。
- `BuiltinSkillsAssetsTest` 只在 JVM 验证 frontmatter，R8 后包未验证（已知盲区）。
- dev 槽 `versionCode = gitCommitCount()+1`，开发期每次装新包都会重删重写 2.3MB（正式版仅升级时一次，属预期）。

**顺带核实（非本次引入）**：`assets/notes/` 287 文件 5.8MB 是 Vditor 编辑器前端资源，自 `fd1e02d`（2026-10-13）起既有，与本次无关。

---

## 十三、qa 审查 SendButton 手势修复补充（2026-10-28，均为预置行为，P2）

审查范围：停止后发送无反应 bug 的修复（`pointerInput(Unit)` 状态过期，`ChatInputBar.kt` SendButton）。审查结论可放行；以下 3 条为改动前已存在的预置行为，记录不修：

| # | 问题 | 位置 | 改法 |
|---|---|---|---|
| 1 | 短按手势 `waitForUpOrCancellation()` 返回 null（被父级消费，如滚动劫持）时被当作短按触发发送，滑动中误发送的理论路径 | `ChatInputBar.kt:1243-1247` | 对返回 null 直接 return |
| 2 | 语音按住循环中指针丢失（`?: break`）时不调 finish/cancel，`voiceHoldActive` 残留 true 到下次手势，浮层可能滞留 | `ChatInputBar.kt:1264-1267` | break 前补 cancel 分支 |
| 3 | `detectTapGestures` import 已无使用点 | `ChatInputBar.kt:21` | 删除 |
