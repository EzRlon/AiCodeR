# 交接（2026-10-18 终态）——反馈批次 7（清爽化+沉浸+精灵球+交付物页）+ 上下文压缩×阈值×缓存

> 新会话开工必读。终态安全节点：dev **5f5e800**（反馈批次 7 全部完工+提交，单测 1289 全绿，R8 通过）。工作树干净。

## 〇、反馈批次 7（2026-10-18，**已完工交付**）

> 最新交付包：`许愿码-正式版-20261018-清爽化+沉浸+精灵球+交付物页.apk`（versionCode **78** / git **5f5e800**，versionName 干净无 -dirty）。待用户真机验收；此前批次 6 的未提交改动已全部包含在本批内收尾（精灵球重写/交付物页/悬浮面板配色统一均已完成）。

### 本批交付内容（32 文件，+1358/-310）
1. **会话清爽化**（用户拍板）：轮头默认展开态=运行中（`turnOverrides[key] ?: running`），完成后自动收成「**构造过程 · Xs**」一行；过程子项 expandedOverride=`overrides[id] ?: autoExpandCards`（autoExpandCards=header.running）；手动展开/收起用 turnOverrides 记住。测试：ToolGroupExpansionTest 三例（自动收起/手动展开记忆/更早轮全收）。
2. **侧栏沉浸**：MainActivity ModalDrawerSheet `windowInsets=WindowInsets(0,0,0,0)`，ChatDrawer 面板/标签列改完整 `WindowInsets.systemBars` 内边距；背景铺到状态栏后，底部不进手势区（大屏常驻路径不受影响）。
3. **上下文压缩×阈值×缓存**（择优裁决后实施）：压缩阈值默认 **70**、自动交接兑底 **93**（GeneralSettingsRepository+设置页文案+docs-site general.md/capabilities.md）；`selectTailStartIndex` 无条件把切点前移到最新用户消息；`summarize` 容忍截断响应（截断摘要远好于整体失败）；handoff 继承 mode/goal/goalStatus/goalTurnCount/teamName，marker/summary 行带 isCompactionMarker/isContextSummary（persist 新增默认参数）；`handoffContinuedSessions` 抑制「继续」后的自动交接（**qa 发现死码已修**：移除评估在通用路径做，占用回提醒阈值下恢复资格）；暂停拦截放行 `/compress`；压缩成功解除暂停并 add 进抑制集；ToolRegistry 按 name 排序；SystemPromptProvider 日期改进程级 lazy。
4. **安全修复**：watchMode/scheduledRun 下不可静态判定的良性命令改 ASK（不再借守夜放行）+ 回归测试 scheduledRun_unanalyzableBenignNotAutoAllowed（预授权仅 READ+`echo $(date)`，断言 ASK）。
5. **交付物页**：DAO 投影 fileCount/lastAddedAt、workspacePath join 过滤（跨工作区不串文件）、VM workspace 变更自动 refresh；AttachmentActionMenu scope 参数化（Drawer/测试构建页复用）。
6. **CLI 双源安装**：installCommand npmmirror 优先/curl-wget 探测/官方回退（agent-cli.md 已同步）。
7. **界面杂项**：TaskProgressBorder indeterminate 呼吸段 + isBusy 即显示（无 todo 也有光带）；ToolSection unified diff 行级着色（looksLikeUnifiedDiff+remember(AnnotatedString)）；首个思考与轮头不叠印；keepalive 通知文案组合（只开球→「悬浮球已开启」）；悬浮面板配色统一（semanticTokens 卡片面/静音面/状态色）。
8. **文档同步**：chat.md 构造过程/任务折叠、capabilities.md 交接策略（70/93）、general.md 压缩阈值、65-capabilities.md。

