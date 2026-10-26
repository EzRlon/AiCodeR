# 项目规则

## 新设备首次开工（必须先做）

- **触发条件**：`app/keystore.properties` 或 `~/.aicode/keys/aicode-release.jks` 不存在——说明是 clone 后的全新环境。
- **行动**：完整执行 [`ai-config/RESTORE.md`](ai-config/RESTORE.md)（恢复发布签名 → AI 配置 → 编译环境 → musl 补丁 → 构建验证），**每步自验通过后才可开始任何开发或构建**。
- 本文件位于仓库根：若 App 以本仓库目录作为项目打开，此规则会自动注入 AI 会话。

## 质量流程（强制）

- **重大改动完成后必须派发 `qa` 子代理**（定义见 `.aicode/agents/qa.md`，仓库内副本在 `ai-config/agents/` 若未恢复）做一轮独立审查，之后才可向用户报告完成。重大改动指：新功能、重构、一次修改 3 个以上文件、关键 bug 修复。
- 派发时向 qa 子代理交代清楚：审查范围（git diff 或文件清单）、构建/测试方式、期望产出（带 `文件:行号` 的问题清单）。
- qa 派发纪律：qa 禁自跑构建（只读审查）；**单次范围 ≤ 一个文件或一个问题**（禁止「通读 N 个文件 + 写长篇分级报告」这类超长单回合）；search 工具不可用时用 grep/find。
- **子代理卡死判别与防范（2026-10-26 第二次验证，强制）**：子代理的 LLM 流式回合会静默冻结——不报错、不结束、state 永远 running。触发条件是「长回合 + 重负载窗口」（如主线程正在跑 R8 正式包构建时派大范围审查），已两次命中（2026-10-19、2026-10-26）。规则：①派发前先跑一个极简探针（读一行配置）验证链路；②正式包构建期间不派长任务，窗口内只派探针或等构建结束；③派发后 3–5 分钟无输出就 `task(action="read")` 确认，无输出立即 stop 重派小任务，不要连续等十几分钟；④`task(action="list")` 的 `runningSeconds` 用于区分「还在干活」与「已死该重派」。排查全链与结论见 `ai-config/notes/subagent-stall-rootcause-2026-10-26.md`。
- **发送链路专项核查（2026-10-22 用户定，强制）**：每个交付包构建前，qa 审查必须专项回归发送链路——普通发送、忙碌排队、会话暂停/终止后发送、悬浮窗发送（FloatBallCommandCenter.send）、外部注入（queueExternalText）五个入口逐一确认不被任何守卫/状态卡死；本类问题已多次发生过，禁止再带病出包。
- 轻微改动（单文件小修、注释、纯格式化）可豁免，但需在回复中说明跳过原因。

## 最终编译前流程（2026-10-21 用户定，强制）

- 适用：每次正式交付包（`assembleArmsoloRelease` 等 R8 包）构建**之前**；开发中的验证性编译不适用。
- 流程：①先派 `qa` 子代理（正确性/缺陷/回归，见上一节）→ ②再派 `optimizer` 子代理（性能/功耗/架构/屎山多维审查，定义见 `app/src/main/assets/agents/optimizer.md`）→ 两份报告都处置完毕（修复或记录）后才可构建正式包。
- **审查范围决策**：用挂起式询问问用户「仅审查当前批次 / 全盘周期性审查」；用户未响应或未表态时按默认规则自行抉择：
  - 距上次全盘审查 ≥ 3 个批次，或本次改动跨 ≥ 2 个功能模块，或涉及核心链路（agent 工作流/容器/悬浮球）→ **全盘（FULL）**
  - 否则 → **仅当前批次（BATCH）**
- optimizer 产出的建议：P0（持续耗电/明显卡顿/状态卡死）当批修复；P1/P2 记入 `.aicode/notes/` 待排期，不在发版临界期强改。

## 性能与功耗纪律（2026-10-15 用户定，强制）

