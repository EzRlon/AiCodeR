# 插件

设置 → 插件 里有两类插件，它们的归属完全不同，别混着看：

| 类型 | 装进哪里 | 谁在管 |
| --- | --- | --- |
| **原生插件** | 本 App 自己的能力（技能、MCP、钩子等） | AiCodeR 自己 |
| **引擎插件** | Claude Code / Codex / OpenCode 等外部引擎 | 对应引擎自己的 CLI |

引擎插件的装与卸在「在线商店 → 插件」里按引擎进行，设置里只做查看与卸载。本页讲原生插件。

## 装一个原生插件

原生插件就是一个文件夹，放在 App 私有目录的 `plugins` 下（容器内即 `~/.aicode/plugins`），
**文件夹名必须等于插件清单里的 `id`**。放进去之后打开 设置 → 插件 → 原生插件 就能看到它。

最小结构：

```
~/.aicode/plugins/com.example.demo/
├── plugin.json
└── skills/
    └── my-skill/
        └── SKILL.md
```

`plugin.json` 长这样：

```json
{
  "id": "com.example.demo",
  "name": "示例插件",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "一句话说明它做什么",
  "disclaimer": "使用本插件产生的任何后果由使用者自行承担。",
  "permissions": ["workspace.read"],
  "capabilities": {
    "skills": ["skills"]
  }
}
```

`id` 要用小写反向域名形式（`com.example.demo`），`version` 和 `name` 不能为空。

## 启用与卸载

打开插件行右侧的开关即为启用。第一次启用前需要阅读并同意免责声明（清单里的 `disclaimer`），
未同意的插件不允许启用——这是准入条件，不是提示。

启用时插件会把自己的内容装进 App 既有目录，所以装完之后在「技能」「MCP」「子代理」等页面里
就能像自带功能一样看到它：

| 清单字段 | 装到哪里 |
| --- | --- |
| `capabilities.skills` | 设置 → 技能（`~/.aicode/skills`） |
| `capabilities.agents` | 设置 → 子代理（`~/.aicode/agents`） |
| `capabilities.teams` | 设置 → 专家团（`~/.aicode/teams`） |
| `capabilities.scripts` | 设置 → 脚本（`~/.aicode/scripts`） |
| `capabilities.prompts` | 设置 → 提示词（`~/.aicode/prompts`） |
| `capabilities.mcp` | MCP 服务器列表（全局层） |
| `capabilities.hooks` | 钩子表（`~/.aicode/hooks.json`） |
| `capabilities.assets` | 清单里 `to` 指定的 `~/.aicode/…` 或 `~/workspace/…` |

关闭开关即摘除：插件装进去的技能、MCP 条目、钩子会一并撤掉，AI 那边立刻看不到它。

**同名不覆盖。** 如果目标位置已经有同名的技能或 MCP 服务，插件这一项会被跳过，不会改掉你原有的东西。
跳过的原因会列在插件详情页的「没装上的内容」里，一条一条写清楚。

## 详情页能看什么

- 状态（已启用 / 已停用）、版本、作者、许可协议
- 免责声明全文
- 申请的权限
- 提供的能力（几项技能、几个 MCP、几个钩子……）
- 占用文件清单：插件装了哪些文件，卸载就按这份清单删
- 插件目录的绝对路径，可直接在文件管理器里打开

## 出错怎么办

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| 插件列表下出现红字「有 N 个插件目录没能加载」 | 清单缺字段、`id` 不合法、目录名与 `id` 不一致 | 按红字里列出的具体原因改 `plugin.json` |
| 开关打不开 | 还没同意免责声明 | 进详情页读一遍免责，再打开开关 |
| 详情页「没装上的内容」里有「目标已存在同名内容」 | 你已有同名技能 / MCP | 改名，或先删掉原有的 |
| 有「容器未就绪，环境准备未执行」 | 插件声明了 `provision`，但容器没启动 | 启动一次容器，关掉再打开插件开关 |
| 启用后技能页没看到 | 插件目录结构不对（应为 `skills/<技能名>/SKILL.md`） | 对照上面的最小结构检查 |

## 清单字段速查

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 小写反向域名，必须与文件夹名一致 |
| `name` | 是 | 显示名 |
| `version` | 是 | 版本号，任意非空字符串 |
| `apiVersion` | 否 | 宿主 API 版本，当前只支持 `1` |
| `description` | 否 | 列表里显示的一行说明 |
| `author` / `license` / `homepage` | 否 | 元信息，详情页展示 |
| `disclaimer` | 否 | 免责文本；写了就必须同意后才能启用 |
| `permissions` | 否 | 申请的权限名；出现不认识的名字会导致清单不合法 |
| `capabilities` | 否 | 见上表 |
| `slots` | 否 | 界面入口占位声明，当前版本保留未启用 |
