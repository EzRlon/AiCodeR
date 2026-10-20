---
name: floatball-attach-required
description: 坑（已验证两次）：FloatBallOverlay 构造后必须显式 attach()，两次遗漏导致真机球不显示；诊断靠链路日志点；写完须 grep 全部构造点核对
---
# 悬浮球「创建必须挂载」坑（已验证，两次踩中）

**根因**：`FloatBallOverlay` 的构造函数只创建对象，窗口挂载靠显式调用 `attach()`。任何承载方（原 FloatBallService、现 TerminalKeepaliveService.attachFloatBall）在 `overlay = FloatBallOverlay(...) {...}` 之后**必须调用 `overlay?.attach()`**（attach 幂等；赋值后再挂载，保证失败回调能拿到已赋值的 overlay 完成清理）。

**两次踩中**：① 原 FloatBallService 从未调用 attach（qa 两轮才发现，真机「球不出现」）；② 通知合并重构时把悬浮球并入 TerminalKeepaliveService，attachFloatBall 又漏写 attach（真机复验再次「球不出现」）。

**纪律**：新建/迁移「构造后需显式启用的对象」（overlay、服务、监听器）时，写完必须 grep 全部构造点核对启用调用；qa 审查必须包含「对象创建点 → 启用调用」的链路核对。

**诊断路径**（下次真机再报球不出现）：让用户看 设置→日志 搜「悬浮」，链路日志点：`Service created` / `Float ball synced: enabled=...` / `无悬浮窗权限，不显示悬浮球` / `悬浮球已挂载 x=...` / `悬浮球挂载失败：...` / `资源 id 解析失败：...`——每步都能定位断点。