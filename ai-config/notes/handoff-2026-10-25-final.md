# 交接（2026-10-25 凌晨）：统一画布 + 开发环境搭建 + 引擎二期配套已完成，vCode129 已交付待验收

> 本文件是**当前权威交接**。开工顺序：读本文件 → `engine-bridge-phase2-disposition-2026-10-25.md`（引擎二期台账）→
> `annotation-unified-canvas-disposition-2026-10-24.md`（统一画布台账）→ `devenv-one-tap-disposition-2026-10-24.md`（开发环境搭建台账）。
> 历史交接：`handoff-2026-10-23-final-queue.md`（队列权威）。

## 一、状态一句话

仓库 `~/workspace/AiCode`，分支 `dev`，**本地 133 个提交、未 push（用户禁止未经允许同步 GitHub；本地提交照常）**。
工作区干净（仅 ai-config/notes 有他人/历史笔记未跟踪，勿动）。最新提交 `bdbc0c1`。
**vCode129 正式包已交付待真机验收**：`~/workspace/许愿码-正式版-20261025-vCode129-统一画布与开发环境.apk`，
SHA-256 `f8d2e236cc89b3d3d4fa3bef249abe9dad287f7c7cdcd48479f2318c33bfcd64`，签名 CN=AiCode 同历史钥匙可覆盖升级。
versionCode = gitCommitCount()+1，当前 133 → 下一包自动 134。

## 二、本会话已完成（勿重做）

| 项 | 提交 | 状态 |
|---|---|---|
| 反馈6 标注中心统一画布（批1 画布合一 / 批2 账本图层+updateMarks / 批3 删旧入口）+ qa/optimizer 双审处置 | a719d6d / 5d48cd0 / 49d8494 / 621eb71 | 完，待验收 |
| 开发环境一键搭建（设置→开发环境，10 场景，确认后新会话自动发搭建任务）+ qa 处置 | b3153ee | 完，待验收 |
| CLI 一键安装后「检测不到命令」提示升级（探测 npm bin 给 export 命令） | 713c8b1 | 完 |
| 引擎二期批A：Claude Code 认证预检（发送前拦截+卡片未登录态）+ App 内 Anthropic key/baseUrl 经 extraEnv 透传 | a866154 | 完，待验收 |
| 引擎二期批B：本体能力 MCP 桥 aicode_bridge（记忆读/写、规则、技能、账本查询+状态推进，经 PermissionBridgeServer kind:data 分流） | 82ee23a | 完，待验收 |
| 引擎二期 qa 处置（P0 ledger_advance 假成功、P1 useFullUrl 透传、P1 $HOME 硬编码等） | 6ba2306 | 完 |
| 顶栏会话 id 移除（用户反馈） | bdbc0c1 | 完 |

附带修复：ScriptsViewModel.refresh 去重吞刷新竞态（代际+Mutex）。MCP 桥 mjs 已过 node --check 与 stdio 冒烟。

## 三、待真机验收（vCode129 覆盖升级 + 引擎二期）

1. 标注中心：进出入画布无跳页；未定格态触摸穿透（弹窗照弹）；定格含弹窗；缩放平移；画A删A画B重做；账本点条目补画→更新；汇总发 AI 跳回聊天；旧入口全消失。
2. 开发环境：设置→开发环境选 Python → 确认 → 新会话自动发任务 → AI 装包验证汇报；静态网页零安装；断网给替代方案。
3. 引擎二期：配 Anthropic key 后切 CC 引擎免登录直接对话；未配时发消息收到登录引导；CC 会话让 AI 查记忆/技能/账本（ledger_list）并修完推进（ledger_advance，仅 FIXING/REVIEW）；专家团不可用提示仍在。

## 四、队列（用户已确认的推进方向）

1. **三期引擎适配：OpenCode / Codex**（用户已知未做，说开工即开工）。权限桥与 kind:data 数据通道现成可复用；每引擎先调研 headless 协议（codex exec --json / opencode）→ Bridge 翻译层 → 引擎卡片。
2. **引擎二期剩余**：recall（跨会话档案）进 MCP 桥（走同一 kind:data 通道即可）；能力全域打通（用户自装 MCP/脚本/商店映射给 CLI 引擎；反向同步 CLI 生态）。
3. 出包节奏：真机验收反馈 + 上述项合并出 vCode134；发版前 qa+optimizer 双审 + R8 自查（五项清单）+ 汇报逐项完成情况。
4. P2 待排期（带行号）：`annotation-unified-canvas-disposition-2026-10-24.md`（迷你条锚点缩放、hostFit 双真值、pointerInput key、FullScreenCapture 轮询、mjs scope 判定嵌套布局等）+ engine-bridge 台账（无新增）。

## 五、环境与纪律（必读）

- 构建（musl 补丁前缀，勿并行多构建）：
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew :app:compileArmsoloReleaseKotlin`
  快验；`assembleArmsoloRelease` 出正式包（产物 app/build/outputs/apk/armsolo/release/，aapt/apksigner 校验后拷 ~/workspace 按命名惯例）。
- 容器内无 rg（search 工具不可用）：用 `grep -rn`；busybox grep 不支持 --include；勿对 app/src/main/assets 全库 grep。
- 审查路由：L2 派 qa、L3 qa+optimizer；可用子代理仅 Explore/qa（optimizer 用通用子代理 + 读 `app/src/main/assets/agents/optimizer.md` 提示词）；报告必须逐条处置写台账。
- **push 一律先问**；commit 本地照常。detekt：`tools/detekt/run-detekt.sh <文件>`。
- 能力感知纪律：新功能同步 `docs-site/docs/guide/capabilities.md` + `app/src/main/assets/prompts/65-capabilities.md`（本会话已同步过，勿重复）。
- 迁移号：无新迁移；当前最大 71。
