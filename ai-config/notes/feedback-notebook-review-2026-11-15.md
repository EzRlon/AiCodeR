# 反馈与建议 + 随记浮窗 · 审查处置（2026-11-15）

> 范围：`695b2f9..HEAD`（c4ddea6 功能、2986e18/ aecd771 边界修复）。
> 流程：自查 → qa 独立复审（会话重启中断两次，第三次完成）→ 修 P1 → 编译 ✅ / 全量单测 1856 ✅ / detekt 0。
> **optimizer 复审进行中**；正式包（armsolo）等其结论后再构建。

## 一、qa 结论（P0 = 0）

| 级别 | 问题 | 处置 |
| --- | --- | --- |
| P1 | `undoPolish` 未校验路径：切笔记后撤销会把旧笔记内容写进新笔记 | ✅ 进入时捕获 path，写盘与状态更新前校验 |
| P1 | 首次加载远端异常 → `loading` 永久 true（只转圈无提示） | ✅ `openInitialNote` 整体 runCatching + 失败态文案；新建失败同样兜住 |
| P1 | 脱敏漏 JSON 键值（`"token":"..."`）、Basic 认证 | ✅ 键后允许闭引号 + 新增 `Basic ***`；补 2 例单测 |
| P1 | 几何只在 prefs，VM 快照不更新 → 关掉重开退回启动位置 | ✅ `geometry` 改为可变字段，提交时同步 |
| P1(未验证) | `template=md` + `body=` 的 GitHub 预填行为无文档保证 | ⏳ 保留剪贴板兜底 + 提示；需真机/网页实测一次 |
| P2 | `includeLogs` 关掉后 `loadingLogs` 卡在 true | ✅ 复位 |
| P2 | 注释写「编码膨胀 3 倍」，实际约 9 倍 | ✅ 注释修正（中/测试） |
| P2 | 深浅色切换重载时用滞后种子，可能丢输入 | ✅ `NoteEditorWebView` 增加 `latestTextProvider`，随记传内存最新内容 |
| P2 | 润色期间切走再切回原笔记，旧结果会盖新输入 | ✅ 追加内容一致性校验（`latestMarkdown == 发起时文本`） |
| P2 | IME 上移按整个键盘高度、未按重叠量 | ✅ 改为只上移「窗口与键盘重叠量」 |
| P2 | 能力感知件未登记随记/反馈 | ✅ `docs-site/docs/guide/capabilities.md` + `prompts/65-capabilities.md` |
| P2 | 几何高度下限误用 minWidth | ❌ 复核为误报（代码本就是 minHeight） |

## 一·补、optimizer 结论（P0 = 1，已修）

| 级别 | 问题 | 处置 |
| --- | --- | --- |
| P0 | 首次加载失败后无重试路径，状态永久卡死（再次打开走 reloadCurrent 直接 return） | ✅ 失败时复位 `loaded`；失败态加重试按钮 `retryLoad()`；成功/切换/新建均清 `loadFailed` |
| P1 | 清空正文后 `ifBlank` 回退旧种子，恢复/发送会用旧内容并写回文件 | ✅ 新增 `hasEdits` 区分「未输入」与「清空」，统一走 `currentContent()` |
| P1 | `createNoteFile` 吞写失败仍返回路径 → 后续静默丢内容 | ✅ 返回 `String?`，失败落到失败态 |
| P1 | 切换期旧 WebView 迟到回调可能串稿 | ✅ 编辑回调带 path，VM 校验当前路径 |
| P1 | 窗口可拖进状态栏触摸区（按钮点不到、拖不回） | ✅ 顶部边界改用状态栏下沿（拖动与渲染一致） |
| P2 | 日志先对全文件跑 5 次正则再截断 | ✅ 先取尾部 200 行/6000 字符再脱敏 |
| P2 | 反馈长表单无 `imePadding()`（API30+ ADJUST_NOTHING） | ✅ 已加 |
| P2 | 打开浮窗时 ensureLoaded 与最小化 effect 各回写一次 | ✅ 最小化 effect 只在 true→false 边沿触发 |
| P2 | 剪贴板未标敏感 | ✅ Android 13+ 标 `EXTRA_IS_SENSITIVE` |
| P2 | 注释/文档漂移（「回读磁盘」、自动保存 1 秒） | ✅ 注释与文档已改 0.8 秒 |
| 记录 | 每次打开/切笔记重建 WebView（Vditor 约 5MB 资源，数百 ms） | 排期：同窗口切笔记改用 JS `setValue`；最小化保活 |
| 记录 | 远端模式 list/select 串行多次 RTT | 排期：合并请求/复用列表结果 |
| 记录 | VNC 小窗与新浮窗组件重复实现 | 维持现状，后续迁移 |
| 记录 | `pinned` 无 UI 入口、部分 contentDescription 为空、魔数未走主题 | 排期清理 |

## 二、仍待真机/实测

1. GitHub 预填链接（模板 + body）在手机浏览器是否真的填入正文；不填时剪贴板兜底是否顺畅。
2. Vditor 在浮窗内的键盘弹出/焦点；IME 上移与缩放手柄手势冲突。
3. 远端 SSH 模式：`.aicode/notebook` 目录创建、读写、断开时的失败态。
4. 反馈页在 API 30+ `ADJUST_NOTHING` 下，键盘是否遮挡底部提交按钮（需 `imePadding` 则补）。
5. 浮窗每次打开重建 WebView（本地资源加载约百毫秒）的观感；若明显卡顿再考虑缓存实例。

## 三、已知取舍

- VNC 小窗**未**迁移到 `core/ui/floating`：避免动已验证模块，行为已对齐（拖动/缩放/最小化/56dp 可见/最小 220×180）。迁移列为后续重构。
- 反馈提交不存 GitHub 令牌、无后端；代价是正文长度受 URL 限制（中文编码约 9 倍），超长时链接内裁剪、剪贴板保完整。
- 随记文件不自动进入 AI 上下文（故意与 `.aicode/notes/` 知识库目录隔离）。
