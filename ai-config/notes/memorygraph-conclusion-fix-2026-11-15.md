# 记忆图谱「结论」自动沉淀修复（2026-11-15）

## 症状

- 记忆图谱默认页「结论」长期空白；`graph_search` 返回「图谱中还没有结论」。
- 用户原话：「记忆图谱 结论 这个功能好像坏了没有再全自动工作起来了」。

## 根因（已核实）

1. **抽取只挂在 App 上下文压缩之后**（`ContextCompactor.compactOnBackground`）。
   Claude Code / Codex / OpenCode 的上下文由 CLI 进程自己管理，App 侧从不触发压缩，
   因此全程使用外部引擎的项目永远抽不到任何结论。本项目会话正是 Claude Code。
2. **CLI 侧图谱检索用错工作区路径**。桥脚本把容器路径（`/root/workspace`）作为
   `projectRoot` 传给 App，而图谱按 `chat_sessions.workspacePath`（宿主路径）存库，
   `graph_search` / `recall_search` 永远查不到同一项目的数据。

## 修复（提交 `0d3f653`、`6982478`）

- 新增 `AgentWorkflow.extractRecentConclusions(sessionId)`：
  - 仅外部引擎；开关 `graphExtractEnabled` 先判，关闭时零后台工作。
  - 每完成 6 个回合触发一次；距上次抽取不足 12 条新增历史则跳过；
    只摘要最近 `min(40, 新增+8)` 条镜像历史（不落库、不改会话历史）。
  - 摘要走 `ContextCompactor.buildHandoffSummary`（九段式），解析后写入图谱；
    调用记录 kind=`graph_extract`，不混入压缩统计。
  - 失败只记日志，绝不影响对话；未配置可用供应商时降为 debug。
- `PermissionBridgeServer` 的工具上下文改用**桥接会话自己的 workspacePath**，
  会话缺失时才按 `WorkspacePathMapper` 兜底。
- 顺带处置 qa 审查：子代理收尾单点去重、取消/异常路径 abandon、重放不重复建子会话等。

## 前置条件（重要）

外部引擎的自动沉淀需要 App 内**可用的 AI 供应商**（会话绑定的 provider/model，
或全局默认 provider）。只用 CLI 自身登录、App 内没有任何可用 Key 时，自动沉淀会静默跳过，
此时结论页仍为空——这是预期行为，不是 bug。

## 未验证项

- 真机运行验证（需重新打包安装）：外部引擎 6 回合后结论是否落库、`graph_search` 是否命中。
- Claude Code 后台 Task 的 `<task-notification>` 实际配对行为（沿用既有实现，未改动协议解析）。
- Codex `collab_tool_call` 的 `result/output/last_message` 字段名（按常见写法兼容，待实测）。

## 相关提交

`da811fe`（外部引擎子代理建子会话）、`169ffd5`（导出全部口令配置）、
`0d3f653`（结论沉淀闭环 + 路径修复）、`6982478`（qa 收尾项）、`9075db5`（凭据文案/入口）。