### 新会话接手指引
- 用户验收重点：清爽化收起效果、侧栏沉浸（状态栏无割裂）、精灵球 v3、交付物页、CLI 安装、70/93 上下文策略长对话表现。
- 若 qa 未完成项需补：上轮 qa 子代理跑到 ~3h 被停（确认过：死码已修、文档不同步已修、listFilesRecursive 相对路径假设早验证过正确）；新批次改动面大时建议拆成多段小批次提交，qa 按批次审。
- 待办队列见 roadmap：授权中心体验/定时任务 v2/活标注 v2/图标。

---

## 〇(旧)、反馈批次 6（2026-10-18 晨，已随批次 7 全部收尾）

> 上一交付包：`许愿码-正式版-20261018-悬浮球修复+专家团+翻译+通知合并.apk`（versionCode 76 / git `c8ab636`）。以下是该包测试后的反馈与当前状态。

### 已实现但【未编译验证】的改动（全部在工作树，未提交）
1. **文件树空白防御修复**（`AIAgentViewModel`）：browseState 加 `.catch` 显式报错（原来是未捕获异常→列表卡 Loading 永久空白）；`loadExpansion` 防损坏（空集回默认 `{"~/workspace"}`）。
2. **悬浮球 v3「可爱精灵球」重写**（`FloatBallOverlay.kt` 852 行全新）：
   - 形态：44dp 渐变皮肤球 + 双白底黑瞳眼睛（3.4s 眨眼、拖动时瞳孔跟随方向）+ 微笑/皱眉嘴型 + 顶部高光 + todo 进度外环；
   - 骑边：松手吸附**最近一边（上下左右四边）**，球心骑在边缘线上（半出屏）；可拖任意位置；
   - busy 呼吸缩放（仅忙碌态，功耗纪律）；
   - 面板 v2：状态行（点色+标题+排队数+✕）、正在做什么（toolRunningLabelRes）、待办进度条+当前节点、回复预览卡、待审批卡（授权场景**直接允许/拒绝**，走 FLOAT_BALL 审计源）、快速输入发送、停止任务、回 App、关闭悬浮球；
   - 面板滑入/滑出动画；**操作完成 800ms 后自动收起**（点外也收）；球常驻无限次使用，手动关闭才停；
   - 依赖已就绪：FloatBallStateStore.syncDetails、VM 派生流（currentSessionRunningToolName/StreamingPreview）、VM 多 collect 推送快照、FloatBallCommandCenter.stop/resolveApproval、PermissionAuditSource.FLOAT_BALL、strings 21 条。
3. **左栏「交付物」第三 tab**（风格与「会话/项目」骑线按钮一致）：
   - 数据源：`AgentMessageDao.messagesWithAttachments()`（按时间倒序 300 条）解析附件 + 工作区 APK 扫描（去重）；VM `deliverables: StateFlow<List<DeliverableItem>>` + `refreshDeliverables()`；
   - UI（ChatDrawer）：按后缀分组（安装包/压缩包/图片/文档/其它），组内时间倒序；行=图标+文件名+「时间 · 大小」+⋯；点击弹 DropdownMenu（复用 attachmentActionEntries：打开/安装、MT、文件管理器、保存到手机、分享、复制路径）；
   - MainActivity 接线（deliverables 收集 + refresh + deliverableFileAccess=viewModel.fileAccess）；strings 9 条；65-capabilities 已登记。
4. **上一轮收尾修复**（已在工作树）：VM 悬浮球详情收集器（快照+多 collect）、resolveEarliest 返回 Boolean、PermissionsSettingsSection FLOAT_BALL 分支、strings perm_audit_source_float_ball。

