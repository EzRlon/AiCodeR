# 交接（2026-10-23 夜间批次终态，08:00 前暂停点）— 新会话开工前必读

## 零、暂停点（07:05，用户主动叫停以避开上下文陷阱）

- **工作区与 v95 构建状态完全一致，无半成品**：`git status` ≈ 63 项改动（本批全部批次）＋未跟踪新文件（笔记/新源文件）；已逐个核实**无半成品残留**。
- **已确认零改动**：脚本系统切片 A（run_script 工具）在开工后即被叫停，无任何文件改动（strings 无 `script_` 键、无 `run_script` 代码）——不在手尾。
- 本会话上下文负荷高（16 个子代理报告＋大量源文件阅读）；接手前**不要重跑任何已完成的调查**（冲突调查/P2 归档/上游 diff/竞品核实结论均已落盘）。

> 上一份 `handoff-2026-10-23-overnight-batch.md` 是本批的施工指令（已执行完毕）；本文件记录**执行后终态与顺延项**。

## 一、已交付（等待用户真机验收）

- **正式包 vCode 95**：`~/workspace/许愿码-正式版-20261023-vCode95-夜间批次-全主线+商店安全层+本地模型.apk`（R8 构建成功、单测全绿、aapt 校验 versionCode=95）。
- 完整交付说明：`ai-config/notes/overnight-batch-delivery-2026-10-23.md`（含 11 批次清单、审查处置、冲突调查结论、上游 diff 结论、验收要点、清理记录）。
- **未 commit**（用户指令：验收通过后才本地 commit，可按批次拆分，**绝不 push**）。
- versionCode 机制改动：`app/build.gradle.kts` = `gitCommitCount() + 1`（交付前构建不与上一发版撞号）。**若再出第二包需 +2 或先 commit**，否则同号。

## 二、本批实施要点（避免重复调查）

- 微压缩（发送副本投影，保留最近 5 条工具结果；`TOOL_OUTPUT_ARCHIVE_PATH` 正则须有界 `[^\s\]"]+`）、压缩失败熔断（3 连败暂停 10 轮）、压缩后工作集恢复（≤5 文件 ≤40K，注入前有 `restoreFitsBudget` 护栏，自动/溢出/手动三路）。
- 存储推荐清理（`StorageCleanupAdvisor`，`PATH_GUARD_KINDS` 决定哪些项走路径禁区校验；禁区动态 rootfs 前缀排除 `rootfs_images`）+ 删会话孤儿源修复（AIAgentViewModel 两条删除路径清级联子会话检查点）。
- 商店安全层批次一（`StoreInstallScanner`＋assets/store-scan-rules.json；安装链路 plan/commit 两段式，挂起态 `PendingScanGate`）；远端名称落盘走 `safeName`。
- summon_team 工具（子代理**双层禁用**：AgentDefinition.filterToolNames＋AIAgentViewModel isSub 工具集）；脚本切片 A（`run_script` 工具＋`~/.aicode/scripts/`）。
- 能力下沉：`assets/skills/code-intel/`＋`assets/aicode/lib/scenarios/codeintel.sh`＋`assets/aicode/lib/tools/{repo-map,callchain}.sh`（随 extractEnvTool 释放到容器 `~/.aicode/lib`）。
- 本地模型：`scenarios/localmodel.sh`（apk llama.cpp＋v3.24 跨版本源兜底；Ollama 解包到 `$LOCALMODEL_ROOT/ollama`）+ `providers.json` localmodel 预设。
- 首帧闪色：`values/styles.xml` 的 `Theme.AICode.DarkBg/BlackBg` 覆盖主题＋MainActivity 注入后同步读主题模式。

## 三、审查与纪律状态

- qa×2 轮 + optimizer×2 轮全部处置闭环：**P0×0；P1×9 全部当批修复；P2×20 余条带行号归档**于 `ai-config/notes/overnight-batch-optimizer-p2-2026-10-23.md`（含处置决定）。
- 新增记忆（踩坑，已验证）：`gradle-parallel-build-ksp-cache-pitfall`（多批次并行构建污染 KSP 生成物；修法=停并行＋删生成目录＋`--no-build-cache`）；`network-switch-stream-hang` 已更新（NetworkWatcher 已实现，真机仍待验）。

