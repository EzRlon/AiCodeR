# 漏做 / 未完成项大盘点 + 补全交接单（2026-10-14 调查）

> 调查会话说明：本次为「只查不改」盘点会话，调查主 agent 之外的历史工作。
> 证据源：`~/.aicode/notes/` + `~/workspace/.aicode/notes/` 全部笔记、`~/workspace/AiCode` git 历史/工作区状态、代码全库扫描。
> 判定口径：只看**真实代码**；已实施并提交的功能一律不列（视为"已做好"）。
> 快照时点：2026-10-14 04:04–04:30（主 agent 的 handoff 文件与 git 工作区状态）；主 agent 正在施工中且推进较快，动手前先 `git status` / `git log` / 重读 handoff-2026-10-13-batch.md 复核。本盘点已经独立复核子代理交叉验证（22 项全核对，结论一致）。
>
> **笔记路径索引**（文中引用的设计稿）：
> - `~/.aicode/notes/`：remote-manager-plan、live-annotation-plan、message-intent-tag-plan、tool-authorization-plan、scheduled-ai-task-plan、handoff-2026-10-13-batch、v2-staging/、本文件
> - `~/workspace/.aicode/notes/`：build-master-plan、online-store-plan、release-pending-verification、roadmap-final、e4-goal-hosting-plan、expert-teams-design、handoff-pending、mapping-preview-constraints

---

## 一、主 agent 正在收尾（半成品——接手前先看 git 状态，勿重复动）

| # | 项 | 状态快照 | 设计稿 |
|---|---|---|---|
| A1 | **远程管理**（局域网 HTTP 文件管理，MT 同款） | 代码全部写出：服务端 `feature/workspace/domain/remote/http/` 两文件、网页 `assets/remote/index.html`、手机页 `RemoteManagerScreen.kt`+VM、ChatDrawer 双入口、MainActivity 路由；**qa 审查已通过（修正项已修：CSRF 头/上传残件清理/启停互斥/存储权限判定等）**；**正式版编译（Release+beta）进行中；未提交** | remote-manager-plan.md |
| A2 | **抽屉右缘索引标签改造**（仅图标+按住浮出说明） | 已实施（`ChatDrawer.kt`/`WindowSize.kt`/strings 修改）；编译通过；**未提交** | handoff-2026-10-13-batch.md §5 |
| A3 | **附件按钮忙碌禁用修复** | **已完成**（已放开：+ / 麦克风 / 排队行附件标识）——待随反馈批提交 | handoff 执行顺序 |
| A4 | **正式版编译**：`assembleUniversalRelease`（R8 验证+归档）+ `assembleUniversalBeta`（隔离包 `com.aicode.beta`） | **进行中**：Release 已产出（`app/build/outputs/apk/universal/release/app-universal-release-unsigned.apk`，04:35 时点）、Beta 构建紧接着进行；未提交 | handoff §8 执行顺序 |
| A5 | **反馈批 git 提交**（归拢 A1–A3） | 未做 | handoff §8 |

---

## 二、已设计完成、等待实施（设计稿齐全，可直接开工；handoff 执行顺序）

