---
name: token-saver-builtin-plan
description: 内置技能批设计（2026-10-15 用户要求）：省token 2 技能 + MCP 开发套件 7 技能（官方来源已核实）+ MCP 推荐区（Context7 零安装一键）+ 释放机制与能力感知；排队中
---

# 省 Token 能力内置批（2026-10-15 用户要求）

## 需求原话
"将目前最好的节省 token 技能、MCP 服务器，内置到软件中，并随项目的更新能自动更新；最大化优化压缩节省上下文的技能也同样内置集成。"

## 调研结论（2026-10-15）
- **省 token MCP 口碑最佳**：
  - **Context7**（upstash，~63k★）：最新库文档注入、防 AI 猜 API；**官方 HTTP 托管** `https://mcp.context7.com/mcp`——零安装，与 AiCode 的远程 HTTP MCP 完美契合。
  - **Serena**（oraios，~30k★）：LSP 语义检索 + 符号级编辑，最省"读文件"token；Python stdio，需容器 aarch64 可用性验证。
  - dbhub（bytebase）：token-efficient 多数据库 MCP。
- **省 token 技能生态**：caveman（输出压缩 65-75%）、spartan（双向压缩 70-85%）、context-compression（tokens-per-task 原则）、token-optimization（缓存/上下文分叉/输出过滤体系）。
- **AiCode 已有**（避免重复）：自动+手动压缩、交接摘要、子代理隔离、技能/MCP 系统；缺"内置省 token 纪律技能 + 一键 MCP 推荐"。

## 设计
1. **内置技能 ×2**（assets 释放到 `~/.aicode/skills/`，覆盖式、随 App 升级自动更新——与 extractPrompts 机制一致）
   - `token-saver`：输出精简（结论先行/不灌水/不重复）、工具输出按需过滤、精确读取（先搜后读/窗口读）、大输出交子代理只带回摘要、及时用压缩/交接。
   - `context-compression`：何时压缩、压缩保留项（改过的文件/命令/决策）、tokens-per-task 原则（防过度压缩导致重取信息反噬）。
2. **MCP 推荐**：MCP 添加面板新增"推荐"区——**Context7（HTTP，一键添加，默认禁用）**；Serena（stdio，附安装说明；容器验证通过则提供一键）。
3. **机制**：新增 `extractBuiltinSkills`（照 `extractPrompts` 模式：启动释放、覆盖式）；MCP 预设清单内置代码随 App 升级更新。
4. **能力感知**（项目纪律）：`capabilities.md` 登记（场景：控成本/省上下文）+ `65-capabilities.md` 速查同步；docs-site 同步。

## MCP 开发套件（7 技能，2026-10-15 用户补充清单，来源已核实）
用户清单核实为两个官方仓库的成品，作为「内置技能」第二组纳入：
- **Anthropic `mcp-server-dev` 插件**（anthropics/claude-plugins-official，官方 3.4 万安装）：
  - `build-mcp-server`：入口五阶段工作流（用例询问 → 部署模型（远程 HTTP / elicitation / MCP App / MCPB / 本地 stdio）→ 工具设计模式（单动作 vs search+execute）→ 框架（TS SDK / FastMCP）→ 脚手架）
  - `build-mcp-app`：为服务器加交互 UI（表单/选择器/图表/实时仪表盘，沙箱 iframe 在聊天内渲染）
  - `build-mcpb`：本地 stdio 服务器+运行时打包为 .mcpb 单文件（用户免装 Node/Python）
  - 每技能含 references/（认证流程、工具设计、widget 模板、manifest schema、安全加固）
- **MCP 官方 `ext-apps` 仓库**（modelcontextprotocol/ext-apps，4 技能）：
  - `create-mcp-app`（脚手架）/ `migrate-oai-app`（OpenAI Apps → MCP Apps）/ `add-app-to-server`（给现有服务器加 UI）/ `convert-web-app`（Web 应用 → 混合 MCP App）
- 兼容性：SKILL.md + references 开放格式，AiCode 技能系统直接兼容；references 由 AI 按需 readFile（技能目录 /root/.aicode/skills/ 对 AI 可见）。
- **局限提示**：MCP App 的交互 UI 需在支持 MCP Apps 的宿主渲染（Claude Desktop 等）；AiCode 聊天不渲染 inline iframe——技能产出为服务器代码，用户交付到目标宿主使用；部分技能需 Node 20+（容器可备）。
- 更新策略：技能内保留来源标注与版本；随 AiCode 版本更新（未来可定期同步上游）。
- **内置清单裁决（2026-10-15，尺子：①AiCode 内开发闭环（写→跑→测→接回 AiCode 验证）②场景频度）**：
  - ✅ 第一批内置（4）：`token-saver`、`context-compression`、`build-mcp-server`、`build-mcpb`（服务器代码类——容器可跑、Inspector 可测、可接 AiCode 自身 MCP 客户端验证，闭环完整）
  - ⏳ 延后观察（4）：`build-mcp-app`、`create-mcp-app`、`add-app-to-server`、`convert-web-app`（MCP App UI 系——渲染/调试需支持它的宿主，AiCode 无法内验证；等真实需求）
  - ❌ 不内置（1）：`migrate-oai-app`（存量 OpenAI Apps 迁移场景过窄）

## 排期
会话交互批 → 密钥管理批 → **内置技能批（4 技能第一批 + Context7 一键 + 释放机制；MCP App 系 4 个留观察）**；其中 Context7 一键添加 + 技能释放机制成本低，可提前插入。
