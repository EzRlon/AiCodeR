# 构建耗时基线与 A/B 实测（2026-10-24）

目的：编译/QA 链路提速改造的数据底账。所有数字为本机实测（PRoot 容器，手机端），非文献引用。

## 环境基准

- PRoot 容器（musl Alpine）跑在手机上；Gradle 8.14 wrapper；AGP 8.9.3；Kotlin 2.2.21；KSP2（2.2.21-2.0.5）；R8 8.13.19。
- gradle.properties：缓存/并行/配置缓存已开；workers.max=4；堆 4096m。
- 任务：快验 = `:app:compileArmsoloReleaseKotlin`；正式包 = `assembleArmsoloRelease`（带 `--no-build-cache`）。

## 实测数据

| # | 场景 | 配置 | 耗时 | 备注 |
|---|---|---|---|---|
| 1 | 空跑（39 task 全 up-to-date） | in-process + jetifier | **1m01s** | 冷 daemon（daemon 重建） |
| 2 | 空跑（配置缓存命中） | 同上 | **1m13s** | 热 daemon；PRoot 下固定底价 |
| 3 | 增量编译（MainActivity.kt ±1 行注释，全新内容未命中缓存） | in-process + jetifier | **7m45s** | 2 task 真实编译；单次测量 |
| 4 | 增量编译（同文件新内容，真实编译） | **daemon** + jetifier | **6m05s** | Kotlin 4m33s + KSP 1m27s；比 in-process 快约 23%（单次测量，有方差风险） |
| 5 | 增量编译（同内容对照 jetifier 开关） | daemon + **jetifier=false** | **1m22s** | compile 命中缓存（25s）→ **jetifier 对本项目依赖字节级零改动，可安全关闭**；资源转换重跑 78s 为一次性代价 |

### 初步结论

1. **固定底价约 1 分钟**：无论改没改代码，每次 gradlew 调用都要付 ~1 分钟（daemon 启动 + up-to-date 检查在 PRoot IO 下的代价）。⇒ 策略上「少跑次数、每次多带活」优于频繁小构建。
2. **增量编译 ~8 分钟**：改一个文件要 7m45s，单一大 :app 模块 + KSP(Hilt/Room) + Compose 是大头。⇒ 快验慢的主因在此；模块化拆分（架构级）是根治方向。
3. Kotlin build report（kotlin.build.report.output=file）在本环境未落盘；改用 `--profile` 归因。

## A/B 待完成

- ~~A/B#4（正式包恢复 build cache）~~ ✅ 2026-10-26 已完成：`assembleArmsoloRelease --no-configuration-cache` **不带** --no-build-cache，13m17s 构建成功（90 tasks，80 up-to-date），坏 KSP 缓存未复发。**结论：正式包可恢复使用 build cache，--no-build-cache 从正式包流程移除**（正式包仍必须带 --no-configuration-cache，版本号冻结坑独立存在）。耗时已入 build-times.csv。
- 注：本表均为单次测量（手机上跑官方 gradle-profiler 多轮基准需数小时，暂不值）；关键取舍如需定论可对单项做 3 轮取均值。

## daemon 3 轮均值复核（2026-10-26，已定案）

热态增量（同一变体，每轮对 MainActivity.kt 追加全新注释行防缓存命中，走 build-with-lock.sh）：

| 轮次 | 墙钟 | 配置缓存 | Kotlin 增量 | 说明 |
|---|---|---|---|---|
| 1 | 80s | 首次落库 | 10.7s | 含一次性配置缓存 store 代价 |
| 2 | 55s | 复用 | 8.0s | |
| 3 | 60s | 复用 | 10.2s | |

- 2/3 轮方差 9%（±10% 内），Kotlin 增量方差更小 → **daemon 策略定案维持**。
- **基线修正**：本表第 4 行的 6m05s 是 daemon 切换后冷启动单次测量（Kotlin daemon 增量管线首次重建）；**热态下 daemon 增量实际约 1 分钟墙钟**（Kotlin ~10s + KSP ~28s + Gradle 固定底价）。此后的「增量编译 ~8 分钟」结论按热/冷两态重新理解：冷启动（新 daemon/大改）才付分钟级代价。
- 构建耗时曲线已落地：ai-config/notes/build-times.csv（build-with-lock.sh 自动追加，含 task/秒数/退出码）。

## 并行会话注意事项（2026-10-24）

- 另一会话已将本会话的构建配置优化（daemon 策略/jetifier off/build report）与 detekt 工具收编入提交（6762a79、f05e0fc）；f05e0fc 误收本会话的构建测试注释（`// build-timing-ab2b-20261024`），工作区已删除该注释待下次提交入库。
- 本会话在用户通知前**不再跑任何 Gradle 构建**。

## 已落地的 QA 链路改造（同日完成，非编译）

- detekt 前置扫描：`tools/detekt/run-detekt.sh`（detekt-cli 2.0.0-alpha.6，华为云镜像；只扫改动文件，无全量 baseline——2.0 alpha 的 --report/--create-baseline 落盘有缺陷，只用控制台输出；轻量模式漏报 `!!` 空指针类 bug，诱饵实测，完整模式需 classpath 待补）。
- qa 定义收敛：`.aicode/agents/qa.md`（diff + 一跳邻居，上下文 ≤30 文件/8000 行）；仓库副本 `ai-config/agents/qa.md` 已同步。
- 审查并行化：AGENTS.md 审查路由 + `ai-config/notes/qa-discipline.md`（qa 只读审查可与主线程构建并行；大批次拆 2 分片）。
- `.gitignore`：排除 tools/detekt/detekt-cli.jar（84MB）。
