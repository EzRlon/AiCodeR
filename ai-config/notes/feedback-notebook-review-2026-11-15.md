# 反馈与建议 + 随记浮窗 · 审查处置（2026-11-15）

> 范围：`feat/feedback-notebook` 分支 `c4ddea6`、`2986e18`、`dbe15d3`、`b4c42be`。
> 流程：自审（qa 子代理三次被会话进程重启杀掉）→ optimizer BATCH 复审 → 修复 → 编译 / 全量单测 1856 / detekt 0。

## 一、optimizer 结论与处置

| 级别 | 问题 | 处置 |
| --- | --- | --- |
| P0 | 切换/新建笔记后 `AndroidView` factory 不重跑，界面挂着已 `destroy()` 的旧 WebView（白屏/不可编辑） | ✅ `b4c42be`：`key(path)` 包裹 AndroidView（与 `CodeEditorScreen` 的既有规避一致） |
| P1 | 切换笔记读失败时用整屏失败页顶掉当前仍可编辑的笔记 | ✅ `b4c42be`：改为一次性 Toast（`actionErrorTick`），当前笔记保持不动 |
| P1 | 浮窗每次打开重建 WebView + 6.5MB Vditor 资源，约百毫秒级启动成本 | ⏳ 需真机确认；若明显再做 WebView 缓存 |
| P2 | `reloadCurrent` 每次重开都无条件写盘 | ✅ `b4c42be`：无编辑（`hasEdits=false`）时直接返回 |
| P2 | 日志整体 `readText` + 全文物化 + 多次正则 | ✅ 先按字符截尾（4× 上限）再切行脱敏 |
| P2 | Issue 正文模板与类型文案硬编码中文（英文用户仍是中文正文） | ⏳ 排期：模板文案按 Locale 选择 |
| P2 | `core/ui/floating` 与 VNC 小窗逻辑重复，VNC 未迁移 | ⏳ 已知取舍：本轮不动 VNC；后续抽公共组件迁移 |
| P2 | 随记 VM 中「落盘上一篇 → 读/建下一篇 → 刷新列表」三段近重复 | ⏳ 排期：抽 `switchTo` |
| P2 | `canUndoPolish` 在继续输入后不清除，撤销可能连带回退新输入 | ⏳ 排期：编辑后清除撤销点或二次确认 |
| P2 | 笔记下拉菜单全量渲染（非 LazyColumn） | ⏳ 量级小，暂不处理 |
| P2 | 编辑器页（笔记模式）仍未传 `latestTextProvider`，深浅色切换可能用滞后种子 | ⏳ 存量隐患，后续统一 |
| P2 | `onSaved()` 空实现属无意义间接层；进程被杀最多丢 800ms 防抖窗口 | ⏳ 记录为已知取舍 |

## 二、本轮额外修掉的正确性问题

- 润色慢请求返回时若用户已切笔记/继续输入，放弃覆盖（防串稿、防吞字）。
- 首次加载未落地时忽略切换/新建，避免两路协程互相覆盖 `currentPath`。
- 笔记防抖写盘移到 IO 线程（原实现跑在主线程）。
- 无当前会话时「发给 AI」明确失败，不静默新建会话。
- 随记首次加载延迟到打开浮窗，App 启动不做无关扫盘。
- 反馈日志开关关闭时复位 loading 文案；脱敏补 JSON 键值与 Basic 认证。

## 三、仍未验证（需真机）

- Vditor 在浮窗内的键盘弹出、焦点与 IME 上移观感。
- 缩放手柄与 WebView 内容手势是否冲突。
- GitHub Issue 预填链接在手机浏览器中是否真的填入正文（不填则依赖剪贴板兜底）。
- 远程 SSH 模式下 notebook 目录创建与读写。
- 反馈页在窄屏/英文下的排版（三个 FilterChip 是否换行）。