### 【未做】清单（新会话接手顺序）
1. **悬浮面板小窗配色统一（用户最后一条反馈）**：要干净、与 App 整体风格统一、**跟随主题色**。建议：Surface 改 `semanticColors.cardSurface` + `RoundedCornerShape(14.dp)`（与 SettingsGroup 一致）；回复预览/输入框底改 `semanticColors.mutedSurface`；状态点用 `semanticColors.success` / `colorScheme.error` / `onSurfaceVariant.copy(alpha=.5)`；球皮肤也可改用默认主题时状态色（semanticColors.success 等）——核对 `core/theme/AIEditorTheme.kt` 的 token。
2. **编译验证全部未编译改动**（上次编译被用户停止；注意批量脚本编辑多次造成 null 类问题：import 缺失/初始化顺序/多余括号/超重载，编译错误先修）。已知需重点复查：FloatBallOverlay 全文件、ChatDrawer 交付物组件、AIAgentViewModel 快照收集器。
3. **全量单测**（testUniversalDebugUnitTest，基线 1286 全绿）→ **assembleUniversalRelease**（只编 universal；性能/功耗/R8 纪律见记忆 `final-build-optimization-discipline`；R8 minify 后若打包中断，单独重跑可秒级补齐）→ aapt 校验 versionName → 复制命名交付 → sendFile。
4. **git 提交** + 更新本笔记 + AGENTS.md 交接段。
5. **真机复验清单**：① 悬浮球：精灵球显示/眨眼/拖动四边吸附/点开面板不崩/审批与发送后自动收起/通知栏仅一条；② 左栏文件页：工作空间与内置容器恢复显示（若仍空白，让用户发 设置→日志 中「构建文件树失败」相关行）；③ 左栏交付物页：分组与时间排序、行菜单动作；④ 商店翻译中文显示（腾讯源）；⑤ 在线专家团安装。

## 〇、反馈批次 5（2026-10-18 晚，已交付 `许愿码-正式版-20261018-悬浮球挂载修复.apk`，versionCode 75）

- **真机复验失败→根因**：通知合并重构（批次 4）时把悬浮球并入 TerminalKeepaliveService，`attachFloatBall()` 又漏写 `overlay?.attach()`——球对象创建但从未显示（与历史 FloatBallService 同型错误，**第二次踩中**）。修复=赋值后立即 attach。
- **已写入记忆 `floatball-attach-required`**：FloatBallOverlay 构造后必须显式 attach；新建/迁移此类对象须 grep 全部构造点核对启用调用；给出真机诊断日志点清单（Service created / Float ball synced / 无悬浮窗权限 / 悬浮球已挂载 / 挂载失败 / 资源 id 解析失败）。
- 本批验证：编译通过、单测 1286 全绿、R8+签名通过、versionCode 75 可覆盖安装。
- **待真机复验**：开开关→球是否出现；点球→面板是否正常；通知是否单条且显示「悬浮球已开启」。

## 〇、反馈批次 4（2026-10-18 凌晨，已交付 `许愿码-正式版-20261018-悬浮球面板+翻译+专家团+通知合并.apk`）

安全节点 dev **提交后 HEAD**（versionCode 74 / `-71-gdbe395c-dirty`）。本批内容：
- **悬浮球面板崩溃修复（真机日志 ViewTreeLifecycleOwner not found from PopupWindow$PopupDecorView）**：根因=Compose 从「窗口根」找运行环境 owner，平台 PopupWindow 的窗口根是内部装饰层无法设值。面板改为与球相同的 **WindowManager 直连窗口**（自建 FrameLayout 容器挂 tag；可聚焦输入、FLAG_WATCH_OUTSIDE_TOUCH 点外收起）。**待真机复验**。
- **商店翻译换源**：微软 Edge auth 接口已失效（404）→ 主源**腾讯交互翻译 transmart.qq.com/api/imt**（无 key、支持 text_list 批量、国内直连，容器实测可用）+ MyMemory 逐条兜底；无 VPN 优先免费、有 VPN 优先模型，双向回退。
- **专家团（团队包）**：「专家团」= 成套团队非单角色。内置 4 团队（dev-squad 软件开发 / product-squad 产品设计 / design-squad 美工设计 / research-squad 调研）+ 新增 `ui-designer` 成员角色；安装团队自动补齐成员 agents。
- **在线专家团市场**：调研确认成熟公开源=Claude Code 插件市场 wshobson/agents（40k stars、MIT、92 插件包≈团队）；经 **gh-proxy 镜像**拉 `.claude-plugin/marketplace.json`，一键安装=GitHub contents API 列 agents → raw 下载 → **清洗外部 frontmatter（剔除 tools/model/color，避免 Claude 工具名造成空白名单）** → 生成可召唤团队 md。DeepSeek Harness 生态存在但无公开市场 API；豆包/Coze 商店封闭。
- **两条常驻通知合并为一条**：悬浮球并入 TerminalKeepaliveService（删除 FloatBallService 与 Manifest 声明），通知文案聚合「保活状态 · 悬浮球已开启」；开关入口改 `TerminalKeepaliveService.syncFloatBall`。**待真机复验**。
- **性能**：审批通知聚合加 debounce(500)；R8 确认全开（minify+shrinkResources+optimize+full mode）；「最终编译必做性能/功耗/R8 优化」已入项目记忆 `final-build-optimization-discipline`。
- **编译环境坑（已验证）**：意外中断会留下损坏的 KSP 增量状态（generated/ksp 文件缺失 → Unresolved AIAgentViewModel_Factory / Hilt aggregatedroot FileNotFoundException），修复=`rm -rf app/build/generated/ksp` 后重编；R8 minify 成功后 package 阶段中断，单独重跑 assembleUniversalRelease 可秒级补齐。
- 本批验证：编译通过、单测 1286 全绿、R8+签名通过、versionCode 74 可覆盖安装。

