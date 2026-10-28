# 评审包（压缩版，2026-10-25）— 交更强模型审核

> 仓库：/root/workspace/AiCode（Android/Kotlin，Compose，PRoot 容器内编译）。本包自包含，不依赖外部文件即可评审；
> 若需逐行核对，关键文件路径见末尾。

## 1. 背景与目标（用户定，2026-10-24）
四目标：①agent/子代理更强更快更准更聪明，合理调用各家主流大模型；②编译/QA/最终产物更快更准更稳；③会话更懂用户、智能承载长期项目；④多供应商模型自动检测+智能分配（涉成本跃升或未配置供应商先问用户）。硬规矩：本地模型不集成；同题只留最优方案；不重做已完成项；**云端部署目前不考虑**（不做 GitHub Actions 云卸载）；批次 8 架构级大件本轮延期（模块化拆分/proot seccomp 补丁/云卸载，ROI 评估已存 notes/enhancement-program-2026-10-24.md 的 2.3 条目）。

## 2. 已完成且已裁决（勿重做/勿回退）
- gradle.properties：`kotlin.compiler.execution.strategy=daemon`（增量 7m45s→6m05s，-23%）；`android.enableJetifier=false`（同内容对照 1m22s：compile FROM-CACHE 25s + artifact transform 首跑 78s；实测字节级零改动）；`kotlin.build.report.output=file`（本环境实测不落盘，归因改用 --profile）。
- PRoot 实测基线：空跑 1m01s/1m13s（固定底价，含大量进程创建）；真实增量 6m05s（Kotlin 4m33 + KSP 1m27）→ 瓶颈=Kotlin/KSP 纯 CPU，非 IO。
- detekt 工具链 tools/detekt/（CLI 2.0.0-alpha.6 + detekt.yml + run-detekt.sh；jar gitignore 需华为云镜像重下）。裁决：只走**完整模式（补编译 classpath）+ 只扫改动文件 + 堆上限 1g + 只用控制台输出**（alpha 版 --report/--create-baseline 落盘有缺陷）；**不引入 Android Lint**（其强项是 Android 资源/清单；App 已关 lint/lintVital）。轻量模式会漏报 `!!` 空指针类（已用诱饵实测）。
- qa 定义收敛（.aicode/agents/qa.md + ai-config/agents/qa.md）：范围=diff hunk+触及文件+一跳调用邻居(ctags/readtags)；预算 ≤30 文件/8000 行；禁跑 Gradle、禁改文件、禁全量通读。
- 审查并行化（AGENTS.md + ai-config/notes/qa-discipline.md）：detekt 前置扫描先于派 qa 完成（不与构建重叠）→ qa 只读可与主线程编译并行；L3 可拆 2 个不重叠分片。
- 审查路由：L0 豁免；L1 主代理自查；L2 qa(BATCH)；L3 qa+optimizer（跨≥2模块则 FULL）。最终 R8 包前强制三步：①detekt 前置 ②qa 与编译并行 ③optimizer；报告逐条处置完才出包。每批收尾做性能/功耗五项自查。
- 外部评审闭环：DeepSeek 审 8 问 → 7 采纳 / 1 部分采纳 / 0 推翻（见 notes/review-disposition-2026-10-24.md）。据此已改：评测集降级为「烟测+回归护栏」（50-100 条不足以检出 5% 级提升，长期裁判改用运行时指标：用户纠正率/任务完成率）；压缩/缓存两条硬约束；路由表=配置数据可热更新；验收用本机实测每任务成本，不引用文献数字（98%/15-30% 属服务端场景）。

## 3. 待施工批次（一次只做一个批次，不夹带）
**批次 0（前置，最高优先）用户 UI 反馈：同日用户撤回反馈 1（顶栏补位）/反馈 2（引擎交互），仅保留：**
- F3 会话长按弹窗补「导入会话」：ChatDrawer.kt `SessionActionSheet`（现有 置顶/重命名/复制会话 id/导出/批量选择/删除）、ChatSessionPicker.kt（复用同菜单）、MainActivity.kt SAF（519-545 已有导出 create-document 流，需加 open-document）。底层已就绪：`BackupManager.exportSession` 产单会话 tar.gz（注释写明可由 import 还原），`BackupManager.import/prepareImport` 已支持还原 chat_sessions/agent_messages/todo → 主要做 UI 接线 + **ID 冲突语义**（同 ID=恢复 upsert？本地同标题=另存副本？）。
**批次 1 构建纪律+A/B#4**（2-3h）：tools/build-with-lock.sh（防双会话构建）、tools/rollback-build-tuning.sh（daemon/jetifier 一键回退）、构建耗时写入 notes/build-times.csv、A/B#4（assembleArmsoloRelease 不带 --no-build-cache 验坏缓存不复发）、daemon 3 轮均值复核（±10% 定案）。
**批次 2 Harness 借鉴第一批+派发链路**（3-5h，1.1/1.6/1.4）：子代理持久 catalog、step-owned 工具准入、任务书模板库、同范围审查结果复用。借鉴自 DeepSeek Harness(dsh)：直接借鉴=spill 落盘/压缩崩溃安全锁/持久 catalog/审批 fail-closed；改造后借鉴=waterfall→Kotlin 责任链、step-owned 工具准入；不引入 Cordis 框架/liveshare/外部子代理后端。
**批次 3 评测集+运行时指标+自检+意图理解**（6-10h，1.3/1.7/1.8/1.2）：评测集 50-100 条「用户原话→期望行为」作回归护栏；失败事件埋点（意图纠正/任务失败/编译失败）接已有 Vico Token 看板；产出自检三要素（影响范围+回退方式+风险提示）；意图理解三层制写进 45-message-intent.md（低模糊直接做/中模糊一句话复述/高模糊选项式澄清 2-4 项）。
**批次 4 消息装配合并施工**（5-8h，1.5+分档压缩+3.4）：append-only 布局（tools→system→messages 固定前置，动态只追加尾部）；压缩只在「追加新消息前」边界执行一次；切换模型不改写历史；压缩必须保留语义锚点（退出码/错误行/关键字段/失败原因）。
**批次 5 模型探测+预算感知+路由表**（5-8h，4.1/4.5/4.2）：复用 ModelApiService.fetchModels/testModel（现为设置页手动）静默探测→模型能力画像（可用性/延迟/成本档）；每轮显示预估成本/延迟；快/准开关；静态路由表（配置数据可热更新，过渡期用 qa/optimizer/Explore 三角色初版映射）+ 失败升级级联；否决训练型路由器与 LLM-as-Router。
**批次 6 会话能力**（10-18h，3.1→3.2→3.3）：跨会话任务账本产品化→画像自动归纳→项目知识库；UI 形态需用户验收（最大头）。
**批次 7 最终包脚本化自检**（2-4h，2.2）：体积差分/启动路径/keep 规则核对。
合计约 33-56h（不含批次 0），单会话连续 5-8 工作日；Kimi K3 最高思考主要收益在批次 3/4/5 返工减少（净省 10-20%）。

