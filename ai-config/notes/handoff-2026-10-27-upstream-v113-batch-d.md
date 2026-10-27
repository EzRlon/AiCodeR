# 交接：上游 v1.13 合并（2026-10-27 终态，批次 A/B/C/D 全部完成）

## 一句话状态

上游 v1.13.0-rc1 采纳清单已落地**四个批次**并全部提交（`749f810` A+B+C、`5d6a690` D）。编译、单测、detekt、正式包自检全绿。下一会话只剩 E1/E2/F 三项，其中只有 E1 可直接开工。

## 仓库与基线

- 项目根：`~/workspace/AiCode`（**不是** `~/workspace`，后者是另一个 git 仓库）
- 分支 `dev`，HEAD `5d6a690`（161 提交）
- 上游 tag：`v1.13.0-rc1`；本地解压 `/tmp/up113/AiCode-1.13.0-rc1`（容器重启会丢，需重新取）
- 取上游走代理前缀：`https://gh-proxy.com/https://github.com/jieapi/AiCode/archive/refs/tags/v1.13.0-rc1.tar.gz`（直连 GitHub 被 TLS 重置）
- 我方上次合并基线：`539c922`（上游 2026-10-17 快照）

## 已完成批次

### 批次 A（子代理链路 + 文件/安全）— `749f810`

- `SubAgentEventBus` 槽位原子占位（`tryReserveSlots`/`releaseSlots` + `slotLock`），修并行 `task(create)` 超额创建
- 新增 `DELETED` 事件：`task(del)` 后不再给已删会话补送通知
- `AgentMode.permissiveness`/`limitedBy` + `SessionUseCase.resolveChildMode`：子代理权限只能收不能放（堵 PLAN 下派 `mode: build` 绕只读）
- `EditFileTool` 逐字符扫描容忍 LF/CRLF，替换沿用原换行
- 备份空口令禁止上传；FTP 随机口令 + `KeystoreCipher` 加密，识别旧 `123456`
- 浏览器混合内容降 `COMPATIBILITY_MODE`；超时先判、`CancellationException` 重抛

### 批次 B（审批串号 + terminal + 思考档位）— `749f810`

- `PlanApprovalManager` 单槽改多槽 `LinkedHashMap`，请求带 `id`；通知 PendingIntent 的 `data` 带 kind/requestId/choice
- `terminal` read 支持 `filter` 正则；close 支持 `tab_ids` 批量
- `AIProvider.reasoningLevels`：OpenAI 按模型声明透传（max/xhigh 不再压成 high）；Gemini budget=0 不带 `includeThoughts`

### 批次 C（子代理摘要 + 能力标注 + 效率/安全）— `749f810`

- 新增 `SubAgentDigester`：完成通知带结论摘要（800 字上限）+ 改动文件；按工具映射路径参数名（`generateImage` 用 `output_path`）
- `AgentDefinition.capabilitySummary`：清单每行标注读/写/命令；缓存 key 带 mode + `definitionsVersion`；白名单未列 `messageParent` 也保留
- 空会话查询下沉 SQL（`NOT EXISTS` + `deletedAt IS NULL`）
- `ShellCommandParser.blockers`：拦截原因回显具体构造；补命令替换括号深度跟踪
- 设置页 `SaveableStateProvider` 接住分区滚动位置

### 批次 D（列表多选 + LazyColumn）— `5d6a690`

- 新增 `core/ui/ListSelection.kt`：多选状态机 + 顶栏开关 + 操作条 + 批量删除确认
- **修 `SwipeToDeleteRow` 组合期读 `offsetX.value`**：按钮宽度固定 104dp，露出进度全在 `graphicsLayer` 现算；加多选参数
- `LazyGroupItem`：逐项画卡片、首末项圆角、项间补分隔线
- 技能 / 子代理 / MCP 三类列表 `Column+forEach` → `LazyColumn`，接多选批量启用/停用/删除（MCP 另加重连）
- 提示词页无需改：早已是 `LazyColumn` + 拖拽

## 验证基线（下一会话改前可复跑对照）

```
sh tools/build-with-lock.sh :app:compileArmsoloReleaseKotlin
sh tools/build-with-lock.sh assembleArmsoloRelease --no-configuration-cache
sh tools/release-selfcheck.sh app/build/outputs/apk/armsolo/release/app-armsolo-release.apk
```

