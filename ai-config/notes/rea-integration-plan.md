# REA（Reverse Engineer Anything）集成方案

> 2026-10-17 · 调研 + 容器实测（POC 全通过）· 目标：让「许愿码（AiCodeR）」获得手机端逆向分析能力
> 状态：**一期已实施**（95189ac 主体 + 21b3822/756b04d qa 处理；编译验证与 R8 交付进行中）

## 一、REA 是什么（为什么值得接）

- 原仓库 `morluto/rea`（现 `rea-tools/rea`），2026-10-10 上线，一周内 GitHub Star 1.5 万 → 23 万+，当前 25 万——近期最火的逆向工程项目。
- 定位：**一个 MCP 服务器 + CLI**，把逆向分析引擎（JADX / Ghidra / Hopper / IDA / apktool / binwalk 等）包装成 **AI Agent 可直接调用的接口**，让「AI 编程助手自己去做逆向」。
- 核心设计：
  1. **桥接层**：统一 MCP 接口，多引擎可插拔；
  2. **证据契约（Evidence as Trust）**：每条结论附带证据与局限说明（地址、引用、置信度、limitations）；
  3. **工作流注入**：安装时附带「reverse-engineer-anything」技能（调查方法论 search→XRef→call graph→decompile→重建）。
- 能力面：原生二进制、**Android APK（JADX）**、Android 设备（adb）、JS/Electron、.NET、网站、抓包、固件、Flutter、包资源、进程行为等。
- 官方接入形态（对我们最关键）：**标准 stdio MCP**——`npx -y rea-agents@<版本> mcp`；本地分析、结果带证据。

## 二、可行性结论（2026-10-17 容器实测 POC）

环境：用户的 aarch64 Alpine（3.21）+ PRoot 容器（即 App 内真实运行环境），Node 22.23.2、JDK 17.0.18、Python 3.12。

| POC | 内容 | 结果 |
|---|---|---|
| 1 | REA 6.5.0 本体运行（`rea doctor`） | ✅ node 检查通过；catalog = **207 个 MCP 工具** / 153 CLI 命令 / 6 prompts |
| 2 | 静态 JS 分析全链路（`analyze-javascript-application`） | ✅ 16 阶段全部完成，产出完整证据 JSON（模块图/调用链/每个关系带 source-range 与 limitations） |
| 3 | **APK 分析全链路**（`inspect-android-package`，用我们自己的 27MB R8 包） | ✅ jadx-headless-mcp 0.7.1（42.6MB，sha256 与 REA pin 值一致）+ JDK17 启动成功；**11.4s 加载、10975 个类、1983 个资源全量解析**；输出完整 Manifest + 证据链 |

已知偏差/限制（如实记录）：
- `rea doctor` 的 host 检查报 `unsupported_host: alpine 3.21.3`（官方支持 apt/dnf/pacman 系；**但实测分析链路不拦截**，三条链路均完整跑通）。若后续个别功能被拒 → 备选路径：把容器切到 Debian 镜像运行。
- APK 签名不验证（`signature_verification: not_performed`）；分析为静态（不执行 APK）。
- JADX 分析内存按容器可用内存自适应（实测 heap≈2.9GB）。超大 APK 会慢/吃内存。

前置缺口（POC 中已补/已确认）：
- **procps**（Linux 上 REA 的进程监督要求 procps 兼容 `ps`；Alpine 的 busybox ps 不兼容）——POC 已 `apk add procps`，**一期必须进场景脚本**。
- `REA_JADX_MCP_JAR` 环境变量指向 jadx jar——我们的 MCP 配置**已支持 env 字段**（`McpServerConfig.env`）✅。

## 三、集成设计（分期）

### 一期（建议先做）：容器场景 + MCP 预设 + 技能 + 能力感知 + 文档

