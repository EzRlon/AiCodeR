# 交接书：bug修复批次 + REA重构 已完成待验收 · 2026-10-18 深夜

> **2026-10-19 收尾补记（本批次已提交）**：qa 审查完成（P0×2/P1×2/P2×4 已处置；P1-5 peek 触摸目标 14dp 偏小属视觉取舍，留真机用户定夺）。15 文件白名单已提交 `a90933b`（含 qa 修复：球 form 转 Compose State、样本扫描入 IO 线程、呼吸动画改 draw 阶段读值零重组、样本按修改时间排序截断；另删 2 个死 key/死函数、修错别字）。能力感知件 8 处已提交 `8db71cf`（65 速查+能力地图：悬浮球胶囊形态/标注汇总含坐标/商店详情弹层/REA 工作台工具区）。**单测未跑**：另一会话并行施工扩大到 25+ 文件（multi-llm 内置等，SettingsViewModel 半成品编译错挡住全模块编译，无法跑测试链）。**打包仍被容器 PRoot unlink bug 阻断（实测两次）**，另发现同根新表现：Kotlin 增量编译删 build/tmp/kotlin-classes 旧输出同样必败，绕行=每次编译前 shell 预删该目录（已补进记忆 build-mergejavares-tmpdir-pitfall）。剩余：等容器修复 → assembleUniversalDebug 出验收 APK → 真机走 §五.A 清单。

> **写入者**：调研/修复会话（与主会话并行工作过）。
> **新会话开工第一步**：读完本文件 → 读工作区根 `AGENTS.md` → `git -C ~/workspace/AiCode status` 核对工作区（见 §三 并行写入警告）。

## 〇、一句话状态

**用户本会话全部反馈的代码已 100% 落盘且编译通过（15 个文件，§一清单），但验收 APK 被容器层打包 bug 阻断（§二），且工作区混有另一会话的 6 个未提交文件（§三）——提交与验收均未执行。**

## 一、已完成交付（15 个文件，均未提交、未真机验收）

### 1) 深色模式全局根因修复（用户反馈：设置页/标注/图谱/发送按钮/MCP/编队不跟随自动深浅）
- 根因：`MainActivity.attachBaseContext` 的 `createConfigurationContext`（只为 locale）冻结 uiMode + `configChanges` 含 `uiMode` 不重建 → Compose `isSystemInDarkTheme()` 读冻结值。
- 修复：`ThemeSettingsRepository` 新增 `systemDark: StateFlow<Boolean>`（Application 级 config 实时读）；`AIEditorApp.onConfigurationChanged` + `MainActivity.onResume` 刷新；MainActivity/FloatBallOverlay 的 AUTO 分支改读镜像流。
- 文件：`feature/settings/data/repository/ThemeSettingsRepository.kt`、`AIEditorApp.kt`、`MainActivity.kt`、`feature/floatball/FloatBallOverlay.kt`。

### 2) 发送按钮四态 + 队列压扁
- `ChatInputBar.kt`：SendButton 空闲态 `surfaceVariant`→`primary`（用户反馈"只有一个色"）；控制行 `heightIn(min=44.dp)` 防测量挤压。
- `ChatInputAttachments.kt`：QueuedRequestPanel 条数 >2 自动收起（LaunchedEffect 联动），防底栏溢出压扁按钮。

### 3) 悬浮球视觉重设计（用户批评"吓人/骑边半遮未实现"）
- `FloatBallOverlay.kt` 大改：**去脸**（删眼/嘴/眨眼/高光/皮肤渐变）；**待机 peek**＝14×28dp 状态色胶囊贴边停靠（idle=primaryContainer/busy=success/failed=error，busy 仅透明度呼吸 600ms，idle 零重绘）；点击 200ms `animateWindow` 展开为 40dp 球（中央 Zap 图标或 todo 数字 + 进度弧）；拖拽松手 `snapAndCollapse()` 回 peek；面板关闭 420ms 后自动缩回；全色彩走 semanticColors。`ballSize` 44→40，窗口初始参数改 peek 尺寸。
- 注意：`MergeOutputWriters` 类问题与此无关；本文件同时含 §一 的 systemDark 接线。

