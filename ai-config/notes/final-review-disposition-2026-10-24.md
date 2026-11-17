# 发版前双审总台账（FULL · versionCode 117 · 2026-10-24）

> 审查对象：本会话全部未提交改动（回收站/返回键守护/修复账本/引擎桥接/危险外框/记忆图谱 v2/编队投递/fixledger 回写）。
> 两份报告：qa FULL（含 R8 与发送链五入口）、optimizer FULL（性能/功耗/架构/屎山）。均已逐条处置。

## qa FULL（结论：有条件可构建 → P1×3 已修）

| 级别 | 发现 | 处置 |
|---|---|---|
| P1-1 | PermissionBridgeServer 单 token：并发 CLI 会话互踩，先启动者权限请求被静默拒 | **已修**：expectedTokens 集合 + ensureStarted 收集 + 回合结束 removeToken |
| P1-2 | fixledger 自动补建分支漏推进（账本停 SENT、标注已前进，两侧失衡） | **已修**：补建后立即推进到 target |
| P1-3 | 投递超时回调单槽：定时任务 VM 覆盖注册 + onCleared 置 null → 主界面提醒失效 | **已修**：改 CopyOnWriteArrayList 多监听器，各 VM 按引用注册/注销 |
| P2 | 图谱同步无清理通道、搜索无防抖乱序回写、超时文案硬编码中文、openDetail 注释并行 | 防抖+乱序已随 optimizer 处置（250ms 防抖+Job 取消）、文案已本地化、注释已修；孤儿节点清理**记录待排期** |

**通过项**：发送链五入口全部走通（a 主输入栏/b 悬浮球/c 通知栏/d 外部注入/e 排队恢复——各入口「不可用」仅剩合理业务：暂停态可见 Toast+草稿保留）；R8 通过（@Serializable keep 覆盖新字段、实体 keep 覆盖新表、桥接脚本为 Kotlin 常量运行时写盘无打包问题、资源收缩安全）；跨批次交叉回归无破坏（onboarding 强开链路、引擎分流与 goal/子代理、迁移 68→71 逐列一致）。

## optimizer FULL（结论：中风险 → P0×1 已修）

| 级别 | 发现 | 处置 |
|---|---|---|
| P0 | 图谱进页同步逐会话全量读消息（大历史内存尖峰+卡顿，双触发翻倍） | **已修**：新增 memoryToolCallsByWorkspace 单条 SQL 聚合（JOIN chat_sessions 限工作区）、节点 id 复用省一半查询、chip 双触发去掉一次 |
| #2 | 图谱搜索每键击 IO 查询风暴 | **已修**：250ms 防抖 + Job 取消 |
| #3 | 节点列表逐项线性扫全边表 | **已修**：remember(edges) 预计算计数 Map |
| #6 | 每次开模型弹窗拉容器进程跑版本检测 | **已修**：isAvailable 5 分钟 TTL 缓存 |
| #7 | runCatching 静默吞错不可观测 | **已修**（图谱链路）：同步/检索失败 FileLogger.w/e 留痕 |
| #11 | onExpired 单槽身份比对不严谨 | 已随 qa P1-3 改多监听器（按引用注销） |
| #4/#5/#8/#9/#10/#12 | ServerSocket 常驻监听（低-中）、domain→presentation 存量违例（非本批）、fixledger→annotation 单向耦合（合理）、sweep get-check-set 非原子（双扫描无害）、Canvas 每帧小分配（微） | **记录待排期**，不阻塞发版 |
| 屎山 | AIAgentViewModel 4048 行（本批 +156），建议下批拆「引擎分流」「投递超时处理」 | **记录待排期** |

## 双审后验证

- `:app:compileArmsoloReleaseKotlin`：处置后 BUILD SUCCESSFUL（2026-10-24）。
- 全量单测：双审处置后执行（见下批汇报）。
- 真机项随交付验收：迁移 68→71、重投时序、0.55px 外框可见性、CLI 流式表现。
