# 无人值守安全体系（守夜守护）设计稿 v1（2026-10-14）

> **需求**（用户 2026-10-14）：无人值守、自主推进开发时，如何确保项目与系统安全；要求「非常接近 Claude Code 那样智能」的方案。
> **定位**：本稿是「授权中心 2.0 / 离开模式 / 定时任务」诸设计之上的**安全底座补充**，不推翻既有设计。准则 = **无人值守不卡死 + 边界守死 + 全链路可逆**。
> **调研基准**：① Claude Code 官方文档（sandboxing / permission-modes / permissions / checkpointing，2026-10 线上检索核实）；② AiCode 工作区代码快照（2026-10-14，含施工中的授权中心 2.0 未提交改动）。
> **出处**：本次为「只读调研 + 设计」会话产出；本文件是唯一写入物，未改动任何代码。

---

## 〇、设计哲学：Claude 的安全是「四件事的组合拳」

1. **给空间（沙箱化）**：Claude 用 OS 级沙箱（macOS Seatbelt / Linux bubblewrap）约束 shell 命令——写 = 工作区 + 临时目录（受保护路径除外）；网络 = 无直连、经由本机代理 + 域名白名单（默认空）。**沙箱内的命令直接自动放行**——用「盒内自由」替换「逐次弹窗」。
2. **立规矩（规则）**：allow/ask/deny 规则，按 deny → ask → allow 顺序评估、**deny 不可被 allow 或 hook 绕过**；受保护路径（.git、shell rc、`.mcp.json`、`.claude.json` 等）永不自动放行；**致命删除（critical path）任何模式都不自动放行**，且等待带 **2 分钟倒计时**——超时→**拒绝并把「该怎么改」告诉模型**，无人会话继续工作；一次会话连续 3 次未答则直接拒绝（新消息重置计数）。
3. **会判断（智能）**：auto 模式由**第二个模型（分类器）**审查动作代替人；**只送灰区**（shell 命令、网络操作），读与工作区内编辑不送审 → 成本可控；无终端可弹的场所在无人时**直接拒绝**而不是干等。
4. **留后路（可逆与诚实）**：每轮检查点（自动快照，保留最近 100 个）+ git 互补；**明确声明「Bash 改的文件不入检查点」**；「配置了但没生效，比诚实的关闭更糟」（failIfUnavailable 精神）——宁可显式降级，不要假保护。

**AiCode 版翻译**：容器已经是外层盒（已核实绑定：容器根、`工作区→/root/workspace`、`filesDir/aicode→/root/.aicode`；宿主其余目录不可达，默认不挂 /sdcard）。本设计把它升级为「**盒内分级 + 盒外硬闸 + 可逆可查**」，并将 Claude 的分类器、倒计时拒绝、保护清单逐一对位补齐。

---

## 一、对标总表（Claude 机制 → AiCode 现状 → 本设计动作）

