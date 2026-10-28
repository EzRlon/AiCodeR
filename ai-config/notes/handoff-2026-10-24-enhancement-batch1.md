# 交接单（2026-10-24 增强计划第一批）— 新会话开工必读

> 目标读者：接手的新会话。**读完本文即可直接开工**，不必重新调研、不得重做已完成项。

## 〇、一句话状态

调研与计划阶段已全部完成（含外部 DeepSeek 评审闭环），**施工尚未开始**；用户已授权立即开工，第一批 = 「构建纪律 + 正式包缓存验证」。

## 一、开工第一步（按序执行）

```bash
# 1) 读计划与处置（必读，跳过=重做风险）
cat ai-config/notes/enhancement-program-2026-10-24.md      # 主计划（含续作纪律、裁决、排期）
cat ai-config/notes/review-disposition-2026-10-24.md         # 外部评审逐条处置
cat ai-config/notes/build-speed-baseline.md                  # 编译 A/B 实测数据

# 2) 对齐另一会话进度（他可能已提交/正在构建）
git -C . log --oneline -15 && git -C . status --short

# 3) 确认没有别的构建在跑（双会话并行=KSP 缓存污染，已验证坑）
pgrep -af gradle || echo "无构建在跑"
# 交接时实测：另一会话仍活跃（最新提交 713c8b1 一键安装/开发环境/标注系列），
# 随时可能发起构建 → 开工后第一步先建锁脚本，之后所有构建走锁。
```

## 二、已完成（禁止重做，只验收）

- 编译提速三件套已入库：Kotlin `daemon` 策略（增量实测 +23%）、`android.enableJetifier=false`（实测字节级零改动）、`kotlin.build.report.output=file`
- detekt 前置扫描工具（`tools/detekt/`：CLI 2.0.0-alpha.6 + detekt.yml + run-detekt.sh；jar 已 gitignore，需重新下载见 §六）
- qa 定义收敛（`.aicode/agents/qa.md`：diff+一跳邻居、上下文预算 ≤30 文件/8000 行；副本 `ai-config/agents/qa.md` 已同步）
- 审查并行化（AGENTS.md 审查路由 + qa-discipline.md：qa 只读可与构建并行、L3 拆 2 分片）
- Harness 深研报告（`notes/deepseek-harness-borrowing-2026-10-24.md`）、外部评审处置（`notes/review-disposition-2026-10-24.md`）

## 三、本轮待施工（按批，一批一提交，不夹带）

**批次 0（前置，最高优先）：用户 UI 反馈（2026-10-25 录入；同日用户撤回反馈 1 顶栏补位与反馈 2 引擎交互，仅保留反馈 3；详情见主计划「批次 0」节）**
- 反馈 3｜会话长按弹窗补「导入会话」→ `ChatDrawer.kt` `SessionActionSheet` + `ChatSessionPicker.kt` + `MainActivity.kt` SAF；底层 `BackupManager.import` 已能还原单会话包（exportSession 产物），主要做 UI 接线与 **ID 冲突语义**

**批次 1（当前批）：构建纪律 + 正式包缓存验证**
1. `tools/build-with-lock.sh`：锁文件保证同一时间仅一次构建（另一会话在构建时直接拒绝并提示等待）
2. `tools/rollback-build-tuning.sh`：一键回退 daemon/in-process、jetifier 开/关（配置类优化可秒退）
3. 构建耗时曲线：每次构建耗时写入 `ai-config/notes/build-times.csv`（解析 Gradle profile/构建报告）
4. A/B#4：`assembleArmsoloRelease` **不带** `--no-build-cache` 跑一次，验证坏缓存不复发；复发则维持 `--no-build-cache` 并记录结论
5. daemon 策略 3 轮均值复核（新增内容防缓存命中，方差 ±10% 内方可定案）

**批次 2：Harness 借鉴第一批 + 子代理派发链路（1.1/1.6/1.4）**——子代理持久 catalog、step-owned 工具准入、任务书模板库、1.4 同范围审查结果复用
**批次 3：评测集 + 运行时指标 + 自检 + 意图理解（1.3/1.7/1.8/1.2）**——1.2 意图理解三层制写进 45-message-intent.md，与评测集同批改提示词口径
**批次 4：消息装配合并施工（1.5 + 分档压缩 + 3.4）**——append-only 布局、缓存友好、压缩保真规则（同一段代码一次改完）
**批次 5：模型探测 + 预算感知 + 路由表（4.1/4.5/4.2）**——4.2 初版映射用现有三角色（qa/optimizer/Explore）
**批次 6：会话能力（3.1 任务账本 → 3.2 画像归纳 → 3.3 项目知识库）**
**批次 7：最终包脚本化自检（2.2）**
**批次 8（本轮延期，用户 2026-10-25 裁决：先做批次 1-7；云端部署目前不考虑）**：架构级大件（2.3 模块化拆分 / proot 性能补丁 / 重活云卸载）——三项 ROI 评估已存主计划 2.3 条目（云卸载本轮不启用、proot 补丁降级为实验、模块化拆分建议不做），本轮不动。**因不走云端，正式包仍在本机构建，批次 1 的本地编译提速是本轮唯一抓手。**

