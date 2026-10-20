---
name: voice-offline-auto-fallback-plan
description: 离线语音自动降级与模型自动下载方案（验收反馈 F2/F7）：探测记忆→使用时降级→双通道自动下载（多源+续传+校验）→设置联动；待用户宣布反馈完毕后随修复批实施
---

# 离线语音自动降级与模型自动下载方案（F2 / F7）

> 状态：**设计完成，待实施**（验收反馈接收期产物；随修复批排期）。
> 对应反馈：F2（无 VPN 时语音识别失败）、F7（自动引导/后台下载离线模型，国内源）。

## 目标
- 无谷歌服务 / 无 VPN 的设备：语音输入**零配置可用**——按下语音键即被引导（或后台自动就绪）离线识别，全程无需进设置。
- 下载失败可感知、可重试、不反复打扰；**不做常驻轮询**（性能功耗纪律）。

## 现状（已核实）
- 引擎枚举 `VoiceEngine{SYSTEM, OFFLINE}`，默认 SYSTEM；`VoiceSession.create()` 按 engine 分发到 `VoiceInputController`（系统识别）/ `OfflineVoiceSession`（Vosk）。
- `VoiceInputController.isAvailable()` 已有 `SpeechRecognizer.isRecognitionAvailable()` 探测能力，但**只用于创建前判断，失败统一弹「语音识别失败，请重试」**（`chat_voice_failed`），无降级、无引导——F2 痛点。
- 离线模型：`VoskModelManager`（`filesDir/vosk-model-cn/`，官方源 42MB，zip 下载→解压→校验 `am/`→标记就绪）。

## 方案（四层）

### 1) 可用性探测与状态记忆
- 触发时机（均**一次性**，非常驻）：①冷启动后首次进入会话页；②用户按下语音键（瞬时）；③设置页语音区可见时。
- 探测：`SpeechRecognizer.isRecognitionAvailable()`。
- 状态记忆（DataStore `voice/system_stt_state`）：AVAILABLE / UNAVAILABLE / UNKNOWN + 时间戳；UNAVAILABLE 后界面与降级逻辑默认推荐离线。
- 不做定时重试、不做后台轮询。

### 2) 使用时自动降级（F2 主诉求）
按住说话时，当前引擎为 SYSTEM 且：
- 探测状态 UNAVAILABLE，或本次 `onError` 属**网络/服务类**（`ERROR_NETWORK`、`ERROR_NETWORK_TIMEOUT`、`ERROR_RECOGNIZER_BUSY` 连续多次）：
  - 离线模型已就绪 → **自动切 OFFLINE 并立即用离线识别**，Toast「已切换离线识别」；
  - 模型未就绪 → 弹「离线语音引导卡片」（见 3），本次操作取消。
- 不触发降级的错误：`ERROR_NO_MATCH`（没说话声）、`ERROR_INSUFFICIENT_PERMISSIONS`（权限）等按原逻辑处理。

### 3) 模型自动下载（F7 核心 · 双通道）
- **主动通道（引导卡片）**：聊天区弹卡片「未检测到系统语音服务，是否下载离线中文语音模型（约 42MB）？」——[立即下载]（内嵌进度条、可取消、失败可重试）/ [以后]。完成→自动切引擎→提示就绪。
- **后台通道（静默）**：冷启动探测发现 UNAVAILABLE ∧ 模型未就绪 ∧ WiFi（或不计费网络）→ 后台静默下载；完成→通知栏提示 + 自动切引擎。**每次冷启动至多尝试一次**，失败记录原因，不循环。
- **下载源（多源顺序尝试，单源连接超时 ~8s）**：
  1. 官方：`https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip`
  2. Internet Archive：`https://archive.org/download/vosk-models-small/vosk-model-small-cn-0.22.zip`（41.9MB，已核实存在）
  3. （推荐补充）**项目自建 Gitee Release 镜像**——国内直连稳定、项目可控；实施时搬运官方 zip 上传一次即可，源列表配置化预留此位。
- **源调研结论（2026-10-16）**：魔搭 ModelScope 无此模型搬运；HuggingFace 仅有 vocab 文本/1.36GB 大模型；GitHub 上为解压散文件不可程序化下载；**无权威「国内官方镜像」**，故采用多源 + 自建镜像策略。
- **完整性**：zip 结构校验（沿用现有 `am/` 检查）+ 内置 sha256（实施时计算写入常量，两源同一文件）；**断点续传**（Range 请求，失败重试不从头开始）。

### 4) 设置页联动
- 引擎行副标题动态显示：「系统识别（本机不可用）」。
- 模型行显示下载源与失败原因（排障友好）；下载/切换动作与设置页状态一致。

## 实施落点（代码）
- `VoskModelManager`：多源列表 + 续传 + sha256 + 失败原因上报。
- 新增 `VoiceAvailabilityProbe`（探测 + DataStore 状态记忆）。
- 语音入口（VoiceSession / AIChatPanel 语音回调处）：错误分类 + 降级/引导触发。
- 引导卡片 UI（新组件，聊天区弹层）+ 后台下载协调（应用级一次性任务）。
- 设置页文案与状态展示；strings 中英。

## 验收标准
1. 无谷歌服务设备首次按语音 → 引导卡片 → 下载成功（自动选可达源）→ 自动切离线 → 松手转文字正常，全程无需进设置。
2. 两源均不可达：卡片显示失败原因与重试按钮；不崩溃、不反复弹。
3. 有谷歌服务的设备：行为不变（默认系统识别）。
4. WiFi 静默通道：自动下载完成 → 通知 + 已切换。
5. 功耗自查：无常驻轮询、无循环重试；探测与下载均为一次性、可取消、失败有界。

## 与反馈清单对应
- F2（无 VPN 识别失败）→ §2 使用时降级。
- F7（自动弹出/后台下载/国内源）→ §3 双通道 + 多源。
