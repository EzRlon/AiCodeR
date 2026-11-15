# 三引擎编排批次 · 审查处置（2026-11-15）

> 范围：`6982478..HEAD`（a07e5d4 编排守则+任务板、bdde775 docs、5d7f54a 审查修复、46ceb6a Codex 指令解析修复）。
> 流程：qa 独立复审 → 修 P0/P1 → 编译 ✅ / 全量单测 1838 ✅ → optimizer 复审（无 P0）。

## 一、qa 结论与处置

| 级别 | 问题 | 落点 | 处置 |
| --- | --- | --- | --- |
| P0 | `config.toml` 存在但无该键时，被当成「无法解析」→ Codex 上编排/语言/知识注入全跳过（回归） | `CodexBridge.kt:319` | ✅ 46ceb6a：`present=false` 只下发 App 指令 |
| P1 | 多行 TOML `"""..."""` 被单行正则解成空串 → 反向覆盖用户指令 | `CodexBridge.kt:808` | ✅ 46ceb6a：多行/字面量/非法转义一律 `text=null` 跳过注入 |
| P2 | 注释中的键名被误判为已配置 | 同上 | ✅ 一并修：行首非注释匹配 |
| P2 | 转义顺序错误（`C:\\temp` 变制表符） | 同上 | ✅ 一并修：单遍解码 + `\u/\U` |
| P2 | 进程级 `PERMISSION_BRIDGE_TOKEN` 传 sessionId | Claude/Codex 桥 extraEnv | ✅ 一并修：统一 bridgeToken |
| P2 | OpenCode 未使用 `java.io.File` import | OpenCodeBridge.kt:15 | ✅ 一并删 |

解析逻辑抽出为纯函数 `CodexUserInstructions.kt`，新增 12 例单测（`CodexUserInstructionsTest.kt`）。

## 二、optimizer 结论（BATCH，无 P0）

已确认无新增常驻轮询/动画/唤醒；任务板仅在工具调用时读写；内置引擎零改动；三引擎注入已收敛到 `CliOrchestrationPrompt` + `OrchestrationPolicy`。

### P1（下一批修，未在打包临界期改）

1. **OpenCode 首次资产写跑在 Main**：`OpenCodeBridge.kt:564-588` 的 `writeBridgeAssets` 未包 `withContext(Dispatchers.IO)`，由 `eventFlow.collect`（Main）调用；本批新增的 `orchestration-context.md` 原子写+fsync 也在这条路径。另两桥已包 IO。
2. **OpenCode 编排指令文件全局共享**：`OpenCodeBridge.kt:581-586,700` 固定路径 `bridge/orchestration-context.md`，内容随会话（专家团）变化；并发会话互相覆盖，或无内容时删掉他人文件。建议文件名带 sessionId。
3. **两套项目任务账本**：`OrchestrationBoardService.kt:34-61`（`board.json`，外部引擎）与 `TaskLedgerTool.kt:24-33`（`task-ledger.md`，内置引擎）互不可见。短期文档明示分工，中期二选一。
4. **团长提示双份模板**：`OrchestrationPolicy.kt:38-76` 与 `SystemPromptProvider.kt:184-255` 各自拼装；外部版缺「团队既有结论」与 qa 兜底。建议抽公共 builder。

### P2（待排期）

5. `CliOrchestrationPrompt.kt:46-49,64` 每个成员各调一次 `findIncludingDisabled` → N 次全量扫盘。
6. `CodexBridge.kt:317,802-807` 每回合读一次 `config.toml`，可按 mtime/size 缓存。
7. `OrchestrationBoardService.kt:43,45,260` history 仅按条数封顶，单任务可达 ~180KB；需按字节截断。
8. `OrchestrationBoardService.kt:124-135` list 无分页，最多 300 条回灌上下文。
9. `OrchestrationBoardService.kt:213-271` 状态机允许任意互转，doing 可无 owner；建议转移表。
10. `OrchestrationBoardService.kt:289-311` `save` 未显式 `withContext(IO)`（当前调用方恰好安全）。
11. `OrchestrationBoardService.kt:313-320` 子串命中多条时文案误报「找不到任务」。
12. `OrchestrationBoardService.kt:150-180,61` `deps` 解析两次；prettyPrint 放大文件。
13. `PermissionBridgeServer.kt:237-245` KDoc 与已删兜底不符；`ClaudeCodeBridge.kt:1138` 仍传死参数。
14. `CodexBridge.kt:325-329,391-407` 格式残留；extraEnv 与 mcpEnvOverride 重复拼 5 个键。
15. `CodexUserInstructions.kt:20-26` 不识别 `[profiles.x]` 表作用域，profile 内指令会被当顶层拼接。
16. `OpenCodeBridge.kt:581-586` 内容未变也无条件重写（与 P1-2 合并修）。

## 三、仍未验证（真机/环境）

- OpenCode MCP 配置里 `environment` 字段与官方 schema 的一致性、是合并还是替换父进程 env（本容器无网络，无法核对文档）。
- Codex 用户 `config.toml` 的真实形态（多行串/profile 段）未在真机采样。
- 任务板落点、三引擎注入是否生效，需真机各跑一轮确认（见交接文件 P1）。