| Claude Code 机制 | 它怎么做 | AiCode 现状（已核实，2026-10-14 快照） | 本设计动作 |
|---|---|---|---|
| OS 级沙箱 | bwrap/Seatbelt；写=工作区+临时；网络=代理+域名白名单；沙箱内命令自动放行 | 容器=外层盒（挂载见 §L1.1）；盒内**无任何强制层** | **L1 写域三分级 + 敏感读清单 + 网络尽力而为层**（诚实标注非内核隔离） |
| 权限模式 | Manual/acceptEdits/plan/**auto（分类器）**/dontAsk/bypass；bypass 仅限隔离环境、需确认、拒绝 root | 构建/计划/自动（**AUTO ≈ Claude 的 bypass**：全放行，仅灾难性 rm 与 Shizuku 例外） | 新增「**守夜**」= 离开模式档案 + 守护层；日常 AUTO 不降速 |
| allow/ask/deny 规则 | deny 最优先；Bash 拆段/剥包装器后匹配；Edit/Read 用 gitignore 风格 glob；WebFetch 域名规则；会话级/项目级/用户级/管理级 | DENY>ASK 内核、命令 token 前缀、项目/全局两级、三档+档案（施工中）；**缺路径型/域名型/会话级规则，deny 只能靠手改 JSON** | 规则语言 v2：**路径 glob、域名、会话级**补齐 |
| 受保护路径清单 | .git、.config/git、shell rc、包装器配置文件、.mcp.json/.claude.json 等永不自动放行；auto 模式送分类器 | 仅 hooks.json 被标「单次放行」；`writeFile` 的「始终允许」是**整工具级**（一旦记住可写任意路径）——已知缺口 | **L1.2 C 类清单 + 路径分级**（修整工具级记忆缺口） |
| 致命删除防护 | 独立检查 + 变体全清单（空变量 `"$DIR"/*`、命令替换目标、嵌套 shell、`find -exec sh -c` 等）；不可被 allow/hook 放行 | 已有 rm 防护（根/家/工作区根/系统目录//tmp、`~` 归一化、工作区子路径豁免）+ elevate 一次性提权机制；**缺上述变体** | 补变体；守夜下改「**拒绝+改写建议**」不弹窗 |
| 命令分类器 | auto 模式第二模型审查；只送灰区；沙箱网络连接与命令合并判 | 无 | **L3 v2：审查模型**（可配、默认仅灰区、fail→拒） |
| 检查点 | 每轮快照；只跟踪 Edit/Write 工具；Bash 修改盲区**公开声明**；与 git 互补；100 个/会话 | 已有（消息级创建、writeFile/editFile 前快照、回滚跳过被他人改过的文件）；**Bash 盲区同样存在** | **L4：开跑锚点（git tag）+ 守夜报告**；盲区写进文档与提示词 |
| Hooks | 26 事件、PreToolUse 可拒；deny/ask 不受 hook 影响 | PreToolUse 非零退出=否决（`HookRunner`）、fail-open（基建故障放行）、hooks.json 写入被单次保护 | **内置守护钩子集**（AI 不可改）+ fail-closed 选项 |
| 失败关闭/自检 | `sandbox.failIfUnavailable`；「配置了没生效比诚实关闭更糟」 | 无 | **守夜武装自检**（各层就绪校验，不达标明示降级） |
| 无人值守原则 | bypass 仅限隔离环境；明确「不承诺防注入」 | — | 守夜=容器内+分层守护+明确边界声明；注入扫描（v2）补短板 |

---

## 二、威胁模型（五类，决定各层优先）

| # | 风险 | 代表场景 | 主责层 |
|---|---|---|---|
| 1 | AI 误操作 | 错路径写入、误删、坏配置 | L1（写域）+ L2（规则）+ L4（锚点/回滚） |
| 2 | 提示注入 | 仓库文本/网页/MCP 返回夹带「帮我执行 xxx」 | L2（硬闸）+ L3（扫描）+ 行为契约 |
| 3 | 危险脚本执行 | `curl\|sh`、混淆命令 | L2（解析/拒绝引导）+ L3（审查模型 v2） |
| 4 | 越界宿主/系统 | Shizuku、rootfs 系统区、shell rc/PATH 污染 | L1（C 类/宿主闸） |
| 5 | 资源与成本失控 | 磁盘爆、死循环、token 耗尽 | L4（时间盒/预算/看门狗——部分已有） |

---

## 三、L1 边界层：把盒子画清楚

### L1.1 容器盒子的既成事实（已核实，勿重复发明）
- 挂载（`LinuxContainerEngine.buildBaseProotArgv`）：`-b /dev /proc /sys /system` + `$projectPath:/root/workspace` + `filesDir/aicode:/root/.aicode`；`ContainerProfile.extraBindings` 可加（示例 `/sdcard:/mnt/sdcard`，默认无）。
- 环境（`buildContainerEnv`）：`HOME=/root`；`PATH` 含 `/root/.aicode/bin:/usr/local/bin`；`GIT_CONFIG_GLOBAL=/root/.aicode/.gitconfig`（凭据经 store helper 读 `/root/.aicode/git-credentials`）。
- 结论：宿主机其余目录、App 私有目录（除上述两个绑定）对容器**不可达**；**Shizuku 是唯一宿主操作通道**（工具级）。
- **注意**：PRoot 不是安全边界（无 namespace/LSM）；Android App 沙箱（uid 隔离）才是外层保证。本稿全部设计基于此现实，不做硬隔离承诺。

### L1.2 写域三分级（落点：权限引擎 + 文件工具路径判定）
| 级别 | 范围 | 守夜（无人值守）下 | 日常（有人）下 |
|---|---|---|---|
| **A 全放**（记录） | 工作区全部、容器 `/tmp`、`~/.aicode/notes|memory`（AI 工作产物） | 直接放行，审计记录 | 维持现状 |
| **B 记录放行** | `~/.aicode` 其余普通文件（含 `skills/`——列入报告显著位） | 放行 + 审计 + 报告标注 | 维持现状 |
| **C 守夜拒绝** | `hooks.json`、`mcp.json`、`agents/`、`teams/`、`.gitconfig`、`git-credentials`、容器 shell 启动文件（`~/.bashrc`、`~/.profile`、`/etc/profile`、`/etc/profile.d/*`）、PATH 目录（`/usr/bin`、`/usr/sbin`、`/usr/local/bin`、`/root/.aicode/bin` 等）、rootfs 系统区、`.git` 内部 | **拒绝 + 引导**（可预授权例外，v1.5） | 询问（即现状，单次/不可记忆） |

