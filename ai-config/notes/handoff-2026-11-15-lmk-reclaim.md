# 2026-11-15 晚二批：LMK 孤儿回收 + 就绪文案资源化（交接）

> 承接 `feedback-batch-2-2026-11-15.md`。分支 `feat/feedback-notebook`，工作区未提交，
> 当前改动约 84 项。**未 push**。

## 一、本批完成

### 1. App 被 LMK 后的孤儿 CLI 冷启动回收（高风险项）

- 新增 `EngineOrphanReclaimer.kt`：`ProcessProbe`（/proc 抽象）+ `EngineProcessFile`（原子 JSON 落盘）
  + `EngineOrphanReclaimer`（校验 → 后序 SIGTERM → 宽限 → 快照内 SIGKILL）。
- `EngineProcessRegistry`：spawn 后把 `pid + startTicks + engine + runId` 落盘；冷启动由
  `AIEditorApp.onCreate` 后台回收；`register` 前同步回收同会话旧记录；kill/unregister 按 pid 精确删。
- 安全边界：PID + `/proc` starttime + cmdline 首参 libproot 三重校验；当前进程 runId 跳过；
  PID 复用、非 proot、僵尸一律不杀；`--kill-on-exit` 下先后代后根。
- **PID 认领**：Android 公共 SDK 的 `Process` 没有 `pid()`（`android.jar` + `api-versions.xml` 已核实），
  改为扫 `/proc` 找本进程 proot 子进程，按 `PERMISSION_BRIDGE_SESSION` 精确匹配，兜底取未认领且
  启动时间最新的 proot 子进程。
- 设计/取舍见 `lmk-orphan-reclaim-design-2026-11-15.md`；待排期项见
  `engine-process-followups-2026-11-15.md`。

### 2. 签名就绪详情英文资源化

- `ReleaseReadinessChecker` 注入 `@ApplicationContext`，detail/nextStep 全部改 `R.string`（28 条新键）。
- `DevKeysSection` 的「下一步」改用格式化资源（中英标点各自正确）。

## 二、审查与验证

- QA（只读）：P0×3 已修（PID 反射不可用 / 回收树漏根 / 测试不一致）、P1-4、P2-6 已修；
  其余 P2/P3 记录。结论：组 B 可发版；组 A 修复后需重跑。
- optimizer（只读，FULL 抽样）：无 P0；P1-1（register 同步全量回收拖慢首回合）已修；
  P2 全部记入 `engine-process-followups-2026-11-15.md`。
- `:app:testUniversalDebugUnitTest --tests com.aicode.feature.agent.domain.engine.*` ✅
  （新增 14 例：回收 9 + 落盘 5，0 失败）。
- `:app:assembleArmsoloRelease` ✅ **BUILD SUCCESSFUL in 9m 39s**，apksigner 验签通过（CN=AiCode）。

## 三、交付包

- `/root/workspace/app-armsolo-2.3.0-rc4-dev.40-353-b4.apk`
- SHA-256 `226d5afa4004ff33002fc03019e729fac0cbf3c4f380fbdcbaf8d91f88e44134`
- versionCode 353 / versionName `2.3.0-rc4-dev.40+8a7bebc-dirty`
- `send_file` 报 token mismatch：MCP 与按预案直连本地桥均被 App 拒绝，未能生成文件卡；
  APK 已放工作区根目录，可从 App 文件浏览器直接安装。

## 四、仍待办

1. **真机冒烟（必须）**：引擎回合后确认 `filesDir/engine-processes.json` 出现记录；
   杀 App 冷启动后孤儿 proot/claude 是否被回收；冷启动首回合延迟；多会话并起时不影响流畅度。
2. 反馈收集端点部署：缺 Cloudflare 授权与 GitHub 令牌（已 blocked）。
3. `board.json` 已由外部引擎会话实跑验证（`/root/workspace/.aicode/orchestration/board.json`）。
4. 真机验证随记编辑器/签名页/引擎续跑与停止（上一批遗留）。

## 五、环境注意

- 本会话期间出现**同会话重复 CLI**（同一 `--resume` 多份），会并发改同一批文件、互相清构建目录。
  已按用户指示终止重复进程；修复方向即本批的孤儿回收 + 既有 `EngineProcessRegistry` 去重。
- 构建命令仍必须：`GRADLE_USER_HOME=/tmp/gradle-home-fresh`、aapt2 覆盖、
  `--no-daemon --no-build-cache --no-configuration-cache --max-workers=1`、in-process Kotlin。
