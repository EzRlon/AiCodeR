---
name: skill-mcp-integration-2026-10
description: 技能/MCP 榜单集成结果（2026-10-27）：新装 6 技能＋Context7 MCP，addyosmani 全仓判定无增量未装
---
# 技能与 MCP 集成记录（2026-10-27）

用户给了一份「Agent Skill / MCP Top10」榜单要求审计并按推荐集成，原则「不养废物」（有重叠就不装）。
**注意：用户纠正过集成目标——必须集成进 App 本体（assets 随版本分发），不是装进开发容器的 ~/.aicode/skills。**

## 已完成
- **Context7 MCP**：App 本体已内置推荐入口（McpAddSheet.kt 的 RecommendedContext7Config，设置→MCP→添加面板「推荐」区一键预填，默认不启用），无需改代码；开发容器全局 mcp.json 另行配置一份自用。
- **新装 6 个内置技能到 app/src/main/assets/skills/**（App 启动时经 ContainerInstaller.extractBuiltinSkills 自动释放到设备，随版本升级覆盖更新；均含许可证文件）：
  - frontend-design（源 anthropics/skills，官方版）
  - ui-ux-pro-max（源 nextlevelbuilder/ui-ux-pro-max-skill，已瘦身：删 scripts/tests、validate_data.py、phosphor-icons-upstream.json、google-font-licenses.json 共约 1.5MB 开发侧文件，运行时只依赖 core.py/search.py+数据 CSV）
  - brainstorming / writing-plans / executing-plans / verification-before-completion（源 obra/superpowers，补齐规划链）
- **能力感知已同步**：65-capabilities.md 新增设计技能与规划链两条；docs-site capabilities.md 新增「内置设计与规划技能」条目。
- **判定不装（防重复）**：find-skills、ECC 整包、caveman、graphify（与自带记忆图谱/技能商店/压缩重叠）；Filesystem/Playwright/BrowserUse/MarkItDown MCP（原生能力覆盖）；shadcn/ChromeDevTools（不适配安卓）；**addyosmani/agent-skills 25 个技能逐一比对全部与已装 superpowers/ponytail/原生规划重叠，全仓不装**。

## 备注
- 开发容器 ~/.aicode/skills 里那 6 个副本因安全层拦截 rm 未删（无害，仅影响开发环境不影响 App）。
- 榜单星数虚高（如 superpowers 实际约 3 万非 30.2 万），不可作决策依据。