| # | 项 | 状态 | 设计稿 |
|---|---|---|---|
| B1 | **消息类型 v1**（底栏类型按钮/13 项/default 未分类/气泡徽章/迁移 64/AI 瞬态提示） | ✅ 已实施并提交 `dfb7a47`（2026-10-14）：DB 迁移 64 + UI 全套 + 备份链路 + 提示词 45 + docs 同步；编译+单测 1152/0；待真机验收 | message-intent-tag-plan.md |
| B2 | **工具授权中心 2.0 v1**（工具分档 自动/询问/禁止 + 中文解释 + 审计记录） | ✅ 已实施并提交 `08f37e2`（2026-10-14）：四区重构 + 22 工具三档 + 授权档案 + 授权记录（迁移 65）+ 规则中文解释；编译+单测 1156/0；待真机验收；无障碍放 v2 | tool-authorization-plan.md |
| B3 | **定时任务 / 定时 Plan v1**（两入口 + AI 型定时任务 + 预授权清单 + 计划先行 + 保活复用 + 排期中心/离开模式/自动接棒） | 未实施（现只有 E5 容器命令型 schedule）；9 决策已代选 | scheduled-ai-task-plan.md |
| B4 | **界面标注 v2「活标注」** | 设计定稿；两份基础件已写（`v2-staging/FullScreenCapture.kt`、`ComposeSemanticsHitTester.kt`，另有 `/root/tmp/v2/` 拷贝）；**主体未写**：`LiveAnnotationOverlay.kt`、MainActivity 接线、`AnnotationEditorDialog.initialMarks`、抽屉入口 | live-annotation-plan.md |
| B5 | **App 图标 SVG 重设计** | 未开始（docs/icon.webp 未动）；方向已代选：终端提示符 `>_` + 青→靛渐变 #22D3EE→#6366F1 + 深板岩底；排执行队尾 | handoff §3 |
| B6 | **在线商店**（技能/MCP/专家团） | 未实施（全库无 claudemarket/store 痕迹）；3 决策点待拍板或按推荐默认（claudemarket.ai API + AI 翻译缓存 + 编队先"从链接导入"）；曾静默降级未告知用户（已认账） | online-store-plan.md |
| B7 | **守夜守护 v1**（无人值守安全：残弹六条升级 + 兜底超时 + 锚点/报告/急停；对标 Claude Code） | ✅ v1 第一刀已实施并提交 `30ebde3`（2026-10-14）：残弹六条 + 超时 + 开关 + 审计 + 提示词；编译绿 + 单测 1167/0 + 外部模型审查；v1 第二刀（锚点/报告/急停）与 v1.5/v2 待续 | unattended-security-plan.md |

---

## 三、总计划（build-master-plan）中的大块未开工

> 基准文档：`~/workspace/.aicode/notes/build-master-plan.md`（10-13 汇总版，"待用户放行"的完整施工清单；B1 段已随反馈批完成）。以下各项全库扫描均无实现痕迹（除注明）。

| # | 大项 | 未做细项 |
|---|---|---|
| C1 | **B2 视觉工具箱** | ①视觉四件套——**拍照/相册已有**（聊天附件三入口：文件/图片/拍照，`ChatInputAttachments.kt`）；**OCR/扫码未做**（全库无 ML Kit/CameraX/扫码，无 CAMERA 权限）；②入口分区 + 全屏「AI 工具箱」网格——未做；③新工具集成 4 个（QuickJS JS 沙箱计算/剪贴板读写工具/语音朗读 TTS 工具化/聊天内图表卡片）——4 项全无（工具注册表 22 个工具逐一核对）；④悬浮球（FloatingX；需 `SYSTEM_ALERT_WINDOW`）——未做（注：VNC 小窗最小化球是应用内的，不跨应用） |
| C2 | **B3 实时语音翻译体系**（用户点名重点） | sherpa-onnx 流式转写+VAD；句级流式翻译（多目标语言+自动识别）；App 内双语悬浮窗+转写进会话；Kokoro-82M 多音色朗读（103 音色+试听；系统 TTS 备选）；音色角色分离（发言人 A/B/C+声纹库）；会议总结。**全库无 sherpa/kokoro/翻译悬浮窗痕迹** |
| C3 | **B4 开发与终端增强** | ①终端 UI 重设计——**部分**：标签横滑条、额外键条（ESC/TAB/CTRL/方向/C-c·d·z·l）已存在；未做：键条**自定义**、命令面板（历史模糊搜索）、终端内搜索、选中即复制、选中发 AI/让 AI 执行；②终端工具市场（tmux/fzf/bat/eza/rg/fd/jq/httpie/rclone/micro 一键安装）+ 设备桥（notify/clipboard/open）——均未做（现有“环境安装”provision 场景≠工具市场）；③文件管理增强：基础文件操作已有（新建/重命名/删除/复制·剪切·粘贴、.gitignore 弱化等），**未做**：git M/A 标记、“复制路径·终端打开·问 AI”菜单、文件大小详情、多选批量、批量重命名模板、压缩解压、名称过滤；④工作流 P1（`.aicode/workflows/<name>.md`+执行器+`/workflow` 入口）——未做（内置命令仅 compress/init/usage）。**（HTTP 远程管理=在进行，见 A1）** |
| C4 | **B5 平台与生态** | ①记忆库功能（画像导入导出 JSON/问询式补充/画像归纳与冲突检测）；②会话历史归档体系（压缩时增量导出 `.aicode/history/`+index.md+AI 检索指引）；③RikkaHub 吸收项（搜索服务多源 Exa/Tavily、AI 翻译、消息分支、数据恢复页、技能页搜索）；④工作流 P2（Compose 节点画布）；⑤定时自主施工保活版（→ 实际已并入 B3 的 scheduled-ai-task-plan 设计） |
| C5 | **C3 沙箱测试空间** | PoC 第一阶段已过（BlackBoxReborn 跑起测试 APK，Android 11 红米 K40）；**产品化未做**（集成需 NDK 编译引擎核，容器未装 NDK） |
| C6 | **G 记忆图谱 P2/P3** | P1 已做（2D 图）。P2：3D 视图（3d-force-graph）/markmap 脑图/AI 扫描模块；P3：冲突提示/全部重划分/权限门。全库无 3d-force/markmap 实现 |
| C7 | **笔记模式 P2/P3** | P1 已做（Vditor）。P2：脑图；P3：导出 PDF（PrintManager）/图片 |
| C8 | **D4 编队看板 UI** | 未做（远期） |
| C9 | **专家团成长与纠正**（用户 10-12 拍板过的设计，实施不完整） | ①AI 写回 `~/.aicode/teams/`、`~/.aicode/agents/` 的**改进记录/变更历史可查看、可回退**——未做；②自制通道“更丰富”要求（成员挑选面板含专长/模型/工具权限、团长指令模板/校验、从现有编队复制、导入/导出、启停管理、工作流预览）——**现状仅：设置页 TeamsSection 为只读列表（提示“手写 teams/<name>.md”）+ TeamPickerSheet 选择用**，上述增强未做 |
| C10 | **E4 目标托管·剩余** | 主功能**已实施**（验收模型/Token 预算/超预算暂停/收尾独立验收，批次5）；剩余：可选「验收结果持久化」（需迁移）未做；真机验证未做 |
| C11 | **"工具提升第一档"** | handoff 原话："工具提升第一档在批次间隙择机做（环境安装先打招呼）"——待主 agent 对照（关联 C3-②终端工具市场） |
| C12 | **映射预览·自动刷新** | “AI 改完预览自动更新（无需手动刷新）”（mapping-preview-constraints.md “实时反馈机制定稿”）——现状：Web 预览为手动刷新按钮（`preview_reload`；页内 HMR 由容器 dev server 自身承担，App 未接线观察）；**“文件监视+WebSocket 推送自动刷新”未做**（社区底座 `core/watch/FileChangeHub.kt` 已有，现服务配置热更/文件浏览，可复用） |

