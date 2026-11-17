---
name: task-state-border-design
description: 底栏外框（TaskStateBorder）业务语义定稿：任务危险程度动态外框，禁止任务进度逻辑；粗细/颜色/动画规格
---
# 底栏外框 = 任务危险程度动态外框（用户 2026-10-24 拍板）

- 组件：`core/ui/TaskStateBorder.kt`（原 TaskProgressBorder 已删除）。
- **业务语义：只表达任务危险程度，禁止再做任务进度/待办逻辑**（用户原话：待办已有展示位，在外框上做进度纯多余；反复强调，禁止回退）。进度弧/节点/点击热点已全部移除。
- 规格规格（用户逐条反馈）：
  1. 描边极细：主弧 0.55px、底轨 0.35px（**像素直取**，原 1.6px 被批太粗要求至少减 2/3；不要用 dp 换算回去）；
  2. 颜色跟随消息发送按钮的状态色语义：执行中=Brand.Orange（发送按钮忙色）、等待审批=error 红；亮度不许过高；
  3. 全程动态动画：呼吸 alpha + 流动亮段绕框跑（AWAITING_APPROVAL 脉冲/流动更快：750ms/2600ms；RUNNING 慢：1900ms/6000ms）；
  4. 危险分级：AWAITING_APPROVAL（授权/提问/计划审批阻塞等待，最高危）> RUNNING；空闲不渲染。
- 数据链：AIAgentViewModel.hasPendingApproval（combine 三个审批管理器）→ AIChatPanel → ChatInputBar.awaitingApproval。
- 关联硬纪律：正式包禁止正常业务下发送按钮不可用（见 send-path-release-discipline 记忆），构建前做发送链五入口专项核查。