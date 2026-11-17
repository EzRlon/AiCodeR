---
name: engine-bridge-phase2
description: 引擎桥接二期配套已交付（6ba2306 待真机验收）：认证预检+key透传、本体能力 MCP 桥 aicode_bridge；台账位置与三期待办
---
# 引擎桥接二期配套（2026-10-25 交付）

- 已提交至 `6ba2306`（批A a866154 认证预检+key透传；批B 82ee23a 本体能力 MCP 桥；6ba2306 qa 处置），未 push，待真机验收。台账：`ai-config/notes/engine-bridge-phase2-disposition-2026-10-25.md`。
- 批A：`ClaudeCodeBridge.authHint()`（容器探测 ~/.claude/.credentials.json，60s TTL）+ 分流点预检 + 引擎卡片未登录态；App 内 Anthropic key/baseUrl 经 `extraEnv` 透传（ANTHROPIC_API_KEY/ANTHROPIC_BASE_URL，useFullUrl 只透 key，仅支持 x-api-key 型端点）。
- 批B：随包 `aicode-bridge.mjs`（server 名 aicode_bridge，随 --mcp-config 挂载）暴露 8 工具：memory_list/read/write、rules_read、skills_list/skill_read（容器文件直读，realpath 白名单）、ledger_list/ledger_advance（经 PermissionBridgeServer 新增 `kind:"data"` 分流查 App Room；advanceLinked 只接受 FIXING/REVIEW，null 必须如实报错——qa P0 教训：**包装型转发通道对底层返回 null/异常不可吞成成功**）。
- 坑：向 PermissionBridgeServer/CLI 安装命令这类「拼接 shell/协议转发」加功能时，先核对底层语义（advanceLinked 的 null 语义、useFullUrl 的完整 URL 语义）再决定包装。
- 待办：三期 OpenCode/Codex 适配（未开工）；scope 判定嵌套布局误标 global 记台账。