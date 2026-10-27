---
name: ctags-code-index
description: 容器内 ctags 符号索引已装好：重建与查询命令、readtags/BusyBox 坑、无调用关系局限
---
# ctags 代码符号索引（容器内已装好）

- 工具：Universal Ctags 6.1（`apk add ctags`，Alpine 仓库包名是 `ctags`，不是 `universal-ctags`）。
- 索引文件：`~/workspace/AiCode/tags`（约 3.9 万符号，已加入 .gitignore）。
- 重建索引：`cd ~/workspace/AiCode && ctags -R --languages=Kotlin,Java --exclude=build --fields=+K -o tags .`（必须带 `--fields=+K`，否则类型字段是裸字母且 awk 统计失效）
- 查询：
  - 找定义：`readtags -e 符号名`（在项目根执行）
  - 列类成员（字段用全限定名）：`awk -F'\t' '/class:com.aicode....类名$/ {print $1}' tags`
  - 字段统计：`cut -f4- tags | grep -oE '[a-zA-Z]+:'`
- 坑：容器 readtags 的 `-Q` 过滤器不可用（COMPILE ERROR: unbound-variable eq）；BusyBox grep 无 `-P`，复杂匹配用 awk。
- 局限：ctags 只有「定义+类归属」，没有「谁调用谁」的引用关系；调用链仍需 grep 源码。CodeGraph 级别的关系图未装（musl+TLS 干扰，成本高）。
- 用途：找功能实现位置、列类结构，比纯关键词搜索省上下文；代码大改后记得重建索引。

# ast-grep 结构化搜索（已装好，支持 Kotlin）

- 安装链路（musl 容器，勿重蹈）：Alpine 仓库的 ast-grep 无 Kotlin parser；GitHub 直连被 TLS 干扰、gh-proxy 镜像 404、pip 要现场编译 Rust 全部失败；**成功路径** = `npm install -g @ast-grep/cli-linux-arm64-gnu --registry=https://registry.npmmirror.com --ignore-scripts --force`（libc 平台检查用 --force 绕过）+ `apk add gcompat`（glibc 兼容层）+ 软链 `/usr/bin/ast-grep -> /usr/local/lib/node_modules/@ast-grep/cli-linux-arm64-gnu/ast-grep`。现装 0.51.0。
- 用法：`ast-grep run -p '$X!!' -l kotlin app/src/main/java`（$X 单节点通配，$$$A 多节点通配）；按 AST 结构匹配，比 grep 准（无字符串误报），可 `-r '替换'` 做结构化改写。
- 已实测：全仓找 22 处 Kotlin 非空断言 `!!` 一次命中。

# repo-map 全仓速览脚本

- `~/workspace/AiCode/tools/repo-map.sh`：输出各模块 类型/方法 规模 + 成员数 Top30 类 + 钻查命令。大型任务开工时先跑它建立全貌，比逐个读文件省大量 token。

# callchain 调用链脚本

- `~/workspace/AiCode/tools/callchain.sh callers 符号名 [目录]`：调用点（直接/成员 `$OBJ.f()`/安全 `$OBJ?.f()` 三形态去重）；`callees 符号名`：函数体内调用的项目符号（与 tags 交集，含标准库重名噪声需甄别）。
- tags 必须用 `--fields=+Kn` 重建（readtags 输出无行号，脚本从原始行解析 line: 字段）。

# code-intel 技能

- 已建 `~/.aicode/skills/code-intel/SKILL.md`：把 repo-map→ctags→callchain→ast-grep 的探索 SOP、维护纪律（改码后重建索引）、环境坑固化；下会话技能清单刷新后生效。