## 四、顺延项（08:00 前无法保质完成，按纪律不赶工）

1. **脚本系统切片 B（脚本页 UI）与切片 C（定时任务「运行脚本」动作＋DB 枚举迁移）**——设计见 `ai-config/notes/user-scripts-plan.md`；切片 A 已做（工具＋目录）。
2. **会话回收站（30 天）**：涉 chat_sessions 软删迁移＋与存储清理/孤儿检测交互；需设计后实施。
3. **修复账本**：需设计（未核实现状）。
4. **大屏双/三栏布局**：已核实为真缺口（`isExpandedWidth` 全库仅 MainActivity:549 一处），设计先行。
5. **CLI 引擎桥接一期（Claude Code）**：设计已定稿在上一份交接单第 12 条（含 UI 布局约束：引擎选择收编进模型选择弹窗、顶栏「智能体」按钮移除时机等），工程大。
6. **标注中心真融合、编队消息投递语义、UI 自动化一期（只读）、记忆图谱 v2（设计先行）**：均属战略大件。
7. 已核**已具备、无需再做**：消息排队（`_queuedRequests` 队列）、文件冲突检测（编辑器 loadedFingerprint 比对＋差异弹窗）。**大屏布局除外**。

## 五、环境状态

- 容器清理已执行（用户批准）：释放约 13.4GB（/root 25.0G → 11.6G）；保留了签名/备份 tar、ai-config、tags、v94/v95、mapping.txt。
- 构建：正式包 `assembleArmsoloRelease` 建议带 `--no-build-cache`（坏缓存机制见新记忆）；快验 `compileArmsoloReleaseKotlin`。
- 上游：`https://github.com/jieapi/AiCode`（记忆 upstream-repo-url）；本批 diff 结论=五个热点文件 0 行上游独有改动，无需合并。

## 五之二、验收后的 commit 拆分建议（供参考，按批次逻辑分组）

1. 既有未提交批次（上一会话产出，已过审＋编译）：商店优化/极黑主题/专家团 SOP/审查路由 AGENTS.md+optimizer.md/tools 两脚本/.gitignore/45·65 提示词/capabilities.md。
2. 能力下沉：assets/skills/code-intel/**、assets/aicode/lib/scenarios/{codeintel,localmodel}.sh、env-common.sh、assets/aicode/lib/tools/**、assets/providers.json。
3. 超长上下文四合一：StatefulAgentWorkflow.kt、assets/prompts/agent/compact-summary.md、CheckpointDao.kt/CheckpointManager.kt、对应测试。
4. 存储推荐清理：feature/settings/domain/{model,service}/StorageCleanup*、StorageCleanupRepository/ScanWorker、StorageViewModel/StorageSection、SessionUseCase.kt、AIAgentViewModel.kt 删除路径。
5. 商店安全层：feature/store/**、assets/store-scan-rules.json、SettingsScreen.kt 接线。
6. REA 合规层：ReaWorkbenchScreen.kt、GeneralSettingsRepository.kt。
7. 工具与新能力：tool/team/SummonTeamTool.kt、tool/script/**、AgentModule.kt、45-message-intent.md；首帧闪色：values/styles.xml、MainActivity.kt；构建：app/build.gradle.kts（versionCode 偏移）。

> 注意：`ai-config/notes/` 下的本批笔记、交付说明、P2 归档一并提交（属项目资产）。

## 六、下一步（用户验收后）

1. 用户验收 v95 → 按批次本地 commit（分批次提交，遵循 commit-msg 钩子；**绝不 push**）。
2. 从第四节的顺延项按用户拍板顺序入队；每批仍走审查路由（L2/L3 → qa/optimizer）＋正式编译前双审。
3. 若出第二包：先处理 versionCode（+2 或先 commit）。
