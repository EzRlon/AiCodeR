# 发版前三路审查汇总（2026-10-20）

> **终态：正式包已交付**（2026-10-20 17:43，versionCode 85，干净 HEAD 677966e，R8 29m 通过，aapt 校验过）。五批工作已由另一会话分 5 个提交入库（546fdb2 FTS / ad92f2c 五批次含P1修复 / e6dc981 悬浮球动画 / fce04e4 能力登记 / 677966e 收尾）。
>
> **真机验收（2026-10-20 晚，全新安装）**：环境一键安装通过；bash 5.2.37/git 2.47.3/rg 14.1.1/curl 8.14.1 全可用，aarch64 musl。`ps -p 1 -o lstart=` 有表头无行——procps 已装且语法识别（批次B补装目标达成），PID1 不可见为 PRoot 正常现象；自愈脚本第①层探针失败后按设计走②层（/proc/stat hidepid 不可读 → --no-daemon 前台模式）。端到端验收点：启动 Codex 应见「容器 /proc 受限…前台模式」提示后正常进入；若再现「failed to read start time」原始报错才是回归。**→ 已验证通过（2026-10-20）：安装 Codex 后点启动，终端正常弹出 Codex 登录选项（ChatGPT/API key 等），无原始报错，批次 B 修复在真机生效 ✓**

三路子代理并行审查（qa 缺陷 / 屎山扫描 / 性能功耗）+ 主线程静态核对，均只读；qa 按 qa-discipline 规则派发（构建结束后、禁自跑构建、grep 替代 search）。

## 结论：无 P0，修复 P1×2 后可放行

- qa 报告：五批次（A 压缩/B Codex 自愈/C 存储清理/D 动画/E multi-llm）+ FTS 全部审毕，**批次 A/B/D 全绿**；FTS schema 与 67.json 逐字符一致、全参数化无注入、会话隔离正确、回填幂等。
- 主线程核对：编队残留清零、PROVISION_VERSION v12 无漂移、全量单测 BUILD SUCCESSFUL、assembleArmsoloRelease R8 通过（term-17，22m59s）。
- 能力感知：recall 工具与 multi-llm 均已登记（capabilities.md + 65-capabilities.md）✅。

## 本轮已修复（P1，已验证：compileUniversalDebugKotlin BUILD SUCCESSFUL + StorageUsageScannerTest 单测通过）

`feature/settings/presentation/StorageViewModel.kt`（2026-10-20 主线程修复，逐段 runCatching + FileLogger，风格与 deleteChatSessions 一致）：
1. `cleanSelected`：scanner.clean / cleanArtifacts 原无兜底，DB/IO 异常会崩协程且 cleaning 卡 true。
2. `deleteArtifact`：同型缺陷（qa 未点名，同文件顺手清）。

## 发版后队列（按优先级）

### 2026-10-21 optimizer 全盘复审增量（发版前三路审查之外的行号修正与新项）
- 旧项行号已全部修正并回写至本文件上表（如 WakeLock 1372-1407、通知 cancel 83-85、SSH 探活 244-262、双 WiFi 锁 261-292、itemsIndexed 919、思考计时 607-613、反向依赖 StatefulAgentWorkflow:44、MultiLlm 非原子 839-852）；AIAgentViewModel 3849→3866 行（本批草稿节流新增后）
- **新 P1×1 待排期**：FloatBallOverlay.kt:643-645 面板组合期 `input = hub.draft` 回写本地 state，与 FloatBallCommandCenter.updateDraft→VM→hub 回声链成环——快速输入偶发丢字；修复选项：组合期回写改 LaunchedEffect(hub.draft)，或面板持独立 buffer 单向出（改浮窗核心交互，需真机验证）
- **新 P2×5 待排期**：①FloatBallSessionHub.kt:70-96 copy-then-set 无锁多写者（改 StateFlow.update{}）②VncOverlayViewModel.kt:76-89 本地模式 5s 回环探测（改指数退避）③StoreViewModel.kt:386-395 loadTeams 切 tab 重复拉网（结果内存缓存+重试入口）④MarkdownPreviewWebView.kt:60-62 每资源请求 FileLogger.v（DIAG 门控）⑤AgentMessageDao.kt:152-190 recall LIKE 全表扫描（已知权衡，可限时间窗）
- **本批已修复（2026-10-21，用户点名内存/性能）**：①ScheduledAiTaskWorker 结果摘要改 DAO LIMIT 1 查询（原全量载入数千条消息，12h 长任务内存尖峰）②AIAgentViewModel 草稿落盘防抖 500ms（原每键入一次 XML 序列化写盘；清空立即落盘 + onCleared flush；悬浮球镜像链路自动同步节流）

