# 残留误导与踩坑 · 一键矫正清单（2026-11-14）

> 背景：一次技术方案调研中踩到的坑，以及排查中发现的一批**「东西早已不存在、但文档/索引仍当它存在」**的残留。
> 用途：**随下一个版本一并处理**。本文档是事实台账 + 矫正清单 + 防复发机制；执行脚本见 `scripts/check-doc-consistency.py`。
> 全量证据来自 2026-11-14 对仓库的实际扫描（命令与结果见各条目「证据」）。
>
> **执行状态（2026-11-15 更新）**：A1/A2/A3+A4、B1、B2 已执行完毕（`test-builds.md` 改写为 `deliverables.md` 并同步侧栏、
> 总览、能力地图、README 中英；REA 侧栏死链已修；总览补「程序分析（REA）」与「交付物」行）；D1 校验脚本已落地并
> 接入 `scripts/verify-all.sh`；文档站 `npm run build` 实测通过（死链会让构建失败，此处 exit=0 即证明已清）。
> **B3（总览缺 5 项登记）经复核：悬浮球/定时任务/记忆图谱已由 `background-run.md`、`memory.md` 两个既有页承接，
> 总览口径是「核心工作流 + 设置入口」，不再另造新行以免与既有行重复**。**B5（英文站 `/en/` 死链）确认为误报**。
> 本文档与 `residue-correction-checklist-2026-11-14.md` 同主题：后者的结论（含 B3、B5）已逐条核实并并入本文件，
> 但该文件由另一会话创建，**未擅自删除**——如需合并为一份，请确认后再删，避免「笔记本身成为新残留」。

## 〇、分类总览

| 类 | 含义 | 条目 |
| --- | --- | --- |
| **A. 本就不存在、却仍有残留误导** | 功能已删/改名，文档与索引未同步，读者/AI 会被带去不存在的页面 | A1 A2 A3 A4 |
| **B. 本应有、却缺失** | 有实际功能，但文档/索引/入口缺失，用户与 AI 都发现不了 | B1 B2 B3 B4（B5 为已排除的误报） |
| **C. 本次踩坑（认知/流程）** | 不是文件问题，是「怎么会被误导」的方法论教训 | C1 C2 C3 |
| **D. 防复发** | 让上述问题下次在 CI 就被拦下 | D1（新增校验脚本 + CI 挂载） |

---

## A. 本就不存在、却仍有残留误导（必须删/改）

### A1 「测试包」功能已于 `f7a4e1f` 整体删除，但文档与索引仍描述它

**事实（已核实）**

- 删除提交：`f7a4e1f`（2026-10-21）「移除测试包入口与功能（与交付物页重复，用户决断保留交付物；入口/路由/strings/`feature/installer` 全清）」。
- 现状：`app/src/main/java/com/aicode/feature/installer/` **目录不存在**；`TestBuildListScreen.kt` / `TestBuildListViewModel.kt` 已删；`strings.xml` 无 `test_build*` 残留。
- 替代：安装链路仍在，走 `core/util/ApkInstaller.kt`（未知来源授权 + FileProvider + `ACTION_VIEW`），入口为聊天 APK 附件 / 文件树长按 / 交付物页。
- 当时**只改了 `README.md` 一处**（上游底座标题），**docs-site 完全未同步**。

**残留清单（逐条待改）**

| # | 文件:行 | 现状 | 处置 |
| --- | --- | --- | --- |
| A1.1 | `docs-site/docs/guide/test-builds.md` | **整页描述已删功能** | **删除该文件**（或改写成「安装与打开构建产物」，指向 `ApkInstaller` + 交付物页） |
| A1.2 | `docs-site/.vitepress/config.ts:125` | 侧栏项「测试包与一键安装」→ `/guide/test-builds` | 删除该侧栏项 |
| A1.3 | `docs-site/docs/guide/overview.md:26` | 功能总览表格行「测试包与一键安装…（1.12.0 起）」 | 删除该行（或替换为「安装与打开构建产物」行） |
| A1.4 | `docs-site/docs/guide/capabilities.md:50` | 能力条目「测试包与一键安装——…点一下装到手机」 | 删除该条（能力已并入交付物页，见 A1.5） |
| A1.5 | `docs-site/docs/guide/capabilities.md:70` | 「文件外送与打开——聊天文件卡片、**测试包**、存储产物…」 | 把「测试包」改为「交付物条目」（功能名已不存在） |
| A1.6 | `docs-site/docs/guide/capabilities.md:92` | 组合菜谱「…→ 侧边栏「**测试包**」装到手机试用 →…」 | 把「侧边栏「测试包」」改为「交付物页」 |
| A1.7 | `README.md:90` | 「**文件外送** — 聊天文件、**测试包**、存储产物统一长按菜单…」 | 把「测试包」改为「交付物」 |
| A1.8 | `README.en.md:86` | "…chat files, **test builds** and storage artifacts…" | 把 "test builds" 改为 "deliverables" |
| A1.9 | `app/src/main/assets/docs/`（构建产物） | 由 `syncAiDocs` 从 docs-site 生成 | 无需手改：删 A1.1 后自动消失 |

