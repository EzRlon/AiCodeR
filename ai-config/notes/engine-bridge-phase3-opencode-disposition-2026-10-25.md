# 三期引擎适配 · OpenCode 批次（2026-10-25）实施台账

## 交付内容（提交哈希待补）

新增 OpenCode 引擎桥接，与 CodexBridge 同构。CLI 锁定 opencode-ai@1.19.0（npm latest 稳定线，1.19.x 即当前代码线；GitHub tag 已到 v2.x 但 v2 仅 dev/beta 预发布）。

| 文件 | 内容 |
|---|---|
| `OpenCodeBridge.kt`（新增，439 行） | `opencode run --format json --auto --thinking [--dir 项目根] [-s <ses_id>] [-m provider/model]`（提示词走 stdin，规避位置参数以 `-` 开头被当 flag）；NDJSON 事件（step_start/text/reasoning/tool_use/step_finish/error，每行带 sessionID）→ AgentEvent；session 映射持久化 `~/.aicode/bridge/opencode-sessions.json`（首轮从事件行取 ses_id，续接 `-s`）；MCP 经 `OPENCODE_CONFIG_CONTENT` 内联挂 aicode_bridge；透传 OPENAI_API_KEY / ANTHROPIC_API_KEY（实测 OPENAI_API_KEY 生效；自定义 provider baseURL 需 opencode.json，本期不透）；认证预检（`~/.local/share/opencode/auth.json`）；--auto 自动批准权限；空闲看门狗/取消杀进程/续接失败转全新回合/spawn 失败 token 清理，与 CodexBridge 同型 |
| `AgentEngines.kt` | 加 `OPENCODE = "opencode"` |
| `AIAgentViewModel.kt` | 注入 openCodeBridge；when 分流加 OPENCODE 分支；isOpenCodeAvailable / openCodeAuthHint / installOpenCode |
| `ScheduledAgentViewModelProvider.kt` | 位置参数插入 openCodeBridge |
| `ChatModelSheet.kt` | EnginePage 重构为描述符循环（三张 CLI 卡同构，消重复）；胶囊加 OpenCode·；CliModelPage 加 opencode 模型列表（default / openai/gpt-5.3-codex / anthropic/claude-sonnet-4-5） |
| `ChatInputBar.kt` / `AIChatPanel.kt` | checkOpenCodeAvailable / checkOpenCodeAuth / onInstallOpenCode 传参 |
| `strings.xml`（中/英） | engine_opencode_not_authenticated / unavailable / hint / install_started |
| 能力文档 | capabilities.md 与 65-capabilities.md 的三期条目扩为 Codex/OpenCode 双引擎 |

## 协议验证（开发容器实测 opencode 1.19.0 + v1.19.0 tag 源码核对）

> 基准更正（qa P2-4）：1.19.0 对应源码在 `packages/opencode/src/cli/cmd/run.ts`（v2 dev 仓库布局为 packages/cli/src/run/noninteractive.ts，与锁定版不同）；事件 emit 形态两边一致，审查以 v1.19.0 tag 源码为准。

- `run --format json` NDJSON 实测：错误事件 `{"type":"error","timestamp","sessionID","error":{name,data.message}}`，exit 1。
- `OPENAI_API_KEY` 透传有效（fake key 直达 api.openai.com，403 地区错=链路通）。
- `OPENCODE_CONFIG_CONTENT` 内联 MCP 实测**会拉起 server**（探针被 spawn）；官方优先级链合并、不整体替换用户配置（mcp 同键会覆盖）。
- 提示词走 stdin、无位置参数：实测可用（直达 API 调用）。
- 事件形态逐行核对 dev 分支 `packages/cli/src/run/noninteractive.ts`：text/reasoning 按块整发（session.text.ended/reasoning.ended 触发，reasoning 需 --thinking）；tool_use 仅在 session.tool.success/failed 后一次到达（state.status=completed|error，output 为 toolOutputText 文本）；step_finish 带 cost/tokens（本期未接）；exit 0 成功 / 1 错误 / 130 中断。
- 权限：--auto 自动批准；否则自动 reject + 反馈注入（引擎卡 hint 已写明走 --auto）。

## 已知限制（协议决定，非缺陷）

1. 无逐 token 文本增量；工具调用无执行中状态（成败后一次性到达，翻译层补 Started+Finished 同 id 配对）。
2. 权限无交互审批：--auto 自动批准（未被显式 deny 时），不走本体授权中心。
3. token/cost（step_finish.part.tokens/cost）未接：无预算基准，记待办。
4. 自定义 provider baseURL 需用户在 opencode.json 配置，本期不透传。
5. OPENCODE_CONFIG_CONTENT 的 mcp 键会覆盖用户同名 mcp 配置（仅影响 App 内 OpenCode 引擎会话）。

## 验证

- `:app:compileArmsoloReleaseKotlin`：通过（首轮即过）；qa 处置后复编：待第 4 轮结果。
- detekt：改动 7 文件 0 findings。
- qa 审查（子代理 44b7a275）：需修复（P0×1/P1×1/P2×3），处置如下：
  - **P0-1 成功回合必以 Failed 收尾**（opencode 成功流无结束事件，EOF 后被误判「提前退出」）→ **已修复**：EOF 后 waitFor(10s) 按退出码判定——exit 0 且已有产出 → emit Completed；否则走 classifyEarlyExit（保住认证/续接缺失分类）。修复后未能在开发容器跑真实成功回合（无可用 provider，地区 403），属源码级修复待真机验收第 2 条验证。
  - P1-1 reasoning 永不清理且不落库 → **已修复**：text 分支随首块正文携带 reasoning 并 clear（与 CodexBridge 一致）。
  - P2-1 死字段 emittedAny → **已消**（P0-1 修复后 acc.emittedAny 被 EOF 判定读取）。
  - P2-2 结构化 output 兑底 → **已修**：补 `state["output"]?.toString()`。
  - P2-3 硬编码模型名 → 保留：openai/gpt-5.3-codex、anthropic/claude-sonnet-4-5 经检索确认有效（退役的是 spark/5.5）；选错仅 CLI 侧报错，default 兑底安全。
  - P2-4 台账基准 → **已更正**（见上）。
  - 其余（session 映射无子会话污染、注入面、flags/stdin、error 即 Failed、tool_use 配对、资源清理、53 位置参数、EnginePage 重构回归、传参链、strings、能力感知）qa 已核无误。

## 待真机验收（并入 vCode134 清单，与 Codex 批合并）

1. 引擎页出现 OpenCode 卡：未装→安装；装后→登录态探测正确。
2. 切 OpenCode 引擎发消息：正常对话、气泡/工具行配对正常（tool_use Started+Finished 同 id）。
3. 续接：同会话第二条消息沿用 opencode 侧历史（session 映射生效）；重启 App 后仍有效。
4. 透传：App 内配 OpenAI/Anthropic key 免登录直用；aicode_bridge 工具可用。
5. 内置/Claude/Codex 引擎路径无回归。