1. **容器场景「逆向分析（REA）」**（`app/src/main/assets/aicode/lib/scenarios/` 新增，注册进 SCENARIO_IDS）：
   - 校验/安装：`procps`、Node ≥22、完整 JDK ≥17（含 javac）；
   - 下载并**校验** `jadx-headless-mcp-0.7.1-all.jar`（REA 官方 pin：sha256 `6e5eacf5…`）→ `/opt/rea/`；
   - 安装 REA：`npm i -g rea-agents@6.5.0`（版本 pin；npx 亦可，冷启动慢，全局装更快）；
   - 所有下载带 sha256 校验；网络走容器既有链路（多源策略沿用）。
2. **MCP 预设条目**（设置 → MCP → 「推荐」区，与 Context7 同机制）：
   - 名称 `rea`（工具名前缀 `mcp__rea__*`）；
   - stdio：`command = npx`（或全局 `rea`），`args = ["-y","rea-agents@6.5.0","mcp"]`；
   - `env = { REA_JADX_MCP_JAR: "/opt/rea/jadx-headless-mcp-0.7.1-all.jar" }`；
   - 默认不启用（与 Context7 一致：安装 ≠ 启用）。
3. **工具裁剪（一期必做，防 prompt 爆炸）**：
   - REA 注册 **207 个工具**远超 token 预算——不能全量暴露给模型；
   - 现有 `McpServerConfig.disabledTools`（黑名单）可用，但 207 挑禁用不实用 →**建议新增 `enabledTools` 白名单字段**（小改动，与 disabledTools 并存；语义：非空白名单 = 只放行名单内工具）；
   - REA 白名单建议（~20-30 个，实施时按 catalog 定稿）：`analyze`、`inspect`、`inspect-android-package`、`inspect-android-class`、`inspect-android-method`、`decompile`、`function`、`inspect-artifact`、`analyze-javascript-application`、`identify-flutter-build`、`evidence-export` 等核心只读分析工具。
4. **技能**：REA 的「reverse-engineer-anything」工作流指令 → 适配进本项目技能体系（`assets/skills/reverse-engineer-anything/SKILL.md`，随内置技能机制发布/更新）：
   - 裁剪 Claude Code/Codex 专属的 setup/doctor 说明；改为「本环境 MCP 已就绪，直接使用工具」；保留调查方法论与证据纪律；中英双语化。
5. **能力感知（两件套）**：
   - `capabilities.md`：「逆向分析」场景 → 能力条目（怎么问、支持什么目标、限制）；
   - `65-capabilities.md`：速查一行（「想搞懂某个 App/二进制/包是怎么实现的、想复刻某功能 → 用 REA 逆向能力（先装场景，对话即可分析）」）。
6. **引导文档**：`docs-site/docs/guide/reverse-engineering.md`：安装步骤、支持目标矩阵、提问示例（「分析这个 APK 的登录流程」「这个符号/函数是干嘛的」）、与「ADB 无线调试」组合（分析本机已装 App）、限制（静态分析、慢速说明）、**法务提示**。
7. **安全边界**：保留 REA 免责精神 + 产品文案明示——仅分析你拥有或获授权分析的软件；不协助破解/绕过授权/恶意用途。

### 二期（可选）：工作台 UI + 引擎扩展
- 「逆向工作台」页面：选文件（APK / ELF / JS 包）→ 任务进度 → 证据面板 + 反编译代码查看器（复用 sora-editor / 现有查看组件）→ 「继续问 AI」跳聊天深挖。
- 引擎扩展场景：apktool（资源解码）、binwalk/unblob（固件）、mitmproxy/tshark（抓包）、Chromium（网站分析，可与 VNC 联动看浏览器画面）。

### 三期（观察）：native 深度与上游同步
- Ghidra headless 在 aarch64+PRoot 的实测（需 JDK21 + ~1.5GB 引擎；内存/速度风险高）。
- Hopper Linux demo（需 Xvfb——可复用 VNC 图形链路）。
- REA 迭代极快（数天一个版本）：建立「版本跟进 + 回归清单」机制。

