# 引擎管理统一重构 + 三引擎接入 App 供应商模型（2026-10-26）qa 处置台账

批次提交：22bb81f（21 文件）；qa 复审后修复以 fix 提交追加。versionCode 143（计数 142+1）。

## P1（当批已修）
- qa-P1-1 Claude/Codex 供应商模型列表未排除 useFullUrl（选中必 401 且预检误报就绪）→ 已修：ChatModelSheet.kt matchingProviders 三分支统一加 `!it.useFullUrl`。
- qa-P1-2 CliModelPage 不可滚动，模型多时底部不可达 → 已修：heightIn(min=360,max=420)+verticalScroll（与内置页同口径）。

## P2（当批顺手修）
- qa-P2-1 跨引擎切换残留旧 (providerId,model) → 已修：ChatSessionDao.updateEngine 同时清 providerId/model；AIAgentViewModel.setSessionEngine 加同值短路（重复点卡不清模型）。
- qa-P2-2 设置页未安装引擎可点选 → 已修：未安装禁选（对齐旧引擎页门禁，卡片就地有安装按钮）。
- qa-P2-3 CLI 引擎卡无选中视觉态 → 已修：trailing 补 Check（defaultEngine 命中时）。
- qa-P2-5 切引擎被拒（会话运行中）无反馈 → 已修：SettingsScreen Toast engine_switch_busy。

## P2（记录待排期，不当批修）
- qa-P2-4 OpenCode 注入段 baseURL 剥离 `/v1`，对「要求 /v1 前缀」的强依赖型反代会 404：需真机用真实反代回归；若有案例再考虑不剥 /v1 或加开关。
- 其余无。

## optimizer（2026-10-26）处置
- 总评中风险，无 P0。已核无风险：VM 生命周期/无泄漏、桥每回合 getAllProviders 频率与线程、OpenCode configContent IO 线程纪律、架构与屎山（净删 430 行）。
- opt-P1 detectVersion 无缓存，设置页反复进出反复拉起 3 个容器进程 → **当批修**：CliAgentManager 加 5 分钟 TTL 版本缓存（对齐桥层 isAvailable 口径）；安装结束/标签手关的复检传 forceRefresh=true 绕过缓存。
- opt-P2 rows 每次重组重建 → **当批修**：remember(engine, providers, builtinLabel)（stringResource 移出 lambda）。
- opt-P2 同值短路的未加载边界（首帧误短路）→ **当批修**：currentSessionState 为 null 时不短路。
- opt-P2 CliEngineRows 整段 recompose（3 行规模）→ 记录不改；updateEngine 清字段与 error Toast 时序已核无风险。

## 已核无误（qa 确认）
CliModelRef UI/桥同源一致；OpenCode 注入降级路径；AgentCliViewModel 预检/复检/收尾与标签手关兜底；发送链路五入口 runTurn 调用点均传 providerId；onboarding 无引擎页悬挂引用；strings 无残留死键。
