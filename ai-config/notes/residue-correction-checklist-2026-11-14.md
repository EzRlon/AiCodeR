# 残留与缺失矫正清单（一键处理）

> 生成：2026-11-14。来源：封装台方案调研中暴露的一批「本来就有 / 本来就没有 / 已删但留残」。
> 用途：**在下一个版本（建议 RC）里一次性收拾干净**，不必为新功能单开批次。
> 每条都带「证据（文件:行号 或 提交）」与「动作」；凡我尚未直接核实到的，一律标注「待核实」，不臆断。

## 一、事实分类（先分清三种，处置方式不同）

| 类别 | 含义 | 处置方向 |
| --- | --- | --- |
| **A. 已删但留残** | 功能代码已删除，文档 / 索引 / 提示词 / README 仍描述它 | 删残留，或补「已下线」说明 |
| **B. 改名未同步** | 文档页改名 / 功能改名，引用方未跟着改 | 改引用，补齐缺失的索引 |
| **C. 本该有却缺** | 已上线功能未登记进总览 / 能力件 | 补登记 |

---

## 二、A 类：已删但留残 —— 「测试包」功能

**事实（已核实）**：提交 `f7a4e1f`（2026-10-21）「移除测试包入口与功能（与交付物页重复，用户决断保留交付物；入口/路由/strings/`feature/installer` 全清）」。`git show --stat` 确认删除 `TestBuildListViewModel.kt`（85 行）、`TestBuildListScreen.kt`（221 行）等；`app/src/main/java/com/aicode/feature/installer/` 目录**已不存在**。
**但**：`docs-site/docs/guide/test-builds.md` **仍在**，且被三处索引引用——文档站会给用户一个「点进去是空功能」的页面。

### A1. `docs-site/.vitepress/config.ts:125` — 侧栏条目
```ts
{ text: '测试包与一键安装', link: '/guide/test-builds' },
```
→ **动作**：删除该行。该位置的前后是「界面标注」与「Web 预览」，删掉不影响层级。

### A2. `docs-site/docs/guide/overview.md:26` — 功能总览表一行
```
| 测试包与一键安装 | 自动扫描工作区 APK，一键唤起系统安装器（1.12.0 起）→ [文档](/guide/test-builds) |
```
→ **动作**：删除该行（总览表应收录现存功能）。

### A3. `docs-site/docs/guide/capabilities.md` — 三处
- `:50` 独立条目「**测试包与一键安装**——工作区里构建出的 APK 集中列出，点一下装到手机。→ [测试包与一键安装](/guide/test-builds)」
- `:70` 「文件外送与打开」条目内的并列词「聊天文件卡片、**测试包**、存储产物……」
- `:92` 组合菜谱第 6 条「**真机验证闭环**——构建出 APK → 侧边栏「**测试包**」装到手机试用 → ……」

→ **动作**：`:50` 删除该条目；`:70` 去掉「测试包、」三字；`:92` 把入口改写为**现有可行路径**（文件树长按 → 打开，或经交付物页），不要留已删入口。

### A4. `docs-site/docs/guide/test-builds.md` — 陈旧文档本体
→ **动作（需你定夺，二选一）**：
- **① 删除该文件**（推荐）：功能已不存在，保留只会误导；`.vitepress/config.ts` 与两处索引同步删。
- **② 改写为「安装 APK」说明页**：讲清现在怎么装自己构建的 APK（文件树长按 → 打开 / 交付物页 → 安装，经 `ApkInstaller` 的「允许安装未知应用」引导），保留一个可用入口。文件名建议改 `install-apk.md`。

### A5. `README.md:90` 与 `README.en.md:84` — 并列词残留
- `README.md:90`：「**文件外送** — 聊天文件、**测试包**、存储产物统一长按菜单……」
- `README.en.md:84`：「**File handoff menu** — one long-press menu for chat files, **test builds** and storage artifacts……」
→ **动作**：去掉「测试包 / test builds」并列词（README 其余部分已正确指向交付物页，无其它残留）。

### A6. `ai-config/` 历史笔记 — 建议不动
`ai-config/notes/*.md`、`ai-config/memory/*.md` 多处提到「测试包」页（如 `handoff-2026-10-17.md`、`file-actions-plan.md`）。这些是**历史归档与交接记录**，记录当时事实，**不应改写**（改了反而失真）。
→ **动作**：不动。仅在最新交接笔记里点一句「测试包页已下线，改为 `ApkInstaller` + 文件树/交付物入口」，供后续会话不再引用。

### A7. `docs-site/docs/guide/capabilities.md:92` 组合菜谱连带
见 A3 第 3 项。注意「真机验证闭环」这条菜谱本身仍有价值（构建 APK → 圈问题 → 修 → 回退），只是入口名要换。

---

## 三、B 类：改名未同步 —— REA 文档页

**事实（已核实）**：提交 `7173cd6`「v40 补漏：清理内置文档『逆向/反代』字样」把 `docs-site/docs/guide/reverse-engineering.md` **删除**（`git log --diff-filter=D` 确认 `D` 状态），同提交新增 `program-analysis.md`。但侧栏没跟着改。

### B1. `docs-site/.vitepress/config.ts:122` — 侧栏指向已删页
```ts
{ text: '逆向分析（REA）', link: '/guide/reverse-engineering' },   // ← 死链
```
实际文件是 `docs-site/docs/guide/program-analysis.md`（标题「程序分析（REA）」）。

**影响**：侧栏点这条 → **404**（文档站死链）。全站扫描仅此一条死链 + `en/guide/quick-start.md:24` 的 `/en/` 一条（见 B3）。

