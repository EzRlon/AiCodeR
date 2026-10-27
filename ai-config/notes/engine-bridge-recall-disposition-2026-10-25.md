# 引擎二期收尾 · recall 进 MCP 桥（2026-10-25）实施台账

## 交付内容（提交 820b3d3，本地未 push）

aicode_bridge 新增 `recall_search` 工具：CLI 引擎（Claude Code / Codex / OpenCode 通用）可跨会话全文检索历史档案（含被压缩归档的原文），复用本体 RecallTool 的 FTS4 + CJK LIKE 降级逻辑，经 PermissionBridgeServer `kind:"data"` 通道回调 App 查 Room。

| 文件 | 内容 |
|---|---|
| `ClaudeCodeBridge.kt`（AICODE_BRIDGE_SCRIPT_SOURCE） | TOOLS 加 recall_search（query 必填 / scope session·workspace·all / limit），路由进 askApp 数据通道并附 projectRoot（mjs env 的 AICODE_PROJECT_ROOT，供 scope=workspace 判定） |
| `PermissionBridgeServer.kt` | 注入 RecallTool；handleDataRequest 加 recall_search 分支：用桥接请求的 session（= App sessionId）+ projectRoot 构造 AgentContext，调 RecallTool.executeWithContext，Success 文本 → okData、Error → errData、Partial → message |
| strings / 能力文档 | engine_limits_hint（中英）与 capabilities.md、65-capabilities.md 加入 recall_search |

## 验证

- mjs 抽出后 node --check 通过；stdio 冒烟：initialize OK、tools/list 9 工具（含 recall_search）、recall_search 断连优雅报错（ECONNREFUSED → 错误文本）。
- detekt（PermissionBridgeServer + ClaudeCodeBridge）：0 findings。
- 编译：通过。
- qa 审查（子代理 79e70b75）：可放行，处置：
  - P2-1 docs-site 三期条目漏 recall_search → **已补**。
  - P2-2 projectRoot 非字符串时 jsonPrimitive 抛异常致 mjs 等满超时 → **已修**：改 as? JsonPrimitive 安全转换；顺带消除 buildMap 冗余拷贝（nit）。
  - Object.assign 顺序（调用方显式 projectRoot 覆盖 env 值）→ qa 判定可接受，保持现状。
  - 其余（session 语义、鉴权边界、RecallTool 无副作用、schema 对齐、Partial 映射）已核无误。

## 语义与边界

- scope=session 用桥接会话 id（即 App 会话），与内置引擎同语义；scope=all 为默认。
- 检索为只读，不经权限弹窗（与账本查询同通道同纪律）。
- mjs 工具数 8 → 9；三引擎共用同一 mjs 源，无需逐引擎改动。

## 待真机验收

并入 vCode134 清单：任一 CLI 引擎会话让 AI「查一下之前会话里关于 xx 的原文」，应返回档案命中列表。