### 性能与功耗（性能报告 8 项，全部「记录在案」未动——发版前动 WakeLock/轮询门控是功能故障高危区）
1. AIAgentViewModel.kt:407/1369-1417/1470：WakeLock 前台也全回合持有 → 改仅后台持锁（enablePersistent 状态翻转时调用）。**改动需真机验证后台任务不被冻结**。
2. FileChangeHub.kt:56/334：快照轮询兜底对所有订阅默认 2s → 小目录/非递归订阅默认关、后台暂停 workspace 级。
3. AIAgentViewModel.kt:1157-1164/2224/2871-2886：流式每 delta 全量 trim+替换+悬浮球 push → 预览 debounce 250ms、take(200) 先截断、悬浮球未启用短路。
4. AgentLiveNotificationManager.kt:60-63/97-99：500ms 节流后仍每次 cancel IPC → 前台且已撤销则跳过。
5. RemoteSshConnection.kt:318-345：正常态 15s 探活 → 拉长 60s。
6. TerminalKeepaliveService.kt:250-270：后台双 Wi-Fi 锁冗余 → 后台仅持 HIGH_PERF。
7. FloatBallOverlay.kt:~940：面板消息 itemsIndexed 补 key。
8. StatusBubbles.kt:617-624：思考计时 100ms → 500ms。

### 屎山/架构（屎山报告，发版后第一批重构）
1. **domain→presentation 反向依赖 3 处**：StatefulAgentWorkflow.kt:57 / ContextCompactor.kt:17 / MessagePersistenceUseCase.kt:9-10 import presentation.AgentAttachment+MessageRole（定义在 feature/agent/presentation/AgentUiModels.kt）。下沉 domain/model，涉及约 20 文件 import（纯机械，编译器兜底）。
2. AIAgentViewModel.kt 3847 行/45 依赖 → 按功能切 6-8 个子 VM/use case（含直接注入 5 个 DAO 绕过 repository，抽 SessionDataRepository 收口）。
3. SettingsViewModel.kt 3190 行 → 按设置域拆分。
4. BrowserManager.kt（domain 层操纵 WebView，2060 行）→ 移 ui/engine 层或拆接口。
5. StorageUsageScanner.kt:~170-240 domain 服务拼展示文案 → 返回结构化数据。
6. core/watch/FileChangeHub.kt:4-8 core 反向依赖 feature → 回调/接口解耦。
7. SafeDelete.kt 与 SafeFileDelete.kt 双实现 → 合一。
8. OpenAI/Anthropic/GeminiAdapter 三份脚手架重复 → 抽基类。
9. ProviderEditorScreen 3259/AIChatPanel 2505/SettingsScreen 2144/ChatDrawer 2091/ChatInputBar 1894/MainActivity 1405 UI 巨石 → 拆 Composable。
10. AIAgentViewModel.kt:~2670 copyHandoffContent runCatching 静默吞异常 → 补 FileLogger.w。

