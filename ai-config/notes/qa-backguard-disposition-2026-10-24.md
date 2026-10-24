# qa 处置台账：返回键守护修复（2026-10-24）

## 背景

用户报严重 bug：会话进行中按系统返回键再重进，会话「停了」、运行中子代理消失。
根因（已核实）：会话/子代理任务协程全部挂在 Activity 级 AIAgentViewModel 的 viewModelScope
（AIAgentViewModel.kt `executeAgentRequestStream` → `viewModelScope.launch`），聊天页是
NavHost 起始页且返回键无拦截 → 返回键 finish Activity → ViewModel 销毁 → 协程全灭；
前台保活服务（TerminalKeepaliveService + wakelock）只保进程不保 ViewModel 协程。

## 修复内容（2 文件）

- `AIAgentViewModel.hasActiveAgentWork()`：sessionJobs / commandJobs / _runningCommandSessions 任一活跃即真。
- `MainActivity.kt` AppNavigation：聊天页 BackHandler 守护——有任务时 `moveTaskToBack(true)`（与 Home 等价，任务与运行态原样保留），无任务时 `finish()`（与原默认行为等价）。
- 编译验证：`:app:compileArmsoloReleaseKotlin` 通过（修复后、P2 处置前）；P2 处置后再次复验见下。

## qa 报告处置（qa：只读，无 P0/P1，可放行）

| 级别 | 发现 | 处置 |
|---|---|---|
| P2-1 | 大屏开右栏后旋转到窄屏，paneKind 残留非 NONE 时守护 disabled，返回键仍可穿透 finish 杀任务（既有缝隙） | **当批已修**：守护 enabled 改为 `(!expanded \|\| paneKind == WorkbenchPaneKind.NONE)`，窄屏残留场景由守护接住 |
| P2-2 | `_runningCommandSessions` 未纳入守护判断（当前靠隐式不变式成立） | **当批已修**：`hasActiveAgentWork()` 补 `\|\| _runningCommandSessions.value.isNotEmpty()` |
| P2-3 | `android.app.Activity` 全限定内联与文件 import 惯例不一致 | **当批已修**：提为顶部 import |

P2 处置后复验：`:app:compileArmsoloReleaseKotlin` 复验通过（BUILD SUCCESSFUL，2026-10-24）。改动未提交，commit 待用户裁决。

## 未验证边界（如实）

- 真机行为（返回键→退后台→重进恢复运行态、moveTaskToBack 实机表现）未验证，待用户真机验收：
  发消息启动任务 → 按返回 → App 应退后台而非关闭 → 重进后会话仍在跑、子代理原样可见。
- 终端后台页签（@Singleton 自持 scope）、定时任务（离屏独立 ViewModelStore）经 qa 核实不受返回键影响，无需守护。