**不误删提醒**：`app/src/main/java/com/aicode/core/ui/BetaWatermark.kt:22` 的「测试包」指 **beta 构建包**（应用语义，与已删功能无关），**不得改**。

**证据**

- `git show --stat f7a4e1f`（`feature/installer/` 下 `TestBuildListViewModel.kt -85`、`TestBuildListScreen.kt -221`）。
- `ls app/src/main/java/com/aicode/feature/installer` → No such file。
- `grep -rn "test_build\|testBuild" app/src/main/res/values*/strings.xml` → 空。

---

### A2 「程序分析（REA）」改名后，侧栏留死链、新页反成孤儿

**事实（已核实）**

- 同一提交 `7173cd6`（v40）中：`docs-site/docs/guide/reverse-engineering.md` **被删除**、`program-analysis.md` **被新增**（「清理内置文档『逆向/反代』字样」——合规改名）。
- 但 `config.ts` **侧栏项未跟着改**，仍指向 `/guide/reverse-engineering`。

**后果**：VitePress 默认 `ignoreDeadLinks: false`（config 未覆盖）→ **构建文档站时该死链会使构建失败**；`docs-deploy.yml` 只在打 tag 时部署，平日 CI 因 `paths-ignore: docs-site/**` 不跑，**问题被推迟到发版当天才引爆**。

**残留清单**

| # | 文件:行 | 现状 | 处置 |
| --- | --- | --- | --- |
| A2.1 | `docs-site/.vitepress/config.ts:122` | `{ text: '逆向分析（REA）', link: '/guide/reverse-engineering' }` | 改为 `{ text: '程序分析（REA）', link: '/guide/program-analysis' }` |
| A2.2 | 同上（连带） | `program-analysis.md` 存在但**侧栏无入口**（孤儿文档，全站不可达） | 由 A2.1 一并解决 |

**证据**

- `git log --diff-filter=D/A` 显示两文件在同一提交 `7173cd6` 删除/新增。
- 全量内部链接校验：`/guide/reverse-engineering` 目标不存在（其余 0 处失效）。

---

### A3 陈旧文档缺「终态」登记，容易被再次当现状引用

**事实**：`test-builds.md` 这类文件被删除后无任何标记；AI（含我自己）读到它就直接当成现状，正是本次踩坑的**直接诱因**。

**处置**：随 A1 处理；并在 D1 校验脚本里对「已删功能名」建立黑名单（见下），防止旧名再次渗入。

---

### A4 `ai-config/notes/` 历史笔记中的旧功能名（**不改**，仅登记）

**事实**：`ai-config/memory/handoff-2026-10-17.md`、`ai-config/notes/handoff-2026-10-17.md`、`acceptance-feedback-2026-10-16.md`、`file-actions-plan.md`、`release-pending-verification.md`、`roadmap-final.md` 等多处提到「测试包」。

**处置**：**有意保留**——这些是**历史交接档案**，记录当时的事实，改写反而毁证据。仅在此登记，避免下次又被误当现状（读笔记时注意其日期与「现状以代码为准」原则）。

---

## B. 本应有、却缺失（补齐）

### B1 `program-analysis.md` 未登记进功能总览与侧栏（=A2.2）

已在 A2 处置（侧栏修正后即可达）。**另**：`overview.md` 的功能总览表格中，也**完全没有**「程序分析」这一行（已核实 `grep` 为空）——建议补一行，与侧栏对齐。

### B2 「交付物页」缺独立用户文档页

**事实**：交付物是**当前承担「安装 APK / 拿文件到手机」职责**的主要入口（A1 删除后尤其如此），但 `docs-site/docs/guide/` 下**无对应文档页**，仅在 `capabilities.md:18` 有一段描述。

**处置**：新增 `docs-site/docs/guide/deliverables.md`（讲清：跨会话聚合范围、按类型分组、点行/长按各能做什么、彻底删除的连带影响、文件何时才导出到手机），并登记进 `config.ts` 侧栏与 `overview.md`。

### B3 `overview.md` 功能总览缺 5 项已上线能力的登记

**事实（已核实，`grep` 计数为 0）**：`docs-site/docs/guide/overview.md` 的「核心工作流与主界面」表中，下列**已上线**能力无独立行：

