# 反馈批次 8（2026-10-29）——六条反馈 + 引擎/终端两项追加反馈

> 状态：全部代码改动完成，单测全绿，正式包已构建。**待真机验收**。

## 一、本批反馈与处置

| # | 反馈 | 处置 | 主要落点 |
|---|---|---|---|
| 1 | 一键安装开发环境必报错，因为把用户环境锚定在内置 Alpine 容器 | 提示词改为**先探测** `/etc/os-release` + `command -v apk apt-get dnf yum pacman`，再按实际发行版安装；App 侧把当前容器名与已探测系统类型注入任务前缀 | `res/values/strings.xml`、`values-en/strings.xml` 的 `dev_env_*_prompt`；`DevEnvironmentsSection.kt`、`SettingsScreen.kt` |
| 2 | 安卓环境介绍写错（暗示软件内置了安卓开发环境） | 文案改为「需自行安装 Android SDK（软件未内置）」，提示词明确说明不内置 SDK/Gradle/JDK 并要求现装 | 同上 `dev_env_android_desc` / `dev_env_android_prompt` |
| 3 | 悬浮球弹窗只保留结果输出与用户消息 | Hub 消息镜像过滤：TOOL 全滤、USER 滤后台通知、ASSISTANT 滤压缩标记/摘要且要求非空；删掉因此失效的 `isVerification` 字段与 `"⚙ "` 前缀 | `AIAgentViewModel.kt`、`FloatBallSessionHub.kt`、`FloatBallOverlay.kt` |
| 4 | 关于页：标题下改开发者署名、「上游项目」改「原作者」 | 标题下由 `about_description` 改为 `about_developer`（开发者：EzRlon）；`about_upstream` 改「原作者 AiCode」 | `AboutSection.kt`、两个 strings |
| 5 | 用一段时间后占用越来越大（30G+），扫描找不到原因 | 见下节「内存与存储」 | 多处 |
| 6 | 审计开源仓库冗余并修 bug | 合并 `SafeDelete` / `SafeFileDelete` 双实现；核对既有审计笔记，确认高风险项已修 | `core/util/SafeDelete.kt`（删 `SafeFileDelete.kt`）、3 个调用点 |
| 7 | 引擎终端交互界面要中文；登录要走浏览器授权 | 容器 `LANG` 跟随系统语言（zh → `zh_CN.UTF-8`）；引擎卡未登录时给「去浏览器登录」按钮（开官方取 Key 页）+ 说明「取 Key 配到 App 供应商后自动透传，不必终端登录」 | `LinuxContainerEngine.kt`、`CliAgent.kt`、`DefaultModelsSection.kt`、strings |
| 8 | 终端内容显示不完整 | **根因是 PTY 初始尺寸固定 80x24**，CLI 的 TUI 在启动早期就按该尺寸排版，后续 resize 不会重排已输出内容 → 初始尺寸改 100x30 | `TerminalSessionManager.kt`、`RemoteTerminalSessionManager.kt` |

## 二、反馈 8 的真根因（重要，已验证）

用户报 `Codex 提前退出: Error: No such file or directory (os error 2)`。

**根因**：三个引擎桥接类把**宿主路径**当容器路径传给容器内 CLI：
- `CodexBridge` 的 `-C <projectPath>` → 容器内不存在该路径 → ENOENT
- `OpenCodeBridge` 的 `--dir <projectPath>` → 同上
- 三个桥接的 `AICODE_PROJECT_ROOT` 环境变量（桥接 mjs 脚本用它定位项目级记忆/规则/技能）→ 同上

**修法**：三处都改用 `pathHomeResolver.home() + "/workspace"`（容器视角路径）。宿主 projectPath 只用于 proot `-b` 绑定，不能透给容器内进程。

## 三、内存与存储（反馈 5）

**实测证据**（用户日志）：`catalog.parse.ready` 时堆从 76MB 涨到 152MB，全部来自启动即解析 5.9MB 模型目录（8946 个模型）。

已改：
1. **启动不再解析模型目录**：`ModelMetadataService.refreshFromNetworkIfStale()` 只拉盘缓存，解析改为首次 `resolve()` 惰性触发 → 省掉启动期 100+MB 堆尖峰与常驻。
2. **存储扫描补外部私有目录**：`otherDataEntry` 增加 `externalFilesEntry()`（`Android/data/<pkg>/files` 下除 logs/ai-logs 的部分）。此前该目录只在日志分类里被部分覆盖，其余内容不在任何分类——这是「30G+ 但扫不到」的最大盲区。
3. **工具输出存档加容量上限**：`ToolOutputStore` 512MB / 3000 文件，超出按 mtime 淘汰最旧（刚写入的那个永不淘汰）。
4. **上下文历史缓存改 LRU**：`MessagePersistenceUseCase.historyCache` 最多 6 个会话。
5. **检查点单会话配额**：`CheckpointManager` 最多 60 个（约 60 轮回滚窗口），超出删最旧（活动检查点不删）。
6. **用量记录保留 90 天**：`SessionUseCase.initColdStartCleanup()` 清理过期 `llm_call_records`（该表此前只增不删）。

## 四、待真机验收清单

1. 设置 → 开发环境 → 任选一个场景（**尤其安卓**）→ 确认会话里 AI 先报出 `cat /etc/os-release` 的真实发行版（用户设备是 Debian 12 bookworm），再用 apt 而非 apk 安装。
2. 设置 → 关于：标题下显示「开发者：EzRlon」，「原作者 AiCode」。
3. 悬浮球面板：只显示自己的消息与 AI 最终回复，不再出现工具行。
4. 设置 → 模型与引擎：Codex/OpenCode 未登录时按钮为「去浏览器登录」，点击打开对应取 Key 页面。
5. 切引擎发一条消息：不再出现 `Codex 提前退出: No such file or directory`。
6. 终端里跑 CLI（`codex` 等）：中文系统下界面文案为中文；长行不再被截断（初始 100 列）。
7. 存储页：「其他数据」分类应包含外部私有目录占用；反复长会话后 tool-output 不超过 512MB。

## 五、踩坑记录

- **AAPT 会把字符串里的 `**`（Markdown 粗体）报成 `Invalid unicode escape sequence`**，错误信息误导性极强（指向的行号也对不上）。strings.xml 里不要写 Markdown 标记。
- 连续改 DAO 接口后必须 `rm -rf app/build/generated/ksp` 再编，否则 `*_Impl is not abstract and does not implement abstract members`（既有记忆 `gradle-parallel-build-ksp-cache-pitfall` 同源）。
- 改 `SessionUseCase` / `CheckpointDao` 的构造签名要同步改测试里的 Fake/mockk 构造点。