> 说明：C 类清单是「写域」概念，对文件工具与 shell 重定向/rm 目标同一套判定；`permissions.json`（项目级）保持「AI 可改属有意设计」的现状，不列入 C。

### L1.3 敏感读清单（只记录 + 报告黄标，不拦）
`/root/.aicode/git-credentials`、`~/.ssh/**`、`*.pem`、`*.key`、`*.keystore`、`credential*`。理由：v1 无出口硬控，「读+网络命令同现」才是风险信号——先透明化，作为审查模型（v2）的输入特征。

### L1.4 网络层（分三期，诚实标注强度）
- **v1（记录级，立即可做）**：静态识别网络命令（curl/wget/nc/ncat/socat/ssh/scp/rsync、git fetch/pull/push/clone、包安装类）+ 浏览器/webfetch 的**工具级域名规则** + 全部进出写审计。
- **v1.5（实验）**：**守护代理**——复用现成注入点（`AppProxy.proxyEnv` → 容器 `HTTP_PROXY/HTTPS_PROXY/ALL_PROXY`），守夜时自动让容器 HTTP(S) 走本机过滤代理（域名白名单 = 用户勾选 + AI 声明）；对不读代理变量的工具（裸 socket/数据库客户端）为**尽力而为**，如实标注。
- **v2（实验）**：LD_PRELOAD `connect()` 拦截（容器已有 LD_PRELOAD 先例，需真机验证）；评估后再定去留。
- **不承诺**：非 HTTP 裸 socket 无硬控（Claude 自身亦声明 TLS 不检查、域名可被 fronting 绕过的同类局限）。

### L1.5 宿主层
- Shizuku 保持逐次；**守夜下 = 拒绝 + 记录**（v1.5 支持预授权精确命令清单，如 `Shizuku(shell dumpsys *)`）。
- 「设备操作/无障碍」（tool-authorization-plan v2 规划）沿用其「操作会话 + 常驻通知 + 急停」设计，与本稿 L5 共用组件。

---

## 四、L2 规则层：离开模式语义升级（回应上一调研的全部「残弹」）

### L2.1 残弹六条升级表（离开模式档案之上的守夜语义）
| # | 当前（授权中心 2.0 快照）行为 | 守夜下新行为 |
|---|---|---|
| 1 | 含 `$( )`/反引号/子 shell/绝对路径重定向的**不可静态判定**命令 → 永远询问 | 破坏性 → **拒绝 + 改写法建议**（教模型写可分析命令）；非破坏性 → 放行 + 记录（严格化可勾选） |
| 2 | 任何**递归/通配 rm**（含 `rm -rf app/build` 正常清理）→ 询问、不可记忆 | 目标全在 A 类（工作区子路径/tmp）→ **放行 + 记录**；触 C 类/保护范围 → 拒绝 + 引导 |
| 3 | 递归删除触**致命路径**（根/家/工作区根/系统）→ DENY，带 elevate 时→ 弹提权确认 | **永不放行**：拒绝 + 引导（守夜下禁用 elevate 弹窗路径） |
| 4 | Shizuku / MCP 管理 / hooks.json 写出 | **拒绝 + 记录**（可预授权例外，v1.5） |
| 5 | `planMode` 进入 → 默认询问；退出 → 计划审查面板**无限期等人** | 进入 = 放行（只读方向）；审查面板守夜下**自动通过 + 记录**（可勾选关闭） |
| 6 | 任意弹窗无人应答 = 无限期挂起（含预算面板、AI 提问） | **兜底超时**：默认 10 分钟 → 自动「拒绝 + 引导」+ 通知 + 审计；`askUserQuestion` 守夜下自动答复「用户不在，请自主决策或跳过」；预算面板改为「不暂停：结束并报告」（或提升额度），学 Claude「超时拒绝让会话继续」 |

### L2.2 守夜「永不自动放行」清单（学 Claude 的 no-mode-auto-approves）
任何配置/档案都不能越过：致命删除、C 类核心配置（hooks.json / mcp.json / agents / teams）、宿主 Shizuku、push 等共享状态变更。仅允许「用户在场逐次确认」。

