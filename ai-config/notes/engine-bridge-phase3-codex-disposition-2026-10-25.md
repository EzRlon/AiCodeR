# 三期引擎适配 · Codex 批次（2026-10-25）实施台账

## 交付内容（未提交 → 提交后补哈希）

新增 Codex 引擎桥接，模式与 ClaudeCodeBridge 一致：每回合 headless 拉起 CLI，stdout JSON 翻译成 AgentEvent，下游落库/UI/停止/队列复用本体链路。

| 文件 | 内容 |
|---|---|
| `CodexBridge.kt`（新增，510 行） | `codex exec --json --skip-git-repo-check --sandbox workspace-write [-C 项目根] [resume <thread_id>] -`（提示词走 stdin）；ThreadEvent JSONL → AgentEvent；thread_id 映射持久化 `~/.aicode/bridge/codex-threads.json`（内存缓存 + 落盘）；MCP 经 `-c mcp_servers.aicode_bridge.*` 内联挂载（与 ClaudeCodeBridge 共用 AICODE_BRIDGE_SCRIPT_SOURCE，kind:data 数据通道回调 App）；认证预检（`~/.codex/auth.json` 探测 or 透传 App 内 OpenAI key：OPENAI_API_KEY / OPENAI_BASE_URL 非 api.openai.com 且非 useFullUrl）；60s 认证缓存 / 5min 可用性缓存 / 30min 空闲看门狗 / 取消杀进程 / 续接失败转全新回合（ResumeSessionMissingException 复用） |
| `AgentEngines.kt` | 加 `CODEX = "codex"` |
| `AIAgentViewModel.kt` | 注入 codexBridge；executeAgentRequestStream 引擎分流 if → when(currentSession?.engine)，codex 分支同 claude（不可用 Failed / 认证预检拦截 / runTurn resume=true）；isCodexAvailable / codexAuthHint / installCodex |
| `ScheduledAgentViewModelProvider.kt` | 手工构造位置参数插入 codexBridge（与构造函数一致） |
| `ChatModelSheet.kt` | 胶囊 CC·/Codex·（codex 空模型显示 default）；EnginePage 加 Codex 卡（探测/认证态/安装/去登录/管理页）；CliModelPage 按引擎出模型列表：codex = default / gpt-5.3-codex / gpt-5.1-codex-max / gpt-5-codex-mini（default 不传 -m，跟随 CLI 配置）；弹窗初始页对任意 CLI 引擎落引擎页 |
| `ChatInputBar.kt` / `AIChatPanel.kt` | checkCodexAvailable / checkCodexAuth / onInstallCodex 传参；sessionCliModel 判定放宽为任意 CLI 引擎 |
| `strings.xml`（中/英） | engine_codex_not_authenticated / engine_codex_unavailable / engine_codex_hint / engine_codex_install_started；engine_resume_hint 文案通用化为「CLI 侧会话」 |
| 能力文档 | docs-site/docs/guide/capabilities.md + assets/prompts/65-capabilities.md 各加 Codex 条目 |

## 协议验证（开发容器实测 codex-cli 0.162.1，非纸面）

- `-c mcp_servers.<name>.command/args` 内联挂载**确认会拉起 MCP server**（探针脚本被 spawn）。
- `CODEX_API_KEY` env 透传有效（fake key 一路走到 API 调用，报 403 地区限制——说明链路通）。
- 实流事件序：`thread.started`（认证前就发，失败回合也能建映射）→ `turn.started` → 顶层 `error`（重连瞬态，解析层忽略）→ `turn.failed`（致命，exit 1）。
- `codex exec resume <thread_id> -`：thread_id 原样回显，stdin 提示词解析正常。
- `--help` 核实：`--full-auto` 已移除（用 `--sandbox workspace-write`）；`-c` 点号键 TOML 值解析。

## 已知限制（协议决定，非缺陷）

1. 无逐 token 文本增量：助手文本在 item.completed(agent_message) 整段到达，UI 无打字机效果。
2. 无交互审批：exec 模式不弹审批，命令在 workspace-write 沙箱自动执行，不走本体授权中心（卡片 hint 已写明）；aicode_bridge 数据工具不受影响。
3. token 用量记 0：turn.completed 的 usage 未接（AssistantText 契约带 token，codex 用量按回合聚合），待后续批次接 ContextUsage 或最终消息补记。
4. 自定义 OpenAI 兼容 provider 走 codex config.toml model_providers（env_key/wire_api=responses），本期只透传 key + OPENAI_BASE_URL。

## 验证

- `:app:compileArmsoloReleaseKotlin` 通过（第 1 轮 3 处错：mcpEnvOverride 缺参、不存在的 string 引用，已修）。
- detekt（tools/detekt/run-detekt.sh）改动 7 文件：0 findings。
- qa 审查（子代理 e6f41393）：可放行，处置如下。
  - P1-1 非 tool 型 item 也发 ToolCallStarted → **已修复**：parseItemStarted 改类型白名单（command_execution/file_change/mcp_tool_call/web_search/todo_list/collab_tool_call），agent_message/reasoning/error 不再产生永不配对的「执行中」工具行；白名单与 Finished 配对一致性留真机验收第 2 条确认。
  - P2-2 threadIds 并发竞态 → **已修复**：改 ConcurrentHashMap（persistThreadId 全量重写文件为弱一致遍历，最坏丢一条映射，下回合重建，可接受）。
  - P2-1 spawn 失败 token 残留 → **本桥已修复**（startStdioProcess 包 try/catch 后 removeToken 再抛）；ClaudeCodeBridge 同型问题按 qa 建议记待排期统一修。
  - P2-5 透传变量名 → **已实测关闭**：开发容器用 OPENAI_API_KEY 走到 API 调用（403 地区错同 CODEX_API_KEY），变量名正确；真机验收第 4 条仍验真实 key。
  - P2-3 resume 失败关键词未实测 → 保持，真机验收补充验证实际报错文案（不匹配仅退化为不自动重试，有 Failed 兼底）。
  - P2-4 TOML 注入面 → qa 已核非缺陷（UUID/约定路径；projectPath 含引号最坏解析失败，无执行面）。
  - 其余各项（when 分流零回归、52 个位置参数对齐、资源清理、AssistantText 契约、UI 回归、能力感知）qa 已核无误。

## 待真机验收（并入 vCode134 清单）

1. 引擎页出现 Codex 卡：未装→安装；装后→登录态探测正确（auth.json 存在或配置 OpenAI key 后可选）。
2. 切 Codex 引擎发消息：正常对话、气泡整段显示、命令/文件修改工具行正常配对。
3. 续接：同会话第二条消息沿用 codex 侧历史（threads 映射生效）；重启 App 后续接仍有效（文件回读）。
4. 透传：App 内配 OpenAI key 免登录直用；aicode_bridge 工具（memory/ledger）在 codex 会话可用。
5. 内置引擎与 Claude Code 引擎路径无回归。
