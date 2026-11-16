# 2026-11-16 第三批：记忆账本/随记/模型底栏/致谢/刷新/存储扫描

> 分支 `feat/feedback-notebook`，工作区 90 项未提交改动，**未 push**。

## 一、本批改动

1. **记忆图谱刷新**（反馈 8）：移除顶栏刷新按钮；四个页签统一用 `PullToRefreshBox`
   大幅下拉触发 `refresh()+syncGraph()`；刷新标记 1.2s 后自动收起（一次性延迟，无常驻循环）。
2. **记忆账本自动运转**（反馈 3）：外部引擎结论抽取周期 6→3 回合；
   `EXTRACT_MIN_HISTORY 12→8`、`EXTRACT_MIN_ASSISTANT 3→2`、`EXTRACT_MIN_FRESH_MESSAGES 12→6`，
   让结论页在 CLI 会话里更快有内容（仍受开关与成本上限约束）。
3. **随记格式条**（反馈 2）：去掉列表/加粗/斜体，改为 待办 / 代码块 / 行内代码 / 链接 / 引用 + 撤销/重做；
   `MarkdownNoteCodeEditor`、`NoteEditorWebView`、`note.html` 三处动作与分发同步。
4. **模型底栏**（反馈 6）：CLI 引擎模型页补无边框胶囊搜索框（复用 `ModelSearchField`）+
   供应商分组可折叠，与内置引擎页一致。
5. **致谢页**（反馈 4）：设置「关于 AiCode」入口下新增「致谢」，弹窗列出 8 个关键开源项目
   （AiCode/Termux/proot/sora-editor/Claude Code/Codex CLI/OpenCode/RikkaHub），可点开仓库；中英双语。
6. **存储扫描提速**（反馈 12）：工作区统计改为单次遍历同时得到「项目总大小 + 附件大小」；
   `workspaceArtifacts` 对 `build/` 只记录顶层后 SKIP_SUBTREE（intermediates 动辄上万文件）；
   附件大小复用单遍历结果，不再二次全量递归。

## 二、中断遗留修复

- 清掉同会话 3 份重复 CLI 引擎（并发改文件、互清构建目录的根因）。
- 修复 `MarkdownNoteCodeEditor` 被截断的 NoteFormatRail 渲染体。
- 修复 `NoteEditorWebView` 对已删除字符串（list/bold/italic）的引用，并同步 `note.html`。
- 删除被 SIGTERM 构建误产出的旧 APK，重新构建。
- 下拉刷新原稿的 1.2s 常驻循环改为一次性延迟收起。

## 三、验证与交付

- `:app:compileArmsoloReleaseKotlin` ✅（合并并发改动后重跑）。
- `:app:assembleArmsoloRelease` ✅ BUILD SUCCESSFUL in 4m 22s；apksigner CN=AiCode。
- 交付包：`/root/workspace/app-armsolo-2.3.0-rc4-dev.40-353-b6.apk`
  SHA-256 `8a566ac016acf33985df42e5762262ad46d935f8f77158305471332300fddf50`
- `send_file` 仍报 token mismatch（MCP + 本地桥均被 App 拒绝），需从文件浏览器安装。

## 四、待办

- VNC 极简桌面：仅审计（A ~10MB / B ~80–120MB / C ~250MB），等用户定方案。
- 「继续无反馈」根因未定位；b5 起会把空结果变成可见报错并打印原始 result。
- 反馈 10 清单中的 10 项（任务板增强、签名页周边、引擎进程 P2、反馈端点、真机验证等）未完成。
- 本批全部为真机待验项。
