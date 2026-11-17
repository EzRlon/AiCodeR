---
name: code-intel
description: 探索/修改本项目代码前必读：用 repo-map/ctags/ast-grep/callchain 四件套替代盲搜，省 token、高准确、拿调用链。首次使用需项目已建 tags 索引；环境未装时先跑「代码智能」场景。
---

# 代码智能四件套

当前项目已可用代码索引工具链。**任何代码探索、定位、改动前的结构调查，优先按下面顺序用工具，禁止直接全文乱搜或反复读大文件。**

## 环境自检（首次使用）

1. 工具链是否可用：`command -v ctags readtags ast-grep`。缺组件时运行安装场景：`sh ~/.aicode/provision.sh --install codeintel`（幂等，失败降级不阻塞；缺 npm 时先装 Node.js 场景再重跑）。
2. 当前项目根是否有 `tags` 索引：没有就先建（见「维护纪律」）。

## 工具与命令

### 1. repo-map —— 开工先建全貌
```sh
sh ~/.aicode/lib/tools/repo-map.sh
```
输出：各模块类型/方法规模 + 成员数 Top30 类。大型任务、跨模块改动、新会话接手时**先跑它**，避免盲目读文件。

### 2. ctags —— 找定义、列结构
```sh
readtags -e 符号名                                   # 找定义（文件路径）
awk -F'\t' '$4=="method" && /\tclass:全限定类名$/' tags   # 列某类全部成员（在项目根执行）
```
索引文件：项目根的 `tags`。

### 3. callchain —— 调用链（谁调它 / 它调谁）
```sh
sh ~/.aicode/lib/tools/callchain.sh callers 符号名 [目录]     # 调用点（三种形态已去重）
sh ~/.aicode/lib/tools/callchain.sh callees 符号名            # 函数体内调用的项目符号
```
callees 结果含与项目符号重名的标准库方法（append/copy 等），需甄别；只反映项目内符号，外部 SDK 调用不列。

### 4. ast-grep —— 结构化搜索与改写
```sh
ast-grep run -p '$X!!' -l kotlin <源码目录>                    # 按语法结构搜索，无字符串误报
ast-grep run -p '模式' -r '替换' -l kotlin <目录>              # 结构化批量改写（先 run 确认再 -r/--rewrite）
```
通配：`$X`=单节点，`$$$A`=多节点。方法调用要写 `$OBJ.method($$$A)` 或 `$OBJ?.method($$$A)`，裸 `method(...)` 只匹配隐式 this 调用。

## 探索 SOP（顺序执行）

1. **建全貌**：repo-map → 锁定目标模块与核心类。
2. **下钻**：ctags 列类成员 → readtags 找定义。
3. **拉链路**：callchain callers/callees 搞清上下游。
4. **精读**：只读定义文件与调用点附近，不再通读。
5. **结构匹配**：找用法/防回归用 ast-grep，不用裸 grep 猜正则。

## 维护纪律

- **改完代码必须重建索引**（在项目根执行）：
  `ctags -R --languages=Kotlin,Java --exclude=build --fields=+Kn -o tags .`
- 大批次改动收尾时，用 ast-grep 做回归检查（如搜 `$X!!`、未处理 TODO 模式）。

## 环境坑（已验证）

- readtags 的 `-Q` 过滤器不可用；readtags 输出无行号，要行号直接解析 tags 原始行（`line:` 字段）。
- BusyBox grep 无 `-P`、无 `--include`/`--exclude-dir`；复杂匹配用 awk。
- ast-grep 用的是 npm glibc 版 + gcompat（「代码智能」场景装的），勿用 apk 版（无 Kotlin parser）。
