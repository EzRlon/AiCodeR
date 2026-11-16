# 2026-11-16 第四批：VNC 极简桌面 + 待办清点

> 分支 `feat/feedback-notebook`，工作区未提交，**未 push**。

## 一、本批完成

1. **VNC 极简桌面（方案 A，用户定）**：`VncServiceController` 启动脚本补
   `jwm + xterm + 基础字体`，写入 `~/.jwmrc`（任务栏/时钟/右键菜单/纯色背景），
   进入 VNC 即有窗口管理器和可用终端；不引入 LXDE/XFCE。文档 `docs-site/docs/guide/vnc.md` 已同步。
2. **编排板加固**：历史事件字段 500 字上限（防 board.json 膨胀）、list 支持 `limit/offset`
   分页（默认 30/最大 100）、done/failed 重开必须带 note（状态转移约束）。
3. **反馈 Issue 正文跟随界面语言**：英文环境生成英文模板/类型/裁剪提示，不再输出中文正文。
4. **随记撤销点**：继续输入后清除「撤销润色」点，避免连带回退新输入。
5. **签名页周边**：设置入口图标改 Key 并补副标题；「打包发布资产」下新增明文口令存放警告。
6. **引擎进程 P2 四项**：/proc 单快照、批量回收（一次 SIGTERM/一次宽限/统一 SIGKILL）、
   stat 读失败区分 `UNREADABLE` 并保留记录、接替回合清理 claimedPids 泄漏。
7. 记忆图谱下拉刷新、随记格式条、模型底栏搜索/折叠、致谢页、存储扫描提速（上一轮已改）。

## 二、未完成（如实）

- **任务板增强**（外部引擎专用 UI、卡死检测重派、优先级、权限升级审批、子代理心跳）：
  属新功能，不在本批安全范围，未做。
- **OpenCode MCP environment schema / Codex 多行字符串/profile**：需对照官方文档实测，未做。
- **反馈收集端点部署**：缺 Cloudflare/GitHub 凭据，blocked。
- 真机验证项（随记、签名页、引擎续跑、LMK 回收、VNC 桌面）仍需用户执行。

## 三、验证

- `:app:compileArmsoloReleaseKotlin` ✅
- `:app:testUniversalDebugUnitTest --tests com.aicode.feature.agent.domain.engine.*` ✅
  （EngineOrphanReclaimerTest 9 例 + EngineProcessFileTest 5 例，0 失败）
- `:app:assembleArmsoloRelease`：见下一条交付记录。
