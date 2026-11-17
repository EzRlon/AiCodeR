# 能力全域打通 · 批一（MCP 映射 + 图片/附件传递）（2026-10-25）实施台账

设计依据：engine-capability-sync-design.md（自动同步层，引擎原生体系优先）。

## 交付内容（提交 b2dd5ad，本地未 push）

### A. 用户自装 MCP 自动映射到三引擎（零写用户配置文件）

| 引擎 | 载体 | 说明 |
|---|---|---|
| Claude Code | 桥每回合生成的 mcp-config.json 内追加 | 该文件本就是 App 生成区，天然无冲突 |
| Codex | `-c mcp_servers.<name>.*` 内联（与 aicode_bridge 同路） | 不碰 config.toml |
| OpenCode | 并入已在用的 OPENCODE_CONFIG_CONTENT | 不碰 opencode.json |

统一规则：只映射 enabled 且名称合法的服务器；OAuth 型（auth != null）无法委托凭据，跳过并记日志；与内置桥同名（aicode_bridge/permission_bridge）跳过防覆盖；stdio 映 command/args/env，HTTP 映 url/headers；disabledTools/enabledTools 工具过滤暂不透传（引擎侧无对应机制，记待办）。

### B. 图片/附件传递（修复标注汇总丢图）

- Claude：stdin 用户消息改 content 块数组（text + image base64 块）。
- Codex：图片 base64 解码落盘 `~/.aicode/bridge/images/img-<sid>-<n>.<ext>`（回合开始先清旧图）→ `-i` 传入。
- OpenCode：同落盘 → `-f` 传入（单文件 ≤8MiB 防线，超限跳过记日志）。
- 附件（AgentAttachment，自带容器路径）：三桥统一在正文末尾追加 `[附件：名（mime，大小），容器路径：…]` 行，引擎用自身文件工具读取。
- ViewModel：三个 runTurn 调用传入 agentContext.inputImages + inputAttachments。

## 验证

- 编译：qa 修复后通过（首轮发现 5 处：缺 import/括号错位/attachmentSuffix 位置/suspend 上下文，均修）。
- detekt 4 文件：0 findings。
- qa 审查（子代理 c18d33ae）：处置如下：
  - P0-1 attachmentSuffix 实例成员被静态调用 → **已修**（改文件顶层 internal fun，三处调用同步；qa 报告生成时本项已在修）。
  - P1-1 图片清理竞态（删其它会话在用图）→ **已修**：清理按 `img-<sessionId>-` 前缀过滤（两桥）。
  - P1-2 图片落盘在主线程 → **已修**：writeImageAssets 改 suspend + withContext(Dispatchers.IO)（两桥）。
  - P2-1 Codex TOML 零转义 → **已修**：tomlString 助手转义 \\ 引号与换行；qa 已核无命令执行面（argv 透传）。
  - P2-2 enabledTools/disabledTools 不透传 → **保持并标注**（引擎侧无对应机制，见上文 A 节已知差异）。
  - P2-3 OpenCode 吞错与另两桥不一致 → **已修**：去掉 runCatching，统一回合失败语义。
  - P2-4 Claude 图片无大小防护 → **已修**：base64 长度预检（约 4.5MiB 上限），超限跳过记日志。
  - 其余（suspend-in-buildList、content 块格式、attachmentSuffix 注入面、ViewModel 传参、无 MCP/无图回归）qa 已核无误。
  - qa 未验证项（真机验证）：claude stream-json 对 image 块的接受度、codex -i / opencode -f 实际行为、opencode environment 键名。

## 待真机验收（并入 vCode134）

1. App 配一个 stdio MCP（如已有的推荐服务器）→ 切任一 CLI 引擎发消息，AI 能列出并调用该 MCP 工具。
2. 标注汇总（含图）发给 CLI 引擎会话，AI 能看到图并按标注处理。
3. 带附件（文件）发送，CLI 引擎能按容器路径读到内容。
4. OAuth 型 MCP 不映射但不报错；未配置 MCP 时三引擎行为与之前一致。