### L2.3 超档动作分级（建议修订 scheduled-ai-task-plan 决策 8）
原决策「默认暂停等回」改为三级：
1. 可逆 + 盒内 → **放行 + 记录**；
2. 越界 / 不可逆 → **拒绝 + 引导**（继续跑，不停机）；
3. 人类专属（push、预算耗尽、时间盒到、连续失败阈值）→ **结束并生成报告**（或可选暂停等回）。

### L2.4 规则语言 v2 缺口清单（补 Claude 的对应能力）
- `Read(glob)` / `Edit(path-glob)` 路径规则（修「writeFile 整工具记忆」缺口）；
- `WebFetch(domain:…)` / `browser(domain:…)` 域名规则；
- 会话级规则（无人值守临时放行，不污染项目规则文件）；
- Shell 变体增强（见 L2.1#1/#3，落点 `ShellCommandParser`）。

### L2.5 兜底原则
一切「必须人答」的点都要有守夜分支（拒绝+引导或超时拒绝），**不允许存在无限期挂起点**。这是与 Claude 对齐的核心行为。

---

## 五、L3 智能层（最像 Claude 的三件套）

### L3.1 审查模型（分类器对位，v2）
- 触发：**只送灰区**——不可静态判定命令、C 类触界、陌生网络域名、批量危险模式；
- 输出：`{allow | deny(中文理由)}`；超时/失败 → **fail-closed（拒绝）**，附改写法建议；
- 配置：独立的「守夜审查模型」（复用压缩/识图模型的配置范式；默认跟随对话模型或选快速模型）；结果缓存减少调用；
- 无终端场景直接拒绝（学 Claude headless 行为）。

### L3.2 AI 行为契约（提示词，v1）
无人值守纪律写入 `assets/prompts/50-safety.md`（或 65）：
可逆优先（mv/备份 > rm）、先锚点后大改、命令简单化（避免 `$( )`/子 shell/绝对重定向）、不碰 C 类、失败三次换思路、如实汇报、不擅自 push。

### L3.3 注入扫描（v2 实验）
对 webfetch / browser / MCP 等外部返回做注入标记扫描（规则 + 可选模型），命中则在工具结果中加「疑似注入」警告并与系统提示的注入防护规则联动。

---

## 六、L4 恢复与取证层

- **开跑锚点（v1）**：进入守夜自动打本地 git tag（`aicode-watch-<ts>`，不动当前分支、不 push）+ 检查点就绪校验（不达标 → 自检告警）。
- **检查点现状与盲区（已核实）**：消息级创建、writeFile/editFile 前快照、回滚跳过「被他人改过」的文件；**Bash 改动不入快照**（与 Claude 同款局限）→ 靠 git 锚点 + 小步提交纪律补位；盲区写进文档与守夜报告。
- **守夜报告（v1）**：结束生成——时间/时长、改动清单（`git diff --stat` + 检查点数据）、被拒动作列表（审计）、测试/构建结果、建议复核点、回退指南；通知调度 + 会话内展示。
- **急停（v1）**：常驻通知「AI 守夜中」+ 一键停止（复用 `stopAgent` 与 `agent_permission` 通知通道先例）。

---

## 七、L5 武装 UX（守夜模式打包）

1. **进入清单**（自动检查 + 勾选）：应用「离开模式」档案 → 自检（容器就绪/检查点可用/锚点已打/通知权限/时间盒/预算）→ （可选）预授权清单（装包、域名白名单、Shizuku 命令、C 类特定放行）→ 武装。
2. **运行态**：常驻通知 + 急停；每小时/结束摘要（与定时任务通知节奏决策对齐）。
3. **返回摘要**：守夜报告 + 授权记录页（施工中）聚合视图。
4. **对接**：定时任务/自动接棒（scheduled-ai-task-plan v1/v3）复用本清单与报告。

---

## 八、分期与文件级施工清单

