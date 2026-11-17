---
name: ui-design-discipline
description: UI/功能设计纪律（用户验收 VNC 后提出，所有新功能必须遵守）：实用主义、统一主题 token、功能分区清晰、过程折叠结果突出
---
# UI 与功能设计纪律（用户 2026-10-11 验收 VNC 后提出）

用户已验收 VNC 悬浮窗功能并表扬「实用、统一适配风格」。此后所有新增功能必须延续：

1. **实用主义**：功能必须回答「不装会痛吗」，答案否定的不做；不追求功能数量
2. **风格统一**：全部视觉走 AIEditorTheme 既有 token（Spacing/Radius/semanticColors/colorScheme），禁止硬编码色值与魔数；组件复用 core/ui 既有件（AppTextField/AppSwitch/AdaptiveModalBottomSheet/WorkbenchIconButton 风格等）
3. **功能分区清晰**：一屏一主任务；过程与结果分层（AI 干活过程默认折叠成一行卡片，结果/产物突出显示）
4. **双语文案**：中英 strings.xml 成对，代码用 stringResource
5. **文档同步**：UI 变化同步 docs-site（仓库硬规则）
6. **移植/克隆/改动一致性（用户 2026-10-13 重申）**：凡移植、克隆、改造自其它项目/上游的代码，必须先改造成本项目的设计哲学与 UI 框架语言再落地，不能原样搬入；**所有 UI 必须同时适配深色与浅色两套配色**（用语义 token 而非单色假定；遮罩/弱化等特殊效果先查 semanticColors 是否有现成项）。

参考共识（来自 Cursor/Claude Code/OpenCode 调研）：工具调用一行卡片+可展开、diff 红绿高亮卡、思考默认折叠、长输出截断展开、代码块主题高亮。