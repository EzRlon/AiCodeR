# 发版前三审处置（2026-10-25，覆盖提交 1e66cdd / 9c9c996 / 820b3d3 / b2dd5ad）· 处置提交 2c62656

审查组成：qa 分片1（引擎桥接域）、qa 分片2（表现层与集成）、optimizer（性能功耗，按 app/src/main/assets/agents/optimizer.md 口径）。detekt 全部触达文件 0 findings；编译+单测已验。

## P0（已修复）
- **qa2-P0-1 CLI 会话选的模型被「历史会话回填」覆盖**：backfill 条件无 engine 判断，第二条消息起 `-m` 传内置默认模型名（一期遗留，三期显性化）→ 修复：回填加 `currentSession.engine == null` 条件（AIAgentViewModel）。真机补验：CLI 会话选模型 → 发两条消息，第二条仍用所选模型且胶囊不回退。

## P1（全部已修复）
- qa1-P1-1 OpenCode EOF 后 waitFor(10s) 阻塞主线程 → withContext(Dispatchers.IO) 包裹。
- qa1-P1-2 内置 mjs 未捕获异常崩 MCP server → handleLine 兜底回 JSON-RPC error、send 防 EPIPE、两脚本 promise 补 catch（.catch）。
- opt-P1-1 三桥回合链路主线程 fsync 写盘与阻塞孵化 → writeBridgeAssets/startStdioProcess 包 withContext(Dispatchers.IO)（三桥）。
- opt-P1-2 同 qa1-P1-1（重复项，已修）。

## P2（修复 6 项 + 记录 6 项）
已修：
- qa2-P2-1 引擎卡切换被拒（运行中）仍滑到模型页 → onSelectEngine 链改返回 Boolean，false 不滑页。
- qa2-P2-4 引擎页三引擎串行探测 → 并行 launch。
- qa1-P2-2 Codex 顶层 error 只走 stdout 时分类退化 → acc.lastError 进 classifyEarlyExit 兜底。
- qa1-P2-4 Codex 用户 MCP env 键名非法致 -c 解析失败 → 键名白名单校验，非法跳过。
- opt-P2-3 token 不匹配不回写致 mjs 挂 5 分钟 → 回写 deny。
- opt-P2-7 认证探测 TTL 60s 过短（几乎每条消息探一次容器）→ 对齐 5min。

记录不修复（带理由）：
- qa1-P2-1 三桥回合起始阻塞 IO 在 Main → 已被 opt-P1-1 的 withContext 覆盖（同点）。
- qa1-P2-3 旧图片残留（无图回合不清理）→ 单张 ≤8MiB 有界，随下一带图回合清理；记 P2 待排期。
- qa1-P2-4 / opt-P2-8 映射文件全量重写无上限增长 → 量级小；记 P2 待排期。
- qa2-P2-2 Codex/OpenCode 不能设为新会话默认引擎 → 能力同步批三范围（设计笔记已列）。
- qa2-P2-3 历史脏 engine 值胶囊显示不一致 → engine 列仅 App 写三常量，风险低，informational。
- opt-P2-5/6/9/10 acceptLoop 常驻零 CPU、soTimeout 5min、states map 重组、停止后授权协程残留 → 均记录不阻塞（最后者归 qa 正确性面，既有行为）。

## 已知问题（非本批引入，如实标注）
- **单测 SyncEngineIgnoreTest 全量跑失败、隔离跑通过**：测试间污染（某更早测试在 IO 协程抛 Main dispatcher 异常，归到后续 runTest 类）。workspace 模块与测试自 10-12 上游快照后零改动；本批未触碰任何被测类；1342/1343 绿。记 P2 待排期（元凶定位需测试顺序追踪）。
- qa 未验证项（真机）：claude stream-json image 块接受度、codex -i / opencode -f 实际行为、opencode environment 键名、OpenCode 退出码判定、Codex MCP 白名单配对。

## R8 五项自查
1. 性能：无主线程重活（optimizer P1 已修，图片 IO 在 IO 线程）。
2. 功耗：无新增常驻轮询/持续动画；watchdog 仅回合期存活（optimizer 确认）。
3. R8：armsolo release 已 isMinifyEnabled+isShrinkResources（build.gradle.kts:190-191）；三桥 JSON 全走 JsonElement 手工解析、无新增 @Serializable 反射序列化面、无新增 keep 需求（qa1 已核）；构建后核对 mapping（105 万行）与资源收缩（resources.txt 3.5 万行）齐全。
4. 流畅性：主线程 IO/阻塞等待已清；弹窗探测并行化。
5. 编译顺序：compileArmsoloReleaseKotlin ✓ → testArmsoloReleaseUnitTest（1342/1343，1 既有 flaky 见上）→ assembleArmsoloRelease ✓ → aapt/apksigner 校验 ✓。

## 交付（2026-10-25）

- 包：`~/workspace/许愿码-正式版-20261025-vCode139-三引擎与能力同步.apk`，SHA-256 `d048c6a4…35c572`，versionCode=139（HEAD 2c62656，138 提交 +1），签名 CN=AiCode 可覆盖升级。
- **坑（已验证）**：Gradle 配置缓存会冻结 `providers.exec`（git 版本号）输出——首次 R8 产物静默回退 versionCode 129/旧 versionName；出正式包必须 `--no-configuration-cache`。已重验 versionCode=139、versionName 指向 2c62656。待写入项目记忆。
