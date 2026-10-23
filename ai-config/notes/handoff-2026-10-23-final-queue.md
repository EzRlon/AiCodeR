# 交接（2026-10-23 晚终态）：vCode113 已交付 + 会话回收站已提交，剩余队列

> 本文件是**当前权威交接**。新会话开工：先读本文件 → `ai-config/notes/session-recycle-bin-plan.md`（刚做完的回收站设计与落地）
> → `ai-config/notes/deferred-queue-2026-10-23.md`（队列）→ `ai-config/notes/handoff-2026-10-23-overnight-batch.md` 第 12~18 条（引擎桥接与战略大件的原始设计）。
> 历史交接：`handoff-2026-10-23-scripts-abc.md`（脚本三切片）、`qa-*/perf-prerelease-review-2026-10-23.md`（双审台账）。

## 一、当前状态一句话

**已交付 vCode113 正式包（待真机验收）**；**会话回收站（30 天软删）已实现并提交 `c77805a`**（qa 复审报告待处置，见 §三）；
本地 `dev` 共 9 条提交、**未 push**。剩余队列＝第 5、7、8 项（第 6 项已按用户要求移出，见 §五）。

- 仓库：`~/workspace/AiCode`（注意不是 `~/workspace`），分支 `dev`。
- 工作区：干净（`git status --short` 无输出）时为安全节点；若有改动先确认是否本人未提交工作。
- 环境与命令见 §六（**每条都必须带 musl 补丁前缀**）。

## 二、本批已完成（勿重做）

| 项 | 状态 |
|---|---|
| 用户脚本切片 A/B/C（`run_script` 工具 + 脚本页 + 定时任务「运行脚本」） | 已提交 `3754b66`/`32b2b80`/`9051d0b`，三轮 qa 全处置 |
| 交付物页口径修正（去掉工作区 APK 盲扫，只认会话附件） | 已提交 `f3c03c0`，qa 复审无 P0/P1 |
| 发版前双审（qa 含 R8 维度 + 性能/功耗） | 已完成，均无 P0/P1，12 项 P2 全处置（`0e0b162`） |
| 正式 R8 包 | **vCode113 已交付**：`~/workspace/许愿码-正式版-20261023-vCode113-用户脚本+交付物修正+双审处置.apk`，SHA-256 `4a2adb50…`，签名与 v95 同一把钥匙（可覆盖升级） |
| 会话回收站（队列第 4 项） | **已提交 `c77805a`**：迁移 68 + 软删/恢复/彻底删 + 可见性封口（会话列表 6 处 + 消息侧 10 处）+ 冷启动逾期清理 + 抽屉内回收站弹层 |

## 三、新会话第一件事（按顺序）

1. **处置会话回收站的 qa 报告**：qa 子代理 `c96242d0-df70-4fe3-9744-b79b707a4117` 的复审（本批 L3 按路由派发）在上一会话结束时**仍在运行/未回读**（`task(action="list")` 可见其 state=running）。
   用 `task(action="read", id="c96242d0-df70-4fe3-9744-b79b707a4117")` 取报告；若该子会话已不可用，就**重新派一个 qa**（prompt 要点：只读、禁自跑 Gradle、
   范围 `git show --stat c77805a`、重点核「软删可见性是否封住所有查询」「迁移 67→68 双路径」「语义分流四处入口」「UI 空态与入口可达性」「备份 DTO 是否带 deletedAt」）。
   处置结果写台账 `ai-config/notes/qa-session-trash-disposition-2026-10-23.md`（纪律：报告不得无响应归档）。
2. **给用户真机验收清单**（vCode113 + 回收站需一起验，因为回收站带 DB 迁移 67→68）：
   - 从已装 vCode113 **覆盖升级**到含回收站的新包后：历史会话与消息完好、能正常发消息（验迁移）；
   - 删会话 → 侧栏消失 → 搜索/召回/交付物页/记忆图谱里都不再出现（验封口）；
   - 会话列表顶部出现「回收站（N）」→ 恢复整组回来 → 彻底删除后检查点与待办一并清掉 → 一键清空；
   - 会话全删光但回收站非空时，入口仍可达（空态不能把入口藏掉）。
3. **继续队列**：第 5 项修复账本 → 第 7 项 CLI 引擎桥接一期 → 第 8 项战略大件（§五）。

## 四、未验证 / 已知边界（如实）

- vCode113 与回收站**均未真机验证**；回收站的 DB 迁移（67→68）只能在真机确认。
- Room DAO 行为**本地无法单测**（Robolectric 在 aarch64/musl 不可用），DAO 断言依赖真机；本批只补了 UseCase 层单测。
- 回收站一期：附件不由回收站管理（附件按工作区存放、与会话无映射）；不做 Snackbar 撤销；UI 是抽屉内弹层而非独立页面；逾期清理只在冷启动做一次。
- `llm_call_records`/`permission_audit` 刻意不随会话删（用量与授权审计，按会话删会破坏历史统计）。
- 既有偶发（非本批）：全量单测里 `SyncEngineIgnoreTest.upload_skips_custom_ignored_paths` 偶报 `UncaughtExceptionsBeforeTest`（别的用例泄漏协程异常），隔离复跑通过。
- 脚本系统已知待排期项：远程 SSH 模式真正支持脚本（需改走 `FileAccessProvider`）、`away` 档位压过「不可记忆」、失败路径绕过 `ToolOutputStore`、`recall` 未收录授权目录、`list()` 逐文件读首行是 O(n) IO。

## 五、剩余队列（第 6 项已按用户要求移出）