---

## 四、验证与质量缺口（功能已做但未闭环）

| # | 项 | 内容 |
|---|---|---|
| D1 | **真机验收大清单** | release-pending-verification.md 全部未验收项：浏览器（视频声音/全屏/下载/书签）、语音长按、网络切换（详见 D5）、抽屜竖排标签、自我改进/交接提醒设置、交接提醒流程、专家团、QEMU、存储空间增强交互、主题白字专项（深→浅两路径） |
| D2 | **已知小问题（暂留 9 条）** | 批量模型测试（进度分母/pendingClean 残留）；`agent_budget_stopped` 文案只用 %1$d；验收调用取消记 error（主路径记 cancelled）；存储空间产物明细静默上限/reveal 不滚动/清理中删除静默/VNC 死代码（requestReconnect）；项目内符号链接指向另一项目、reveal 同名误定位 |
| D3 | **测试债** | SafeFileDelete 与中间版迁移路径无自动化用例（容器 Robolectric 不可用，已用实测+SQLite 模拟替代） |
| D4 | **记忆图谱 P1 / 笔记模式 P1 真机测试** | 编译过了，未真机 |
| D5 | **网络切换后流式请求卡死（bug 调查单）** | 修复**已实现**（`NetworkWatcher` 接线 `AIEditorApp.kt:170` + `ActiveStreamGuardRegistry.interruptAll()` + 首字超时 120s + 数据块间隔 120s）；**待真机验证**（三类切换场景）；修复方向 #3（“在等/卡死”UI 区分）未做；**记忆 `~/.aicode/memory/network-switch-stream-hang.md` 状态过时（仍写“未修复”）——验收后按记忆要求更新状态+补验证记录** |

---

## 五、明确搁置（非漏做，仅记录透明度；用户恢复时再做）

