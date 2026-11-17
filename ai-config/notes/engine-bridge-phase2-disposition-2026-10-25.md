# 引擎桥接二期配套（认证缺口 + 本体能力 MCP 化）· 实施与审查台账（2026-10-25）

## 交付内容（三笔提交，本地未 push）

| 提交 | 内容 |
|---|---|
| `a866154` 批A | 认证缺口：`ClaudeCodeBridge.authHint()` 容器内探测 `~/.claude/.credentials.json`（不拉起 claude、不耗 token）；发送分流点（`AIAgentViewModel.executeAgentRequestStream`）预检，未登录直接发带引导的 Failed，不空跑回合；引擎卡片显示「已安装 · 未登录」+「去登录」按钮（未登录禁选）；App 内 Anthropic key 经 `extraEnv` 透传 `ANTHROPIC_API_KEY`（非官方 baseUrl 透传 `ANTHROPIC_BASE_URL` 支持反代）——配置了 key 则认证检测直接放行 |
| `82ee23a` 批B | 本体能力 MCP 桥：随包新增 `aicode-bridge.mjs`（零依赖 node，随 `--mcp-config` 挂载，server 名 `aicode_bridge`）。工具 8 个：memory_list/read/write、rules_read、skills_list/skill_read（容器文件直读，路径白名单 + realpath 防穿越/防名称注入）；ledger_list、ledger_advance（经权限桥 TCP 通道新增 `kind:"data"` 分流到 App 侧查 Room：标注+修复账本合流、状态推进与本体 fixledger 工具同语义只能前进） |
| `6ba2306` qa 处置 | 见下 |

## 验证

- 编译通过；mjs 经 node --check + stdio 运行时冒烟（initialize/tools/list 8 工具/memory_write 落盘/memory_read 与 skill_read 的路径穿越与名称注入拒绝/ledger 断连优雅报错）。

## qa 审查（子代理 65a3f558）处置

- **P0 ledger_advance 假成功**（advanceLinked 返回 null 被吞成 ok=true「已推进」；mjs/App 放行 SENT 但 advanceLinked 只接受 FIXING/REVIEW，必然假成功）→ **已修复**：null/异常如实 errData；enum 与校验收窄到 FIXING/REVIEW。
- **P1-1 useFullUrl 提供商透传出错误 ANTHROPIC_BASE_URL**（baseUrl 是完整 endpoint，剥 /v1 无效 → 双重 /v1/messages）→ **已修复**：useFullUrl=true 只透 key 不透 URL。
- **P1-2 认证探测硬编码 /root** → **已修复**：改为容器内 `"$HOME"` 展开，与 PathHomeResolver 约定一致。
- **P2 authHint 无缓存**（每条消息拉一次容器进程）→ **已修复**：60 秒 TTL 缓存。
- **P2 容器内自配 key 场景误报** → 引导文案补「若已自行配置 ANTHROPIC_API_KEY 可忽略」。
- **P2 多 Key 恒透第一把**（与 App 内轮换语义不同步）→ 代码注释说明（透传场景多为单 key 反代，影响小）。
- **P2 mjs symlink 逃逸（纵深防御）** → resolveMemory 改 realpathSync 归一后比对。
- **P2 AUTH_TOKEN 型反代不透传**（claude 用 x-api-key）→ 能力文档标注「仅支持 x-api-key 型端点」。
- qa 已核无误：kind=data 分流在 token 鉴权后无绕过；依赖无环；旧权限裁决路径逐行未动；Failed 引导文案直达 UI；UI 参数链完整；strings 中英一致。
- 记待排期：memoryList/skillList 的 scope 判定在「项目根嵌套于 AICODE_HOME 下」布局会误标 global（当前布局不触发）。

## 口径变更（已同步）

- 引擎页限制提示与能力登记改为：专家团等本体工作流仍不支持；**记忆/规则/技能与标注·修复账本已可用**（aicode_bridge 工具，AI 可 ledger_advance 推进 FIXING/REVIEW）。

## 待办

- OpenCode/Codex 引擎适配（三期，未开工）：每个引擎需调研 headless 协议 → Bridge 翻译层 → 权限/数据桥复用（PermissionBridgeServer 的 kind 分流可直接复用）→ 引擎卡片。
- 真机验收：①配置 Anthropic key 后切 CC 引擎免登录直接对话；②未配置时发消息收到登录引导而非裸报错；③CC 会话里让 AI 查记忆/技能/账本（ledger_list）并修完推进状态（ledger_advance）；④专家团入口在 CC 会话不可用的提示仍在。
