# 专家团呈现优化（选择反馈 · 成员命名 · 排版）

> 生成：2026-11-15。来源：用户三条反馈——①选择面板排版不佳；②选中后零反馈，不知道选了什么；③成员应有名字并贯穿会话界面/侧栏。
> 每条都带「证据（文件:行号）」；凡未核实的一律标注「待核实」，不臆断。

## 一、现状诊断（已核实）

| 反馈 | 现状 | 证据 |
| --- | --- | --- |
| ① 面板排版 | 每行「name（中文名）· N 名成员」+ 最多两行描述，纯文字、无图标、无分组（内置/全局/项目混排），成员列表完全不展示 | `TeamPickerSheet.kt:110-150` |
| ② 零反馈 | 顶栏专家团按钮 `active = false` **硬编码**；会话界面输入栏/状态条均不显示当前团；唯一反馈是弹层里的勾 | `ChatHeaderComponents.kt:186` |
| ③ 成员名 | 消息流 `task` 派发**已有**成员名（英文标识）；侧栏子代理行显示的是会话标题（任务描述）而非成员名；`subagentType` 已存成员名但侧栏未用 | `ToolMessageComponents.kt:220,273`；`ChatDrawer.kt:1060`；`TaskTool.kt:241` |

## 二、方案

### 反馈 ①：面板重排
- 按来源分组：内置（`filePath` 为空）／全局／项目（`TeamDefinitionEntry.scope` 现成）
- 卡片化，选中态 = 主色边框 + 勾（不再只靠文字变色）
- 成员胶囊一行铺开，序号暗示建议顺序（对应 `TeamDefinition.lifecycle`）

### 反馈 ②：三层正反馈
| 层 | 改法 |
| --- | --- |
| 顶栏按钮 | `active = teamName != null`，选中主色高亮 |
| 输入栏横幅 | 新增一条 `专家团 dev-squad · 5 名成员 · 更换/移除` |
| 选中瞬间 | Toast「已召唤 dev-squad（5 名成员）」 |

### 反馈 ③：成员名贯穿
- 消息流：成员名后补中文名（`前端 · frontend`）
- 侧栏子代理行：`subagentType` 成员名作前缀 + 任务描述
- 会话内成员条：列出该团成员及各自状态

## 三、成员命名机制（保证商店新增团队同样守纪律）

统一入口 `TeamMemberNaming`，优先级链：

1. `StoreTranslation.nameZh`（商店团，已自动翻译 —— 新装团队自动具备）
2. 内置中文名表（`assets/team-member-names.json`：product-manager→产品经理 等常见角色）
3. 成员 `AgentDefinition.description`
4. 英文标识兜底

> 关键：商店团队走既有 `StoreTranslation`（`TeamPickerSheet.kt:124` 已在用），**新增团队自动继承中文名**，无需人肉维护。

## 四、改动范围（五处）

1. `TeamPickerSheet.kt` — 分组 + 卡片 + 成员胶囊
2. `ChatHeaderComponents.kt` — 按钮 active 联动 + 传入 teamName
3. `ChatInputBar.kt` — 专家团横幅
4. `ChatDrawer.kt` — 子代理行显示成员名
5. `ToolMessageComponents.kt` — 成员名补中文
6. 新增 `TeamMemberNaming.kt` + `assets/team-member-names.json`
7. 新增 strings（中英双语）

## 五、验证

- 冒烟编译 `:app:compileUniversalDebugKotlin`
- 单测 `:app:testUniversalDebugUnitTest`（含 TeamMemberNaming 新用例）
- 真机验证（UI 改动，按 PR 流程需发 APK）