## 四、关键工程决策点（实施前定稿）

| 决策 | 倾向 | 理由 |
|---|---|---|
| 工具裁剪：enabledTools 白名单 vs disabledTools 生成 | **新增 enabledTools** | 207 → 挑 20-30 放行，白名单可读可维护；黑名单不实用 |
| REA 安装形态：全局 npm vs npx | **全局（场景装好）+ npx 兜底** | 全局启动快（MCP 每次会话启动）；npx 冷启动慢 |
| 版本 pin | rea-agents@6.5.0 / jadx 0.7.1（sha256） | REA 迭代快，避免 breakage；跟版本手动升级 |
| 场景与 MCP 的联动 | 场景装好后 MCP 条目即可用；未装时提示「先装场景」 | 避免用户装了 MCP 却缺引擎 |
| Alpine 警告 | 接受（实测不拦）+ 文档注明 Debian 备选 | 避免过度工程 |

## 五、风险与边界

- **网络**：npm/GitHub 下载（国内可达性）——沿用容器多源/代理经验；下载全带 sha256 校验。
- **性能**：PRoot 下 Java 分析（JADX）实测可接受（11s 加载 27MB APK）；超大 APK（数百 MB）慢/内存风险——文档注明。
- **Prompt 预算**：207 工具必须裁剪（已设计），否则每请求 tools 参数巨大。
- **法务**：免责声明 + 正当用途限定（产品文案 + 文档 + 技能内）。
- **上游演进**：pin 版本；升级走「版本跟进批」。
- **POC 残留物**（当前开发容器）：已装 procps、`/root/jadx-headless-mcp-0.7.1-all.jar`、npx 缓存 rea-agents 6.5.0——无害保留，供后续实施复用/对照。

## 六、一期实施清单（供排期）

1. 场景脚本 `rea.sh`（+SCENARIO_IDS 注册 + 名称/描述文案中英）
2. `McpServerConfig` 新增 `enabledTools`（模型+管理端+过滤点+读写兼容）与 REA 预设条目
3. REA 技能适配（提取 → 裁剪 → 双语 → `assets/skills/`）
4. 能力件两件套更新（capabilities.md + 65-capabilities.md）
5. 用户文档 `guide/reverse-engineering.md`
6. 安全文案（免责/正当用途）
7. 编译 + 单测 + qa + 真机 POC（装场景 → 分析一个 APK 走全流程）

## 七、实施记录（2026-10-17）

- **提交**：`95189ac`（主体，20 文件）→ `21b3822`（qa 处理，12 文件）→ `756b04d`（编译期修正）。
- **白名单定稿（24 个）**：binary_session / open_binary / close_binary / binary_overview / inspect_artifact / inspect_android_package / inspect_android_class / inspect_android_method / search_android_classes / trace_android_references / analyze_javascript_application / identify_flutter_build / inspect_go_binary / inspect_rust_binary / inspect_analysis_view / list_strings / search_strings / find_xrefs_to_name / analyze_function / procedure_pseudo_code / procedure_callers / batch_decompile / list_unknowns / export_evidence_bundle。
- **qa 复审（对 95189ac）**：无阻断；11 项改进**全部处理**——入口文案（终端 `aicode`）x4、apt 镜像指引、白名单对齐、编辑器白名单 UX、ManageMcpTool 覆盖保留、rea.sh 健壮性（Node≥22.19/javac 版本/wget 兼容/pin 幂等）、McpJsonCodec 提取 + 4 条回归单测。
- **实测记录**：场景安装/幂等（/opt/rea）；MCP stdio 链路（207 工具）；APK 分析（10975 类/11s）；均在本容器完成。
- **已知限制（一期）**：仅保证 Alpine 镜像全自动（Debian 系 Node 旧）；native 深度分析（Ghidra 等）未部署；对比类工具（compare_*）未放行。
- **POC 残留物**：本开发容器已装 /opt/rea 全套 + 全局 rea-agents；供后续复验复用。
