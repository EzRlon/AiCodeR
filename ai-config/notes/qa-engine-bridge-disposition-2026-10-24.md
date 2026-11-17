# qa 处置台账：CLI 引擎桥接一期 + 底栏危险程度外框（2026-10-24）

> qa 审查结论：需修复后放行（P0×1、P1×6、P2×9）。以下为逐条处置。

## P0 / P1 处置（全部当批修复）

| 级别 | 发现 | 处置 |
|---|---|---|
| **P0-1** | stream-json 增量被当「全量累积文本」下发，消费端整体替换 → CLI 会话流式气泡闪烁错乱 | **已修**：bridge 内按回合维护 TurnAccumulator（text/reasoning 分 accumulation），delta 累积后下发全量；assistant 消息落库后重置 |
| P1-1 | `--resume` 重复添加两次（if/else 内 + 尾部） | **已修**：删尾部重复块 |
| P1-2 | 一条 user 消息含多个 tool_result 时只回传最后一个 | **已修**：parseToolResult 返回 List，逐个 emit；toolName 由 assistant 消息的 tool_use id 映射补齐 |
| P1-3 | CLI 工具无 ToolCallStarted：参数预览缺失、preparing 状态悬挂 | **已修**：assistant 消息到达时逐个 toolCall 补发 ToolCallStarted（带 id/name/args）；Finished 带真实 toolName |
| P1-4 | CLI 会话胶囊/勾选显示默认 provider 模型而非 CLI 模型 | **已修**：ModelIconButton 增加 cliModel 参数（AIChatPanel 从会话 model 取，engine=claude_code 时才生效）；胶囊与 CliModelPage 勾选均对齐会话 CLI 模型 |
| P1-5 | 「选中引擎自动滑到模型页」是死代码（选引擎即关 sheet） | **已修**：引擎选择不再关闭弹窗（滑页可见），仅模型选定/管理页跳转/手动关闭才收起 |
| P1-6 | 空闲看门狗可能杀掉长静默工具执行 | **已修**：stderr 心跳同样刷新活跃时间；超时上限 10min→30min |

## P2 处置

| 发现 | 处置 |
|---|---|
| P2-1 FIRST_LINE_TIMEOUT_MS 未用 | 已删 |
| P2-2 续接失败判定靠英文 stderr 关键字 | **记录不修**：resume=true 且无 result 时若命中关键字会转全新重试（已比纯 Failed 好）；改用退出码判定需真机核实 CLI 行为，记待办 |
| P2-3 resume 重试可能重复发事件 | **已修**：runTurn 记录 emittedAny，已发过事件则不再重试 |
| P2-4 权限桥回环端口无鉴权 | **已修**：一次性 token 随 env 下发脚本、请求行校验，不匹配丢弃 |
| P2-5 切引擎后残留内置模型名透传 --model | 记录不修：CLI 侧 sonnet 等名字与内置名共存时由用户在模型页显式选择；-model 无效时 CLI 报错可见 |
| P2-6 hasPendingApproval Eagerly | **已修**：改 WhileSubscribed(5_000)；全局（跨会话）语义保持（与既有 pendingRequest 单槽一致） |
| P2-7 死代码清理 | **已修**：ChatInputBar lerp/TodoStatus import、activeToolLabel 参数（含 AIChatPanel 调用点）、AgentEngines.BUILTIN 已删；DefaultModelsSection sheetState 经核对仍被外壳使用（非死代码） |
| P2-8 0.55px 描边高密度屏可见性 | 记录：随最终包真机验收确认，过细则当场调 |
| P2-9 SegmentedTabs 用 currentPage 拖动滞后 | 记录：体验 nit，下批 UI 打磨时改 targetPage |

## 通过项（qa 独立核实）

迁移 70 三方一致（DDL/Entity/70.json）、双建库路径、备份 DTO 兼容；权限桥 127.0.0.1 + 一行一 JSON + updatedInput 无注入面；TaskStateBorder 空闲零动画、组合条件正确、awaiting>busy 分级合理；能力登记双语齐。

## 待验证边界

- P0-1/P1-x 运行时行为需真机 + 容器内 claude 实跑（随最终包验收）。
- P2-8 描边可见性真机确认。