→ **动作**：改为 `{ text: '程序分析（REA）', link: '/guide/program-analysis' }`（文案与 `capabilities.md:60` 已有的「REA 程序分析」对齐）。

### B2. `program-analysis.md` 未进总览索引
`docs-site/docs/guide/overview.md` 的功能表里没有「程序分析（REA）」行（已核实 `grep` 无命中）。
→ **动作**：在总览表补一行，指向 `/guide/program-analysis`（归 A 类「本该有却缺」亦可，见 C 类）。

### B3. 英文站死链
`docs-site/docs/en/guide/quick-start.md:24` 引用了 `/en/`（无对应页）。
→ **动作**：改为 `/en/` 实际落点（`/en/guide/quick-start` 或站点首页），或删该链接。**待核实**：需人工确认该行语境再定改法（我未逐字通读该英文页）。

---

## 四、C 类：本该有却缺 —— 总览索引缺登记

**事实（已核实）**：`docs-site/docs/guide/overview.md` 是「使用手册快速导览索引」，但下列**已上线功能未登记**（`grep` 计数为 0）：

| 缺失项 | 现状证据 | 建议 |
| --- | --- | --- |
| 交付物页 | `overview.md` 无「交付物」；README:88 有 | 补一行（跨会话聚合安装包与文件） |
| 悬浮球（精灵球） | `overview.md` 无；`capabilities.md` 有 2 处 | 补一行（归「核心工作流」或「自动化」） |
| 记忆图谱 | `overview.md` 无；`capabilities.md` 有 3 处 | 补一行 |
| 定时任务 / 定时 Plan | `overview.md` 无；`capabilities.md` 有 3 处 | 补一行 |
| 会话回收站 | 两处均无（`capabilities.md` 亦缺） | 视口径决定：界面维护类可不入能力地图，但总览应有 |

→ **动作**：按项目〈能力感知〉纪律（新功能上线须同步 `docs-site/docs/guide/capabilities.md` + `overview.md`）补齐。**待核实**：这几项是否为「有意不收」（总览页声明以「核心工作流 + 设置入口」为口径，部分可在设置页已覆盖）——建议你确认口径，我按口径补。

---

## 五、D 类：本次调研踩到的坑（供流程矫正）

| # | 坑 | 影响 | 矫正 |
| --- | --- | --- | --- |
| D1 | **文档站索引与文档页双向失配**：侧栏指向已删页（B1）、存在页却无侧栏项（B2/早先 `program-analysis`） | 用户看到 404 或找不到页 | 在 `scripts/` 增加一个**文档一致性校验**（见 §六），纳入 `verify-all.sh` |
| D2 | **功能删除只清代码，不清文档**：`f7a4e1f` 清了 `feature/installer` 与 strings，漏了 `docs-site` 三处 + README 两处 | 陈旧文档误导（本次方案就被 `test-builds.md` 误导） | 删除功能时把「docs-site 三处索引 + README 中英」列入删除清单；或靠 D1 的校验脚本兜底 |
| D3 | **能力感知件不同步**：`overview.md` 缺 5 项已上线功能 | 新会话 AI 找不到入口 | 校验脚本加一条：`capabilities.md` 出现的 `→ [xxx](/guide/yyy)` 链接，其目标页必须存在且在侧栏可达 |
| D4 | **调研信源优先级**：我最初以 `docs-site/docs/guide/test-builds.md` 为准确认「测试包页存在」，实为陈旧文档 | 方案表述出错（已当轮更正） | 结论一律以**代码 + `git log`** 为准，文档仅作线索；已在方案笔记中记录该口径 |

---

## 六、建议的一键做法（可并入下个版本）

### 立即修（纯文档，风险极低，可直提 `main`）
1. `config.ts:125` 删测试包侧栏项（A1）
2. `config.ts:122` 修正 REA 死链（B1）
3. `overview.md:26` 删测试包行（A2）；补程序分析行（B2/C）
4. `capabilities.md` 三处改口径（A3）
5. `README.md:90` / `README.en.md:84` 去并列词（A5）
6. `test-builds.md` 删除或改写（A4，**待你定夺**）
7. `en/guide/quick-start.md:24` 死链修正（B3，**待核实语境**）
8. 总览补 5 项缺登记（C，**待确认口径**）

### 顺手立个护栏（防复发）
9. 新增 `scripts/check_docs_links.py`：
   - 校验 `docs-site/docs/**/*.md` 内部链接目标存在；
   - 校验 `config.ts` 侧栏每一项都对应存在的 `.md`；
   - 校验「有 `.md` 但无侧栏项」的孤儿页清单（输出警告）。
   接入 `scripts/verify-all.sh`，与 `check_migrations.py` 并列在推送前跑。CI 亦可挂。

### 与封装台新功能解耦
以上 1–9 **不依赖**封装台方案落地，可先单独发一版（按项目规则：纯文档 → 可直接发正式 `x.y.Z`；含脚本护栏 → 建议 RC 观察一次）。

---

## 七、待你定夺

- **Q1** A4：`test-builds.md` 删除，还是改写成「安装 APK」说明页（保住一个可用入口）？
- **Q2** C 类：`overview.md` 的收录口径是什么（是否「核心工作流 + 设置入口」为准）？按此补齐 5 项，还是只补部分？
- **Q3** D2/D1：是否同意新增 `check_docs_links.py` 并纳入 `verify-all.sh`（一次性投入，长期防复发）？
- **Q4** 这批矫正：单独先发一版，还是与封装台新功能合并进同一个 RC？
