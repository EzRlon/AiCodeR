# 交接：屎山/技术债审计的开工指引（2026-10-27）

> 给新会话的第一件事：读本节 + 读档案 `ai-config/notes/debt-audit-2026-10-27.md`，然后按 §八 的批次顺序开工。
> 状态：**审计只标记、未开工**（用户 2026-10-27 明确「开工等我批准」）。本文件是待批准后的执行指引。

## 一、现状与产物（澄清「三个」的说法）

本会话（2026-10-27）在屎山审计上只产出 **1 份档案**，但它包含 **两轮独立审计**；另有 **2 份更早的历史审计**被它引用作对照（不是本会话产物）。加上 1 条项目记忆，共 4 处相关资产：

| # | 资产 | 性质 | 说明 |
|---|---|---|---|
| 1 | `ai-config/notes/debt-audit-2026-10-27.md` | **本会话新建（未提交）** | 主档案，453 行、§一~§十。含**两轮审计**：§一~§八 = 全库基线审计；§九 = 最近 18 小时增量审计 |
| 2 | `ai-config/notes/pre-release-audit-2026-10-20.md` | 历史（已提交） | 被 §六 逐条核实其 22 条状态 |
| 3 | `ai-config/notes/pre-release-audit-2026-10-22.md` | 历史（已提交） | 同上（P2 9 条） |
| 4 | 项目记忆 `ponytail-debt-audit` | 记忆 | 审计结论摘要 + Ponytail 技能说明 + 可复用审计方法论 |

**档案内部分级（用于排期）**：

- §一 客观基线（行数/大文件/detekt 复杂度/层间依赖/测试缺口）
- §二 **P0 必修 5 条**（数据安全类，均已回源核实成立）
- §三 **P1 应修 12 条**
- §四 **P2 可选 14 条**
- §五 重复实现 7 组（含「是否值得合并」判断，避免为合并而合并）
- §六 历史项核实（旧记录 22 条 + 补充 11 条）
- §七 巨型文件拆分建议（按职责切，不按行数硬切）
- §八 **建议执行顺序：5 个批次** + 增量项归位
- §九 **最近 18 小时增量审计**（增量 P1×4 / P2×13，含「本批已修项」与「核对后排除的误报」）
- §十 本次审计未覆盖范围

**基线**：§一~§八 基于提交 `c92a0c2`；§九 覆盖到 `b72cf74`（当前 HEAD）。工作区无未提交的 .kt。**所有行号会随代码变动漂移，动手前必须重新 `readFile` 核实。**

## 二、Ponytail 技能已装（全局）

`~/.aicode/skills/` 下 5 个：`ponytail`、`ponytail-audit`（整仓审计，本次所用）、`ponytail-review`（单次改动/PR 审查）、`ponytail-debt`（扫 `shortcut:` 注释，本项目当前 0 处故无输出）、`ponytail-help`。
`ponytail-review` 适合每批改动收尾时自审（六维排序 correct > safe > 规模 > tested > fast > lean；每条发现必须带具体触发场景）。

**2026-10-27 补充：ponytail 四件套已随 App 内置**（`app/src/main/assets/skills/` 下的 `ponytail` / `ponytail-review` / `ponytail-audit` / `ponytail-debt`，启动时经 `extractBuiltinSkills` 释放到设备 `~/.aicode/skills/`，随版本升级覆盖）。正文已译为中文并按 App 机制改写：技能按回合注入、不会常驻，`/ponytail lite|full|ultra` 的档位参数由 `$ARGUMENTS` 带入。`ponytail-help` 未内置（内容全是 Claude Code 的 `/plugin`、`band` 等宿主专属操作，在 App 里没有对应物）。同批新增静态校验 `app/src/test/java/com/aicode/feature/agent/domain/skill/BuiltinSkillsAssetsTest.kt`：校验每个内置技能都能解析、name 与目录名一致、description 与正文非空。

## 三、执行批次（档案 §八 的原文，按此顺序）

- **批次 1（数据安全，最高优先，5 条）**：P0-1 编码损坏、P0-2 消息级联删除、P0-4 与 P0-5 裸 `deleteRecursively`、P1-3 备份导入无事务。
- **批次 2（正确性）**：P0-3 子代理误报完成、P1-4 `_expandingPath` 卡死、P1-5 消息历史缓存失效、P1-6 交接暂停态落盘。
- **批次 3（安全收口）**：P1-1 权限桥 token、P1-2 VNC mixed content、`shellQuote` 四份合并。
- **批次 4（性能功耗）**：P1-7 会话态泄漏、P1-8 状态收集下沉、P1-9 输入测量、P1-10 逐帧、P1-11 配额、P2 全项、**增量 P1-1（冷启动全量重释放 2.4MB 内置技能）**、增量 P2-5～13。
- **批次 5（架构重构，工程量大，建议单独立项分批做）**：层间依赖下沉（含**增量 P1-4** agent 域层直连 memorygraph data 层）、巨型文件拆分（含**增量 P1-2** 多选脚手架三份重复、**增量 P1-3** 第 4 份会话清理块）。

批次 1 的具体落点与最小改法见档案 §二，摘要：

