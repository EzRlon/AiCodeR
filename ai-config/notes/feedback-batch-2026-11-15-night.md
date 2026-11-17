# 2026-11-15 晚反馈批次（8-9 条）处置记录

> 分支 `feat/feedback-notebook`，工作区未提交。编译与单测见文末。
> 本文件是断点续跑用的状态表：新会话先读这里，再看 `git diff`，不要重复实现。

## 一、反馈清单与状态

| # | 用户反馈 | 状态 | 落点 |
|---|---|---|---|
| F1 | 专家团必须始终本土化；在线商店新增团队也要守纪律 | ✅ 代码完成，待真机 | CJK 分流、脏缓存校正、内置 `nameZh`、在线安装翻译、渲染兜底 |
| F2 | 设置 → 签名与凭据入口不应带副标题，与其他选项卡一致 | ✅ 完成 | `SettingsScreen.kt` 删除 subtitle |
| F3 | 「签名与凭据」页不直观；检查正式包选项；支持多项目/多工作区 | ✅ 代码完成，待真机 | 项目选择器 + 每项目就绪卡 + 正确读取 `app/keystore.properties` + storeFile/alias/keyPassword/未验证项 |
| F4 | 会话中断后说「继续」要从断点续跑，不能每次新开进程 | ✅ 代码完成，待真机 | 首回合 `resume=false`、`ContinuationDirective`、进程登记/回收 |
| F5 | 后台跑时发消息不能被吞/中断；多次点发送才强停 | ✅ 代码完成，待真机 | 队列持久化、`stoppingSessions` 闸门、停止保护、跳过外部引擎自动交接 |
| F6 | 主代理忙时拉子代理回复/接续，由主代理服务用户 | ✅ 代码完成，待真机 | 只读旁路助手：子会话 + PLAN/read-only 档 + 独立 CLI session id；问题/反馈走旁路，任务仍排队；侧栏可点入 |
| F7 | 侧栏当前会话下显示子代理数量/会话，可点入实时界面 | ✅ 代码完成，待真机 | 运行中/总数徽标 + 子行运行态 + 点入已有；子会话只读 |
| F8 | 随记浮窗编辑页很乱、按钮/竖线不明所以，要像简易笔记软件 | ✅ 代码完成，待真机 | 隐藏 Vditor 换行工具栏、原生文字格式条、底部文字动作、保存状态、占位符 |
| F9 | 装包后验收自测表 | 🟡 部分完成 | 容器自检通过；`board.json` 未生成；UI 项待用户回答 |

## 二、关键技术结论（勿重复踩坑）

1. **英文论文摘要来源**：不是团队数据文件，是腾讯交互翻译把中文描述按 en→zh 回译后写入缓存。修复必须同时做「源文 CJK 不送翻」「读取时按源文校正」，只改 UI 不够。
2. **签名就绪检查必须看 `app/keystore.properties`**：Gradle `signingConfigs` 读的就是项目模块下这个文件；证书与配置分离是正常布局，旧实现按「证书同目录」找会误报。
3. **同会话重复 CLI 进程**：根因是 `process.destroy()` 挂在 Job 完成回调，CLI 阻塞在 `readLine` 时停止/取消不及时；且停止后立即消费队列会拉起第二份。修复为 `EngineProcessRegistry` 定向杀 + `stoppingSessions` 闸门 + 300ms 退出窗口。
4. **首回合固定双起**：`EngineTurnRouter` 原来恒传 `resume=true`，新会话先 `--resume` 失败再起第二份；现按 App 侧历史是否为空传 `resume`。
5. **外部引擎自动交接是反效果**：交接摘要只进 App 历史，CLI 不读；现对外部引擎跳过自动交接（手动 `/handoff` 保留）。
6. **队列原先只在内存**：用户发完消息后 UI 立即清草稿，进程被杀就丢；现落 `filesDir/queued-requests.json`（图片 base64 不落盘）。

## 三、仍未完成 / 下一步

