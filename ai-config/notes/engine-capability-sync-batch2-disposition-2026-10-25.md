# 能力全域打通 · 批二（App 子代理 → CLI 引擎 agent 映射）（2026-10-25）实施台账

设计依据：`engine-capability-sync-design.md` §分期批二、§映射表「智能体定义」行（claude 待做 / opencode 待做 / codex 挂起）。

## 一、格式调研（动手前实测，非仅查文档）

| 引擎 | agent 载体 | 实测依据 |
|---|---|---|
| Claude Code | `--agents <json-or-file>`（print 模式可传文件路径；JSON 形如 `{name:{description,prompt}}`） | 容器内 `claude --help` 输出该参数说明；本批用桥目录文件传路径 |
| OpenCode 1.19.0 | `OPENCODE_CONFIG_CONTENT` 内联 `agent`（singular）段，条目含 `description` / `mode` / `prompt` | `OPENCODE_CONFIG_CONTENT='{"agent":{"aicode_probe":{...}}}' opencode agent list` → 列出 `aicode_probe (subagent)`；`opencode agent list` 显示自带 agent = build/compaction/explore/general/plan/summary/title |
| Codex | 不做 | 设计定「等原生 agent 机制稳定（挂起）」 |

## 二、交付内容

- 新增 `CliAgentMapping`：CLI 名称合法性（ASCII 标识符、≤40 字符）、跳过引擎自带 agent 名、构建两引擎的 agent JSON（仅映射描述 + 提示词；description 空则回退为名称）。
- `ClaudeCodeBridge`：每回合枚举 App 已启用子代理 → 写桥目录 `agents.json`（App 管理区，**不写用户 `.claude/agents/`**）→ args 追加 `--agents <路径>`；无可用定义时不传该 flag。
- `OpenCodeBridge`：枚举同一份定义 → 并入已在用的 `OPENCODE_CONFIG_CONTENT`（键 `agent`，`mode=subagent`），**不写 opencode.json**；枚举读文件，调用点包 `withContext(Dispatchers.IO)`。
- 能力感知件：`app/src/main/assets/prompts/65-capabilities.md`、`docs-site/docs/guide/capabilities.md` 已登记（含 Codex 侧暂不支持、同名跳过口径）。
- 新增纯逻辑单测 `CliAgentMappingTest`（名称校验 / Claude JSON / OpenCode mode 与自带名跳过 / 跳过原因）。

## 三、映射口径与已知差异（设计决定，非缺漏）

1. 只映射**启用的**定义（`AgentDefinitionRepository.listEnabled()`，设置页启停即生效）。
2. 名称非 ASCII 标识符（含空格、中文等）的定义跳过并记日志——CLI 侧 agent 名同时是标识符/文件名。
3. 与引擎自带 agent 同名的定义跳过（OpenCode 侧 lowercase 比对；App 内置 Explore 与 opencode 自带 `explore` 同名，故不会覆盖引擎原生体系）。
4. **工具白/黑名单与绑定模型不映射**：App 工具名与 CLI 工具名体系不同，App 模型来自 App provider（对 CLI 未必合法），硬映射会产生行为不符的 agent。记待办。
5. 同名冲突：qa 实测 `OPENCODE_CONFIG_CONTENT` 与用户 opencode.json 是**按名字深合并**（非整键替换）——用户其他 agent 仍在列，仅同名项以 CONTENT 为准；Claude `--agents` 同为同名覆盖。App 是唯一入口，暂接受并记录。
6. 名称含中文/空格的 App 子代理不映射（CLI 侧名即标识符/文件名），当前仅记日志；设置页提示待排期（qa-P2-5）。
6. 生效时机：下一次发送（每回合重新生成），改定义无需重启。

## 三·补、容器内实测（把「推断」变成事实）

- 单条环境变量上限：`env BIG=<n 字节> opencode --version` → 131000 字节成功、140000 字节 `OSError: Argument list too long`（E2BIG）。据此设 `MAX_CONFIG_CHARS = 64KB`，超限则不映射 agent 段（降级而非整回合失败）。
- `OPENCODE_CONFIG_CONTENT` 的 `agent`/`mcp` 与用户配置按名字深合并（qa 实验结论，见上 §三.5）。