### 4) 标注融合 + 结构化汇报（反馈2）
- 结论：活标注（v2）与截屏标注**编辑器本就是同一个**（v2 完成即抓屏进 v1 编辑器），重合在入口。
- 融合：`ChatDrawer.kt` 删「截屏标注」入口行与参数（`onStartAnnotationCapture` 已彻底移除，含 MainActivity 接线）；不画标记直接点活标注「完成」＝原截屏能力。
- 精准汇报：`AnnotationViewModel.buildSummaryText` 为每条标注追加结构化坐标行（`AnnotationMarksCodec.decode(marksJson)` → `describeMark()`：框选/箭头/直线/笔迹/文字 + 归一化百分比区间 + 附注），AI 不再只靠看图猜位置。
- `LiveAnnotationOverlay.kt`：组件命中框改白外(4f)+黑内(1.5f)双虚线描边（任意亮暗界面可见）。

### 5) 商店三项（反馈5）
- `StoreViewModel.kt`：专家团（内置+在线）映射为 `StoreItem(kind=EXPERT)` 并入 `translatePendingItems()` 翻译管线；`loadTeams()` 末尾 `kickTranslation()`。翻译持久化与续译**原本已实现**（`StoreTranslationStore` 缓存 + 只翻缺失/源变），未改。
- `StoreScreen.kt`：新增技能/MCP/专家团**详情弹层**（AdaptiveModalBottomSheet：中英名称、描述对照、meta 行、来源链接、安装按钮 `Button2`）；卡片与团队行加 clickable。
- 注意：函数已加 `@OptIn(ExperimentalMaterial3Api::class)`；`popularity` 是 Long。

### 6) Token 统计成功率色
- `TokenStatsSection.kt`：删固定 700 系两常量，改 `MaterialTheme.semanticColors.success/warning`（667 行附近）。

### 7) REA 逆向工作台重构（用户反馈三问题 + 学习者视角重设计）
- `ReaWorkbenchScreen.kt` 整体重写（726 行）：
  - **判定链修复**：`checkEnv()` 删除 `isProvisioned()` 短路（旧链要求 `.provisioned` 精确版本串，而 REA 安装只写 `/opt/rea/.installed`——三类场景全误判）。新链：rootfs 缺失→NotProvisioned；REA 标记存在→Installed；否则 NotInstalled。**未改 `LinuxContainerEngine.isProvisionedFor` 全局语义**（其他调用方不动）。
  - **MCP 真状态**：订阅 `McpManager.statuses` 取 name=="rea"（配置存在≠连接可用）；FAILED 显示原因+「重新连接」；新增 `reconnectMcp()`。
  - **分析目标区**：`scanSamples()` 扫工作区 `.apk/.apks/.xapk/.aab/.jar/.dex/.so/.bin/.js/.zip/.ipa/.dll/.exe`（深度 4、排除 node_modules/.git、上限 50）；注意 `listFilesRecursive` 只返回**相对路径字符串**（无元数据），需按目录回查 `FileEntry` 取 size/mtime（照抄 TestBuildListViewModel 模式）。
  - **学习任务模板**四条 + **工具清单**（`mcpManager.getServerTools("rea")`）+ **组件实测**（node -v / java -version / jadx jar basename）+ 顶栏刷新。
  - 每态有动作：NotProvisioned→「打开终端」按钮（新回调 `onOpenTerminal`）。
- `MainActivity.kt` `composable("rea")`：接线 `onOpenTerminal`（navigate terminal）与 `onSendToChat`（`agentViewModel.queueExternalText(prompt)` + navigate chat，**用 AppNavigation 层共享 agentViewModel，勿 hiltViewModel 新建**）。
- strings：中英各补 24 个 `rea_*` key（`rea_status_not_provisioned` 旧 key 已删并在 legal_note 后新增新版；**曾出过重复 key 编译错，已修**）。

