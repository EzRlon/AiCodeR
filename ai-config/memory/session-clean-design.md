---
name: session-clean-design
description: 会话清爽化定案（2026-10-18 用户拍板）：完成后只留用户消息+最终实施结果，过程收进「构造过程」折叠头
---
# 会话清爽化定案（2026-10-18 用户拍板）

用户最终设计：**每轮完成后，会话只常显「用户消息 + 该轮最终实施结果」**；思考、工具调用、中间正文全部自动收进折叠头，名称统一为**「构造过程」**（运行中显示「构造过程 · 执行中」，完成后「构造过程 · 12.3s」）。

规则：
- 轮头默认展开态 = **仅运行中**（running）；完成后自动收起（`AIChatPanel.buildChatItems` 的 `expanded = turnOverrides[key] ?: running`）。
- 单张思考卡/工具卡的自动展开同样**只在运行中的轮**（`autoExpandCards = header.running`）；手动展开轮头后卡片默认收起（二级细节点开看）。
- 用户手动展开/收起过的轮与卡，一律以手动覆盖为准（`turnExpansionOverrides` / `toolExpansionOverrides` / `reasoningExpansionOverrides`）。
- 官方文档 docs-site/docs/guide/chat.md「任务折叠」节描述的正是该行为——代码曾偏离文档（F4 改成最新轮保持展开），2026-10-18 回归文档语义。

背景：用户曾先要求「编程指令与最近思考卡片自动展开」，后明确补充「会话完成后全部自动收起」——两者合成「运行中看进展、完成后清爽」。不要再改回「最新轮保持展开」。