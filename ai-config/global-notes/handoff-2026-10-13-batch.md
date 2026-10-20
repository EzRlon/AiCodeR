# 会话交接：2026-10-13 反馈批（新会话接手用）

> 上一会话因上下文过长出现「输出递归重复」症状，用户主动中断并要求回退安全节点、准备交接。
> 本文件自包含，供新会话直接接手。仓库：`~/workspace/AiCode`（分支 dev）。

## 最新已验证状态（安全节点）
- 最新提交：`212f6d8`（远程管理全套 + 抽屉右缘索引标签 + 附件可用性修复；qa 通过；Release+Beta R8 编译验证）。前序 `ba0cf59`（nanohttpd 预置）、`723d76f`、`d7fc3f1`。
- 更早：`6a866e9`（模式 UI 重做第一批）、`bf18cbb`（进度框真实化 + 顶栏顺序）、`19dc16b`（底栏触摸修复 + 标注增强）。
- 以上均编译/单测验证（1152 项 0 失败）。
- ✅ **消息类型 v1 已完结**（2026-10-14，提交 `dfb7a47`）：DB 迁移 64 + UI 全套 + 备份链路 + 提示词 45 + docs 同步；编译 + 单测（1152/0）通过；外部模型（DeepSeek）交叉审查无阻断。工作区无未提交 WIP。
- 编译命令（必须原样使用）：
  `cd ~/workspace/AiCode && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew :app:assembleUniversalDebug`
- 单测：`:app:testUniversalDebugUnitTest`（历史基线 1152 项）。编译约 3~12 分钟。
- 无 ripgrep，搜索用：`python3 /root/tmp/ksearch.py "正则" 目录`。

## 用户长期指令与偏好
- 用户已授权「最后阶段全权执行、不再询问」；当前 AUTO 模式。
- **决策代选授权（2026-10-14 新规）**：评审/反馈中用户未拍板的决策点，AI 按『合理、科学、最优』代选并写入对应笔记；不可逆操作、容器环境安装、最终交付确认仍先问；用户可随时推翻。
- 汇报要中文、简洁；界面要**精美统一、不要突兀的框、该有的数据/图标必须有**；进度展示必须**真实**（不可摆设）；功能要能被 AI 使用。
- 纪律（AGENTS.md）：重大改动完成后派 `qa` 子代理独立审查；能编译必须编译；设计先落档 `~/.aicode/notes/`。
- 用户对"模式 UI"评审要求：先收集 WorkBuddy 交互逻辑（已完成）→ 对比老版/当前版 → 裁决取舍（已交付评审稿）。

## 已完成批次（全部编译通过并提交）
1. 底栏触摸修复（TaskProgressBorder 纯绘制化 + 独立热点层）+ 标注编辑器增强（点击选中/拖动/直线/箭头/文字）+ 语音与附件按钮对调 + 6 编辑页保存钮统一小对勾。
2. 任务进度框真实化：删假进度光带、节点=真实 todo、进行中节点拼接当前真实工具活动（数据源 AIChatPanel 的 thinkingLabel）、提示词新增「进度真实可见」指引（assets/prompts/60-tools-and-paths.md）。
3. 顶栏按钮顺序改为：菜单 → 专家团 → 智能体 → 记忆图谱 → Git → 终端（ChatHeaderComponents.kt）。
4. **模式 UI 第一批（本批）**：
   - 模式胶囊：图标+中文名（构建/计划/自动），废弃「盲循环切换」；
   - 点击弹三选一选择层（图标+名称+规则说明+当前勾选，点选直达）；
   - PLAN/AUTO 激活时输入框上方细提示条（规则说明 + × 一键退回构建）；
   - strings 中英 `agent_mode_*` 9 条。
5. **模式 UI 收尾 + 版本重叠修复（`d7fc3f1` / `723d76f`，双轮 qa 通过）**：
   - 切换模式轻提示 Toast（无会话守卫防假提示）；× 按钮无障碍文案「返回构建模式」；死串清理；docs-site 中英同步；
   - 用户真机反馈「设置-关于-版本」文字重叠：根因=版本号超长（git describe 含非 v 前缀 tag）＋ trailing 无约束，已限宽单行省略（同镜像源行款）。

## 未完成队列（按优先级）