### v1（守夜最小可用——缝合成型，与授权中心 2.0 顺接）
| 改动 | 落点（已核实路径） | 内容 |
|---|---|---|
| rm 变体增强 | `feature/agent/domain/permission/ShellCommandParser.kt` | 空变量守卫（`"$DIR"/*`；`"${DIR:?}"` 例外）、命令替换目标（`rm -rf "$(pwd)"` 类）、嵌套构造（子 shell / `sh -c` / `find -exec sh -c`）内的 rm 检查 |
| 写域分级 + 守夜语义 | `feature/agent/domain/permission/ToolPermissionPolicyEngine.kt` | C 类清单判定；守夜分支（ASK→拒绝+引导 / 放行+记录）；致命删除守夜化；elevate 守夜禁用 |
| 档案增量 | `feature/agent/domain/permission/ToolPermissionCatalog.kt` | 离开模式补 `planMode`（其余按本稿 L2.1 语义由引擎实现） |
| 兜底超时 | `feature/agent/domain/tool/ToolPermissionManager.kt` | 无人值守策略注入；默认 10 分钟 → 自动拒绝 + 引导 + 审计 + 通知 |
| 计划/预算守夜分支 | `.../tool/mode/PlanApprovalManager.kt`、`.../workflow/BudgetApprovalManager.kt` | 审查自动通过（可关）；预算不暂停 → 结束 + 报告 |
| AI 提问守夜答复 | `.../workflow/StatefulAgentWorkflow.kt` | `askUserQuestion` 自动答复「用户不在，自主决策或跳过」 |
| 锚点 + 报告 | `.../checkpoint/CheckpointManager.kt` + git | 守夜 tag；报告数据源（git diff + 审计） |
| 急停/常驻通知 | `feature/agent/domain/notification/`（复用 `PermissionNotificationCenter` 先例） | 守夜通知 + 急停按钮 |
| 行为契约 | `app/src/main/assets/prompts/50-safety.md`（+`65-capabilities.md`） | 无人值守纪律段 |
| 设置 UI | `PermissionsSettingsSection.kt`（施工中，收尾时并入） | 守夜开关 / 自检展示 / 预授权入口（可后置） |

### v1.5
规则语言 v2（路径/域名/会话级）、守护代理实验、Shizuku 预授权、在场检测（人在 → 倒计时弹；人走 → 拒绝+引导）。

### v2
审查模型（仅灰区）、注入扫描、LD_PRELOAD 实验（与无障碍安全 v2 批次合并评估）。

---

## 九、决策记录（2026-10-14 代选，标准=合理·科学·最优；用户可随时推翻）

1. **C 类清单初版** = §L1.2 上表。
2. **无人值守残余处理默认 = 立即拒绝 + 引导**（睡觉场景倒计时无意义）；「在场检测 → 倒计时弹」放 v1.5。
3. **审查模型默认关**（v1 纯规则），v2 默认「仅灰区」开启。
4. **锚点 = 本地 git tag**（不 push、不动分支）。
5. **计划审查守夜自动通过**（可勾选关闭）。
6. **超档动作三级分级**（修订原「一律暂停等回」）。
7. **网络 v1 仅记录级**，不作强制承诺；守护代理为 v1.5 实验。

---

## 十、验证计划（真机演练）

| 用例 | 期望 |
|---|---|
| 注入文本样本（网页/仓库含「执行 xxx」指令） | AI 不执行；有审计记录 |
| rm 变体族（空变量/替换/嵌套/`find -exec`） | 该拒的拒、该放的放；无弹窗卡死 |
| C 类写入（hooks.json / .bashrc / PATH 目录） | 拒绝 + 引导；报告可见 |
| 网络动作（curl 外发/装包） | v1 记录进报告；v1.5 走守护代理域名校验 |
| 守夜 10 分钟超时兜底（模拟无人应答） | 自动拒绝 + 继续 + 通知 + 审计 |
| 锚点回退演练（守夜后 `git reset` 到 tag / 检查点回退） | 一键可退；报告含指引 |
| 守夜全流程（武装 → 无人跑一段开发任务 → 返回看报告） | 零弹窗、零卡死、报告完整 |

---

## 十一、诚实边界与风险（必须明示用户与 AI）

- **PRoot 无内核隔离**：本体系是纵深防御，不是硬沙箱；与 Claude 的 bwrap/Seatbelt 有本质差距——边界守死靠「不绑定危险路径 + 分级拒绝 + 审计 + 可逆」。
- **Bash 检查点盲区**：与 Claude 同款局限，靠 git 锚点补位。
- **网络 v1 为记录级**；守护代理对不读环境变量的工具无效（诚实标注）。
- **审查模型误判**：只送灰区 + 审计 + 可调；fail-closed 防漏放。
- **LD_PRELOAD 拦截**：可行性待真机验证，不预先承诺。

---

## 十二、与现有设计的关系（勿重复造轮子）

