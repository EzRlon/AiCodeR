# 交接单：夜间大施工批次（2026-10-22 深夜 → 2026-10-23 08:00 前完工）

> 新会话必读本文件全文后再动手。用户已明确授权：**开工后中途不再询问任何许可，直到交付最终安装包**。
> 时限：**2026-10-23 08:00 前完成全部工作**；完工后若仍有时间，执行第七节的存储扫描清单（只列不删）。

## 一、用户最终指令（逐条，不得遗漏）

1. 按「我的建议＋子代理协商结果」开工本轮全部反馈批次；
2. 中途**不问任何许可**，直到完工交付最终安装包；
3. **正式编译前必须走新审查路由**：qa 子代理＋optimizer 子代理与主 agent 配合（定义见
   `app/src/main/assets/agents/optimizer.md` 与路由体系），按新标准产出，**禁止交问题超多的交付物**；
4. 最终安装包发给用户（命名规范见第五节）；
5. **等用户测试后**才 `git commit`（本地提交），**GitHub 不推送**（不 push）；
6. 明早 8 点前全部完工且仍有时间 → 按本批新增的存储推荐清理设计，**扫描本项目/容器环境**的
   过期冗余数据，罗列「完全可安全删除」条目清单交用户批准，批准前**一个都不删**。

## 二、当前工作区状态（接手第一步先核实）

- `git -C ~/workspace/AiCode status`：**有一批未提交改动**（本会话完成的四批：商店优化 4 文件＋
  qa 修复、极黑主题 7 文件＋P2 修复、专家团 SOP 5 个提示词文件、审查路由 AGENTS.md＋optimizer.md、
  tools/repo-map.sh＋callchain.sh＋.gitignore、45/65 提示词、capabilities.md）——**保持原样勿回滚**，
  它们已全部过 qa 与编译，最终包必须包含它们；提交时机见第一节第 5 条。