## 三点五、工期基线（2026-10-25 估算，供节奏参考，非承诺）

| 批次 | 预估 | 主要耗时来源 |
|---|---|---|
| 1 | 2–3h | 脚本 1h；正式包 25min；daemon 3 轮均值 20min |
| 2 | 3–5h | catalog 持久化 + 工具准入要动真代码 |
| 3 | 6–10h | 评测样本本身 3–4h；看板要接 Compose |
| 4 | 5–8h | 核心链路，改后重测压缩/缓存行为 |
| 5 | 5–8h | 探测可靠性 + 预算估算口径要试 |
| 6 | 10–18h | 唯一带完整 UI+DB+跨会话的产品级项，最大头 |
| 7 | 2–4h | apkanalyzer 口径要与真机对齐 |
| **1–7 合计** | **约 33–56h** | 单会话连续 ≈ 5–8 个工作日 |

要点：①编译/审查等待占大部分，不随模型变快；②Kimi K3 最高思考主要收益在批次 3/4/5 的返工减少（净省 10–20%）；③批次 6 的 UI 形态需用户验收，等待时间是工期变量；④批次 1–7 完成后，若用户同意，可并行两会话（批次 3/5 与 1/4/6 文件不重叠）压缩到 2.5–4 个工作日。

## 四、纪律红线（硬性）

1. **只做一个批次**，不夹带其他批次的改动；
2. **不 push**；**未获用户验收不 commit**（历史约定：验收通过后按批次本地提交）；
3. 审查路由照旧：L2+ 派 qa（BATCH）、最终 R8 包前走「detekt 前置 → qa（与编译并行）→ optimizer」三步；qa 只读、禁自跑 Gradle；
4. **同题只留最优方案**：实施中发现与计划冲突的备选，不并列执行，回到计划里的裁决；
5. 每批收尾跑性能功耗五项自查；改动收尾时更新 `build-speed-baseline.md` 与主计划的「已完成」清单。

## 五、已知坑（勿踩）

- **另一会话并发**：交接时另一会话仍在活跃施工（标注/开发环境系列）。构建必须走批次 1 新建的锁脚本；代码改动范围互不重叠。
- **KSP 缓存污染**：两个会话/两个批次并行跑同一变体会把 KSP 产物写成坏快照（症状：反复 from-cache 复现坏包）→ 修法=停并行+删生成目录+`--no-build-cache` 重跑。用 §一 的锁脚本防它。
- **build-mergejavares**：容器内 AGP mergeJavaRes 有 PRoot 临时目录删除被拒的历史坑（现由 musl 补丁规避），再遇对照 `ai-config/RESTORE.md` 已知坑表。
- **detekt 2.0 alpha**：① `--report/--create-baseline` 落盘有缺陷 → 只用控制台输出；②轻量模式漏报 `!!` 空指针类 bug → 需补 classpath 走完整模式；③堆上限设 1g 防 OOM；④**不引入 Android Lint**（已裁决）。
- **缓存假优化**：A/B 必须用**全新内容**（否则 compile 命中历史缓存，测出假秒数）。
- **另一会话在活跃施工**：标注/开发环境一批（提交 a719d6d→713c8b1 等）。其工作区改动（如 `ai-config/notes/devenv-one-tap-disposition-2026-10-24.md`）**不是我们的，不要动、不要回滚**；我们只改 `tools/`、本轮计划/交接笔记、以及明确列入本批次的文件。

## 六、环境参数

```bash
# 编译（musl 容器必须带补丁前缀）
cd ~/workspace/AiCode
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk \
LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew <任务>
# 快验：:app:compileArmsoloReleaseKotlin    正式包：assembleArmsoloRelease
# detekt jar（华为云镜像；腾讯/直连 Maven 无此件）
wget https://repo.huaweicloud.com/repository/maven/dev/detekt/detekt-cli/2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6-all.jar -O tools/detekt/detekt-cli.jar
```

## 七、相关文件

主计划 `ai-config/notes/enhancement-program-2026-10-24.md`｜评审处置 `ai-config/notes/review-disposition-2026-10-24.md`｜编译数据 `ai-config/notes/build-speed-baseline.md`｜Harness 借鉴 `ai-config/notes/deepseek-harness-borrowing-2026-10-24.md`｜qa 纪律 `ai-config/notes/qa-discipline.md`｜审查路由 `AGENTS.md`｜用户镜像包 `notes/build-master-plan.md`、`notes/pre-release-audit-2026-10-20.md`
