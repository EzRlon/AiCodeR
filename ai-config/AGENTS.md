# 项目规则

## 质量流程（强制）

- **重大改动完成后必须派发 `qa` 子代理**（定义见 `.aicode/agents/qa.md`）做一轮独立审查，之后才可向用户报告完成。重大改动指：新功能、重构、一次修改 3 个以上文件、关键 bug 修复。
- 派发时向 qa 子代理交代清楚：审查范围（git diff 或文件清单）、构建/测试方式、期望产出（带 `文件:行号` 的问题清单）。
- 轻微改动（单文件小修、注释、纯格式化）可豁免，但需在回复中说明跳过原因。

## 性能与功耗纪律（2026-10-15 用户定，强制）

- 所有正式版本：**软件必须流畅**（交互跟手、无卡顿掉帧）；在保证高效性能的同时，**禁止增加额外功耗**；目标是**低功耗、能用更长时间**。
- 设计与实现自查：不引入常驻轮询 / 持续动画 / 无谓唤醒；动画只存在于交互与短暂过渡且时长克制；后台任务克制、可取消、有缓存不重复消耗；避免高频重组、不必要的系统 IPC 与网络请求。
- 编译与审查环节纳入该维度（qa 审查覆盖）；存疑的取舍要在交付说明中如实标注。

## 进度汇报心跳（2026-10-15 用户定）

- 执行类任务期间汇报节奏：**关键节点（完成/失败/转向）即时报** + **时间心跳**（连续多步工作中约每 **15 分钟**一条一句话进度，按运行时上下文时间判断；临近收尾直接给结果）。
- 心跳“最大化简要”：只说当前进展与下一步/剩余量，不重复、不展开；等待后台任务/用户回复的静默期不需要心跳（后台任务完成通知会唤起汇报）。
- 该规则已同步内置进产品提示词 `AiCode/app/src/main/assets/prompts/10-communication.md`，对所有用户会话生效。

## UI 反馈对齐纪律（2026-10-16 用户定，强制）

- 用户反馈界面问题（「这里不对 / 这个样式改一下」）时：**首选让用户用标注直接画出来**，禁止仅靠文字描述反复猜改。
- 按当前环境分流：
  - 环境为增强版 App（侧边栏有「界面标注 / 截屏标注」）→ 引导闭环：界面标注（活标注=在真实界面直接圈画，可定格含弹窗）或截屏标注画好 → 「标注账本」勾选条目 → 点「汇总发给 AI」（汇总文本 + 标注图直接进聊天输入框）→ 发送后 AI 即可看到原图与说明。
  - 环境没有该功能（如官方版容器）→ 让用户用「系统截图 + 涂鸦画圈/箭头」后作为聊天附件发来。
- 目的：一次对齐视觉预期，杜绝「AI 盲改 UI → 用户多轮返工」。条目同步维护于 `AiCode/app/src/main/assets/prompts/65-capabilities.md`。

## 能力感知（新功能交付纪律）

- 新功能必须同步「AI 能力感知件」：①在能力地图 `AiCode/docs-site/docs/guide/capabilities.md` 登记（场景 → 能力）；②影响 AI 建议/调用行为的功能，同步更新速查片段 `AiCode/app/src/main/assets/prompts/65-capabilities.md`。
- qa 审查覆盖该项：让全新上下文的 AI 能答出该功能的存在与用法，否则不算完成。
- 收录口径：地图收录与 AI 协作/功能发现相关的能力，纯界面与系统维护类不纳入；规划中的能力不上地图，上线后登记。
- 背景与分期见 `.aicode/notes/capability-awareness-plan.md`。

## 构建

- 安卓项目：`cd ~/workspace/AiCode && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk /root/tools/gradle-8.11.1/bin/gradle assembleDebug`，产物在 `AiCode/app/build/outputs/apk/debug/`。
- 注意：工作区根目录不是 Android 项目（历史 TestApp 已清除）——一切构建都在 `AiCode/` 内进行。

## 会话交接

- **最新交接（2026-10-19）**：新会话开工前必读 `.aicode/notes/handoff-2026-10-19-five-batches.md`（五批次已全部实现+编译+完整打包通过未提交：A 压缩误报静默跳过 / B Codex 启动自愈+procps v12 / C 设置页专家团改名+子代理移位+存储清理 8 类+会话删除 VACUUM / D 动画 token+pressScale+FloatingTabBar 去重+过渡补齐 / E multi-llm 内置 MCP 列表区场景实测通过；未做：qa 独立审查两次中断需按 qa-discipline 新规则重派、真机验收、白名单拆分提交——工作区混有另一会话 FTS 未提交工作；环境坑：打包必带 LD_PRELOAD=fix_remove.so、apk add 已装包 exit 2、KSP 偶发增量损坏清缓存即过）。
- **上一交接（2026-10-18 深夜）**：新会话开工前必读 `.aicode/notes/handoff-2026-10-18-bugfix-rea-batch.md`（bug修复批次8项+悬浮球重设计+REA重构已完成待验收；**打包被容器层 PRoot unlink bug 阻断**，见记忆 build-mergejavares-tmpdir-pitfall；**工作区混有另一会话 6 个未提交文件，提交必须按白名单分开**；含待办任务表/验收清单/后续批次实施书）。
- **上一交接（2026-10-18 终态）**：`.aicode/notes/handoff-2026-10-17-feedback-batch-final.md`（反馈批次 7 清爽化+沉浸+精灵球+交付物页+上下文 70/93 全部完工，安全节点 dev `5f5e800`，正式包 versionCode 78 已交付待真机验收；含待办队列、qa 按批次审建议）。
- **历史交接（2026-10-17）**：`.aicode/notes/handoff-2026-10-17.md`（REA 工作台批次，已收尾）。
- **历史交接（2026-10-16）**：`.aicode/notes/handoff-2026-10-16.md`（终态：全部批次已提交至 `208b178`、R8 通过、验收包已交付；含关键经验、后续队列与环境坑）。
- 历史交接项已全部收尾（记忆图谱/笔记模式 P1 已编译验证，交接提醒已实施）；遗留细节见工作区 `.aicode/notes/handoff-pending.md`。
- 能力雷达 P0（能力地图 + 65 场景速查片段 + 交付纪律）已于 2026-10-14 随「工具授权中心 v1」批次并入提交；设计与分期见 `.aicode/notes/capability-awareness-plan.md`。

## 路线图执行

- 增强版路线图见 `~/.aicode/notes/roadmap-final.md`（含 D 段专家团、G 段记忆图谱的落点与分期）。
- 执行 **D「协作智能体编队」** 时，先读取该笔记的 **D 段（D3 专家团/编队）** 与 **G 段（记忆图谱）** 再动手。

## AI 工作笔记与记忆库纪律（2026-10-13 用户定）

- **记忆库存两类内容**：①**用户画像**（偏好、习惯、沟通方式——提升体验）；②**踩坑经验**（bug 根因、环境陷阱、操作教训——避免重复踩坑，须验证后写入）。
- **路线图、计划、验收清单、流程规则等技术内容一律写工作区 `.aicode/notes/`**，按需读取，不进记忆库。
- 现存笔记（`~/.aicode/notes/`）：roadmap-final、release-pending-verification、qa-discipline、handoff-pending、e4-goal-hosting-plan、self-improve-mode-plan、self-improve-and-teams-order、expert-teams-design、mapping-preview-constraints。