## 〇、反馈批次 3（2026-10-17 下午，已交付 `许愿码-正式版-20261017-悬浮球修复+五项反馈.apk`）

- **悬浮球真机修复**：qa 发现 P0——FloatBallService 创建 overlay 后从未调用 `attach()`（原始实现就缺，真机「开关开、权限有、球不出现」的直接根因）→ 已补；ComposeView 需挂 ViewTree 三件套（androidx 2.9 已隐藏 ViewTree* 静态类，改用 `resources.getIdentifier` 解析 `view_tree_*` 资源 id 后 setTag，qa 已验证与库读取同机制、release 资源保留）；`maximumWindowMetrics` 低版本回退 getRealSize；attach/panel 全链路 catch Throwable + 日志；授权返回自动置开（rememberSaveable）。
- **五项反馈**：①文件树两个根行各自小刷新按钮（顶部大按钮撤销）②骑线小卡片未选中=cardSurface、选中=primaryContainer ③商店翻译免费路线（微软 Edge 公开接口：token 8 分钟缓存、401 重试一次、无 VPN 优先免费/有 VPN 优先模型/部分命中继续模型回退；Edge auth 端点容器内探测 404 未能端到端验证，需真机确认）④主代理+各子代理独立模型（默认模型页「智能体模型」组；DataStore key `subagent_model_<名>`，默认子代理用空串；优先级：定义显式配置>设置页覆盖>继承父会话，按 provider+model 成对）⑤在线商店「内置专家」tab（assets/agents 一键安装；启动自动释放现只保留 explore/qa，其余 4 专家由商店安装）。
- 经验：**新建悬浮窗/服务类组件后必须全链路 grep 调用点**（attach 这类「实现完整但从未调用」的问题 qa 两轮才查出）；ComposeView in Service 必须挂 ViewTree 三件套且用资源 tag 方案。

---

# 交接（2026-10-17 终态）——反馈批次 2 + 官方上游择优合并

> 新会话开工必读。终态安全节点：dev **7ca14ab**（merge 提交，含反馈批次 2 a802dc0 + 官方 2026-10-17 快照合并）。工作树干净。

## 一、本批交付内容（全部完工，正式包已交付）

**正式包**：`~/workspace/许愿码-正式版-20261017-反馈批次2+上游合并.apk`
- versionName `2.0.0-aicoder.dev+milestone/vnc-overlay-70-g7ca14ab`，versionCode 73，包名 com.aicoder
- 构建：assembleUniversalRelease（R8+签名，BUILD SUCCESSFUL 25m），单测 1286 用例全绿，qa 复审「可放行」