- 所有正式版本：**软件必须流畅**（交互跟手、无卡顿掉帧）；在保证高效性能的同时，**禁止增加额外功耗**；目标是**低功耗、能用更长时间**。
- 设计与实现自查：不引入常驻轮询 / 持续动画 / 无谓唤醒；动画只存在于交互与短暂过渡且时长克制；后台任务克制、可取消、有缓存不重复消耗；避免高频重组、不必要的系统 IPC 与网络请求。
- 编译与审查环节纳入该维度（qa 审查覆盖）；存疑的取舍要在交付说明中如实标注。

## 进度汇报心跳（2026-10-15 用户定）

- 执行类任务期间汇报节奏：**关键节点（完成/失败/转向）即时报** + **时间心跳**（连续多步工作中约每 **15 分钟**一条一句话进度，按运行时上下文时间判断；临近收尾直接给结果）。
- 心跳“最大化简要”：只说当前进展与下一步/剩余量，不重复、不展开；等待后台任务/用户回复的静默期不需要心跳（后台任务完成通知会唤起汇报）。
- 该规则已同步内置进产品提示词 `app/src/main/assets/prompts/10-communication.md`，对所有用户会话生效。

## UI 反馈对齐纪律（2026-10-16 用户定，强制）

- 用户反馈界面问题（「这里不对 / 这个样式改一下」）时：**首选让用户用标注直接画出来**，禁止仅靠文字描述反复猜改。
- 按当前环境分流：
  - 环境为增强版 App（侧边栏有「标注中心」）→ 引导闭环：标注中心内点「活标注」（回原界面直接圈画，可定格含弹窗）或「截屏标注」画好 → 同页账本勾选条目 → 点「汇总发给 AI」（汇总文本 + 标注图直接进聊天输入框）→ 发送后 AI 即可看到原图与说明。
  - 环境没有该功能（如官方版容器）→ 让用户用「系统截图 + 涂鸦画圈/箭头」后作为聊天附件发来。
- 目的：一次对齐视觉预期，杜绝「AI 盲改 UI → 用户多轮返工」。条目同步维护于 `app/src/main/assets/prompts/65-capabilities.md`。

## 能力感知（新功能交付纪律）

- 新功能必须同步「AI 能力感知件」：①在能力地图 `docs-site/docs/guide/capabilities.md` 登记（场景 → 能力）；②影响 AI 建议/调用行为的功能，同步更新速查片段 `app/src/main/assets/prompts/65-capabilities.md`。
- qa 审查覆盖该项：让全新上下文的 AI 能答出该功能的存在与用法，否则不算完成。
- 收录口径：地图收录与 AI 协作/功能发现相关的能力，纯界面与系统维护类不纳入；规划中的能力不上地图，上线后登记。
- 背景与分期见 `.aicode/notes/capability-awareness-plan.md`（恢复自 ai-config/notes/）。

## 构建

