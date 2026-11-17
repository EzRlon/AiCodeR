---
name: context-strategy-decision
description: 上下文策略定案（2026-10-18）：压缩优先、交接退化为兜底；暂停态可 /compress；继续后抑制再次自动交接
---
# 上下文策略定案（2026-10-18，用户委托按择优裁决）

目标（用户原话精神）：降低模型变笨概率、支持超长对话、极限提高缓存命中率。

## 已定案的策略（已实现）
1. **压缩优先**：自动压缩阈值默认 90%→**70%**；自动交接 75%→**93%**（退化为「压缩救不回」的兜底）。用户已存的设置值不受影响，仅默认值变化。
2. **压缩永远保留最新用户请求**：`CompactionText.selectTailStartIndex` 无条件把切点前移到最后一条用户消息（当前任务绝不能被摘要化，这是「变笨」主因）。
3. **摘要截断不再判失败**：`summarize` 去掉 `!result.isTruncated` 检查——截断的摘要远好于压缩整体失败。
4. **交接兜底携带现场**：`handoffToNewSession` 继承 goal/goalStatus/goalTurnCount/mode/teamName；marker/摘要行标记 `isCompactionMarker/isContextSummary`（渲染为压缩卡而非普通气泡）。
5. **暂停态放行 /compress**：唯一例外命令；`compactCurrentSession` 成功后自动解除暂停并抑制再次自动交接。
6. **「继续」后抑制**：`handoffContinuedSessions`（内存 set），占用降回提醒阈值（默认 50%）以下前不再自动交接。
7. **缓存稳定**：ToolRegistry.getAvailableTools 按名称排序（MCP reload 后顺序稳定）；SystemPromptProvider 日期改进程级 lazy 固定（跨天不击穿 system 缓存）。

## 明确暂缓（下一批候选，均因兼容/验证风险）
- Anthropic 第 4 断点（tool_result 块 cache_control）与 1h TTL：需真机实测网关兼容性，400 风险高。
- 交接新会话复制最近 tail 原文：persist 重建链复杂，下批做。
- OpenAI 请求时不再重排 tool 结果（落库规范化）：改动大。
- 模式提醒落库 / mode 进 system prompt：行为变化大，单独批。
- goal 回合强制 AUTO：权限风险，改为只修文案+resume 重置基线。

## 相关事实
- 75% 旧默认下「自动交接先于 90% 压缩触发」，交接只带 ≤4096 token 摘要且丢 goal/team/mode——调研结论：这是长会话「变笨」的主因（交接比重启会话损失更大，因为连最近 1/8 消息都没有）。