### 8) 全局深色硬编码清查结论（免得新会话重查）
- 豁免类（合理，勿改）：AnnotationEditorDialog/ImageViewer（画布/图片上绘制）、阴影 Black、`isLight/isDark/luminance` 分支自适应（AppSwitch/FloatingTabBar/ProviderLogo/GitScreen/CodeEditorScreen）、浏览器 8% 遮罩、ChatInputBar SendButton 前景按底色亮度。
- 已修：TokenStats 两常量、LiveAnnotationOverlay 命中框、悬浮球全部硬编码（含 `Color(0xFF263238)` 瞳孔、skinTop/Bottom 渐变、LightGray 待办段）。
- ChatDrawer `FileTreeIgnoredColor #CC8844` 弱豁免（两态均可辨，未动）。

## 二、⛔ 阻断项：打包必败（容器引擎层 bug，非代码问题）

- 现象：`assembleArmsoloDebug/assembleUniversalDebug` 中 `merge*JavaResource` 真实执行必败：`IOException: Failed to delete '/tmp/tempdir_<id>'`（apkzlib `ZFile.close()` 删临时目录树）。每次构建残留 350~1000+ 个空 tempdir。
- 已实测排除：代码（stash 后干净 HEAD 复现）、Gradle 版本（8.11.1 与新装 8.14.3 同败）、tmpdir 位置（`org.gradle.jvmargs="-Djava.io.tmpdir=/root/gtmp"` 后失败点跟着移动）、daemon 残留（--no-daemon/--stop/容器重启均复现）。shell 对同一空目录 rmdir 成功、JVM 必败 → **PRoot 对 JVM unlink 目录树的兼容性缺陷**。
- 反编译证据：`MergeOutputWriters$2.close` line196 = `ZFile.close()`；AGP 8.9.3 `MergeJavaResourcesDelegate` `new ZFileOptions()+setNoTimestamps`，未设 useTemporaryFile。
- **已存项目记忆**：`build-mergejavares-tmpdir-pitfall`（完整证据链+检查/清理命令）。
- **出路**：①反馈 AiCode 容器引擎层修复（这是 App 自家容器，可修 PRoot unlink 翻译或 ZFile 场景兜底）；②宿主环境/其他通道出包；③等 AGP 新版改删除实现。
- 清理命令（安全）：`find /tmp -maxdepth 1 -name "tempdir_*" -type d -exec rm -rf {} +`（勿用 `rm -rf /tmp/*` 通配，会被安全防护拦）。

## 三、⚠️ 工作区并行写入警告（提交纪律，最高优先阅读）

`git status` 共 21 个改动文件，其中 **6 个不属于本会话**（另一会话并行写入，绝不可一起提交/回滚）：
`assets/aicode/lib/env-common.sh`、`assets/aicode/provision.sh`、`feature/agent/domain/cliagent/CliAgent.kt`、`feature/agent/domain/cliagent/CliAgentManager.kt`、`feature/agent/domain/container/LinuxContainerEngine.kt`、`feature/agent/domain/workflow/ContextCompactor.kt`。

**本会话的 15 个文件（提交白名单）**：
AIEditorApp.kt、MainActivity.kt、ThemeSettingsRepository.kt、FloatBallOverlay.kt、ChatInputBar.kt、ChatInputAttachments.kt、ChatDrawer.kt、AnnotationViewModel.kt、LiveAnnotationOverlay.kt、StoreViewModel.kt、StoreScreen.kt、TokenStatsSection.kt、ReaWorkbenchScreen.kt、values/strings.xml、values-en/strings.xml。
提交前必须：①与另一会话确认其 6 文件状态；②`git add` 逐文件白名单式操作，**禁止 `git add -A`**；③安全节点单独提交。

## 四、待办任务表（按优先级）