- **F 暂缓池**：用量仪表盘（注：上游 Token 统计页已存在，形态待对账）、多会话看板、Web/IM 遥控、Android 系统自动化、离线小模型
- **MenuetOS/KolibriOS 的 QEMU 演示安装**（已代选"不做"，仅演示价值）
- **专家团两个待定题**（待用户确认/待设计）：①内置 6 成员是否 "只可停用不可删"；②App 升级时"官方内置更新 vs 本地成长版"合并策略（哈希比对区分未动/被改）

---

## 六、一键交接指令（直接复制给主 agent）

```
【漏做项补全交接 · 2026-10-14】
先读 ~/.aicode/notes/handoff-gap-sweep-2026-10-14.md（本盘点单），再动手。
背景：跨会话盘点发现一批"设计过/承诺过但未实施或半成品"的项；你是主施工会话，按下面顺序补齐。
纪律：①动手前逐项核实当前状态（git status/log + 该单第一节"收尾中"可能已被你部分完成）；②设计稿一律以所述笔记为准，不重新发明；③每批完成走统一标准：编译 → qa 子代理审查 → 双语/主题检查；④涉及 DB 迁移自 64 起排（核对 AgentDatabase.SCHEMA_VERSION 与 assets/migrations 最大号）；⑤环境类安装（NDK、端侧模型等）先向用户打招呼；⑥暂缓/放弃决定必须明示用户，不允许静默降级。

补全队列（建议顺序，可按你手头施工灵活插队；先完成手上收尾）：
0. 【收尾】远程管理提交 + 抽屉标签提交 + 附件按钮修复 + 正式版编译（assembleUniversalRelease + assembleUniversalBeta）+ git 提交（若已完成则跳过）
1. 消息类型 v1 —— 设计 message-intent-tag-plan.md（含迁移 64，4 决策已定）
2. 授权中心 2.0 v1 —— 设计 tool-authorization-plan.md（5 决策已定；无障碍 v2）
3. 定时任务/定时 Plan v1 —— 设计 scheduled-ai-task-plan.md（9 决策已定）
4. 活标注 v2 —— 设计 live-annotation-plan.md；基础件已在 v2-staging/（另有 /root/tmp/v2/ 拷贝），缺：LiveAnnotationOverlay.kt + MainActivity 接线 + AnnotationEditorDialog.initialMarks + 抽屉入口
5. 图标 SVG —— handoff-2026-10-13-batch.md §3 方向（>_ + #22D3EE→#6366F1）
6. 在线商店 —— 设计 online-store-plan.md（3 决策点可代选=推荐默认；记得先告诉用户开始做了）
7. 大块（build-master-plan.md）：B2 视觉工具箱 / B3 实时语音翻译 / B4 终端与文件增强、工作流 P1 / B5 记忆库、历史归档、RikkaHub 吸收项 / C3 沙箱产品化（需 NDK，先问）/ G 记忆图谱 P2P3 / 笔记模式 P2P3 / 专家团成长与纠正增强
8. 验证缺口：真机验收清单、已知 9 小问题、测试债、记忆图谱/笔记 P1 真机测试（release-pending-verification.md）
9. 明确搁置项（F 池等）不动，除非用户点名。
汇报：每完成一批向用户简报；全部列项状态变化时更新本盘点单（勾除或标注）。
```

---

## 附：已核实"做好"的内容（不列清单，供对照防误判）

批次1–5 全部（智能体宿主/对话分层/标注账本/Web 预览/Hooks/记忆管理 E3/定时任务 E5/浏览器补齐/自我改进/交接提醒/专家团基础/存储空间/QEMU）；模式 UI 双批、进度框真实化、底栏修复、标注编辑器增强、顶栏顺序；上游合并 a2b49d7 + 幂等迁移；E4 主功能（验收模型/预算/暂停/收尾验收，独立复核无缺口）；记忆图谱 P1、笔记模式 P1；VNC 悬浮窗；双槽构建；终端标签横滑条/额外键条、文件浏览器基础操作（新建/重命名/删除/复制粘贴）、聊天拍照/相册附件；网络切换修复（NetworkWatcher，见 D5）。

---

## 复核说明

本盘点由两路独立核实交叉验证：①盘点会话逐项全库扫描；②独立 Explore 子代理按 22 项清单二次核对（含关键词多拼写变体）。两路结论一致；子代理对“终端重设计/文件管理/视觉四件套/Web 预览”四项作了“部分已做”的精确化，已合并入上表。两路的共同局限：容器无 ripgrep，判定为“完整枚举+全库 grep/阅读范围内未发现”，个别冷门命名理论上可能漏网。
