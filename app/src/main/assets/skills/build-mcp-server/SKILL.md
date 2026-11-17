---
name: build-mcp-server
description: This skill should be used when the user asks to "build an MCP server", "create an MCP", "make an MCP integration", "wrap an API for AI hosts", "expose tools to AI", or discusses building something with the Model Context Protocol. Entry point for MCP server development — interrogates the use case, picks the deployment model (remote HTTP, MCPB, local stdio) and tool-design pattern, then hands off to specialized skills. 中文：构建 MCP 服务器的入口技能——先明确用例，再确定部署形态、选择工具设计模式并衔接后续技能。
version: 0.1.0
---

# Build an MCP Server

> 来源：anthropics/claude-plugins-official（`plugins/mcp-server-dev/skills/build-mcp-server`），获取日期 2026-10-15；已按 AiCode 环境本地化。

You are guiding a developer through designing and building an MCP server that works seamlessly with MCP hosts. MCP servers come in many forms — picking the wrong shape early causes painful rewrites later. Your first job is **discovery, not code**.

**本地化说明（AiCode）**：本技能面向 AiCode 环境——你在 AiCode 容器（Alpine）内用 Bash 工具搭建与测试服务器，产物可经 `manageMcp` 接回 AiCode 自身的 MCP 客户端验证（见 Phase 6）。MCP 规范是通用标准；如服务器要发布到某个特定宿主平台（如 Claude 连接器目录），再以其平台最新文档为准。

Do not start scaffolding until you have answers to the questions in Phase 1. If the user's opening message already answers them, acknowledge that and skip straight to the recommendation.

---

## Phase 1 — Interrogate the use case

