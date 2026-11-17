---
name: send-path-release-discipline
description: 用户强制纪律：交付包禁止再出现「消息不能再发送」问题；qa 发送链路五入口专项核查
---
# 发送链路出包纪律（2026-10-22 用户定，强制）

用户明确要求：编译交付的安装包**禁止再出现「消息不能再发送」类问题**——此前已多次发生（网络切换流式卡死、强制终止后发送无反应等）。

执行要求：
- 每个正式交付包构建前，qa 审查必须对发送链路做专项回归核查，五个入口逐一确认：
  1. 聊天页普通发送（ChatInputBar SendButton → send 链）
  2. 忙碌时排队发送（processNextInQueue）
  3. 会话暂停 / 强制终止后的发送（40e3000 修过：入队分支需防御性调度，拒绝时 Toast 反馈）
  4. 悬浮窗发送（FloatBallCommandCenter.send 桥）
  5. 外部注入（queueExternalText / queueExternalAttachments，如标注中心汇总、REA 发送）
- 核查点：任何守卫（暂停/命令占用/活跃 job/网络状态）不得把发送链路卡死成「静默无响应」；拒绝必须有可见反馈。
- 已知未修复项：网络切换后流式请求静默卡死（见 network-switch-stream-hang 记忆，根因待真机验证）——出包前如未修复，须在交付说明中如实标注。
- 规则已写入工作区 AGENTS.md「质量流程」节。