- 单测 **156 用例 0 失败**：`SubAgentDigesterTest` 10、`AgentDefinitionTest` 22、`ShellCommandParserTest` 15、`ToolPermissionPolicyEngineTest` 72、`SessionUseCaseModeTest` 5、`BackgroundTerminalToolsParsingTest` 7、`ContextCompactorTest` 19、`ListSelectionStateTest` 6
- detekt 改动文件 0 发现
- **dex `classes.dex` 64769 / 65536，余 767**（批次 A+B+C 各 +34、批次 D +64）

## 剩余工作

### E1（可直接开工，小、低风险）

摘上游思考能力子系统里的两块独立修复，不搬整个 resolver：
- 400 报错文案白名单学习（上游 `ReasoningLearning`）
- Gemini / Anthropic 关闭思考语义修正

上游位置：`/tmp/up113/AiCode-1.13.0-rc1` 下的 `ReasoningLearning`、`ReasoningResolver` 等文件（需先确认本地解压还在）。

### E2（建议单独立项出方案，大）

整体替换为上游 `ReasoningResolver` + `ReasoningCapabilityTables` + `ReasoningPlan/Wire`（6 个新文件）。
**硬约束**：上游迁移号 63（`add_llm_call_reasoning_wire`）已被我方 63 号 `add_session_team` 占用，我方号段到 73，必须重编号到 **74** 并同步 `SCHEMA_VERSION`。
**dex 风险**：余量仅 767，6 个新文件 + 全量替换大概率越界，开工前必须先算 dex 或先拆 dex。

### F（需用户先决策，可能不做）

上游子代理模型档位 tier 与我方 `ModelRoutingTable`（FAST/BALANCED/STRONG 任务路由）功能重叠。评审结论：不建议并存两套，要做就合并成上游 tier 语义，属改造而非搬运。**未获用户确认前不要动手。**

## 硬约束（务必遵守）

1. **dex 余量 767**：任何新增代码前先评估，每批构建后必须核 `release-selfcheck.sh` 的 dex 数。越界需拆 dex 或裁依赖。
2. **构建一律** `sh tools/build-with-lock.sh <任务>`（锁 + musl 前缀已内置）；正式包必须加 `--no-configuration-cache`。
3. **多会话共用工作区**：`git status` 里有另一会话未提交的笔记，**不要提交、不要回滚**：
   - 6 个已跟踪笔记的修改（`devenv-one-tap-disposition` / `engine-bridge-phase3-*` / `engine-capability-sync-*`）
   - 11 个未跟踪笔记（`debt-audit-2026-10-27` / `deepseek-harness-borrowing` / `enhancement-program` / `handoff-2026-10-24/25/26` / `kimi-review` / `review-disposition` / `review-package` 等）
   - 可提交范围 = `app/src/**` + `ai-config/notes/build-times.csv` + 本批自己的笔记
4. **交付包版本号**：当前产物 versionCode 161，构建于 `909a745`（批次 D 提交前）。下次发版重新构建即可自然对齐。

## 环境坑（本会话验证过）

- **`search` 工具不可用**：容器未装 `rg`，调用直接报 `RG_MISSING`。改用 `grep -rn`（注意 BusyBox grep 无 `--include`）或 `list`/`readFile`。
- **PLAN 模式禁用 Bash/terminal**：只读探索只能用 `readFile`/`list`，`search` 又不可用，定位文件要靠 `list -R` 逐层翻。
- **Bash 安全防护会拦含 `$(...)`、`|` 管道到多命令的写法**：写命令避免命令替换与子 shell。
- **`editFile` 批量编辑任一失败会整体回滚**：import 行常因上下文变动匹配失败，改 import 前先 `grep` 确认现状。
- **qa 子代理本会话两次静默失效**：`lastOutput` 始终是初始请求、`updatedAt` 停在启动时刻即为铁证。此时不要空等，停掉后自行审查。
- **编译耗时**：增量 Kotlin 1–6 分钟，R8 正式包约 10 分钟。用 `terminal` + `notify=true` 等通知，不要轮询。

## 明确未采纳（勿再评估）

提示词通用化重写、免责声明/隐私门控、供应商预设增删、`api.official.json` 全量刷新、应用内更新、文件树「当前容器」根节点、容器镜像锁定模式、压缩阈值 90→85（我方已 70%）、gradle 堆 7168m、Alpine rootfs 升级、远程终端 1 秒轮询退出标记、MCP OAuth。理由逐条见 `upstream-v1.13-review-2026-10-26.md`。
