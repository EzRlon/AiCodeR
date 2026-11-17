# 交接（2026-10-26 批次6 中断点）— 新会话接手必读

> 用户 2026-10-26 叫停施工，要求新会话接手继续。本文是唯一权威断点记录，读完即可直接续作，不要重新调研。

## 〇、一句话状态

批次 0–5 已本地提交（6 个 commit，未 push）；**批次 6 代码已写完但编译未通过，卡在 `SystemPromptProvider.kt` 的 `ProjectNotesSource` 改动上**。批次 7 与最终发版未开始。

## 一、已完成并提交（勿重做）

| 批次 | 提交 | 内容 |
|---|---|---|
| 0 | `bdee912` | 会话长按菜单「导入会话」（SAF + 同 ID 覆盖 / 同标题冲突弹窗 / 加密包魔数判定）；qa P1-1、P2-2/3/4 已修，P2-5/6 记录待排期 |
| 1 | `3521bb5` | `tools/build-with-lock.sh`（构建锁 + 耗时入 `build-times.csv`）、`tools/rollback-build-tuning.sh`；A/B#4 定案：正式包**可恢复 build cache**（`--no-build-cache` 移除，`--no-configuration-cache` 仍必须）；daemon 3 轮 80/55/60s 定案，基线修正：热态增量约 1 分钟 |
| 2 | `7945e72` | 1.6 任务书模板库（`~/.aicode/task-briefs/`，`task(brief=)`）；step-owned 工具准入（`task(tools=)`）；子代理报告 catalog（`~/.aicode/subagent-reports/`，删除后仍可 read）；1.2 意图三层制、1.8 建议三要素入提示词 |
| 3 | `d20bafe` | 1.7 失败埋点（`failure_events` 表，迁移 72，三类事件）；1.3 评测集 12 样本 + JUnit 护栏（5 测试全绿） |
| 4 | `3b3ede3` | 3.4 压缩保真：微压缩保留语义锚点（退出码/BUILD 结论/error 行）；九段式摘要加「语义锚点必保留」硬规则 |
| 5 | `1bf9244` | 4.1 `ModelProbeService`（启动后延迟 8s、1h TTL、失败静默）；4.2 路由表（DataStore 配置化，explore→FAST/qa→BALANCED/optimizer→STRONG + 升级链）；4.5 预算感知入提示词 |

## 二、未提交的批次 6 改动（当前工作区）

新增文件：
- `app/src/main/java/com/aicode/feature/agent/domain/tool/ledger/TaskLedgerTool.kt`（3.1 任务账本工具，状态机 queued→doing→done→accepted 只前进，落 `<项目>/.aicode/notes/task-ledger.md`）
- `app/schemas/com.aicode.feature.agent.data.local.database.AgentDatabase/72.json`（Room 导出的 72 schema，**必须随迁移 72 一起提交**）

已改文件：
- `app/src/main/java/com/aicode/di/AgentModule.kt`：注册 `taskLedger` 工具（已改好）
- `app/src/main/java/com/aicode/feature/agent/domain/prompt/SystemPromptProvider.kt`：新增 `ProjectNotesSource`（3.3 项目知识库索引，注入 `<项目>/.aicode/notes/*.md` 标题清单）+ `PROJECT_NOTES_VAR`/`TASK_BRIEFS_VAR` 变量渲染（**当前编译不过**）
- `app/src/main/assets/prompts/99-runtime-context.md`：新增「任务书模板」「项目知识库」「任务账本」三节 + 记忆「纠正即沉淀」小节
- `docs-site/docs/guide/capabilities.md`：子代理条目补三件配套 + 新增「项目任务账本」条目

## 三、当前阻塞（接手第一件事）

**症状**：`:app:compileArmsoloReleaseKotlin` 报 40 个 `SystemPromptProvider.kt` 错误，全是 companion 常量 Unresolved（`TAG`/`SOURCE_CACHE_LIMIT`/`SUBAGENT_BASE_FILE`/`LEADING_COMMENT`/`AGENTS_FILE`），外加 `InitCommandHandler`/`ContextCompactor`/`StatefulAgentWorkflow` 的 `resolvePrompt` Unresolved 级联。

