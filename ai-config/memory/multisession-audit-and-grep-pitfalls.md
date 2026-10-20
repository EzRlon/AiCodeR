---
name: multisession-audit-and-grep-pitfalls
description: "跨会话\"漏做盘点\"可靠方法（两处 notes+git+代码全库+共享终端历史；App 聊天记录容器不可达）＋busybox grep --include 静默失败坑（假阴性）"
---
# 多会话盘点方法（2026-10-14 实测走通）

目标场景：盘点 AiCode 多会话接力开发中"漏做/半成品"项（只查不改）。

证据源（按价值排序）：
1. 笔记库：`~/.aicode/notes/` 与 `~/workspace/.aicode/notes/`（设计稿 + handoff 交接单）——跨会话意图的主要载体。
2. git：全史 + 工作区未提交改动 + reflog + stash（工作区干净时也可看 reflog 有无被 reset 的工作）。
3. 代码全库扫描：判定"未做"的唯一可靠标准；笔记常滞后于代码（实例：E4 目标托管实际已在批次5 完成、网络切换修复已实现但记忆仍写"未修复"）。
4. 共享终端历史：`terminal read`（不传 tab_id）能列出**全部历史构建标签**——能还原每个会话做过的构建/验证动作与命名（是一个隐藏的会话痕迹宝库）。
5. `/root/tmp/` 残留（如 v2-staging 两份拷贝）；`~/.aicode/tool-output/` 日志价值低。
6. ⚠️ App 内的原始聊天记录在宿主私有目录，**容器不可达**，不要试图读取。

产出模式：盘点单写 `~/.aicode/notes/`（分类+状态+证据+一键交接指令块，可直接复制给主 agent）。

# busybox grep 坑（已对比实验验证）

- 容器是 **busybox grep，不支持 `--include`**。
- 带 `--include="*.kt"` 且 `2>/dev/null` 时：报错被吞、输出为空 → **假阴性**（曾因此误判"网络监听器不存在"）。
- 正确做法：`cd` 到目标子树直接 `grep -rn "pattern" com/aicode`；多词用 `\|` 分隔；长输出接 `| head`。

# busybox ps / find 坑（2026-10-15 实测）

- 容器 **busybox ps 不支持 `-p PID`**：`ps -p 8673 && echo 存活 || echo 退出` → 错走「退出」分支（实际进程仍在跑，差点引发错误决策）。判断进程存活用 `[ -d /proc/<pid> ]` 或 `ps aux | grep "[k]eyword"`。
- busybox find 的 `-newermt "YYYY-MM-DD HH:MM"`（带空格格式）多次返回空结果，与文件实际 mtime 不符——时间窗口筛选优先用 `-mmin -N`（相对分钟）。