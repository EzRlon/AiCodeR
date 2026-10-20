---
name: network-switch-stream-hang
description: bug 调查单：网络切换后流式请求静默卡死（根因待真机验证、未修复）；含已核实代码位置、疑似根因链、修复方向与抓日志关键字
---
# 网络切换后流式请求静默卡死（bug 调查单）

**状态：⚠️ 根因链为「代码事实支撑的推测」——未经真机复现验证、尚未修复。修复完成后请更新本记忆。**
- 调查：另一会话已完成只读核查（2026-10-12，用户转交）；本会话已二次核实关键行号（下述）。
- 用户报告的场景：WiFi→移动、VPN 开→关等**默认网络/VPN 通道变化**后，大模型流式请求一直「正在思考」无输出无报错。

## 已核实的代码事实（2026-10-12 核实）
- 全库**无任何网络变化监听**（无 ConnectivityManager / registerDefaultNetworkCallback；AndroidManifest 已有 ACCESS_NETWORK_STATE，具备实现条件）。
- OkHttp 超时（`di/AgentModule.kt:191-194`）：connect 120s / write 120s / **read = 0（无限）**；`pingInterval(30s)` 只对 WebSocket 生效，对 SSE 流无用。
- 应用层兜底超时：首字节默认 300s（`feature/agent/domain/provider/RetryPolicy.kt:42`）；数据块间隔默认 300s（`feature/settings/data/repository/GeneralSettingsRepository.kt:56/59`）；两者可在设置中调整，**0=不限制**。
- 重试机制存在：最多 6 次指数退避（`AIProvider.kt` 附近），**但前提是超时先被触发**；重试时 UI 会显示「重试第 N/6 次」气泡（`StatusBubbles.kt:227`）。

## 疑似根因链（待真机验证）
网络切换（尤其 VPN 断开/建立）使已建立的 TCP 连接**静默失效**（不报错不断开）→ 请求已发出但永远等不到回复 → 因 read=0 + 300s 兜底，最长 **5 分钟**才触发首字节看门狗 → 开始重试；重试若又落网络不稳窗口，每轮再 5 分钟 × 最多 6 轮 → 用户视角「一直转圈、毫无动静」。

## 修复方向（建议，供修复会话）
1. **监听默认网络变化**：切换瞬间主动取消进行中的流式请求，立即用新网络重发，并提示「网络已切换，正在重试」（治本）。
2. 首字节/数据块间隔默认 300s 偏长，建议缩短（如 60~120s），慢模型可通过设置放宽。
3. 等待响应期间 UI 给出反馈，区分「在等」与「卡死」。
4. 真机验证三类切换场景 + 长时间无数据场景。

## 复现时抓日志
「设置→日志」调 Debug 级，搜索：`callFailed` / `First content timeout` / `流式请求失败，第` / `网络请求失败，第` / `SSE 流被中断`。
日志路径：`/storage/emulated/0/Android/data/com.aicode/files/logs/`

## 附注
- 与 E4「目标托管」强相关：托管类长任务在移动网络下跑时，此 bug 会严重破坏体验（卡 5 分钟无反馈）。
- 修复后须补：真机验证记录 + 更新本记忆状态。

## 2026-10-19 新观察（子代理中招实录，已验证现象）
- qa 子代理在重网络活动窗口（apk/pip/npm 下载 + 19 分钟 Gradle 完整打包并发）中，LLM 流式回合于某时刻静默冻结：state 永远 running、无报错、无残留进程，数小时后只能 stop——**子代理的 AI 回合同样会中招**，且表现为「子代理永远 running」而非任何错误。
- 运维规避已写入 .aicode/notes/qa-discipline.md：构建不与 qa 并发、qa 禁自跑 gradle、单批小范围派发。