- 安卓项目在本仓库根。**优先用仓库自带 wrapper**：`./gradlew <任务>`（gradle-8.14 腾讯镜像，无需手动装 Gradle）。
- **Alpine（musl）容器必须带补丁前缀**（Debian/Ubuntu 镜像不需要）：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew <任务>`。补丁来源与重编方法见 `tools/fix-remove/` 与 `ai-config/RESTORE.md` 第 4 步。
- 快验：`:app:compileArmsoloReleaseKotlin`；正式包：`assembleArmsoloRelease`（R8），产物在 `app/build/outputs/apk/armsolo/release/`。
- 构建失败先对照 `ai-config/RESTORE.md` 末尾「已知环境坑」表（KSP 缓存损坏 / apk add exit 2 / TLS 干扰）。
- **交付前跑自检**：`sh tools/release-selfcheck.sh <apk> [基线 apk]`——核对版本号（versionCode == 提交数 + 1）、体积差分（逐文件增量 Top 8）、启动路径阻塞/轮询扫描、keep 规则（点名类 + 包级通配均有类存活）、dex 引用数余量。有 `[FAIL]` 不得交付；`[WARN]` 逐条在交付说明里回应。

## 会话交接

- **最新交接（2026-10-23：用户脚本三切片 A/B/C）**：见 `ai-config/notes/handoff-2026-10-23-scripts-abc.md`。
  一句话状态：A（`run_script` 工具 + 脚本仓库）、B（设置 → 脚本页）、C（定时任务「运行脚本」）均已过 qa 并逐条处置（A/B 各两轮、C 一轮 BATCH，均无 P0/P1），已提交 6 条：`3754b66` A、`32b2b80` B、`9051d0b` C、`f3c03c0` 交付物页口径修正（去掉工作区 APK 盲扫）、`061eb2b` 队列笔记、`0e0b162` 发版前双审处置；
  发版前双审（qa 含 R8 维度 + 性能/功耗）均**建议出包、无 P0/P1**，12 项 P2 全部当批处置（1 项经核实为误报、1 项记录为可接受）；
  正式 R8 包已构建并交付：**versionCode 113**（`~/workspace/许愿码-正式版-20261023-vCode113-用户脚本+交付物修正+双审处置.apk`，签名与 v95 同一把钥匙、可覆盖升级），**待真机验收**（交接 §五 清单 + `qa-sliceC-disposition-2026-10-23.md` §四 新增 4 点）；**尚未 push**。
  本批笔记：`handoff-2026-10-23-scripts-abc.md`（权威）、`handoff-2026-10-23-scripts-sliceB.md`（B 细节 + C 计划原文）、`handoff-2026-10-23-scripts-sliceA.md`（A 设计决策）、`qa-sliceA-disposition-2026-10-23.md`、`qa-sliceB-disposition-2026-10-23.md`、`qa-sliceC-disposition-2026-10-23.md`、`qa-prerelease-review-2026-10-23.md`、`perf-prerelease-review-2026-10-23.md`、`deliverables-scope-fix-2026-10-23.md`、`deferred-queue-2026-10-23.md`。
- **上一版交接（2026-10-20 发版终态）**：五批次（A 压缩/B Codex 自愈/C 存储清理/D 动画/E multi-llm）+ FTS 检索 + 悬浮球 mini 会话**全部已提交并推送**（`677966e` 等五批提交）；发版前三路审查（qa/屎山/性能）完成，P1×2 已修复入库（ad92f2c）；正式包 **versionCode 85** 已交付并真机验收通过（环境一键安装 ✓、Codex 启动 ✓）；README 重写为与上游功能区别导向（反代 MCP 介绍已按用户要求移除）；签名密钥备份与 AI 协作资产已入库（`backup/`、`ai-config/`）。
- **发版后队列**：性能优化 8 项 + 架构重构 10 项 + P2 记录 5 项，全部带行号归档于 `ai-config/notes/pre-release-audit-2026-10-20.md`，按该笔记优先级执行。
- 历史交接档案（2026-10-13 至 10-19）在 `ai-config/notes/`（原工作区 `.aicode/notes/`），按需读取。

## 路线图执行

- 增强版路线图见 `ai-config/notes/roadmap-final.md`（含 D 段专家团、G 段记忆图谱的落点与分期）。
- 执行 **D「协作智能体编队」** 时，先读取该笔记的 **D 段（D3 专家团/编队）** 与 **G 段（记忆图谱）** 再动手。

## AI 工作笔记与记忆库纪律（2026-10-13 用户定）

- **记忆库存两类内容**：①**用户画像**（偏好、习惯、沟通方式——提升体验）；②**踩坑经验**（bug 根因、环境陷阱、操作教训——避免重复踩坑，须验证后写入）。
- **路线图、计划、验收清单、流程规则等技术内容一律写 `.aicode/notes/`**，按需读取，不进记忆库。
- 全局笔记现存（`~/.aicode/notes/`）：roadmap-final、release-pending-verification、qa-discipline、handoff-pending 等（恢复自 ai-config/global-notes/）。
