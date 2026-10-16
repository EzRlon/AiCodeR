---
name: reverse-engineer-anything
description: 逆向分析（REA）：分析 App / APK / JS-Electron 包 / 归档包 / 二进制元数据等「没有源码的成品软件」是怎么实现的——解释功能原理、定位实现代码、复刻某功能、对比版本差异。用户问「这个 App/这个功能怎么实现的」「帮我仿一个」「看看某软件内部逻辑」「反编译看看」或需要类/方法级分析、字符串与引用追踪时使用。普通源码仓库的架构分析不要用它。
---

# REA 逆向分析（Reverse Engineer Anything）

用于「分析已发布的成品软件」：APK、JS/Electron 包、归档包（ZIP/APK/IPA/MSIX 等）、
带元数据的 Go/Rust 二进制等。分析是**静态**的（不运行目标），结论必须可追溯到证据。

## 本环境说明（先读）

- 本 App 已通过 MCP 接入 REA（服务器名 `rea`，工具名前缀 `mcp__rea__*`）；
  默认按白名单放行核心分析工具（约 21 个）。
- **工具不可见 / 调用被拒时**：通常是「逆向分析」环境未装或 MCP 条目未启用，引导用户两步：
  ① 设置 → 容器与镜像 → 环境安装 → 勾选「逆向分析」；
  ② 设置 → MCP → 添加 → 推荐区 → 点「REA 逆向工程」→ 保存。
  完成后让用户**新开或重启会话**再继续分析。
- 可用引擎：APK 分析（JADX + JDK，已随场景装好）；JS/Electron、Go/Rust 元数据、归档包为内置静态分析（无需引擎）。
  native 深度反编译（Ghidra/IDA/Hopper）未部署——相关工具不可用属预期，如实告知限制即可。
- 用 `binary_session`（空参数）可随时查看当前会话的**真实工具可用性**；不可用时说明原因，不要假装分析过。

## 核心工作流（调查方法论）

1. **先路由目标类型，再选入口**：
   - APK（含 Flutter 识别）：`inspect_android_package` 起步 → 需要时 `search_android_classes`
     （找候选类）/ `inspect_android_class`（类成员）/ `inspect_android_method`（单方法反编译，重载用 overload_index）
     / `trace_android_references`（静态引用）；Flutter 专用 `identify_flutter_build`。
   - JS / Electron 包（目录或 ASAR）：`analyze_javascript_application`；大包先 `detail: "summary"`。
   - 归档包成员清单（ZIP/APK/IPA/MSIX…）：`open_binary` + `inspect_artifact`。
   - 通用二进制/其它：`open_binary` → `binary_overview`；字符串与引用：`list_strings` / `search_strings` / `find_xrefs_to_name`；
     函数级：`analyze_function` / `procedure_pseudo_code` / `procedure_callers` / `batch_decompile`。
2. **summary 优先**：大结果先总览（`detail:"summary"`），只针对未解答的具体问题做聚焦深挖；
   不要重复完全相同的分析；不要凭空猜测选择子。
3. **证据纪律（硬要求）**：每条结论区分「观察 / 推断 / 未知」；引用证据（Evidence ID、位置）；
   保留 limitations 与覆盖不完整说明；**不得暗示静态分析观察到了运行行为**；负面结果只限其搜索边界。
4. **多步调查**：把请求拆成问题清单（每个问题需要什么证据）→ 从最小总览入手 → 逐个假设聚焦推进
   → 维护「发现台账」（结论 ↔ 证据 ↔ 未解问题）→ 收尾时回看清单，标注已回答/部分回答/未解决。
5. **收尾**：用平实语言向用户解释发现并绑定证据；用户要「照着做一个」时，切回普通编码工具，
   把「观察到的行为」与「你的设计选择」分开；分析完用 `close_binary` 释放会话。

## 深入参考（按需读取本技能目录下的 references/）

- `references/android-applications.md` —— APK 分析细则（工具、JADX/JDK 前置、限制）。
- `references/javascript-applications.md` —— JS/Electron 包分析细则。
- `references/native-and-artifacts.md` —— 归档/二进制元数据/抽取细则。
- `references/evidence-workflows.md` —— 证据分页、对比、未知项与复核。
- `references/runtime-observation.md` —— 运行时观察（本环境多数依赖未部署，仅供概念参考）。

## 法规与安全边界

仅用于分析**用户拥有或获授权分析**的软件（学习理解、兼容互操作、安全研究、恢复自有代码等正当用途）。
不为破解授权、绕过保护、恶意用途提供协助；遇到此类请求明确说明边界并拒绝。