## 四、验证

- 编译：`:app:compileArmsoloReleaseKotlin` 通过。
- 单测：`CliAgentMappingTest` 通过（`testArmsoloReleaseUnitTest --tests ...`，BUILD SUCCESSFUL）。
- detekt：触达 4 文件 0 findings。
- optimizer（子代理）：**无 P0/P1**；量化确认每回合新增开销毫秒级（本地模式 5 个定义约 12–14 次 stat/read；远程 SFTP 约 8–10 次 RTT，进程启动前串行段，不阻塞 UI），全部 IO 在 Dispatchers.IO，无重复扫描（引擎回合不经本体 prompt 扫描），写盘走 tmp+rename 原子替换故并发安全，无新增常驻订阅/定时器/动画/唤醒。4 条 P2：
  - opt-P2-1 `OPENCODE_CONFIG_CONTENT` 无长度护栏，极端配置（多 agent × 超长 prompt）可能超 env 上限致 spawn 失败、整回合报错 → **当批加固**：agent 段序列化后超过预算则不映射本回合（记日志降级，而非硬失败）。
  - opt-P2-2 resume 降级重试使同回合扫描/拼接双跑 → 记录不改（绝对值毫秒级，改造需把结果下沉到 runTurn 层，收益不值）。
  - opt-P2-3 与 MCP 映射开销不对称（MCP 走内存 StateFlow 缓存、子代理每回合扫盘）→ 有意取舍（CLI 每回合新进程、定义改动应立即生效；功耗纪律禁常驻缓存），仅记录。
  - opt-P2-4 同一列表被重复过滤 2–3 次 → **当批合并**：CliAgentMapping 改为 `partition()` 一次遍历产出「可映射 + 跳过及原因」，JSON 构建不再重复 filter。
- qa（子代理）：**结论可合入，无 P0/P1**；另做容器内只读实测（`claude --help`、`opencode agent list`、临时 `OPENCODE_CONFIG` 合并实验，未碰用户配置与源码）。7 条 P2 处置：
  - qa-P2-1 Claude 侧未排除自带 agent 名、能力页措辞字面覆盖两引擎 → **当批修**：文档改为只写「OpenCode 侧与自带 agent 同名的跳过」，`CliAgentMapping` KDoc 注明 Claude 侧不做同名排除且其自带名清单未确认（列入真机核实）。
  - qa-P2-2 自带名集合两处重复定义 → **已消**（集合只在 `CliAgentMapping.OPENCODE_BUILTIN_NAMES`，OpenCode 桥改用 `partitionForOpenCode`）。
  - qa-P2-3 两引擎 agent 枚举失败语义不对称（OpenCode 侧不包兜底会让整回合失败）→ **当批修**：OpenCode 侧包 `runCatching` + 日志，明确「MCP 枚举异常仍致命（批一口径）/ agent 枚举异常只丢本回合映射」。
  - qa-P2-4 `agents.json` 写入未纳入 assetsMutex → 记录不改（写盘为原子替换、内容与会话无关、每回合仅一次，qa 已核实无竞态）。
  - qa-P2-5 非 ASCII 名定义静默跳过、用户不可见 → 记录待排期（子代理设置页加「CLI 引擎不支持该名」提示）。
  - qa-P2-6 能力页声称可用 `@名字` 召唤未经真机验证 → **当批改文案**为「可被主代理用 Task 派发」；`@名字` 可用性列入真机核实。
  - qa-P2-7 KDoc 归属错位（原 writeBridgeAssets 的说明被新函数挤到上方）→ **已修**（说明归位）。

## 五、待真机验收（并入下一包）

1. 设置 → 子代理建一个自定义子代理（英文名）→ 切 Claude Code 引擎发消息 → claude 侧能用 `@名字` 或 Task 召唤它，且行为符合其提示词。
2. 同一定义切 OpenCode 引擎 → `opencode` 会话内该子代理可用（App 里禁用后下一次发送即消失）。
3. 中文名/带空格名的定义不报错、不出现（只影响本回合，日志有跳过原因）。
4. 用户已有 `.claude/agents/` 与 opencode.json 不受写动（无文件被本批写入用户配置目录）。