| 序 | 项 | 规模 | 现状与开工要点 |
|---|---|---|---|
| 5 | **修复账本** | 中 | 现状未核实，先设计。要点：把「用户反馈 → 修复 → 复验」记成可追溯账本（对标标注账本的状态机：待确认→已发AI→修复中→待复验→关闭），入口与标注中心衔接。开工先派 Explore 核现状（是否已有 `AnnotationEntity` 状态机可复用）。 |
| 7 | **CLI 引擎桥接一期（Claude Code）＝用户说的「引擎切换」** | 大 | 设计已定稿（`handoff-2026-10-23-overnight-batch.md` 第 12 条）：headless `stream-json` 双向流 → 翻译成本体 `AgentEvent`；权限请求桥接授权中心/通知栏；历史一期走 CLI 侧续接（`~/.claude`），不迁库；适配层新包 `feature/agent/domain/engine/`；**UI 约束（用户已拍板）**：引擎选择收编进「模型选择弹窗」——顶部「引擎/模型」两段式分段按钮 + HorizontalPager 左右滑；选中 CLI 引擎自动滑到第二页选可续接会话；底栏与输入栏文案改 `CC·sonnet` 式；设置加「默认引擎」；会话列表加引擎角标；顶栏「智能体」按钮在桥接上线后移除（`ChatHeaderComponents.kt:164-173`）。二期 OpenCode、三期 Codex。 |
| 8 | **战略大件（四项，各自设计先行）** | 大 | 见下节逐项定义。建议顺序：记忆图谱 v2（最重、用户明确说现版粗糙）→ UI 自动化一期（只读）→ 编队消息投递语义 → 标注中心真融合。 |

### 第 8 项逐项定义（原文核实自 `handoff-2026-10-23-overnight-batch.md` 第 14/16/17/18 条）

1. **标注中心真融合**（第 14 条）：目标是「统一画布 / 状态机自动流转 / 复验闭环」的完整闭环，分期实施。上一会话派出的探索子代理已终止、**结论未产出**——开工前必须**先重派 Explore** 摸清现有标注中心（活标注/截屏标注/账本/汇总发给 AI）的实现与缺口。
2. **编队消息投递语义**（第 16 条，吸收 CCCC 思想）：团队派活加「送达 / 已读 / 必须回复」追踪，防长任务丢消息、漏汇报；落点 `feature/agent/domain/subagent/team` 的消息链。
3. **UI 自动化一期·只读**（第 17 条，战略缺口）：无障碍 + Shizuku 驱动**读屏 / 找元素 / 描述界面**（配合标注中心做「被开发 App 的自动验收」）；二期再做执行（点击/输入）。风险中等，**ROM 兼容需真机实测**，一期交付前必须有权限边界说明。
4. **记忆图谱 v2**（第 18 条，用户反馈「现版粗糙，对标 Operit 记忆库」）：①多记忆空间（按项目隔离）；②从会话/附件自动抽取实体与关系（替代手动建节点）；③节点关系可视化编辑；④时间/语义/关系混合检索（语义层设备端务实实现待定：**不上云、不依赖大模型 embedding**，可先做「关键词＋时间＋图谱游走」三路混合）。基线：现版泳道可视化保留，v2 重在其下的数据与检索层。

## 六、环境与命令（勿与其它 Gradle 构建并行）

```sh
cd ~/workspace/AiCode
# musl 容器必须带补丁前缀；快验用编译，正式包用 assemble
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so \
  ./gradlew :app:compileArmsoloReleaseKotlin
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so \
  ./gradlew :app:testArmsoloReleaseUnitTest            # 全量约 6~17 分钟；基线 1342 用例 0 失败
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so \
  ./gradlew :app:assembleArmsoloRelease                # 正式 R8 包，产物 app/build/outputs/apk/armsolo/release/
```
- 容器内**无 ripgrep**（`search` 工具不可用）：用 `grep -rn`；busybox 的 grep **不支持 `--include`**；**别对 `app/src/main/assets` 全库 grep**（min.js 会淹没输出）。
- 单测里 mock `CommandEngine` 需同时桩 `notReadyHint()` 与 `defaultShell()`（严格 mock 未桩即抛）。
- 构建中途被杀后重跑要带 `--no-build-cache`（避免 KSP 生成物被污染的坑）。
- `versionCode = gitCommitCount() + 1`：当前提交数 **115 → 下一个正式包 versionCode 116**，高于已交付的 113，不会撞号（每次提交后自动递增，无需手改）。

## 七、纪律（必须遵守）

- **commit 需用户裁决**；**push 一律先问**（共享状态；直连 GitHub 会被 TLS 干扰，需先开加速器）。
- 发版前必须走：`qa` + `optimizer` 双审（含 R8 维度：keep/混淆/资源收缩）→ `assembleArmsoloRelease` → aapt 校验版本与签名 → 交付；给用户安装的正式包**构建前先汇报逐项完成情况**。
- 审查路由：L2（≥3 文件或核心链路单文件）派 qa；L3（新功能/重构）派 qa + 性能审查；报告必须逐条处置或记录理由，写台账。
- 性能功耗纪律：无常驻轮询/定时器/持续动画；后台任务克制可取消；不新增唤醒源。
- UI 纪律：统一主题 token、功能分区清晰、过程折叠结果突出；用户报 UI 问题时**首选引导用标注画出来**。
- 能力感知纪律：新功能须在 `docs-site/docs/guide/capabilities.md` 登记 + 同步 `app/src/main/assets/prompts/65-capabilities.md`。
- 迁移号纪律：官方号保持原样，我方私有迁移用官方未用号；当前最大为 **68**（本批新增），下一个从 69 起，开工前先核对 `app/src/main/assets/migrations` 与 `app/schemas`。
