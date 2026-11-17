# 界面标注 v2「活标注」设计方案（2026-10-13 用户纠正后定稿）

## 背景 / 用户原话
「界面标注中 当点击弹出类窗口组件时 没有按照真实实际变化情况变动 这不对 请重新认真读懂业务需求」

解读（已与用户对齐）：**标注对象必须是活的真实界面**——
- 标注时点击弹出类组件，界面**按真实行为响应**（菜单展开/弹窗弹出）；
- 点击功能组件**能选中该组件**（高亮框住）；
- 对标注可**改文字 / 移位置**；支持**划线、箭头**；
- v1 错在哪：静态截图不响应点击、且独立窗口（Dialog/Popup）根本不在截图里。

## 总体架构：v2 = 「活标注捕获器」，v1 = 编辑器（复用）
1. **v2**：在真实界面之上叠加透明标注层——界面保持真实可交互；用户画标注（画在真实界面坐标上）→「完成」时**整屏捕获一帧（含弹层）**→ 把标注合成 → 携带 `bitmap + marks` 打开 v1 编辑器。
2. **v1**（AnnotationEditorDialog）：增加 `initialMarks` 参数，其余不动；账本/发送链路全部复用。

## 交互设计
- **入口**：抽屉新增「界面标注」（v2）；原「截屏标注」保留（静态截图编辑）。
- **悬浮工具条**（可拖动、半透明小胶囊，贴边）：【颜色｜矩形｜直线｜箭头｜自由线｜文字｜选择｜撤销｜完成✓｜×】约 10 项。
- **操作态（默认）**：所有触摸真实传递——**弹窗照弹**（核心诉求）；浮条自身除外。
- **绘制**：点工具→进入绘制（界面点击被接管）→画完**自动回操作态**（连续绘制可再点工具）。
- **选择态**：点「选择」→点界面→**命中组件**→虚线高亮框住→迷你操作条【文字】【箭头】【删除所选】；**若点在已画标注元素上→拖动移动它**（先判标注元素，再判组件）；点文字标注→弹输入框编辑。
- **完成**：MediaProjection 捕获一帧全屏（含弹窗等全部窗口层）→ 合成标注 → 进 v1 编辑器精修/保存/发送。
- **取消**（×）：退出，不产出。

## 组件识别（选择态命中）
- **主窗口**：通过 Activity decorView 找到 AndroidComposeView → `semanticsOwner.rootSemanticsNode` 递归下钻：取「最深、边界包含点击点、有语义（testTag/contentDescription/text/role）」的节点 → 边界 + 标签（名称/文本）。
- **弹层（独立窗口）内组件**：v2.0 不支持精确命中（零反射原则），退化为「画框/箭头指过去」；v2.1 再评估（候选：WindowManagerGlobal 反射或 ComposeView 注册表）。
- **坐标**：boundsInRoot + composeView.locationOnScreen → 屏幕坐标。

## 全屏捕获（关键）
- **MediaProjection**：`createScreenCaptureIntent` 授权（首次弹一次系统授权）→ `createVirtualDisplay + ImageReader` 抓**一帧**后立即释放。仅「完成」时执行，不常驻。
- PixelCopy 排除（单窗口、不含弹层）。
- **风险**：Android 14+ 对 MediaProjection 的前台服务要求——我们 targetSdk 28，预期不强制；**真机验证**；如被拦，备选：短命 `mediaProjection` 类型 FGS。

## 实现清单（文件级）
- 新增 `feature/annotation/presentation/component/LiveAnnotationOverlay.kt`（浮条 + 画布 + 交互，核心）。
- 新增 `core/ui/ComposeSemanticsHitTester.kt`（组件命中）。
- 新增 `core/capture/FullScreenCapture.kt`（MediaProjection 一帧捕获）。
- 改 `MainActivity`：入口 + overlay 挂载（照 SpotlightOverlay 先例）。
- 改 `AnnotationEditorDialog`：`initialMarks` 参数。
- 改抽屉菜单：新增「界面标注」项。
- strings：~12 条（界面标注/选择组件/完成/…，中英）。
- 复用：`AnnotationMark`/`drawMark`/`AnnotationLedger`/发送链路。

## 验证点（真机验收清单）
1. 授权弹窗只出现一次（或每次完成一次，可接受）。
2. 操作态点弹窗按钮正常弹出；「选择」能高亮主窗口组件；已画元素可拖动/改字。
3. 完成合成图**包含弹层内容**；进 v1 可精修；存入账本/发送给 AI 正常。
4. 深色/浅色主题下浮条与高亮可见性。

## 状态
- [x] 设计落档（本篇）
- [x] 实现（2026-10-15）：LiveAnnotationOverlay（触摸穿透 + 可拖动浮条 + 绘制/选择/文字 + 完成链路）；基础件并入（core/ui/ComposeSemanticsHitTester + core/capture/FullScreenCapture）；编辑器加 initialMarks；MainActivity 挂载 + 抽屉双入口（「界面标注」v2 / 「截屏标注」v1 改名）；双语 strings；docs 页 + 能力感知两件套同步。
- [ ] 编译 + qa 审查 + 真机验收

### 实现注记（2026-10-15）
- **零反射折衷**：当前 Compose 版本中 AndroidComposeView 为 internal 类（无法静态引用），组件命中的「取语义根」一步改为「按类名识别 + 单次 getter 调用」（`getSemanticsOwner`），其余全部走公开语义 API。若未来 Compose 提供公开入口可无痛替换。
- **v1.0 范围**：组件命中仅主窗口（弹层窗口内组件仍退化为画框/箭头指过去，符合原设计）；按钮等纯图形（无文字/无 contentDescription）组件命中后标签为空，高亮仍可用。