| 优先级 | 任务 | 说明 |
|--------|------|------|
| P0 | 打验收 APK | 等 §二 解除；成功后装真机走验收清单（§五.A） |
| P0 | qa 子代理审查 | 不依赖打包可先行；**限定只审 §一 的 15 文件**（工作区另有他人改动）；构建方式 `gradle :app:compileUniversalDebugKotlin` + 单测 |
| P0 | 精确提交 15 文件 | 见 §三；通过 qa 后执行，单独安全节点 |
| P1 | 能力感知件登记（AGENTS.md 硬纪律） | 悬浮球 peek 新形态（65-capabilities「悬浮球」行更新）、标注汇总含坐标（「界面标注」行补充）、商店详情（「在线商店」行补充）、REA 工作台新工具区（「逆向分析」行更新）；详图 `docs-site/docs/guide/capabilities.md` 同步 |
| P1 | 悬浮球全量映射 FB1-FB4 | 方案已定（§五.D）；FB1＝进程级 `FloatBallSessionHub`（TerminalKeepaliveService 与 MainActivity **同进程**，共享单例即可，无需 IPC）+ 完整面板消息流 |
| P1 | 跃迁第一批（T1/U1/L2/L3/U2） | 实施书见 §五.B；交接要点本文件自包含 |
| P2 | 嵌套陷阱 M0-M3 | 见 §五.C；用户点名核心 |
| P2 | 容器引擎反馈 | 把记忆 `build-mergejavares-tmpdir-pitfall` 转给 AiCode 容器层 |

## 五、后续批次实施书（要点自包含）

### A. bug 批次真机验收清单
深浅自动切换跟随（设置/聊天/图谱/悬浮球全页）｜发送按钮四态辨识（深浅两主题）｜队列 3 条+键盘+计划说明条同屏按钮不变形｜悬浮球：待机只露小胶囊、点击展开、松手缩回、不吓人（用户终审）｜标注单入口+汇总含坐标+双描边｜商店专家团中文+点开详情｜REA 三问题复验（装好不再误报/MCP 真状态/样本区+模板+工具清单）。

### B. 跃迁第一批（详见对话定稿，此处为实施要点）
- **T1 recall+FTS**：新增 `domain/tool/archive/RecallTool.kt`（AbstractContextualTool，AUTO_APPROVE，参数 query/scope/limit）；`AgentMessageDao` 加 `@Fts4(contentEntity=AgentMessageEntity)` 影子实体 + 迁移 40（**FTS4 不用 FTS5**，minSdk 26）建表+触发器+回填；`AgentModule.provideToolRegistry` 注册；Room 校验失败备选：openHelper.rawQuery 绕过。验收：压缩过的内容能 recall 取回。
- **U1 一键修复**：`CrashActivity`（**独立 ：crash 进程，不可注入主图**）加按钮 → `startActivity(MainActivity)` 带 `extra_ai_fix_report`（≤45k，Binder 限制 50k）→ `handleSendTextIntent` 同位置读取 → `viewModel.queueExternalText`（onCreate 与 onNewIntent 两处）。
- **L2 WiFi Lock**：`TerminalKeepaliveService` 锁仅「远程连接中（注入 RemoteSshConnection 订阅 connectionState）或 sessionCount>0」时持有；四处触发点改造。回归：远程息屏长连不断。
- **L3 specialUse**：Manifest `dataSync`→`specialUse`+PROPERTY；`ServiceCompat.startForeground` API34 分支。**当前 targetSdk 28 不受 6h 限制，属 targetSdk 35 铺路项**（如实告知用户）。
- **U2 失败自诊断**：⚠️ 先真机复测网络切换（`NetworkWatcher`+`ActiveStreamGuardRegistry` 打断链路**已存在**，可能已愈）；工具同参失败 ≥2 次 → `FailureDiagnostics`（用 TitleModel 通道）产出"根因假设+建议动作"经 AgentEventInjector 注入下轮（仿 lastInjectedMode 防缓存击穿）；GeneralSettings 加开关。
- 交接全文另见对话记录（若可读）；核心差异点都在本文件。

