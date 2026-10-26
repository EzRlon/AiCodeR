# 上游 v1.13.0-rc1 评审与裁决（2026-10-26）

## 评审基线
- 上游：https://github.com/jieapi/AiCode release `v1.13.0-rc1`（tag 源码包已下载解压，约 10.8MB）
- 我方已合并基线：`539c922`（上游 2026-10-17 快照），我方 HEAD `c92a0c2`（158 提交）
- 差异面：**218 个文件**（186 修改 + 32 新增）；迁移号冲突确认——上游到 63，我方到 73
- 网络：GitHub 直连被 TLS 重置，走 `https://gh-proxy.com/` 前缀可取 release 元数据与源码包

## 一、采纳（P0，缺陷真实存在且我方已复现）
| 项 | 上游位置 | 我方现状 | 价值 |
|---|---|---|---|
| 子代理并发槽位原子占位 | SubAgentEventBus.kt（tryReserveSlots/releaseSlots + slotLock） | TaskTool.kt:166 `if (eventBus.isFull)` 检查与创建非原子 | 并行 task(create) 可超额创建；我方 10-26 只修了 tryEmit 与 early return，未修竞态 |
| DELETED 事件类型 | SubAgentEventBus.kt | 我方无 DELETED，del 后仍可能补送通知 | 防「幽灵会话」烧一轮 LLM 落孤儿消息 |
| 子代理权限只能收不能放 | ChatSession.kt `AgentMode.limitedBy` + SessionUseCase.kt `resolveChildMode` | SessionUseCase.kt:209 `mode = mode?.name ?: parent.mode` 可放大 | PLAN 下派 `mode: build` 子代理可绕只读，属安全漏洞 |
| editFile LF/CRLF 容忍 + 迭代扫描 | EditFileTool.kt `findLineEndingTolerant` | 我方 split/indexOf 精确匹配，CRLF 文件必 NO_MATCH | 我们记忆里已有该坑，这是根治 |
| 通知审批绑定 requestId | AgentApprovalNotificationManager.kt（data URI + EXTRA_REQUEST_ID） | 我方仅靠 requestCode 区分 | 新通知会复用旧 PendingIntent，点旧通知误批新请求 |
| Gemini 关闭思考不发 includeThoughts | GeminiAdapter.kt（budget=0 时排除 includeThoughts） | GeminiAdapter.kt:677 恒带 includeThoughts | Gemini 拒绝二者同时出现，请求硬失败 |
| 备份强制加密口令 | BackupViewModel.kt（password.isEmpty 直接报错） | BackupViewModel.kt:163 无校验 | 明文凭据上传云端 |
| FTP 默认弱口令 | FtpServerManager.kt（随机口令 + KeystoreCipher 加密存储） | FtpServerManager.kt:67/91 硬编码 123456 | 同网段可直连取文件 |
| 浏览器混合内容策略 | BrowserManager.kt:459→MIXED_CONTENT_COMPATIBILITY_MODE | BrowserManager.kt:510 ALWAYS_ALLOW | MITM 可篡改 https 页面子资源 |
| 浏览器超时与取消区分 | BrowserTool.kt catch 顺序（Timeout 先、Cancellation 重抛） | 我方 Timeout 分支在 Exception 之前但无 CancellationException 重抛 | 协程取消被吞成 BROWSER_ERROR |
| 计划审批多会话分槽 | PlanApprovalManager.kt（LinkedHashMap + id） | 单槽 currentDecision，后到覆盖前者 | 多会话并行时前者永久挂起 |
| 空会话查询下沉 SQL | ChatSessionDao.kt getEmptyRootSessionByWorkspace | SessionUseCase.kt:161-163 全量读根会话逐条查消息 | 每次新建会话多轮 DB 查询 |
| 设置页滚动位置保持 | SettingsScreen.kt rememberSaveableStateHolder | 我方无 | 从子页返回列表跳回顶部 |
| 安全拦截回显触发构造 | ShellCommandParser.Analysis.blockers + PolicyEngine | 我方只有 analyzable 布尔 | AI 不知道是 `$(...)` 还是重定向，整条反复重试 |
| 子代理完成通知带摘要与改动文件 | SubAgentDigester.kt + AgentNotificationFormatter | 我方通知无摘要，需再 task(read) | 省一次工具调用与一轮上下文 |
| 子代理清单标注读写命令能力 | SystemPromptProvider SubAgentListSource + AgentDefinition.capabilitySummary | 我方清单只有名称与用途 | 防把写任务派给只读子代理 |