- 未跟踪文件：`tags`（已在 .gitignore）、tools/*.sh（需 git add）。
- versionCode：上一正式包 v94（20261022），本批正式包应 bump（开工时查 `app/build.gradle.kts`
  确认当前值，建议 +1~+2）。

## 三、施工队列（10-23 用户拍板：全部并入主线，按最优规则排序；新会话开工时拉 2-3 个子代理过一遍即执行）

> 排序规则：①依赖先行 ②同链路合并（一次冲突调查/一次审查）③独立模块可子代理并行 ④风险集中时段。

### 第一梯队（低风险快赢，先行）
1. 能力下沉：代码智能四件套内置 App（assets/skills/code-intel ＋ scenarios/codeintel.sh ＋ tools 脚本释放＋能力感知两件套）。
2. REA 工作台合规层＋界面重构（知情告知书＋警告横幅＋三区重排＋SAF 文件选择＋样本删除）。
3. 九段式压缩摘要（纯提示词：compact-summary.md 补用户原话逐字段/当前工作/带引用下一步）。
4. 存储推荐清理（推荐引擎三级判定＋孤儿检测＋PROTECTED_PATHS 白名单＋每周 WorkManager 只扫不删＋只读运行健康卡；前置：SessionUseCase 删会话递归子代理修复）。
5. 首帧闪色修复（极小顺手项：values-night/styles.xml:13 固定深蓝底改动态取当前主题背景）。
6. 本地模型低成本接入（用户 10-23 拍板纳入）：容器 llama.cpp/Ollama 场景脚本＋provider 预设（离线兜底/小任务；不嵌 MNN）。

### 第二梯队（核心链路「工具结果管道改造块」＋安全层，合并为一个冲突调查块）
7. **冲突调查（一次做完，结论向用户汇报）**：微压缩/落盘/熔断/工作集恢复与既有链路
   （compactIfNeeded 阈值链、checkpoint/恢复、网络切换流式卡死〔记忆 network-switch-stream-hang〕、recall）交叉面逐项核清。
8. **微压缩（P1）＋超大输出落盘（P4）＋压缩后工作集恢复合并实现**（用户 10-23 拍板纳入 P4 剩余）：
   同在「工具结果进上下文」管道——内存投影保留最近 5 条工具结果＋旧项占位符；超阈值输出存文件留引用行；
   压缩完成后重读最近编辑的至多 5 个文件（≤50K 预算）。一次改完一次审。
9. **压缩失败熔断（P3）**：连续 3 次失败暂停 N 轮（小，同块收尾）。
10. **商店安全层批次一**：InstallScanService（PASS/WARN/BLOCK、纯本地规则、特征库 assets JSON）＋警示 UI——独立模块，可子代理并行。
11. **summon_team 自动召唤工具（用户 10-23 拍板纳入）**：新工具 domain/tool/summon-team——识别项目级诉求后免手动直接绑团；会话 teamName 绑定＋能力感知登记（与 45-message-intent 意图识别衔接：从「建议召唤」升级为「可直接执行召唤」）。

### 第三梯队（战略大件；08:00 前完不成则顺延，不赶工降质）
12. **CLI 引擎桥接一期（战略级，设计已定稿）**：把 Claude Code / OpenCode / Codex 三个 CLI agent
    的交互流桥接成本体会话形态（同一聊天 UI，会话级引擎切换入口）。
    - **一期只做 Claude Code**：headless stream-json 双向流→翻译成本体 AgentEvent；权限请求桥接到
      授权中心/通知栏；历史一期走「流式显示＋CLI 侧续接」（~/.claude session），不迁库。
    - 二期 OpenCode（server 架构现成）；三期 Codex。UI 须明示该会话部分本体能力（专家团/标注/
      记忆/recall）不可用。适配层落点建议 `feature/agent/domain/engine/`（新包）。
    - 入口布局约束（用户 10-23 拍板）：悬浮球底栏五按钮已满员，一个位不加——引擎选择收编进现有
      「模型选择弹窗」内部；交互定稿：弹窗顶部「引擎/模型」两段式分段按钮＋HorizontalPager 左右滑
      双通道（滑是快捷方式，分段按钮是显式入口）；选中 CLI 引擎后自动滑到第二页接选可续接会话；
      底栏按钮文案变「引擎·模型」缩写（如 CC·sonnet）；主界面输入栏模型胶囊同改；设置默认模型页
      加「默认引擎」；会话列表加引擎角标。悬浮球与主界面共用同一弹窗。
    - 顶栏「智能体」按钮移除（桥接一期上线后执行）：位置 ChatHeaderComponents.kt:164-173（顺序：
      专家团、智能体、记忆图谱、Git、终端）。功能去向：①未安装的引擎卡直接带「安装」按钮；
      ②已安装卡次级菜单（长按或 ⋯）保留「打开管理页/终端」；移除后顶栏剩四钮；管理页本体保留。
    - 子代理协商重点评估：stream-json 协议细节、权限流翻译、keepalive/崩溃恢复。
13. **脚本系统一期**（`ai-config/notes/user-scripts-plan.md` 一期：脚本页＋run_script 工具＋定时任务「运行脚本」动作）。
14. **标注中心真融合**（原探索子代理已随上会话终止，结论未产出——先重派 Explore；判据：统一画布
    引擎/状态机自动流转/复验闭环，分期实施）。
15. **竞品吸收小项**：会话回收站（30 天）、会话 zip 导出导入、修复账本、消息排队核实、
    文件冲突检测（编辑器保存前检测 AI 已改盘）、大屏双/三栏布局现状核实（用户 10-23 拍板纳入）。
16. **编队消息投递语义（用户 10-23 拍板纳入，CCCC 思想吸收）**：团队派活加送达/已读/必须回复追踪，
    防长任务丢消息漏汇报；落点 feature/agent/domain/subagent/team 消息链。
17. **UI 自动化一期·只读（用户 10-23 拍板纳入，战略缺口）**：无障碍＋Shizuku 驱动读屏/找元素/
    描述界面（配合标注中心做被开发 App 的自动验收）；二期再做执行（点击/输入）。风险中等，
    ROM 兼容需真机实测；一期交付前必须有权限边界说明。
18. **记忆图谱 v2（用户 10-23 反馈：现版粗糙，对标 Operit 记忆库；设计先行，最重）**：
    ①多记忆空间（按项目隔离）；②从会话/附件自动抽取实体与关系（替代手动建节点）；③节点关系
    可视化编辑；④时间/语义/关系混合检索（语义层在设备端的务实实现待子代理协商——不上云、
    不依赖大模型 embedding，可先做「关键词＋时间＋图谱游走」三路混合）。基线：现 v86 泳道版
    保留为可视化层，v2 重在其下数据与检索层。

## 三之附、原未拍板项处置记录（用户 10-23 终局指令：全部按当时最优解纳入主线）

上述 5/6/8/11/15/16/17/18 项即原未拍板项的落位；**明确维持不做**（当时最优解=不做，记录理由备查）：
语义/向量记忆检索的云端方案（设备端务实版并入 18）、角色卡体系、Tasker 集成（已有定时任务）、
消息分支（非刚需，末位候选）、更换 PRoot 路线（cccodroid 的同长前缀+seccomp 方案不采用）、
自建插件体系/自建托管市场（MCP＋技能＋商店已覆盖）。

## 四、流程纪律（全部强制）

- 审查路由：L0 豁免/L1 自查/L2 qa/L3 qa＋optimizer；本批多数为 L2-L3。**最终正式编译前：
  qa＋optimizer 双审，报告逐条处置后才准构建**（用户点名要求，勿跳）。
- 正式包：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk
  LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew :app:assembleArmsoloRelease`；
  快验 `:app:compileArmsoloReleaseKotlin`。构建失败查 `ai-config/RESTORE.md` 已知坑表。
- 性能功耗纪律（五项清单）＋发送链路五入口专项核查（交付包禁止「消息不能发送」）＋
  双语 token 检查＋R8 keep/反射面逐项过。
- 新功能带能力感知两件套（capabilities.md＋65-capabilities.md）。
- 心跳汇报：约每 15 分钟一句进展（关键节点即时报）。
- 主线程 Gradle 运行时绝不派发 qa/optimizer；qa/optimizer 只报告不改码。
- editFile NO_MATCH 先 readFile 核对；busybox grep 无 -P/--include，用 awk/find。
- 新功能若涉核心链路改动，冲突调查结论写进交付说明。

## 五、交付物

- 正式包命名：`~/workspace/许愿码-正式版-20261023-vCode<NN>-夜间批次-<亮点>.apk`
  （从 `app/build/outputs/apk/armsolo/release/` 取，注意 vCode 值）。
- 交付说明：批次清单＋每批验证状态＋冲突调查结论＋真机验收要点（精简，问题不留给用户）。

## 六、Git（严格按用户指令）

- 用户测试确认前**不 commit**；测试确认后本地 commit（可按批次拆分，遵循仓库 commit-msg 钩子规范），
  **绝不 push**。

## 七、08:00 前完工且有余力时：环境清理扫描（只列不删）

- 扫描范围：`~/workspace`（重点：约 20 个历史 APK、两个 20261017 备份 tar、/tmp 残留、
  容器内 pip/npm/apt 缓存、旧日志）＋ App 数据目录不在容器侧则说明。
- 产出：推荐清理清单（条目/大小/为什么安全），**列给用户批准，批准前不删任何东西**；
  白名单：签名密钥、ai-config、backup tar 中的 jks、当前最新 APK、tags 索引。
- 执行方式：容器侧 Bash 手工扫描即可（存储推荐清理功能是 App 内产品能力，与环境清理是两回事）。

## 十、上游更新（用户 10-23 已提供官方仓库，永久生效不再询问）

- **官方上游：https://github.com/jieapi/AiCode**（存入长期记忆 upstream-repo-url）。
- 执行时机：若夜间施工有余力，或正式编译前发现与压缩/商店/工作流相关的官方新改动需求，
  则先做快照 diff 分析再定合并；**不阻塞主线**。
- 合并方法与坑（历史）：官方快照为孤立根，用 git commit-tree 重挂（锚点 539c9225）；
  迁移号冲突（官方 60/61，我方 62/63+，开工前核对 assets/migrations 最大号）；
  热点人工合并文件：MainActivity/StatefulAgentWorkflow/SystemPromptProvider；
  网络走 gh-proxy 镜像或加速器。
- 旧表述作废：「等用户提供材料」——现已可直接 fetch。

## 八、长期记忆指针（新会话自动注入，详情按需读取）

context-pipeline-batch-plan（四合一＋冲突调查纪律）、store-security-plan、user-scripts-plan、
storage-cleanup-plan、capability-productization、ctags-code-index（四件套用法与坑）、
acceptance-and-upstream-rules、build-mergejavares-tmpdir-pitfall、final-build-optimization-discipline、
send-path-release-discipline、heartbeat/UI 对齐等既有纪律。
