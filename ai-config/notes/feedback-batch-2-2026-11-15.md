# 2026-11-15 晚第二批反馈处置

> 承接 `feedback-batch-2026-11-15-night.md`。工作区未提交，分支 `feat/feedback-notebook`。

## 一、本批反馈与落点

| # | 反馈 | 状态 | 落点 |
|---|---|---|---|
| 1 | 随记编辑条目改纵向排列 | ✅ | `MarkdownNoteCodeEditor` 右侧纵向格式条（待办/列表/加粗/斜体/撤销/重做，带文字） |
| 2 | 用代码编辑器的格式实现，复用已有 Markdown | ✅ | 复用 sora-editor + `TextMateSetup` 的 `text.html.markdown`；不再用 Vditor 浮窗 |
| 3 | 窗口放大缩小自动换行 | ✅ | `setWordwrap(true)`，宽度变化自动重排 |
| 4 | 代码编辑时显示多少行 | ✅ | 行号 + 底部「第 N 行 / 共 M 行」 |
| 5 | 发给 AI 只要图标，不要底部大卡片，与润色统一 | ✅ | 标题栏图标：列表/撤销润色/羽毛/纸飞机；footer 已删 |
| 6 | Desktop/Tablet 按钮无用则移除 | ✅ | 浮窗改用 sora-editor；`note.html` 也隐藏 `.vditor-preview__action` |
| 7 | 签名页不要独立项目切换，跟随当前工作空间 | ✅ | 删选择器/确认框；`KeysViewModel` 监听 `workspaceRepository.current` 自动重扫 |
| 8 | 反馈不要 GitHub 登录/浏览器，自动提交到项目收集页 | 🟡 客户端完成，端点待部署 | `FeedbackSubmitter` + `FeedbackOutbox` + 匿名 installId/机型；`tools/feedback-relay` Worker |
| 9 | 处理完上述后做剩余 7 项，按推荐顺序 | 🟡 已做 2 项 | ③ 持久化中断标记 ✅；② Codex/OpenCode 映射持久化 ✅ |

## 二、仍剩余（交接）

1. **旁路助手回答合并回主会话时间线**：目前回答在子会话，侧栏可点入，但不自动出现在主会话消息流。
2. **LMK 孤儿 CLI 冷启动回收**：需 PID 落盘 + `/proc` 校验；高风险，未做。
3. **签名就绪详情英文资源化**：`ReleaseReadinessChecker` 的 detail/nextStep 仍是中文硬编码，英文界面会显示中文。
4. **`board.json`**：需在 App 外部引擎会话里让专家团实际调用 `orchestration_board` 跑一轮。
5. **反馈收集端点部署**：`tools/feedback-relay/README.md`；部署后把 URL 写进 `data` 分支 `data/feedback-endpoint.json`。端点为空时反馈会本地入队并自动重试，不丢内容。

## 三、验证

- `:app:compileUniversalDebugKotlin` ✅
- 全量单测 **1868 例 0 失败**（本批未新增单测；功能边界测试通过）
- 构建命令（aarch64 必须）：
  `-Pandroid.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2`
  `GRADLE_USER_HOME=/tmp/gradle-home-fresh`，`--no-build-cache --no-configuration-cache`
- 新 APK 产物：`app/build/outputs/apk/armsolo/release/app-armsolo-release.apk`（versionCode 仍由 git commit count 决定）。

## 三之补充：装包后崩溃修复（2026-11-15 19:37）

- 崩溃：`StringIndexOutOfBoundsException: Line 2 out of bounds. line count:2`，页面聊天（随记浮窗覆盖其上）。
- retrace：`MarkdownNoteController.prefixLine(MarkdownNoteCodeEditor.kt)` / `wrapSelection` → `CodeEditor.setSelection`。
- 根因：sora-editor 的 `setSelection(int, int)` 是**行/列**，旧代码传了绝对字符下标；2 行文档里下标 2 被当第 3 行。
- 修复：编辑前取 `leftLine/leftColumn`，插入/替换后按行/列设置光标；`prefixLine` 用 `(line, prefix.length)`，`wrapSelection` 用 `(line, column + prefix.length)`。
- 修复包：`-b3`，SHA-256 `aac12e8c799c87f6d47b64c911ba3a0c1ffc9a6e084458600e4209e20dbdd24d`，versionCode 353。
- `send_file` 因 bridge token mismatch 未能生成文件卡；APK 已放工作区根目录，文件浏览器可直接安装。

## 四、设计取舍

- 浮窗不再 WYSIWYG：按用户要求改成 Markdown 源码编辑器；表格/代码块等直接写语法。
- 反馈无登录方案必须有一个服务端中转（GitHub 令牌不能进 App）；Worker 已给出，部署是唯一外部依赖。
- 中断标记用 filesDir JSON 而非 Room，避免迁移 78；只记录 RUNNING/INTERRUPTED 与 80 字预览。