### 1. 远程管理（MT 管理器同款 HTTP）✅ 已完成（`212f6d8`，qa 通过 + 正式版编译）
- 设计已落档：`~/.aicode/notes/remote-manager-plan.md`（含入口调整：并入左栏「Web 预览」行，点击弹两个入口【Web 预览 / 远程管理】，原功能保留；设置里不再加重复入口）。
- **施工前摸底已完成**（Explore 报告）：入口改造点 ChatDrawer.kt:479-486（单点改动覆盖大小屏两条路径）；新全屏路由 `"remote"` 挂 MainActivity.kt:903 旁；生命周期先例=FtpServerManager（单例+StateFlow+DataStore、不起 Service）；网页加载先例=VNC WebViewAssetLoader；/sdcard 直读走 targetSdk28 legacy storage、递归删除用 SafeFileDelete；**内置 FTP 已占「远程」概念（2121 端口、filesDir/projects），实现时避让**。
- **nanohttpd:2.3.1**：gradle 级解析已验证（`:app:compileUniversalDebugKotlin` 通过）；依赖已入构建并提交（`ba0cf59`）。
- **施工状态（代码全部写出，未提交）**：服务端两文件（`feature/workspace/domain/remote/http/RemoteHttpServerManager.kt` + `RemoteHttpServer.kt`）+ 网页端 `assets/remote/index.html`（424 行，浏览/上传含拖拽与进度/下载/重命名/删除/新建文件夹，深浅色自适应）+ 手机页 `RemoteManagerScreen.kt`(+`RemoteManagerViewModel.kt`) + ChatDrawer 双入口弹层（Web 预览行点击弹两入口）+ MainActivity 路由 `"remote"` + 中英 13 条文案；**qa 审查通过**（无阻断；全量重编译 8m13s + 单测 1152/0 全绿 + APK 资源/类打包核验）；**qa 修正项已修**：按住提示窄屏自适应左右锚定、写接口防 CSRF 头、上传残件清理、启停互斥、存储权限判定对齐、网页端清理、标签 selectable；**附件按钮忙碌禁用已放开**（+ / 麦克风 / 排队行附件标识）。**正式版编译（Release+beta，R8 全开）BUILD SUCCESSFUL；已提交 `212f6d8`，本批完结。**
- 技术：NanoHTTPD `org.nanohttpd:nanohttpd:2.3.1`（需验证 maven 可拉取；备选：仿 MCP 回环自写 ServerSocket 手写 HTTP）。
- 网页前端放 `assets/remote/index.html`；API：`/api/list`、`/api/download`、`/api/upload`、`/api/delete`、`/api/rename`、`/api/mkdir`；根目录 v1 = `/sdcard`。
- 相关代码：`ChatDrawer.kt:478-492`（preview 行入口）、`MainActivity.kt:657-661`（onNavigateToPreview 现逻辑）、`core/util/ScreenCapture.kt`（现有截图工具，可参考 FileLogger 用法）。
- 服务生命周期 v1：随开关启停；前台通知增强后置。

### 2. 界面标注 v2（活标注）——用户点名，核心
- 方案落档：`~/.aicode/notes/live-annotation-plan.md`（v2=活标注捕获器，v1=编辑器复用；交互：操作态默认真实可交互、工具画标注、选择态点选组件、完成时 MediaProjection 一帧整屏捕获（含弹层）→ 进 v1 精修）。
- **已写好待入项目的基础件**（两份拷贝，优先用 `/root/tmp/v2/`，丢失时用备份）：
  - `~/.aicode/notes/v2-staging/FullScreenCapture.kt` → 目标 `app/src/main/java/com/aicode/core/capture/FullScreenCapture.kt`（MediaProjection 一帧整屏捕获，已验证 API 设计；Android 14+ 前台服务风险已注明，targetSdk 28 预期不强制，真机验证）。
  - `~/.aicode/notes/v2-staging/ComposeSemanticsHitTester.kt` → 目标 `app/src/main/java/com/aicode/core/ui/ComposeSemanticsHitTester.kt`（组件语义命中；已 javap 验证 `AndroidComposeView.semanticsOwner`、`SemanticsOwner.unmergedRootSemanticsNode`、`SemanticsNode.parent`、`SemanticsConfigurationKt.getOrNull` 均 public）。