- **授权中心 2.0**（施工中，`tool-authorization-plan.md`）：本稿是其「无人值守语义」的升级与守护层；§八 v1 多数点在它收尾后顺接，不推翻其三档/档案/审计结构。
- **定时任务 / 离开模式**（`scheduled-ai-task-plan.md`）：预授权清单、通知、急停、接棒对齐；建议同步修订其决策 8（见本稿 §L2.3）。
- **无障碍设备操作**（`tool-authorization-plan.md` v2）：共用「操作会话 + 急停 + 审计」。
- **E4 目标托管**：预算面板守夜策略衔接（见 §L2.1#6）。

---

## 十三、给施工会话的最小切入建议

1. 先读：本稿 + `tool-authorization-plan.md` + `scheduled-ai-task-plan.md` §6/§9 + 最新 handoff（`handoff-2026-10-13-batch.md` 与 `handoff-gap-sweep-2026-10-14.md`）。
2. 动手前先 `git log/status` 核实授权中心 2.0 的最终落地形态，避免与其施工中的文件冲突。
3. 建议第一刀：**§L2.1 残弹六条升级 + §L2.5 兜底超时**（最直接回应「无人值守不卡死」），再做锚点/报告/急停。
4. 每批走统一标准：编译 → qa → 双语/主题检查；本稿涉及权限/提示词/迁移时按既有纪律执行。

---

## 状态
- [x] 调研（Claude 官方文档 + AiCode 代码全核实）
- [x] 设计落档（本稿，2026-10-14）
- [x] 决策代选（§九，可推翻）
- [x] **实施 v1 第一刀**（2026-10-14，提交 `30ebde3`）：残弹六条 + 兜底超时（10 分钟/竞态防护）+ 守夜开关 + 审计扩展（ALLOW_WATCH/WATCH_TIMEOUT）+ 通知 + 提示词；编译绿、单测 1167/0、外部模型审查（2 处严重已修：超时竞态、find 多 exec）；待真机验收（见 `~/workspace/.aicode/notes/release-pending-verification.md`）
- [ ] v1 第二刀：锚点（git tag）/ 守夜报告 / 常驻通知急停（L4 剩余）
- [ ] v1.5：规则语言 v2（路径/域名/会话级）、守护代理、Shizuku 预授权、在场检测
- [ ] v2：审查模型（仅灰区）、注入扫描、LD_PRELOAD 实验

## 附：本稿引用（代码位置与外部依据）

**Claude Code 官方依据**（2026-10 检索）：
- https://docs.claude.com/en/docs/claude-code/sandboxing （沙箱：写域/网络代理/auto-allow/严格模式/failIfUnavailable）
- https://docs.claude.com/en/docs/claude-code/permission-modes （模式：auto 分类器 / 致命删除倒计时拒绝 / 受保护路径 / dontAsk / bypass）
- https://code.claude.com/docs/en/permissions （规则：deny>ask>allow / 路径 glob / 会话级保存）
- https://code.claude.com/docs/en/checkpointing （检查点与 Bash 盲区声明）

**AiCode 代码位置（已核实）**：
- 权限引擎：`app/src/main/java/com/aicode/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt`（tier 插点 :130、AUTO 分支与 safetyDisabled :147）；`ShellCommandParser.kt`（不可判定条件 :13-20）；`BuiltInSafeCommands.kt`
- 档位/档案（施工中）：`ToolPermissionCatalog.kt`（离开模式 :104-117）、`ToolPermissionTierRepository.kt`、`PermissionAuditRecorder.kt`
- 挂起与裁决：`feature/agent/domain/tool/ToolPermissionManager.kt`（awaitApproval :34）；`feature/agent/domain/workflow/StatefulAgentWorkflow.kt`（:406 / :1599）
- 计划/预算：`feature/agent/domain/tool/mode/PlanApprovalManager.kt`（:32）、`feature/agent/domain/workflow/BudgetApprovalManager.kt`
- 通知/急停：`feature/agent/domain/notification/PermissionNotificationCenter.kt`、`PermissionActionReceiver.kt`
- 容器挂载/环境/代理：`feature/agent/domain/container/LinuxContainerEngine.kt`（buildBaseProotArgv / buildContainerEnv）、`core/net/AppProxy.kt`（proxyEnv）
- 钩子：`feature/hooks/domain/HookRunner.kt`、`HookModels.kt`
- 检查点：`feature/agent/domain/checkpoint/CheckpointManager.kt`
- 提示词：`app/src/main/assets/prompts/50-safety.md`、`65-capabilities.md`