| 缺失项 | 现状 | 备注 |
| --- | --- | --- |
| 交付物页 | 仅 `capabilities.md:18` 有；`overview.md` 无 | A1 之后它是「装 APK / 拿文件到手机」的主入口，更该登记 |
| 悬浮球（精灵球） | `capabilities.md` 有 2 处；`overview.md` 无 | |
| 记忆图谱 | `capabilities.md` 有 3 处；`overview.md` 无 | |
| 定时任务 / 定时 Plan | `capabilities.md` 有 3 处；`overview.md` 无 | |
| 会话回收站 | `overview.md` 与 `capabilities.md` 均无 | 属界面维护类，是否入图按〈能力感知〉口径定 |

**处置**：`overview.md` 开头自述口径为「主界面核心工作流与设置页各功能入口」，故这几项**应当登记**（前 4 项确实有独立文档落点）。**但收录口径需用户确认**（见 §五 Q6），确认后补齐。

> 与 `capabilities.md` 的分工：能力地图收「与 AI 协作/发现相关」的能力；功能总览收「主界面核心工作流 + 设置入口」。两者口径不同，缺登记需分别判断，不能一概而论。

### B4 文档一致性缺少自动化校验

**事实**：侧栏死链（A2）与孤儿文档、三方索引（config / overview / capabilities）不一致，全靠人工发现；CI 因 `paths-ignore: docs-site/**` 平日不校验。

**处置**：见 D1。

---

### B5（**误报，已排除**）「英文站 `/en/` 死链」

有一份并行清单提出 `docs-site/docs/en/guide/quick-start.md:24` 的 `[homepage](/en/)` 是死链。

**核实结论：不成立。** `docs-site/docs/en/index.md` **存在**（2648 字节），`/en/` 正常解析到该页；我对 `docs-site/docs/**` 做了全量内部链接校验（含 `en/`），**失效链接 0 处**。

**登记原因**：这正是 C3「孤儿/幽灵文档」的反面教材——**看上去像死链、其实不是**。凡此类断言，必须跑一次真实校验再动手，凭肉眼扫 `link:` 文本容易误判（`/en/` 无 `.md` 后缀，但目录下有 `index.md`）。本台账以此条留痕，避免下次又被同类误报牵着走。

---

## C. 本次踩坑：认知与流程教训

### C1 「文档不是证据」——被 `test-builds.md` 直接带偏

我在方案里写「产物被『测试包』页扫到」完全照抄了 `docs-site/docs/guide/test-builds.md`，**没有先验证该页对应当前代码**。该页描述的功能已删除 3 个月。

**教训固化**：**任何「某功能如何工作」的断言，必须先落到代码（`file:行号`）**；文档只用于「应该长什么样」的线索，不能作为「现在是什么样」的证据。与 CLAUDE.md〈Issue 审查〉「issue 作者的描述不算证据」同源，本次是**文档版**的同一条纪律。

### C2 删除功能时，只改了代码没改文档资产（流程缺口）

`f7a4e1f` 清得很干净：入口、路由、strings、`feature/installer` 全删——**唯独漏了 docs-site**（且 CLAUDE.md〈资产同步〉只强调「新增/变化必须同步文档」，**没有一条讲「删除时要同步清理文档与索引」**）。

**教训固化**：删除与新增同等重要。删除功能时必须一并清理：① 文档正文；② 侧栏 `config.ts`；③ `overview.md` 索引；④ `capabilities.md` 能力条目；⑤ README 中英。→ 落为 D1 校验项 + 建议补进 CLAUDE.md〈资产同步〉。

### C3 「孤儿文档」与「幽灵文档」是同一枚硬币的两面

- **幽灵文档**（A1）：正文还在、功能没了 → 误导读者。
- **孤儿文档**（A2）：正文在、索引没了 → 读者找不到。

两者都因「正文与索引分开维护、无一致性检查」而长期潜伏。D1 一并覆盖。

---

## D. 防复发机制（建议随本批一并落地）

### D1 新增 `scripts/check-doc-consistency.py`，挂进 CI 与 `verify-all.sh`

**校验项**（零网络、纯本地、秒级）

1. **侧栏死链**：`config.ts` 中每个 `link:` 目标 `.md` 必须存在。
2. **孤儿文档**：`docs-site/docs/{guide,advanced}/*.md`（排除 `index.md`）必须被侧栏登记。
3. **三方一致**：`overview.md` / `capabilities.md` 中被链接的 `/guide/*`（及 `/advanced/*`）页必须存在。
4. **中英成对**：`values/strings.xml` 与 `values-en/strings.xml` 的 `name` 集合必须一致（现有资产同步硬规则，目前无自动校验）。
5. **已删功能名黑名单**：在 `docs-site/docs/**`、`README*.md`、`app/src/main/res/values*/strings.xml` 中搜 `测试包`（指该已删功能）/ `reverse-engineering` 等，命中即报错；黑名单集中配置在脚本内，便于以后追加。