- 待做：
  - `LiveAnnotationOverlay.kt`（主体浮层：底部工具条【矩形/直线/箭头/自由笔/文字/选择/撤销/完成/退出】、操作态穿透、选择态组件高亮+迷你条、文字编辑、完成合成传递）；
  - 接线：`MainActivity.kt:566`（`pendingAnnotationBitmap` 定义旁加 `liveAnnotationActive` 状态；`:1023` 旁挂载 overlay）、入口改「界面标注」行为（`ChatDrawer.kt:486-492` 的 `onStartAnnotationCapture` → `MainActivity.kt:636-651` 现为截图，改为进入活标注）；
  - v1 编辑器加 `initialMarks` 参数（`AnnotationEditorDialog.kt:114` 附近的 `val marks = remember { mutableStateListOf... }`）。
  - 复用：`drawMark`（AnnotationEditorDialog.kt:645，internal）、`FitRect/computeFitRect`（:603/:622，internal）、`AnnotationMark` 归一化模型（feature/annotation/domain/model/AnnotationMarks.kt）。

### 3. App 图标 SVG 重设计——用户提出后被中断（未开始）
- 旧图标：`docs/icon.webp`（灰白底 + 灰蓝 `< >` + 线框节点，线性风格）；adaptive icon：`mipmap-anydpi-v26/ic_launcher.xml`，背景色 `#F9FAFA`。
- 产品定位（README）：Android 上的通用 AI Coding Agent · Linux 开发环境 · 本地与远程 SSH。
- 设计方向提案（待用户确认）：终端提示符「`>_`」粗圆头笔画 + 青→靛渐变（#22D3EE→#6366F1）+ 深板岩底（#0F172A），SVG 源放 `docs/`。
- ~~开工前先与用户确认是否继续~~ → **已代选（2026-10-14）：继续推进**（沿用既有设计提案方向，产出 SVG+预览供推翻）；排执行队尾。

### 4. 定时任务 / 定时 Plan（长任务）会话入口——设计稿已交付
- 评审稿 v1 → **v2 增补（2026-10-14 用户追加：设置页排期中心/离开模式等运行规则/自动接棒会话轮转/编队编排）**：`~/.aicode/notes/scheduled-ai-task-plan.md`（9 决策点**已代选**，见笔记 §9）；**待排队实施**。

### 5. 抽屉右缘「产品册索引标签」改造（用户 2026-10-14 反馈）
- 已实施并入批提交（`212f6d8`；qa 通过、Release+Beta R8 编译通过）。

### 6. 工具授权体系升级 + 真人模拟操作屏幕（无障碍）——评审+设计已交付
- 对标项目=**Operit AI**（三档工具权限/无障碍+Shizuku+Root 三通道/PhoneAgent 屏幕操作）；设计稿：`~/.aicode/notes/tool-authorization-plan.md`（5 决策点**已代选**：引入无障碍@v2、v1 先做授权中心；含全规则中文解释硬要求）；**待排队实施**。

### 7. 「消息类型」v1 —— ✅ 已完成（2026-10-14，提交 `dfb7a47`；以下为历史接管指引，保留供追溯）
设计+决策：`~/.aicode/notes/message-intent-tag-plan.md`（4 点已代选：卡片内细标签行 / 13 项清单 / 挂计划联动 / 不进标题）。

**当时已完成（DB 层）**：
- `assets/migrations/64_add_message_intent_type.sql`（新增）+ `IdempotentMigrations.Migration63To64` + `MigrationLoader`（排除 64 SQL、挂幂等类）+ `AgentDatabase.SCHEMA_VERSION=64`；
- `AgentMessageEntity` 表尾加 `intentType: String?` + `toUIMessage` 映射；`AgentUiModels.AgentUIMessage` 加 `intentType`；
- `MessagePersistenceUseCase.persist()` 加 `intentType` 参数并传入实体。