- **App 被 LMK 后的孤儿 CLI**：需要 PID 落盘 + 冷启动 `/proc` 校验回收；本轮未做，真机需验证 `--kill-on-exit` 覆盖。
- **Codex/OpenCode 映射跨重启丢失**：映射只在 finally 落盘且读取不回填内存，多会话重启后会丢；未修。
- **断点续跑标记**：目前靠用户说「继续」+ `--resume`；没有持久化「上次回合被中断」标记，用户说别的句子不会自动补续跑指令。
- **签名页切项目不自动停任务**：已把确认框与文档改成「请先停止」；没有接线 stopAll/关终端（需要宿主回调）。
- **就绪详情的英文界面**：`ReleaseReadinessChecker` 的 detail/nextStep 仍是中文硬编码；资源化留待下一批。
- **board.json 未生成**：需要在 App 外部引擎会话里让专家团实际调用 `orchestration_board` 跑一轮。
- **真机验证**：进程停止/续跑/队列恢复、签名页多项目、专家团中文、随记键盘/缩放、旁路助手只读。

## 四、审查处置

- **qa**：首轮无 P0；P1「停止闸门先于退出窗口清除」已改为 delay 后再清；「闸门残留」加 15s 看门狗；队列合并改按会话拼接；签名页就绪清空/IO/keytool 超时/Gradle 位置判定已修；子会话只读下沉到 `enqueueAgentRequest`；随记保存结果按 path 比对。
- **qa 增量复审**：无 P0；剩余 P1 已处置——`STOP_SETTLE_MS` 提到 **1800ms** 覆盖 registry 的 1.5s 强杀窗口；旁路助手改为「句中任意任务动词即排除」，避免「能不能把按钮改成红色？」被只读旁路接走。
- **optimizer**：无 P0；P1 的「定时任务 VM 误杀进程/误跑用户队列」已用 `@Named("queue_persistence")` + 按 VM sessionJobs 定向 kill 修复；签名页主线程 IO 已移入 `Dispatchers.IO`；停止闸门加看门狗。
- 仍记录为后续：LMK 孤儿 CLI 冷启动回收、Codex/OpenCode 映射持久化、断点标记持久化、签名页切项目自动停任务、就绪详情英文资源化。

## 五、验证

- `:app:compileUniversalDebugKotlin` ✅
- `:app:testUniversalDebugUnitTest`：1868 例 0 失败
- `scripts/check_migrations.py`：通过（8..77，无新增迁移）
- 容器自检 `scripts/smoke-check.sh`：引擎 CLI ✅、随记目录 ✅（133 B）、board.json ❌、桥脚本 ✅
- 本批期间发现同一会话 5 组重复 CLI 进程，已清理 4 组；根因与修复见 `engine-process-duplication-2026-11-15.md`。
- 正式包：qa + optimizer 报告处置后 `assembleArmsoloRelease`。

### 构建环境坑（本轮实测）

1. 同会话重复 CLI 进程会互相 `gradle --stop`，构建被中断；先确认只剩当前引擎。
2. Gradle daemon registry 锁在 PRoot 上报 `canonicalize: Function not implemented` 后会卡死（CPU 不涨），换全新 `GRADLE_USER_HOME` + 复用 caches 软链解决。
3. aarch64 设备上 Maven 的 aapt2 是 x86-64，release 资源阶段必失败；用
   `-Pandroid.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2`。
4. 内存紧时用单 JVM、单 worker、5G 堆、Kotlin 进程内编译，避免 daemon OOM。

### 最终包（2026-11-15 17:04）

- `assembleArmsoloRelease` **BUILD SUCCESSFUL in 9m18s**
- versionCode **353** / versionName `2.3.0-rc4-dev.40+8a7bebc-dirty`
- 产物 `app/build/outputs/apk/armsolo/release/app-armsolo-release.apk`，已复制到 `~/workspace/app-armsolo-release-353.apk`
- SHA-256 `58d6ebb8578893b780dd2e58000eb56c1ac6c7b1ff31fee6a821f94abdb4f26c`
- 签名证书 CN=AiCode（与已装 351 同一套 release 签名），可直接覆盖升级
- `mcp__aicode_bridge__send_file` 当前返回 `token mismatch`，改用工作区根目录文件交付
