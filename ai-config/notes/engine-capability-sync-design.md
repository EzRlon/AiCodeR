# 能力全域打通 · 设计定稿（2026-10-25，用户拍板方向 + AI 细化）

## 用户需求原话（摘要）

切换引擎后：①原引擎已有的东西不掉；②自动切到新引擎自身体系的能力；③入口都映射到原有体系窗口，新引擎自身的东西以增量出现；④不要让用户自己下指令靠 AI 补配置。

## 方案：自动同步层（切换时生成 + 合并，非覆盖、无常驻）

### 原则
- 遵循 engine-native-first-principle（见记忆）：能力真身在引擎原生体系，App 管 UI 入口与自动生成。
- 同步时机：引擎切换时 / 该引擎会话首回合前，一次性写入；不做常驻轮询（功耗纪律）。
- 合并策略：只写 App 管理的带标记区块（如 `#region aicode-managed` / TOML 注释标记），保留用户手写内容；每次同步先清旧标记区块再写入最新。

### 映射表

| App 侧数据（唯一入口） | Claude Code | Codex | OpenCode |
|---|---|---|---|
| 会话历史/记忆/账本/技能内容 | aicode_bridge（已上线） | 同左（已上线） | 同左（已上线） |
| 模型选择（模型页） | --model（已上线） | -m（已上线） | -m provider/model（已上线） |
| 用户自装 MCP（MCP 管理页） | 桥接生成的 mcp-config.json 顺路合并（**待做**） | config.toml `[mcp_servers]` 标记区块（**待做**） | opencode.json `mcp` 标记区块（**待做**） |
| 智能体定义（智能体管理页） | `.claude/agents/*.md` 生成（**待做**） | 等原生 agent 机制稳定（**挂起**） | opencode.json agents 标记区块（**待做**） |
| 登录态/安装（引擎卡） | 已上线 | 已上线 | 已上线 |
| 引擎特有选项 | 权限走授权中心（已上线） | 沙箱档位开关（**待做**，卡片小开关） | --auto 开关（**待做**） |

### 功能可用性对齐（用户定，2026-10-25：切引擎后软件所有功能都正常使用）

总验收标准：引擎切换后 App 功能体验不掉队。已知缺口与处置：
1. **图片/附件传递（P1，必修）**：三个桥当前都丢弃 inputImages/attachments——标注汇总（活标注/截屏标注的图）发到 CLI 引擎会话会丢图。补法：claude 走 stdin content blocks（image 块）、codex 走 `-i <落盘临时文件>`、opencode 走 `-f`。
2. **本体工具选择性桥接（P2）**：本体 toolRegistry 里 CLI 引擎用不到的高价值工具（如 browser）可经 aicode_bridge 增量暴露，逐个评估、不做全量镜像。
3. **纯本体工作流（专家团 UI/PLAN/goal//compress）**：不桥接（引擎原生体系优先已定）；引擎卡提示已明示，不算缺口。
4. **定时任务/队列/停止/悬浮球**：走通用链路，已可用；验收清单里逐项过一遍。

### 分期
1. **批一（MCP 映射）**：McpManager 的已启用 stdio/HTTP 服务器 → 三引擎原生配置合并写入；引擎卡显示「已映射 N 个 MCP」。
2. **批二（智能体映射）**：App 自定义 agent → Claude agents 目录 + OpenCode agents 配置；引擎会话可用 @ 召唤。
3. **批三（引擎特有开关）**：Codex 沙箱档位、OpenCode --auto、默认引擎支持扩展到 Codex/OpenCode。

### 关联
- 引擎桥接台账：engine-bridge-phase2/phase3-codex/phase3-opencode/recall-disposition（均在 ai-config/notes/）
- 排队依据：handoff-2026-10-25-final.md §四