**反馈批次 2（9 项）**：REA 状态行重叠 / 侧栏加宽按钮 / 上下文阈值强制（50%提醒×2、75%暂停+全自动交接）/ 搜索框折叠（失焦收起修到 BasicTextField 本体）/ 文件页刷新贴右缘 / 终端设置按钮移除 / 商店翻译诊断 / 悬浮球一期 / 压缩×交接协调。
- 悬浮球 qa 曾报 2 P0（双重偏移不可见、点按开不了面板）+4 P1，全部修复并复验（单测 1228→修复后全绿）。**真机验收仍未做**（PopupWindow 定位/ComposeView 无 Lifecycle/拖动手感/软键盘）。

**官方 2026-10-17 快照择优合并（merge 7ca14ab）**：
- 合入：goal 会话目标（迁移落 66、SCHEMA_VERSION=66）、审批通知直达+实时通知（审计化：resolveEarliest 默认 source=NOTIFICATION；两开关默认关）、免责声明门、镜像管理增强、TombstoneParser 崩溃补记、Git 系列整体、EditorLeaveGuard、Shizuku 分叉、ContainerOsLogo
- 不合入：官方 CI、README、CLAUDE.md（恢复我方版）；providers 双合但不启用官方网络刷新；镜像目录网络刷新**暂停用**（发布源 data 分支 CI 未合入，见 §三）
- goal×硬交接适配：续跑被硬阈值拦截 → goal 置 PAUSED；空回合熔断仅在 goal 回合连续累计（用户回合有活动清零）
- 迁移：官方 goal SQL 内容落 `66_add_session_goal.sql`（**不重编号我方 62-65**——用户设备已跑过 62-65，重编号会重复 ALTER 崩库）；官方 schemas/62.json 已删，66.json 由 Room 编译生成
- 能力感知已登记：65-capabilities（goal/悬浮球/自动交接/审批通知直达）、docs-site capabilities.md、goal.md 侧边栏

## 二、待办（新会话接手顺序）

1. **真机验收**（用户装正式包后）：悬浮球（位置/拖动/面板/发送/权限引导）、审批通知直达按钮、免责声明首启、goal 自主循环、镜像下载页 UI
2. **GitHub 上传**：README/图标已随 a802dc0 提交本地，push 待用户发话（EzRlon/AiCode dev；需加速器）
3. 镜像目录网络刷新恢复：待 data 分支发布源就绪后，恢复 AIEditorApp 的 `containerImageCatalog.refreshFromNetworkIfStale()` 调用（现为注释占位）
4. 上游下次合并：官方快照链锚点注意——bfde680 是**孤立根快照**（与 dev 无共同祖先），已用 `git commit-tree <tree> -p 13c22f1` 重挂为 539c9225 后合并；下次同理（锚点现在是 539c9225 在 dev 历史里，可直接 `git diff 539c9225 <新快照>`）

## 三、关键经验与坑

- **merge 冲突「删标记双保留」的括号陷阱**：冲突块两侧函数/块结构不对称时，删掉标记行会导致闭合括号丢失/错位（本批踩了 3 处：GeneralSettingsSection voice 组、SessionUseCase updateTeamName、AIAgentViewModel enqueueAgentRequest 签名）。以后此类冲突要**先读两侧完整结构再合成**，删标记后立刻用编译验证括号配对。
- **官方快照链**：官方 tarball 重新 git init 的快照是孤立根，直接 merge 会报 unrelated histories——必须 commit-tree 重挂到上一官方快照锚点。
- 编译迭代用 `compileUniversalDebugKotlin`（~5-9 分钟）比 assembleDebug 快很多；错误隔离后跑一次全量单测即可。
- 用户设备数据库已到 65（含 62-65 全部迁移），任何迁移号重排都会崩库；新迁移只准追加新号。

## 四、风险与注意

- 通知双轨：官方审批通知（默认关）与我方既有授权通知并存，用户开启后可能出现两类通知；交付说明已告知，属可接受取舍。
- R8 首次带 androidx.core 1.17.0 + 合并代码通过，无新增 keep 需求。
- 真机未验证项：见 §二.1。
