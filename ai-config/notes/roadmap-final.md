---
name: roadmap-final
description: AiCode 增强版总路线图（2026-10-11 定稿）：A 智能体宿主/B 体验基建/C 三层测试体系/D 智能体编队/E 运营增强/F 暂缓池，含批次与设计哲学
---
# AiCode 增强版路线图（2026-10-11 定稿；2026-10-12 补 D3 专家团与 G 记忆图谱）

愿景：AiCode 从「AI 编程工具」升级为「手机上的完整 AI 研发工作台」。全部在现有架构内实现（Kotlin+Compose 本体 + PRoot 容器），不引入云端基建，不申请额外系统权限。

## 六大工程包
**A 智能体宿主**（实测已通，成本低-中）：A1 一键安装器（Claude Code 2.1.296/Codex 0.162.1/OpenCode 1.19.0 已在容器实测跑通，npm 一条命令 ~50s）；A2 一键启动（工具页入口+终端带起）；A3 登录引导；A4 2026-10 模型预设（Kimi K3/GLM-5.3/DeepSeek V4.1/Qwen3.8/Claude Opus 5.5/GPT-6 → providers 预设，点选+贴Key）
**B 体验基建**：B1 对话信息分层改造（过程折叠一行卡/diff卡/思考折叠/长输出拦截/进度可视化/代码块精修，成本中）；B2 语音输入（低）；B3 通知栏批准（中）；B4 系统分享集成进/出（低）
**C 测试验证三层体系**：C1 Web 预览容器（容器 HTTP+本体 WebView 热更新，成本中）；C2 截图标注账本（四种类型 bug/要改/不要/优化 + Room 账本状态机 待确认→已发AI→修复中→待复验→关闭 + 汇总注入会话，成本中）；C3 沙箱测试空间（BlackBox 应用级虚拟化嵌入——AiCode targetSdk 28 与 BlackBox 官方兼容性建议吻合，GPL 兼容，APK 免安装运行+列表管理+编译联动，成本高且**必须先 PoC 验证 Android 16 实机**再产品化）；C4 真机安装闭环（manifest 已有 REQUEST_INSTALL_PACKAGES，一键唤起系统安装器+测试包列表，成本低）
**D 协作智能体编队**（纯配置立刻生效）：D1 角色子代理库 .aicode/agents/（产品/架构/前端/后端/质检五角色，已随 assets/agents 落地，含内置 Explore）；D2 派活纪律写进 AGENTS.md（Claude Code 四支柱：子代理上下文从零+只回传结论摘要+工具白名单+新鲜视角评审；派活四要素=目标/已知信息/边界/期望产出）；D3 专家团·编队（2026-10-12 并入，对标 WorkBuddy「专家团」：teams/<name>.md 定义=团长提示词+成员名单，会话级一键召唤，团长由主代理充当——子代理不可嵌套；复用现成 task 派发引擎。落点：编队存储层 TeamDefinition/Parser/Source/Repository（镜像子代理栈 feature/agent/domain/subagent）+ 团长片段 assets/prompts/agent/team-lead.md + 会话字段 teamName 与迁移 63_add_session_team.sql（2026-10-13 重编号后：官方 60/61、我方 62/63） + 聊天区召唤入口 TeamPickerSheet + 设置页编队管理 + 预置编队 assets/teams）；D4 编队看板 UI（远期）
**E 运营增强**（P1）：E1 产物交付视图；E2 Hooks；E3 自动记忆+管理页；E4 目标托管（双模型验收+token 预算）；E5 定时任务
**F 暂缓池**：用量仪表盘、多会话看板（依赖 D4/E4）、Web/IM 遥控、Android 系统自动化、离线小模型；明确不做：云端托管、企业后台
**G 记忆图谱（2026-10-12 评估，分期，P1 起）**：对标 WenzFlow「AI 记忆图谱」——任务/会话/项目模块挂成一张图，每轮对话长一个节点、点节点切会话、2D/3D/思维导图、悬停看最近 5 条、模块节点信息与目录浏览、AI 扫描模块、冲突提示；每轮成功对话用指定模型自动抽取/更新记忆（默认关，额外 token）。可行性=可做，底座已在（Room v59 会话/消息/checkpoint + Auto Memory 文件式记忆 + TitleModel 配置范式 + WebView + Compose，无需新权限/云）。分期：P1 数据层(Room 新表 memory_nodes/memory_edges + 迁移 61)+2D 图谱+每轮抽取+点跳会话；P2 3D(WebView+3d-force-graph)+思维导图+模块扫描/信息；P3 冲突提示+全部重划分+权限门。开源可借：adoresever/graph-memory(对话抽三元组+PPR 召回,MIT)、modelcontextprotocol/servers src/memory(entity/relation/observation 图谱模型)、3d-force-graph(3D,MIT)、hilyfux/knowledge-graph(git-native 模块记忆)。与 E3(自动记忆+管理页)/F(多会话看板) 重叠，建议并入 E3。

## 执行批次
1. 快赢：A1-A4 + D1-D2 + B2 + B4
2. B1 + C2 + B3
3. C1 + C4 + E1 + E2
4. C3 PoC→产品化 + E3-E5 + F 评估
5. D3 专家团/编队 + G 记忆图谱（分期，先 P1：数据层+2D+抽取+点跳会话）

## 设计哲学（定稿原则）
1. 每功能必答「不装会痛吗」；2. 一屏一主任务；3. 过程折叠结果突出；4. 视觉全走主题 token；5. 软件内闭环优先（复用容器，不引入额外权限/外部依赖）；6. 实验性能力（沙箱）先 PoC 后产品化并标注实验标签

## 验收标准（每包）
冒烟编译 :app:assembleUniversalDebug（必须带 LD_PRELOAD=/root/tmp/fix_remove.so）→ qa 子代理审查 → 真机验收清单 → 双语 token 检查

## 迁移编号（DB 版本）
- **（2026-10-13 重编号）**：官方合并占用 60/61（checkpoint 指纹/连接排序）；我方 annotations=62、session_team=63；`SCHEMA_VERSION=63`。
- D3 专家团、G 记忆图谱等后续功能的 Room 迁移**从 64 起排**；开工前先核对 `app/src/main/assets/migrations` 当前最大号与本登记，避免与其它会话撞号。
- 编号约定：每条迁移一个 `NN_描述.sql`（放 assets/migrations），同时 `AgentDatabase.SCHEMA_VERSION` +1 并生成对应 `app/schemas/…/NN.json`。