**已定位并修掉的真因**：搬移 `ProjectNotesSource` 时在 `MemoryListSource` 闭合后多出一个 `}`（原第 281 行），把整个类提前闭合。**该多余 `}` 已删除**，现在括号层级正确：类在 644 行闭合、`ProjectNotesSource` 在 556–594、`private companion object` 在 596。

**当前状态**：删掉多余 `}` 后又跑过一次编译，**仍报 companion 常量 Unresolved**。判断是增量编译缓存残留（同一文件反复报同类错误、结构已正确）。**最后一条命令（`--no-build-cache --rerun-tasks`）被用户叫停，未执行完**。

**接手建议动作（按序）**：
```bash
cd ~/workspace/AiCode
# 1) 先确认结构仍正确（应为 556 ProjectNotesSource / 596 companion / 644 类闭合）
grep -n "ProjectNotesSource : PromptSource\|private companion object" \
  app/src/main/java/com/aicode/feature/agent/domain/prompt/SystemPromptProvider.kt
# 2) 清掉该文件的增量缓存后重编（走锁脚本）
sh tools/build-with-lock.sh :app:clean :app:compileArmsoloReleaseKotlin
```
若仍报错，用 `git stash` 隔离法：stash 后编译（此时只剩 `SettingsScreen.kt` 引用 `engine_switch_busy` 的他会话未提交错误），确认基线可编译；再 `git stash pop` 后逐段恢复 `ProjectNotesSource`，二分定位。

**旁证**：`git stash` 后编译只报 1 个错误——`SettingsScreen.kt:1145` 引用 `engine_switch_busy`，那是**另一会话**未提交的 strings 改动（`strings.xml` 里该行已回填在工作区），不是我们的问题，勿动。

## 四、剩余批次（未开始）

- **批次 6 剩余**：3.2 画像归纳（已在 99-runtime-context.md 写了「纠正即沉淀」提示词，可视为最小落地）、3.3 项目知识库（`ProjectNotesSource` 即其实现，待编译通过）
- **批次 7**：2.2 最终包多维自检脚本（apkanalyzer 体积差分、启动路径 grep、keep 规则核对）接入发版流程
- **最终发版**：detekt 前置 → qa（可与编译并行）→ optimizer → 性能功耗五项自查 → `assembleArmsoloRelease --no-configuration-cache` → 核对 output-metadata.json versionCode == `git rev-list --count HEAD` + 1 → 拷到 `~/workspace` 按命名惯例交付

用户原话：「直接做完，最后发最终编译包我再测试」——即批次 6/7 + 最终包都要做完再交付。

## 五、纪律与坑（必读）

- 构建一律走 `sh tools/build-with-lock.sh <任务>`（锁 + 自动记耗时）；musl 前缀脚本已内置，不用手写。
- 正式包必须 `--no-configuration-cache`（配置缓存会冻结 git 版本号，已踩）。
- **不 push**；本地 commit 照常。
- 其他会话未提交产物勿动：`strings.xml`/`values-en/strings.xml` 里的 `engine_switch_busy`、`ai-config/notes/` 下未跟踪笔记（enhancement-program、handoff-*、review-*、kimi-review、deepseek-harness-borrowing）。
- detekt 多文件用 `:` 分隔（逗号/空格无效）：`sh tools/detekt/run-detekt.sh a.kt:b.kt`
- 容器无 rg，用 `grep -rn`；busybox grep 不支持 `--include`。
- 编辑大文件（如 600+ 行的 SystemPromptProvider）时**不要用脚本搬移代码块**——本次编译阻塞就是搬移脚本吃掉一个 `}` 造成的，改用 `editFile` 精确替换。

## 六、关键文件位置

- 计划与裁决：`ai-config/notes/enhancement-program-2026-10-24.md`
- 构建基线数据：`ai-config/notes/build-speed-baseline.md`、`build-times.csv`
- 批次 0 台账：`ai-config/notes/import-session-batch0-disposition-2026-10-26.md`
- 本次断点：本文件 `ai-config/notes/handoff-2026-10-26-batch6-interrupted.md`