Ask these questions conversationally (batch them into one message, don't interrogate one-at-a-time). Adapt wording to what the user has already told you.

### 1. What does it connect to?

| If it connects to… | Likely direction |
|---|---|
| A cloud API (SaaS, REST, GraphQL) | Remote HTTP server |
| A local process, filesystem, or desktop app | MCPB or local stdio |
| Hardware, OS-level APIs, or user-specific state | MCPB |
| Nothing external — pure logic / computation | Either — default to remote |

### 2. Who will use it?

- **Just me / my team, on our machines** → Local stdio is acceptable (easiest to prototype)
- **Anyone who installs it** → Remote HTTP (strongly preferred) or MCPB (if it *must* be local)
- **Users who want UI widgets in hosts that support MCP Apps** → MCP app (remote or MCPB；AiCode 聊天暂不渲染内嵌 widget)

### 3. How many distinct actions does it expose?

This determines the tool-design pattern — see Phase 3.

- **Under ~15 actions** → one tool per action
- **Dozens to hundreds of actions** (e.g. wrapping a large API surface) → search + execute pattern

### 4. Does a tool need mid-call user input or rich display?

- **Simple structured input** (pick from list, enter a value, confirm) → **Elicitation** — spec-native, zero UI code. *Host support is still rolling out* — always pair with a capability check and fallback. See `references/elicitation.md`.
- **Rich/visual UI** (charts, custom pickers with search, live dashboards) → **MCP app widgets** — iframe-based, needs `@modelcontextprotocol/ext-apps`. See `build-mcp-app` skill（未随 AiCode 内置；如环境中已安装则用 `loadSkill` 加载）。
- **Neither** → plain tool returning text/JSON.

### 5. What auth does the upstream service use?

- None / API key → straightforward
- OAuth 2.0 → you'll need a remote server with CIMD (preferred) or DCR support; see `references/auth.md`

---

## Phase 2 — Recommend a deployment model

Based on the answers, recommend **one** path. Be opinionated. The ranked options:

### ⭐ Remote streamable-HTTP MCP server (default recommendation)

A hosted service speaking MCP over streamable HTTP. This is the **recommended path** for anything wrapping a cloud API.

**Why it wins:**
- Zero install friction — users add a URL, done
- One deployment serves all users; you control upgrades
- OAuth flows work properly (the server can handle redirects, DCR, token storage)
- Works across MCP hosts — Claude, ChatGPT, AiCode, and other clients

**Choose this unless** the server *must* touch the user's local machine.

→ **Fastest deploy:** Cloudflare Workers — `references/deploy-cloudflare-workers.md` (zero to live URL in two commands)
→ **Portable Node/Python:** `references/remote-http-scaffold.md` (Express or FastMCP, runs on any host)

### Elicitation (structured input, no UI build)

If a tool just needs the user to confirm, pick an option, or fill a short form, **elicitation** does it with zero UI code. The server sends a flat JSON schema; the host renders a native form. Spec-native, no extra packages.

**Caveat:** Host support is new and uneven. The SDK throws if the client doesn't advertise the capability. Always check `clientCapabilities.elicitation` first and have a fallback — see `references/elicitation.md` for the canonical pattern. This is the right spec-correct approach; host coverage will catch up.

Escalate to `build-mcp-app` widgets when you need: nested/complex data, scrollable/searchable lists, visual previews, live updates.

### MCP app (remote HTTP + interactive UI)

Same as above, plus **UI resources** — interactive widgets rendered in chat. Rich pickers with search, charts, live dashboards, visual previews. Built once, renders in hosts that support MCP Apps (e.g. Claude, ChatGPT) — AiCode 聊天不渲染内嵌 widget，产出交付到目标宿主使用。

**Choose this when** elicitation's flat-form constraints don't fit — you need custom layout, large searchable lists, visual content, or live updates.

Usually remote, but can be shipped as MCPB if the UI needs to drive a local app.

→ 用 `loadSkill` 加载 **`build-mcp-app`** 技能继续（未随 AiCode 内置；若不可用，先完成服务端部分，UI 稍后再做）。

### MCPB (bundled local server)

A local MCP server **packaged with its runtime** so users don't need Node/Python installed. The sanctioned way to ship local servers.

**Choose this when** the server *must* run on the user's machine — it reads local files, drives a desktop app, talks to localhost services, or needs OS-level access.

→ 用 `loadSkill` 加载 **`build-mcpb`** 技能继续（随 AiCode 内置）。

### Local stdio (npx / uvx) — *not recommended for distribution*

A script launched via `npx` / `uvx` on the user's machine. Fine for **personal tools and prototypes**. Painful to distribute: users need the right runtime, you can't push updates, and there's no good distribution channel.

Recommend this only as a stepping stone. If the user insists, scaffold it but note the MCPB upgrade path.

---

## Phase 3 — Pick a tool-design pattern

Every MCP server exposes tools. How you carve them matters more than most people expect — tool schemas land directly in the host model's context window.

### Pattern A: One tool per action (small surface)

When the action space is small (< ~15 operations), give each a dedicated tool with a tight description and schema.

```
create_issue    — Create a new issue. Params: title, body, labels[]
update_issue    — Update an existing issue. Params: id, title?, body?, state?
search_issues   — Search issues by query string. Params: query, limit?
add_comment     — Add a comment to an issue. Params: issue_id, body
```

**Why it works:** The model reads the tool list once and knows exactly what's possible. No discovery round-trips. Each tool's schema validates inputs precisely.

**Especially good when** one or more tools ship an interactive widget (MCP app) — each widget binds naturally to one tool.

### Pattern B: Search + execute (large surface)

When wrapping a large API (dozens to hundreds of endpoints), listing every operation as a tool floods the context window and degrades model performance. Instead, expose **two** tools:

```
search_actions  — Given a natural-language intent, return matching actions
                  with their IDs, descriptions, and parameter schemas.
execute_action  — Run an action by ID with a params object.
```

The server holds the full catalog internally. The model searches, picks, executes. Context stays lean.

**Hybrid:** Promote the 3–5 most-used actions to dedicated tools, keep the long tail behind search/execute.

→ See `references/tool-design.md` for schema examples and description-writing guidance.

---

## Phase 4 — Pick a framework

Recommend one of these two. Others exist but these have the best MCP-spec coverage and host compatibility.

| Framework | Language | Use when |
|---|---|---|
| **Official TypeScript SDK** (`@modelcontextprotocol/sdk`) | TS/JS | Default choice. Best spec coverage, first to get new features. |
| **FastMCP 3.x** (`fastmcp` on PyPI) | Python | User prefers Python, or wrapping a Python library. Decorator-based, very low boilerplate. This is jlowin's package — not the frozen FastMCP 1.0 bundled in the official `mcp` SDK. |

If the user already has a language/stack in mind, go with it — both produce identical wire protocol.

---

## Phase 5 — Scaffold and hand off

Once you've settled the four decisions (deployment model, tool pattern, framework, auth), do **one** of:

1. **Remote HTTP, no UI** → Scaffold inline using `references/remote-http-scaffold.md` (portable) or `references/deploy-cloudflare-workers.md` (fastest deploy). 在 AiCode 容器内完成搭建与冒烟测试（Node/npm 与 Python 未装时，引导用户在终端执行 `aicode` 或初始化菜单补齐）。This skill can finish the job.
2. **MCP app (UI widgets)** → Summarize the decisions so far, then load the **`build-mcp-app`** skill with `loadSkill`（未随 AiCode 内置；若不可用，先完成服务端部分）.
3. **MCPB (bundled local)** → Summarize the decisions so far, then load the **`build-mcpb`** skill with `loadSkill`（随 AiCode 内置）.
4. **Local stdio prototype** → Scaffold inline (simplest case), flag the MCPB upgrade path.

When handing off, restate the design brief in one paragraph so the next skill doesn't re-ask.

---

## Beyond tools — the other primitives

Tools are one of three server primitives. Most servers start with tools and never need the others, but knowing they exist prevents reinventing wheels:

| Primitive | Who triggers it | Use when |
|---|---|---|
| **Resources** | Host app (not the model) | Exposing docs/files/data as browsable context |
| **Prompts** | User (slash command) | Canned workflows ("/summarize-thread") |
| **Elicitation** | Server, mid-tool | Asking user for input without building UI |
| **Sampling** | Server, mid-tool | Need LLM inference in your tool logic |

→ `references/resources-and-prompts.md`, `references/elicitation.md`, `references/server-capabilities.md`

---

## Phase 6 — 测试（AiCode 闭环）与发布

Once the server runs:

1. **容器内冒烟测试**：直接运行服务器；用官方 Inspector 交互调试——stdio 服务器 `npx @modelcontextprotocol/inspector node build/index.js`（入口路径按实际项目替换），远程 HTTP 服务器在 Inspector 界面里填服务地址连接（均为 AiCode 容器内直接可用）。
2. **接回 AiCode 验证**：用 `manageMcp` 添加服务器（远程 HTTP 传 `url`，本地 stdio 传 `command` 与 `args`，添加即为启用）——配置在下一次会话生效，届时在对话中实测工具调用；也可提示用户在「设置 → MCP 服务器」查看连接状态与工具列表。
3. **发布（可选）**：自用到此即可；需要分发时发布到 GitHub / npm 等平台。如目标宿主是 Claude，其连接器测试与提交要求见 https://claude.com/docs/connectors/building/testing 与 https://claude.com/docs/connectors/building/submission 。

---

## Quick reference: decision matrix

| Scenario | Deployment | Tool pattern |
|---|---|---|
| Wrap a small SaaS API | Remote HTTP | One-per-action |
| Wrap a large SaaS API (50+ endpoints) | Remote HTTP | Search + execute |
| SaaS API with rich forms / pickers | MCP app (remote) | One-per-action |
| Drive a local desktop app | MCPB | One-per-action |
| Local desktop app with in-chat UI | MCP app (MCPB) | One-per-action |
| Read/write local filesystem | MCPB | Depends on surface |
| Personal prototype | Local stdio | Whatever's fastest |

---

## Reference files

- `references/remote-http-scaffold.md` — minimal remote server in TS SDK and FastMCP
- `references/deploy-cloudflare-workers.md` — fastest deploy path (Workers-native scaffold)
- `references/tool-design.md` — writing tool descriptions and schemas models understand well
- `references/auth.md` — OAuth, CIMD, DCR, token storage patterns
- `references/resources-and-prompts.md` — the two non-tool primitives
- `references/elicitation.md` — spec-native user input mid-tool (capability check + fallback)
- `references/server-capabilities.md` — instructions, sampling, roots, logging, progress, cancellation
- `references/versions.md` — version-sensitive claims ledger (check when updating)