**待做（UI 层，锚点已核）——已于 2026-10-14 全部完成，实施记录见 message-intent-tag-plan.md §状态**：
1) 新建 `feature/agent/presentation/component/MessageIntentCatalog.kt`：13 项枚举（key / labelRes / descRes / icon）；键与中文标签见设计稿 §二；图标选定（均核实存在）：缺陷修复=AlertCircle、新增功能=Plus、方案计划=Clipboard、重构优化=RefreshCw、测试验证=CheckCircle、构建部署=Package、反馈意见=MessageSquare、调研研究=Search、写作编辑=Edit2、翻译润色=Globe、数据分析=BarChart、学习问答=Book、日常自动化=Zap；
2) strings 中英 `intent_*`：选择层标题、「未分类」、chip「类型：%1$s」、13 标签 + 13 说明；
3) `AIAgentViewModel`：内存态 `_intentDrafts` + `draftIntentType: StateFlow<String?>` + `updateDraftIntentType()`（`clearInputDraft()` 一并清）；`QueuedRequest` 加 `intentType`；`enqueueAgentRequest`/`executeAgentRequestStream` 加参；persist 调用点（VM 约 :1623）传 `intentType`；
4) `AIChatPanel`：收集 `draftIntentType`；sendMessage 对 `modelRequest` 追加瞬态行「（本条消息类型：X）」（仅模型可见、不入库）；向 ChatInputBar 传 `intentType` / `onIntentTypeChange`；
5) `ChatInputBar`：加两参数 + `showIntentPicker` 状态；**细标签行插入点**＝工具栏 Row 收尾（`                        )` → `                    }` → `                }`）之后、**在 `                }`（Column 关闭）之前**以 20sp 缩进插入（位于 `if (isBusy && todoItems.isNotEmpty())` 上方，常驻卡内底部）；选择层用 `AdaptiveModalBottomSheet`（两组：编程方向/通用方向 + 顶部「未分类」；样式参考 `AttachmentSheet`）；胶囊走主题 token；
6) 用户气泡徽章：在消息渲染处（`MessageComponents.kt` 一带，**落点待定位**——从 AIChatPanel 消息列渲染找起）显示 `uiMessage.intentType` 小徽章；
7) 提示词片段 `assets/prompts/45-message-intent.md`（类型语义与应对；未分类不注入）；
8) 编译 → qa → 提交。

**风险备忘**：editFile 一律以「最新一次读取的原文」为准（本会话曾因跨文件格式混淆连续失配）；迁移 64 链路**已核验**（编译通过 + `schemas/.../64.json` 已生成且含 intentType）；ChatInputBar 层级插入点严格按上面描述。

### 8. 执行顺序（2026-10-14 代选）
- 本批已收尾（提交 `212f6d8`）→ **消息类型 v1 ✅（`dfb7a47`）** → **授权中心 2.0 v1 ✅（`08f37e2`；能力雷达 P0 并入）** → **守夜守护 v1 第一刀 ✅（`30ebde3`，2026-10-14）** → **定时任务 v1（下一项）** → **活标注 v2** → **图标**（队尾）→ 在线商店 → 大块。工具提升第一档在批次间隙择机做（环境安装先打招呼）。
- 已代选「不做」：MenuetOS/KolibriOS 的 QEMU 演示安装（评审结论=仅演示价值，环境开销大；用户点名可随时改）。

## 关键代码地图（本批相关）
- 模式 UI：`ChatInputBar.kt`（模式胶囊 ~:625-664、提示条 ~:562 插入区、辅助函数 `modeLabelRes/modeDescRes/modeIcon` 文件尾）、strings `agent_mode_*`（values + values-en ~:1838）。
- 进度框：`core/ui/TaskProgressBorder.kt`（纯绘制+热点层边界说明）；`ChatInputBar.kt`（`progressNodes` ~:508、热点层 ~:754、渲染条件 ~:747）。
- 顶栏：`ChatHeaderComponents.kt:150-186`。
- 提示词：`assets/prompts/60-tools-and-paths.md`（进度指引句）。
- 笔记索引（`~/.aicode/notes/`）：roadmap-final、build-master-plan、release-pending-verification、remote-manager-plan、live-annotation-plan、scheduled-ai-task-plan（定时任务/长任务会话入口设计·待裁决）、v2-staging/、handoff-2026-10-13-batch.md（本文件）。

## 环境与风险备忘
- 容器 Robolectric 不可用（native 加载失败）→ 纯 JUnit 逻辑测试 + 真机验证。
- 后台编译建议用 `terminal(action="start", notify=true)`；不要轮询，等完成通知。
- 上一会话出现「输出递归重复」症状——建议新会话：任务粒度小步、避免超长上下文、出现类似症状立即停止并向用户报告。

## 新会话第一步建议
1. 读本文件 + 下一项设计稿 `tool-authorization-plan.md`（授权中心 2.0 v1）；已完结项设计稿（`message-intent-tag-plan.md`、`remote-manager-plan.md`）与后续项（`live-annotation-plan.md`）按需回查；
2. `cd ~/workspace/AiCode && git log --oneline -5 && git status --short` 核对状态；
3. 队列下一项：**授权中心 2.0 v1**（`~/.aicode/notes/tool-authorization-plan.md`，5 决策已代选）；消息类型 v1 已完结（`dfb7a47`，实施记录见 message-intent-tag-plan.md §状态）。
