# DeepSeek Harness（dsh）架构对照笔记（2026-10-24）

> 源：deepseek-ai/deepseek-harness（TS/MIT，本地克隆 /tmp/dsh，子代理深研产出）。用途：喂给「架构重构 10 项」与增强计划目标一/三。

## 核心机制（证据在 dsh 仓库内路径）

1. **插件模型**：插件=实现 Service 的对象，声明稳定 `ctx.<key>`，依赖经 `inject` 自动等待；所有注册是 `ctx.effect()` 可逆 effect（卸载逆序回卷）；事件五模式 emit/waterfall/parallel/serial/bail，waterfall 即可短路/改写的 around 中间件（docs/cordis-primer.md）。
2. **权限三层**：工具事件瀑布 pre-execute(allow/deny/cancel/ask)→execute→post-execute；作用域 ToolRestriction 白黑名单；审批 seam 四态 `allowed-once/rejected/cancelled/unavailable`，**fail-closed**（无应答=拒绝），策略写入会话日志可重放+审计事件对（docs/subsystems/tools.md、approval.md）。
3. **会话 = append-only 事件日志**：「模型可见即已入日志」，历史由日志投影；JSONL 分代文件永不改名/删除，显式迁移链；崩溃尾部可检测修复（docs/architecture.md）。
4. **压缩崩溃安全**：compaction 的 start/summary/end 三事件 log-only，锁最后释放，中途崩溃成可检测的孤儿锁（docs/subsystems/compaction.md）。
5. **子代理**：SubagentProvider 统一本地/外部后端+能力协商+静态模型路由（agentRouteDefaults）；子代理=持久 Session+至多一个活动实例，支持运行中转向（steer）与中断；**父会话持有子代理持久 catalog，冷恢复可续聊**；实验性编队=roster+mailbox+任务 DAG（docs/subsystems/subagent.md、agent-team.md）。
6. **工具协议**：schema+canonical output(JSON Schema)+execute+finalizeContent+timeout+并发安全标记；**step-owned 准入**（每步按 when 条件收敛工具集）；超长输出 **spill=落盘+preview+locator**；UI 中立 card（presentCall/presentResult）（docs/subsystems/tools.md、packages/compaction/README.md）。
7. **worktree**：从 pinned commit 建隔离工作树，不复制未提交文件；**liveshare**：Relay 观看协议，版本+能力声明兼容（docs/subsystems/worktrees.md、packages/experimental/liveshare-protocol/）。

## 借鉴三档结论

### 可直接借鉴（排期优先）
1. **Spill 落盘机制** → 工具系统：超长输出「preview+路径+行号」按需读取（与现有工具输出日志机制对接）。
2. **压缩崩溃安全锁** → 会话 checkpoint：压缩过程三事件入日志+孤儿锁检测，补齐压缩一致性。
3. **子代理持久 catalog + 冷恢复续聊** → 子代理体系：qa/optimizer 报告持久化、可追问。
4. **审批 fail-closed 四态+审计对** → 权限引擎：呼应「静默放行」教训。

### 改造后借鉴
1. waterfall 拦截链 → Kotlin 工具执行责任链（不引入 IoC）。
2. step-owned 工具准入 → 按任务书声明每步允许工具（省 token+防误调）。
3. UI 中立 card → 工具渲染意图与 UI 解耦。
4. worktree 语义 → 子代理隔离纪律（快照基线、禁带未提交状态）。

### 不适用
profile/bundle 组合、liveshare Relay、外部子代理后端、PTC run_code。

## 结论
最值得搬三件：**spill 落盘、压缩崩溃安全锁、子代理持久 catalog**；Cordis 插件框架不整体引入，但 waterfall 拦截链与 fail-closed 审批词汇逐点翻译进 Kotlin 侧。