## 4. 约束与已知坑
- 不 push；未验收不 commit；另一会话活跃施工（提交至 713c8b1 标注/开发环境系列），其工作区改动不动；双会话/双批次并行构建同一变体会污染 KSP 缓存（症状：反复 from-cache 复现坏包）→ 停并行+删生成目录+--no-build-cache。
- 容器：PRoot（chroot/LXC 需 root、AVF/KVM 仅特定机型、迁 Termux bionic=重写）→ 保留；musl 补丁前缀必带：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew <任务>`。
- 工具限制：search(ripgrep) 不可用；BusyBox grep 无 --include/--exclude；Bash 遇命令替换/子 shell/绝对重定向被 SYSTEM_DENIED；PRoot 下 ps 取不到可靠 boot time（查 Gradle 用 /proc/*/cmdline + ~/.gradle/daemon/8.14/daemon-*.out.log）。
- A/B 必须用全新内容（否则命中历史缓存得假秒数）；Kotlin daemon 常驻内存需与 Gradle daemon 叠加内存核算（detekt 已限 1g）。
- 外部模型通道：Claude/GPT/Gemini/Grok/Qwen 需浏览器登录态，当前不可用；DeepSeek 可用。

## 5. 请重点回答的 7 个问题
1. F3 导入会话：ID 冲突语义（同 ID 恢复 upsert vs 始终生成新副本）哪个更符合用户心智？导入包的安全防护需哪些（路径穿越、超大包、恶意 metadata、重复导入幂等）？
2. 批次 1 构建锁：BusyBox/PRoot 下 flock vs mkdir 原子锁+stale PID 清理，哪个可靠？锁粒度到变体级还是仓库级？
3. 批次 4 的两条硬约束是否充分？cache-invalidating 事件如何低成本统计（已有 Token 统计）？压缩保留语义锚点会不会与「压缩优先」目标冲突？
4. 批次 5「低质量」信号如何可靠判定以触发升级级联？静态路由表的冷启动与热更新怎么做才不腐化？
5. 批次 3 评测集在「不依赖人工打分」前提下，最小可行评分口径是什么（自动/模型评分/规则评分各代价）？
6. 批次顺序与优先级是否合理？批次 6 是否应拆小或提前？在「更准/更聪明」目标上是否有被我漏掉的高 ROI 项？
7. 风险盲点：PRoot 下 Kotlin daemon 常驻的内存/功耗代价、Android 后台限制对 4.1 自动探测的影响、评审/路由对外部模型登录态的依赖——有无必须现在补的防护？

## 6. 输出要求
按「问题-严重度(P0/P1/P2)-依据-建议改法」列表；明确指出**我认为拍错的决策**；补我漏的高 ROI 项；最后给一份「若只做三件事」的排序。

## 7. 关键文件路径（若需逐行核对）
app/src/main/java/com/aicode/feature/agent/presentation/component/ChatHeaderComponents.kt（ChatHeader Row）
app/src/main/java/com/aicode/feature/agent/presentation/component/ChatModelSheet.kt（TabRow/Pager/EnginePage）
app/src/main/java/com/aicode/feature/agent/presentation/component/ChatInputBar.kt（sessionEngine）
app/src/main/java/com/aicode/feature/agent/presentation/component/ChatDrawer.kt（SessionActionSheet ~1918）
app/src/main/java/com/aicode/feature/agent/presentation/AIAgentViewModel.kt（engine 写入 ~2433/2458/2482、新会话默认 ~4183、exportSession ~4041）
app/src/main/java/com/aicode/MainActivity.kt（SAF 519-545）
app/src/main/java/com/aicode/feature/backup/domain/BackupManager.kt（exportSession/import/prepareImport/previewImport）
app/src/main/java/com/aicode/feature/agent/domain/engine/AgentEngines.kt
app/src/main/java/com/aicode/feature/settings/data/remote/ModelApiService.kt（fetchModels/testModel）
gradle.properties、tools/detekt/、AGENTS.md、ai-config/agents/qa.md