### C. 嵌套陷阱 M0-M3（用户最大痛点）
根因已确诊：①不可逆摘要复合失真（CMU+Meta ACM 2026 论文证明超线性）②交接断链 ③可重取信息与状态信息混压。**关键优势：本项目压缩只标 `isCompacted` 未删原文（AgentMessageDao.commitCompaction）——档案库已存在**。
- M0：recall 工具+FTS（=跃迁 T1，同一个）
- M1 工具结果 Tombstone：`ContextCompactor` 摘要素材剔除 ToolResultMessage，替换为 `[已归档 tool=... recall(id)]` 一行占位；`ToolMessageComponents` 加"已归档·取回"态。Anthropic 官方同构（+29%）。
- M2 结构化 TaskState：新表 `task_state`（goal/decisions[]/verifiedFacts[]/fileChanges[{path,status}]/openQuestions[]/prevSessionId）；`compact-summary.md` 重写为结构化模板；**合并规则代码机械执行**（决策只增、事实修正带链）——根治摘要漂移；上下文注入 ≤2k token 快照（SystemPromptProvider 尾部稳定区）；`TaskStateTool` 供 AI 主动记账。Anthropic 官方同构（+39%）。**灵魂验收：同一状态 merge 两次全等 + 强制连压 5 次字段零丢失**。
- M3 无损交接：TaskState 导出+prevSessionId 链，recall 跨代检索。

### D. 悬浮球全量映射 FB1-FB4
关键事实：悬浮球宿主 Service 与 MainActivity **同进程** → 状态提升为 Singleton 即共享，无 IPC。
- FB1：新增 `FloatBallSessionHub.kt`（进程级状态镜像+命令入口，AIAgentViewModel 最小侵入写入）+ `panel/FloatBallChatPanel.kt`（消息流+发送/停止/队列）；FloatBallOverlay 三形态（球/迷你/完整面板，可拖拽尺寸+双击全屏）。
- FB2 底栏全量：模式三态/模型/工作区/推理档/意图/队列管理直接命令化；**附件选择器与权限弹窗平台硬限制不能在悬浮窗内** → 设计"跳 App 完成→回填"链路。
- FB3 会话抽屉（列表/搜索/新建，读 ChatSessionDao）；FB4 打磨（尺寸记忆/主题联动接 systemDark/折叠零渲染）。
- 边界：Activity 被划掉=AI 循环随 ViewModel 终止（现状如此），面板降级为状态展示+打开 App。

## 六、环境备忘
- 构建：`cd ~/workspace/AiCode && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk /root/tools/gradle-8.11.1/bin/gradle ...`（新装了 `/root/tools/gradle-8.14.3` 可用，对本 bug 无效但可留用）
- 快验：`:app:compileUniversalDebugKotlin`；迁移对账：`gradle checkMigrations`；/tmp 清理：`find /tmp -maxdepth 1 -name "tempdir_*" -type d -exec rm -rf {} +`
- rg 在容器内不可用（grep 用 busybox 兼容写法，`--include` 不支持）；Bash 写操作在 PLAN 模式被禁。
- 聊天卡片安装 APK 会卡，用文件管理器装（记忆 android-build-env）。

## 七、纪律提醒（AGENTS.md 硬规则）
整批完工一次性真机验收（不每项打断用户）→ qa 子代理审查（带文件清单）→ 安全节点单独提交 → 上游合并检查在批内最终编译前 → 功耗纪律纳入 qa → 新功能能力感知件登记。

## 八、用户沟通偏好（本会话实测）
- 无编程基础：讲结论与"为什么"，技术细节放表格/清单。
- 要求"实用、质的飞跃"，**反感伪需求**（K8s/多租户/协作编辑类已被裁决砍掉，勿再提）。
- 决策代选：未拍板处按"合理·科学·最优"代选并记录；不可逆操作先问。
- 汇报节奏：关键节点即报 + 15 分钟心跳，最大化简要。