### qa P2 记录项（低概率边界，暂不动）
1. StorageUsageScanner.kt:136-139 Checkpoints 先删 DB 行后删文件，毫秒级竞态窗口（可改先删文件）。
2. SettingsViewModel.kt:820-832 attachMultiLlmInternal 读改写非原子（跨两次锁），极小概率丢并发编辑。
3. AdaptiveModalBottomSheet.kt:113-115 Dialog 入场动画前几帧可能被吃（视觉瑕疵）。
4. AgentMessageDao.kt:14-18 FTS4 + INSERT OR REPLACE 不触发 DELETE 触发器的理论坑（当前全 UUID 无重插路径；若未来引入同 id 重放需改插入策略或补偿 FTS）。
5. StorageSection.kt:153 容器大类文案已确认无歧义，无需改。

## 提交白名单归属分析（2026-10-20 主线程逐文件 git diff 核实）

⚠️ 工作区实际混有 **五批工作**，非交接文档说的两批：

1. **五批次 A-E**（2026-10-19 交接）：ContextCompactor/CliAgent/CliAgentManager/env-common.sh/provision.sh/LinuxContainerEngine/ContainerInstaller/AIEditorApp/CheckpointDao/CheckpointManager/StorageUsage/StorageUsageScanner/StorageViewModel/StorageSection/SettingsViewModel/McpSettingsSection/SettingsScreen/Motion.kt(新)/FloatingTabBar/AdaptiveModalBottomSheet/ChatDrawer/ChatInputBar 相关/团队改名包（team 6 文件+teams/*.md+init.md+63迁移注释+TeamsSection/TeamPickerSheet/StoreViewModel/BackupManager/SystemPromptProvider/ChatSessionEntity/AgentMessage/ChatSession）/multillm.sh(新)/multi-llm/(新14文件)/docs(subagent.md+backup.md)
2. **FTS 检索**（另一会话，可能仍在写）：AgentMessageFtsEntity.kt(新A)/67_add_message_fts.sql(AM)/67.json(新??)/RecallTool.kt(AM)/AgentMessageDao.kt（含未暂存的 CJK 降级 LIKE+摘要过滤）/AgentDatabase.kt 注册/IdempotentMigrations/MigrationLoader/AIEditorApp 回填部分/capabilities 两件登记的 recall 部分
3. **悬浮球会话共享+崩溃页+远程保活**（第三来源，疑似进行中）：FloatBallSessionHub.kt(新??)/TerminalKeepaliveService.kt(注入 Hub+远程连接禁 stopSelf)/CrashActivity.kt(按钮布局)/FloatBallOverlay.kt 部分/strings 的 notification_remote_active+float_panel_untitled_session
4. **屎山清理遗留**（上上会话 term-2「屎山清理+黑屏修复」产物）：SafeDelete.kt(新??，NOFOLLOW 安全删除，对应记忆 delete-recursively-symlink-pitfall)/SafeFileDelete 双实现其一/BoundedLineReader/SplitHandle/LocalFileAccess/AndroidManifest 等待逐个确认
5. **记忆图谱**（v84-v87 打包脉络）：MemoryGraphViewModel.kt(新??)/MemoryGraphScreen.kt/GitScreen.kt 等待确认

**混合文件（MM 状态，提交需逐 hunk 拆分或与对应批次同车）**：strings.xml 双语（C 清理文案+悬浮球文案+远程保活文案）、65-capabilities.md、capabilities.md、MainActivity.kt（五批次D动画已暂存+snap眼手未暂存）、AIEditorApp.kt、AgentMessageDao.kt（FTS 专属）。

**提交前置条件（必须全部满足）**：①用户确认第 3/4/5 批来源会话已完结（否则会把半成品提交）；②FTS 会话确认完结；③按上述分组分次提交，MM 文件逐 hunk 归属。**发正式包必须在提交后**：versionCode=gitCommitCount()，不提交新包版本号不递增，无法升级覆盖安装。

## 环境备注
- 编译/打包命令见 AGENTS.md 构建 section + 记忆 android-build-env；KSP 偶发增量损坏清缓存即过；search 工具不可用（grep/find 替代，busybox grep --include 有假阴性坑）。
