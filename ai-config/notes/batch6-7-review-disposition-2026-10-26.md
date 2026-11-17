# 批次 6/7 审查处置（2026-10-26）

> 交付包 vCode154。本批审查**由主线程自行完成**——派出的三个 qa 子代理（批次6/7 全量、脚本+schema 复核、性能功耗维度）均在 15–35 分钟无任何回复，判断为当前环境子代理链路异常（非审查对象问题）。为不阻塞交付，主线程按同一份核查清单逐项自审，结论如下。

## 一、已修复（本批入库）

| # | 问题 | 位置 | 分级 | 提交 |
|---|---|---|---|---|
| 1 | **KDoc 里 `notes/*.md` 触发 Kotlin 嵌套块注释**：块注释支持嵌套，`/*` 开了一层新注释，把后面 `ProjectNotesSource`、companion、类闭合全部吞掉，编译器报 40 个 companion 常量 Unresolved | `SystemPromptProvider.kt:551` | P0（编译阻断） | `2ae14a2` |
| 2 | **单元格含 `\|` 切断表格列**：`parseRows` 按 `\|` 分列，标题/备注含 `\|` 时该行被截断（备注丢失、列错位），后续 advance 拿到脏数据 | `TaskLedgerTool.kt` parseRows / addTask / advanceTask | P1 | `09ca2a6` |
| 3 | **advance 按整行文本相等定位，静默失败**：账本被外部编辑过（行尾空白、手工改备注）时匹配不到原行，但工具仍返回成功——文件没变、下次 list 还是旧状态 | `TaskLedgerTool.kt` advanceTask | P1 | `09ca2a6` |
| 4 | **自检脚本 `set -e` 风险**：`apk file-size` 失败会中止整脚本；基线包损坏时在算术处中止；自动找基线会选中待检包自身（自己比自己 → 差分恒 0 假通过） | `release-selfcheck.sh` | P1 | `7a63131` |

## 二、逐项核查结论（无问题项）

**TaskLedgerTool**
- 状态机：`statusRank <= currentRank` 拦截，确认只能前进不能回退/原地。✅
- 多匹配保护：`matches.size > 1` 报 AMBIGUOUS_TASK。子串匹配在「账本只有一条含该子串」时会命中它——这是刻意的宽松匹配（AI 常给简短标题），配合多匹配拦截可接受。✅
- `FileAccessProvider` 用法：`isFile` / `readFile` / `writeFile(path, content, overwrite=true)` 签名与语义已对照接口定义核实（`FileAccessProvider.kt:41/55/64`）。✅
- `readOrInit` 在文件不存在时返回表头而非抛异常，`readFile` 的 NoSuchFileException 不会被触发。✅
- IO 线程：工具经 `executeWithContext` 在协程中执行，非主线程。✅

**ProjectNotesSource**
- 主线程扫盘：调用点 `StatefulAgentWorkflow.kt:631` 包在 `withContext(Dispatchers.IO)` 内，已核实。✅
- 惰性读取：`file.useLines { seq.firstOrNull { it.startsWith("# ") } }` 是惰性序列，找到标题即停止，不会读全文。✅
- 截断确定性：`files.sortedBy { it.name }.take(30)`，排序后才截断，结果稳定（不随文件系统顺序抖动）。✅
- 会话内不刷新：`SourceCacheKey(sessionId, projectRoot)` 缓存，同一会话笔记新增/修改不重扫——刻意设计（避免每轮重扫 + 保 KV Cache 前缀稳定），符合项目「缓存友好」纪律。✅
- 缓存上限：超 32 条整体 clear，与会话数上限匹配（同时活跃会话远小于 32），不构成抖动源。✅

**SystemPromptProvider 变量渲染**
- `{{AICODE_PROJECT_NOTES}}` 为空时：`renderVariables` 替换为空串，随后 `collapseBlankLines`（`SystemPromptProvider.kt:346`）把连续 3+ 换行压成 2 个，不留空段落。✅
- 位置：`99-runtime-context.md:16`，在「任务书模板」之后、「任务账本」之前，属运行期上下文区（非静态基线核心），清单变化只影响其后的动态段，KV Cache 影响可控。✅

**schema 72.json**
- `failure_events` 的 `createSql` 与 `Migration71To72.execSQL`（`IdempotentMigrations.kt:180`）**逐字一致**：id INTEGER PK AUTOINCREMENT NOT NULL / sessionId TEXT / kind TEXT NOT NULL / source TEXT / detail TEXT / createdAt INTEGER NOT NULL。✅
- 索引：`index_failure_events_createdAt`、`index_failure_events_kind` 两侧一致。✅
- 71.json 存在、72.json 为新增（不覆盖）。✅

**release-selfcheck.sh**
- `skipped` / `pkg_total` / `pkg_missing` 赋值顺序：`pkg_missing=0` 在循环前初始化；`skipped` 在 echo 前赋值。✅
- `comm -23` 两个输入均经 `sort -u`。✅
- 管道内 `while read` 在子 shell 中，但该段无循环内累加依赖（只 echo）。✅
- 命令注入面：`$1`/`$2` 全部以 `"$VAR"` 引用传参，无 eval、无未引用展开。✅

## 三、记录待排期（P2）

1. **dex 引用数 64467 / 65536**（余量 1069）：不是本批引入，是存量逼近上限。再引入大依赖会构建失败。建议后续专项：裁依赖或配 multidex 策略。
2. **`versionName` 带 `-dirty`**：因工作区有其他会话未提交改动（`strings.xml` 的 `engine_switch_busy`、`ai-config/notes/` 未跟踪笔记）。交付包本身对应提交 `7a63131`，但 `git describe` 见脏即标 dirty。属环境状态，非缺陷。
3. **自检脚本 keep 核对只覆盖「点名类 + 包级通配」**：`* implements` / `* extends` 形式的通配规则（23 条）无法静态验证，靠人工确认。
4. **TaskLedgerTool 无单元测试**：状态机与表格解析属纯逻辑，适合补 JUnit 护栏（参考批次 3 的评测集做法）。

## 四、发版记录

- 提交链：`2ae14a2`（批次6）→ `dbd9772`（批次7）→ `09ca2a6`（taskLedger 修复）→ `7a63131`（脚本加固）
- 正式包：`assembleArmsoloRelease --no-configuration-cache`，9m52s（72 up-to-date，R8 全量重跑）
- versionCode **154** == `git rev-list --count HEAD`(153) + 1 ✅
- 自检：FAIL 0 / WARN 2（WARN 见上方 P2 第 1、2 条）
- 交付：`~/workspace/许愿码-正式版-20261026-vCode154-增强计划批次6-7.apk`
- detekt 前置扫描：改动文件 0 findings