**为何值得**：A2 那类死链**当前会让文档站在发版当天构建失败**，代价高；D1 把发现时机从「发版当天」提前到「每次 PR」。

**挂载点**（需并行改两处）

- `.github/workflows/ci.yml`：新增一步 `python3 scripts/check-doc-consistency.py`。**注意**：`ci.yml` 现有 `paths-ignore` 含 `docs-site/**` 与 `**.md`，纯文档改动不会触发 CI——需**同时加一个 docs 专属的轻量 workflow**（或把该步也挂到 `docs-deploy.yml` 的构建前），否则本校验对纯文档改动形同虚设。
- `scripts/verify-all.sh`：新增一步（默认跑，`--no-docs` 可跳过），本地 push 前自查。

### D2 建议补进 CLAUDE.md〈资产同步〉（需用户确认后落笔）

把「删除」写进硬规则，例如：

> **删除功能 → 必须同批清理**：对应文档页、`config.ts` 侧栏项、`overview.md` 索引、`capabilities.md` 能力条目、`README`/`README.en`；并跑 `python3 scripts/check-doc-consistency.py`。

### D3 建议：`docs-site` 构建纳入 CI（可选）

校验脚本覆盖了「死链/孤儿」，但**渲染层错误**（组件语法、frontmatter）仍只有 `vitepress build` 能发现。可考虑在 CI 加一步 `cd docs-site && npm ci && npm run build`（约 1–2 分钟）。**权衡**：CI 时间 +1–2 分钟，换「发版当天不炸」；建议采纳，但由用户定。

---

## 三、执行顺序建议（随下一版本一并处理）

1. **A 类删除/改写**（8 处编辑 + 删 1 页）：纯文档，风险为零。
2. **B2 新增交付物文档页**：纯文档。
3. **D1 校验脚本 + 挂载**：新增脚本与 CI 步骤。
4. **D2/D3**：需用户拍板的规则与 CI 取舍。
5. 跑 `python3 scripts/check-doc-consistency.py` 应为 0 报错；`sh scripts/verify-all.sh --no-tests` 绿灯。

**验证方式**：本次改动多为文档与脚本，按 CLAUDE.md 属「纯 `.md` / 脚本」范畴，**免冒烟编译**；但 D1 若改 `ci.yml`，则该 workflow 属构建配置改动，建议下一次 push 后看一眼 CI 结果确认。

## 四、本次另一条独立踩坑（与残留无关，供记录）

**一次技术验证的方法论**：曾据「容器文档」判断某工具链可行，但真正定论靠的是**在容器内手工跑通全链路**（当时的例子：aapt2→javac→d8→zipalign→apksigner 打出 12.7 KB 签名 APK，v2/v3 验证通过）。**工具链能力要用「跑一次」证明，不能用「文档说」推断**——与 C1 同一纪律。（该次调研对应的方案笔记已按用户要求移除，此处仅保留通用教训。）

## 五、待你定夺

- **Q1 `test-builds.md` 的处置**：① **删除**（推荐，功能已不存在，保留只会继续误导）；② 改写成「安装与打开 APK」说明页（讲清现在经 `ApkInstaller` 怎么装自己构建的包），文件名改 `install-apk.md`。
- **Q2 `overview.md` 收录口径**：① 按现有自述口径补齐 5 项缺登记（推荐，前 4 项确有独立文档落点）；② 只补「交付物页」；③ 维持现状。
- **Q3 是否落地 D1 校验脚本并挂 CI**（推荐：一次投入、长期防复发）。
- **Q4 D2（把「删除功能须同步清文档」写进 CLAUDE.md〈资产同步〉）是否同意。**
- **Q5 D3（把 `docs-site` 构建纳入 CI，+1~2 分钟）是否采纳。**
- **Q6 本批矫正的发布方式**：① 单独先发（按 CLAUDE.md，纯文档可直接发 `x.y.Z`；含脚本护栏建议 RC 观察一次）；② 攒进下个 RC 一并发。**建议独立先发**——它风险为零且能立刻消除误导。

## 六、并行产生的重复笔记（需合并，避免「笔记本身成为新残留」）

本次同时存在两份覆盖同一主题的笔记：

- 本文件 `residual-misleading-cleanup-plan.md`（我整理，含可执行脚本与其验证）。
- `residue-correction-checklist-2026-11-14.md`（**非我创建**，2026-11-14 11:26 出现；内容与本文重叠，补充了「B3 总览缺登记」与「B5 英文站死链」两条，后者经核实为误报）。

**处置建议**：合并为一份（保留本文件，因其配套可执行脚本与验证结论），删除另一份。**合并前需你确认**——我不会擅自删除非自己创建的文件。
