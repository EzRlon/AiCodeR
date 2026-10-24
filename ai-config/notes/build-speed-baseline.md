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

- A/B#4：正式包恢复 build cache（串行构建下验证坏缓存不复现；复现则维持 --no-build-cache）
- 注：本表均为单次测量（手机上跑官方 gradle-profiler 多轮基准需数小时，暂不值）；关键取舍如需定论可对单项做 3 轮取均值

## 已落地的 QA 链路改造（同日完成，非编译）

- detekt 前置扫描：`tools/detekt/run-detekt.sh`（detekt-cli 2.0.0-alpha.6，华为云镜像；只扫改动文件，无全量 baseline——2.0 alpha 的 --report/--create-baseline 落盘有缺陷，只用控制台输出；轻量模式漏报 `!!` 空指针类 bug，诱饵实测，完整模式需 classpath 待补）。
- qa 定义收敛：`.aicode/agents/qa.md`（diff + 一跳邻居，上下文 ≤30 文件/8000 行）；仓库副本 `ai-config/agents/qa.md` 已同步。
- 审查并行化：AGENTS.md 审查路由 + `ai-config/notes/qa-discipline.md`（qa 只读审查可与主线程构建并行；大批次拆 2 分片）。
- `.gitignore`：排除 tools/detekt/detekt-cli.jar（84MB）。
