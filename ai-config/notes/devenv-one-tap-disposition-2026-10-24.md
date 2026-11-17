# 开发环境一键搭建（用户反馈新增功能）· 实施与审查台账（2026-10-24）

## 发版记录（2026-10-25）

- **正式包 vCode129 已交付**：`~/workspace/许愿码-正式版-20261025-vCode129-统一画布与开发环境.apk`，SHA-256 `f8d2e236…fcd64`，aapt 校验 versionCode=129、签名 CN=AiCode（与历史包同一钥匙，可覆盖升级）。包含基线 6762a79 后全部提交：标注统一画布三批 + 双审处置、ScriptsViewModel 竞态修复、开发环境一键搭建。
- 发版前 optimizer（BATCH，6762a79..HEAD）结论：风险低，P0/P1 均无；P2×5 记台账（STROKE 草稿 O(N²) 复制、formatTime 每行新建、decode 在主线程协程、refreshJob 死字段、AnnotationCanvasScreen 776 行待拆分），见 qa/optimizer 报告正文（子代理 23c5e3bb / 824c3dce）。R8 面：无新增反射/序列化，proguard 无需改动（optimizer 已核）。

## 功能定义（对用户原话的工程化）

设置 →「开发环境」（运行环境组）：场景目录列出本软件能开发的程序类型与所需工具链；用户点选想开发的方向 → 弹「新建会话请求」确认框 → 确认后**新建会话（标题=场景名）+ 自动把搭建任务发入该会话 + 跳回聊天页**，由 AI 在当前执行环境真实安装工具链、跑验证命令并汇报版本。安装由 AI 在会话内执行（跟随本地 Alpine apk / 远程发行版包管理器），不写死脚本；失败要求说明原因与替代方案。

## 改动文件（本次已提交）

- 新增 `feature/settings/presentation/component/DevEnvironmentsSection.kt`：10 个场景（静态网页/前端工程/安卓/Python/Node/Java/Go/Rust/C++/数据科学）分 3 组，场景 = 名称 + 工具链摘要 + 完整搭建任务提示词；确认弹窗。
- `SettingsScreen.kt`：`SettingsSection.DevEnvironments` 枚举 + 菜单行（运行环境组）+ 分支渲染 + `onLaunchDevEnv(title, prompt)` 参数。
- `AIAgentViewModel.kt`：`newSession` 的空会话复用逻辑抽取为 `ensureCurrentSession(ws, title?)`（行为等价重构）；新增 `startSessionWithRequest(request, title)` = ensureCurrentSession + `enqueueAgentRequest(request, projectRoot, targetSessionId)`（忙时入队，不绕过发送纪律）。
- `MainActivity.kt`：settings 路由接入回调（startSessionWithRequest + popBackStack 回聊天 + 拒绝 Toast）。
- `strings.xml` / `values-en`：`dev_env_*` + `settings_dev_env` 各 37 条，key 一一对应。
- 能力登记：`docs-site/docs/guide/capabilities.md` + `app/src/main/assets/prompts/65-capabilities.md` 各新增一条。

## 验证

- 编译 `compileArmsoloReleaseKotlin` 通过；detekt（DevEnvironmentsSection / AIAgentViewModel）零发现。
- `SessionUseCaseWorkspaceDeletionTest`（会话创建邻域）通过；queueExternalText 既有链路 diff 未触碰（qa 确认）。

## qa 审查（子代理 23c5e3bb）处置

- **P1-1 startSessionWithRequest 未传 projectRoot**（CLI 引擎会失去 ~/workspace 绑定、系统提示词按「未选择工作区」渲染、项目级记忆/规则不加载）→ **已修复**：`enqueueAgentRequest(request, projectRoot = ws, targetSessionId = sid)`。
- **P2-1 空工作区 Toast 文案与原因不符**（ws.isBlank 也走「可能已暂停」文案）→ **记待排期**：空工作区在正常使用中不出现（聊天本身依赖工作区），防御性保留现状。
- **P2-2 paused 拒绝 Toast 大概率死代码**（新建/空会话不会是硬阈值暂停会话）→ **保留**：防御性分支，无害。
- **P2-3 onLaunchDevEnv 的 title 参数被丢弃** → **已修复**：`ensureCurrentSession` 新增可选 title，新建会话路径用场景名作会话标题（复用空会话路径保持原语义）。
- qa 已核无误：ensureCurrentSession 重构与原 newSession 行为逐点等价（空会话复用/setAgentState 顺序/MCP 重连/赋值顺序）；显式 targetSessionId 无丢消息时序窗口；popBackStack 时机由 currentSessionId StateFlow + 消息落库驱动，无竞态；枚举接入 depth/parentSection 正确；LazyColumn key 唯一；字符串中英逐 key 一致、XML 转义合法；能力登记两处口径一致。

## 真机验收清单

1. 设置 → 开发环境：入口可见、10 张场景卡片分 3 组展示。
2. 点「Python」→ 确认框显示说明 → 点「新建会话并搭建」→ 跳回聊天页：新会话标题为「Python（脚本 / 爬虫 / 服务）」，首条消息为搭建任务并已自动发送。
3. AI 在容器内 apk 安装 python3/py3-pip，跑版本验证并汇报。
4. 安装期间可发消息追问（忙时任务队列正常）；会话列表角标/标题正常。
5. 「静态网页」场景：不装包直接建骨架并给出 Web 预览指引。
6. 「安卓」场景：汇报内置 SDK/Gradle 版本而非重复安装。
7. 断网/装包失败：AI 说明原因与替代方案，不静默跳过。