1. **P0-1**：`feature/editor/presentation/CodeEditorViewModel.kt:107` 读用 `bytes.toString(Charsets.UTF_8)`、`:181` 写默认 UTF-8；而 `feature/editor/domain/FileEncoding.kt` 已实现 UTF-8/UTF-16/GBK 检测与「保存用同一编码」约定但**全库零调用**。改法：`load()` 改用 `FileEncoding.decode(bytes)` 存下 encoding，写回时传 `encoding = detected.charset`（`FileAccessProvider.writeFile(path, content, overwrite, encoding)` 已有该参数）。
2. **P0-2**：`feature/agent/presentation/AIAgentViewModel.kt:4442-4452` 对 USER 消息执行 `deleteMessagesAfterTimestamp`（SQL 为 `timestamp > :cutoff`，见 `data/local/dao/AgentMessageDao.kt:87`），删一条即硬删其后整段；入口 `AIChatPanel.kt:2419` → `MessageContextMenuPopup.kt:136-137` 无确认。改法：加二次确认（可复用批次 D 新增的 `core/ui/ListSelection.kt` 里的 `BatchDeleteConfirmDialog`），或拆成「仅删本条 / 删本条及之后」。与 **P1-5** 同处（无事务 + 无 `invalidateHistory`），建议同批做。
3. **P0-4**：`feature/agent/domain/container/ContainerInstaller.kt:48` 的 `extractDocs` 用裸 `deleteRecursively`；同文件 `:68`/`:110` 已改 `SafeDelete.deleteTree`。改法：第 48 行同样换掉（一行）。
4. **P0-5**：`feature/workspace/data/repository/WorkspaceRepository.kt:456`（删工作区）与 `feature/workspace/data/provider/WorkspaceDocumentsProvider.kt:159`（SAF 删文档）用裸 `deleteRecursively`。工作区被 bind mount 进容器 `/root/workspace`（`LinuxContainerEngine.kt:742`），容器内 AI/用户脚本可投放符号链接；对照 `feature/workspace/domain/LocalFileAccess.kt:199-207` 已用 `SafeDelete`。改法：两处都换 `SafeDelete.deleteTree`。此路径当前零单测，建议补一个。
5. **P1-3**：`feature/backup/data/BackupManagerImpl.kt:582`（`restoreFromTar`）与 `:831-856`（工作区文件直写）全流程无 `withTransaction`。改法：DB 段包 `agentDatabase.withTransaction`；文件段先解到临时目录再整体替换（最小版：加事务 + 失败时明确告知「部分文件已写入」）。

## 四、每批必守的纪律（项目规则 + 本批注意）

- **审查路由**：L1（1-2 文件非核心）主代理自查；L2（≥3 文件或核心链路）派 `qa`（BATCH）；L3（新功能/重构/跨模块）`qa` + `optimizer`。**正式包构建前**必做：detekt 前置扫描（`tools/detekt/run-detekt.sh <改动文件...>`）→ `qa` 与编译并行 → `optimizer`，并过 R8 维度（keep 规则/反射面/资源收缩）。
- **构建**：musl 容器必须带补丁前缀 —— `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew <任务>`；双会话并行构建会污染 KSP 缓存，**走 `tools/build-with-lock.sh`**。快验 `:app:compileArmsoloReleaseKotlin`；正式包 `assembleArmsoloRelease`。
- **Hilt 教训（本窗口踩过）**：`compileArmsoloReleaseKotlin` **不触发 Hilt 组件生成**。凡新增/改动 `@Inject` 构造参数，必须跑 `:app:hiltJavaCompileArmsoloRelease` 或正式包，否则循环依赖/缺绑定要到打包才暴露（`c92a0c2` 即为此修复）。
- **性能与功耗纪律**：不引入常驻轮询/持续动画/无谓唤醒；动画克制；后台任务克制可取消有缓存。批次 4 全在此维度。
- **能力感知**：新功能需登记 `docs-site/docs/guide/capabilities.md` + `app/src/main/assets/prompts/65-capabilities.md`（本次都是修 bug，通常豁免）。
- **不擅自 commit/push**；本地 commit 可，push 需用户确认。
- **档案是活账本**：每修完一条，在 `debt-audit-2026-10-27.md` 对应条目上就地标注「✅已修 <commit>」或「不修 + 理由」，别让它变成只读归档。

## 五、去重规则（避免重复报/重复修）

- 档案 §九「**本批已修复项**」列了上游 v1.13 批次 A/B/C/D/E1 已修的安全与正确性项（子代理权限 `limitedBy`、审批通知 requestId、FTP 随机口令 + 加密、`ShellCommandParser` 括号深度、浏览器混合内容降级等）——**不要再当问题报**。
- 档案 §九「**核对后排除的误报**」列了 2 条已排除项（`AIProvider.reasoningLevels` 不构成并发串档；`SubAgentEventBus` 名额记账自洽）。
- §六 历史项核实表标明「已修复」的旧条目同样不要再修。

## 六、工具环境注意（本容器）

- **没有 `rg`**，`search` 工具不可用 → 用 `find` + `grep`；BusyBox `grep --include` 会**静默失败**（假阴性），一律 `find ... -name '*.kt' | xargs grep -n 'pat'`。
- Bash 命令里不要用 `$(...)`、子 shell `( )`、`>` 输出重定向、`|&`（会被安全防护拒绝）；管道 `|` 与 `&&` 可用。
- **detekt 不能整目录跑**（会崩 `AsyncExecutionService.getService must not return null`），必须逐文件/小批。
- **子代理超时风险**：本次派 3 个子代理审增量 diff，全部 `First content timeout after 300000ms` 失败且无产出。大 diff 审计建议**主线程自己按文件读**，或把子代理范围切得更小（单文件/单主题）。

## 七、本文件未覆盖

批次 2~5 的具体落点未在此展开，全部在档案对应段落（§二/§三/§四/§七/§九），行号动手前需重新核实。