## 二、采纳但需改造（P1）
- **思考能力解析子系统**（ReasoningResolver/CapabilityTables/Learning/Catalog + ReasoningPlan/Wire）：解决我方 OpenAIAdapter.kt:167 `normalizeReasoningEffort` 把 max/xhigh 压成 high、none/minimal 丢弃、各适配器各自硬编码 clamp 的分裂。依赖迁移 63（我方号段已被 session_team 占用，需改号）与 models.dev 新字段解析。建议**分批**：先摘 400 文案白名单学习（ReasoningLearning）与 Gemini/Anthropic 关闭语义修正，再整体替换 resolver。
- **子代理模型档位 tier**：与我方 `ModelRoutingTable`（FAST/BALANCED/STRONG 任务类型路由，ModelCapabilityRepository.kt:40-100）**功能重叠**。不建议并存两套；若要采纳，应把我方路由表合并为上游 tier 语义（按任务难度选档），而非新增第三套。
- **terminal read filter + close tab_ids**：我方 BackgroundTerminalTools.kt 无此二者（grep 无 parseTabIds/filterOutputLines），纯增量能力，低风险。
- **LazyColumn 改造**（技能/MCP/子代理/供应商列表）：我方仍是 Column+forEach（SkillsSection.kt:66/102、McpSettingsSection.kt:79/139），列表增长有重组开销。但改动面大且与多选组件耦合，建议与多选一并做。
- **列表多选批量操作**（ListSelection.kt 274 行 + SwipeToDeleteRow 改造）：一套组件服务六类列表，价值高但需逐页接线，属独立批次。

## 三、舍弃（不采纳）
| 项 | 原因 |
|---|---|
| 提示词全面通用化重写（00/10/15/40/50/60/70/99 + agent/*） | 我方提示词是定制版（含 20-coding-discipline/30-comments/41-token-discipline/45-message-intent/65-capabilities 五段自有内容），上游改写会覆盖掉我们的纪律与能力感知；且我方 15-project-rules 的「用户记忆 > 项目规则 > 内置提示词」优先级是用户明确定下的 |
| 免责声明/隐私政策门控（AgreementGate） | 上游合规需求，我们是私有分叉不分发；且会改动 MainActivity 启动链路 |
| 供应商预设增删（OpenCSG→七牛云、SiliconFlow、MiMo 实名标签） | 营销位与第三方服务，与我们无关；providers.json 差异仅此 |
| api.official.json 10k 行更新 | 上游模型目录全量刷新，我方已有运行时拉取 + 校验机制；直接替换会与我方模型能力画像/路由表冲突 |
| 应用内更新下载 | 我们自建分叉自行发版，不需要走官方源 |
| 文件树「当前容器」根节点、多目录 git 识别 | 我方工作区结构不同，改造成本高于收益 |
| 容器镜像创建后锁定本地/远程模式 | 我方执行模式切换是核心能力（三引擎），锁定会削弱灵活性 |
| 自动压缩阈值 90%→85% | 我方定为 70%（GeneralSettingsRepository.kt:78），已有更保守策略 |
| gradle.properties 堆 4096→7168m | 我方容器 PRoot 内存受限，且构建走 tools/build-with-lock.sh 串行，不存在上游 CI 的三 flavor 并行峰值 |
| 内置 Alpine rootfs 升级 3.21.8 | 我方有独立的容器镜像分发与「用户主动重置才更换」策略 |
| terminal-view Java 选择句柄/字号滑块修复 | 我方终端模块未改动这些路径，且我们记忆里的终端坑集中在 PTY/后台保活 |
| RemoteTerminalSessionManager 退出标记轮询 | 我方已用 notify=true + tabFinishedEvents 机制；上游方案是 1s 轮询常驻监控，违反我们「禁止常驻轮询」的功耗纪律 |
| GitHub device flow / MCP OAuth | 已在上一轮（556bc4e）合并过，本次无新改动 |

## 四、风险提示
- 上游迁移 63 `add_llm_call_reasoning_wire` 与我方 63 `add_session_team` 撞号：采纳思考能力时必须重编号（我方下一个可用 74），并同步 SCHEMA_VERSION。
- dex 引用数 64469/65536（余量 1067）：思考能力子系统 + 多选组件 + 子代理卡片三块若一次全并，有超 65536 风险，必须分批并每批核 dex 数。
- 子代理系统三块（槽位竞态、DELETED、权限收紧）与我方 10-26 修复同源不同实现，合并时以**语义等价**为准，不要两套并存。
