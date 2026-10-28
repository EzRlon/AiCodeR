# Fancy-Ai 项目评估（2026-10-28）

评估对象：https://github.com/Mr-J-369/Fancy-Ai

## 一、它是什么

一个**离线角色扮演 / 虚拟社交 App**（Android，Kotlin + C++），本地跑 LLM、扩散出图、语音，还有一套 PRoot Linux 终端。所有 AI 能力在设备上跑，不联网、不要 API key。

| 维度 | 事实 |
| --- | --- |
| 定位 | 角色扮演聊天 + 模拟社交（Ustagram/Rebbit/Y）+ 本地出图 + 本地语音 + 终端 |
| 技术栈 | Kotlin、Jetpack Compose、C/C++（llama.cpp、whisper.cpp、MNN、stable-diffusion.cpp）、AIDL 跨进程引擎服务 |
| 规模 | 251 个 Kotlin 文件；顶层模块 app 19.4 MB、image 10.4 MB、terminal 1.8 MB、engine 162 KB、voice 69 KB |
| 工程配置 | AGP 9.4.1、Kotlin 2.4.21、compileSdk 37、targetSdk 37、minSdk 33、NDK 30、CMake 4.1.2、JDK 21 |
| 许可 | Apache-2.0（附 NOTICE，含 Qualcomm QNN 专有运行时条款） |
| 活跃度 | 171 commits，2026-06 创建，2026-10-27 仍在推送；40 stars / 5 forks / 单作者 |

**结论先行：整包不能合并，定位完全不同。** 它有 31 个 UI 模块（characters/chat/social/phone/games/music/gallery/lorebook/faceswap/dare…），没有一行代码和「AI 编程 / agent 工作流 / 代码编辑」有关。

## 二、真正对我们有价值的四块

### P1：App 内模型下载器（直接可用，收益最大）
他们的做法（`ui/settings/LlmSettingsStore.kt`）：
- `HttpURLConnection` + `Range` 头断点续传，`206` 才追加，否则重下；
- hf-mirror 开关：URL 前缀替换成 `hf-mirror.com`；
- `PrivateHttp.requiredBaseUrl` 强制 HTTPS，或「HTTP + localhost/私网地址」白名单——安全边界干净；
- `GgufModels.import`：读 GGUF 魔数校验、写临时文件、`fd.sync()` 后原子改名、拒绝对已存在文件覆盖、导入过程带进度回调。

**对我们本地模型方案的影响**：P0 第 3 步原定「容器内 `wget -c` + 轮询文件大小」，改成 **App 内下载**后：进度精确、可暂停、不依赖容器是否装好、超时/断网可控。这一块语义和我们当前代码风格一致（Kotlin + Compose + 私有目录），搬运成本约半天。

### P2：App 进程内 llama.cpp 引擎（价值高，但要先验证）
`engine/` 是我们的「本地模型」方案没做的形态：llama.cpp 以 JNI 直接编进 App，AIDL service 承载会话，不走容器、不开 HTTP 端口。代码量不大（`llama_jni.cpp` 804 行、`LlamaRuntime.kt` 320 行），且是**薄封装 + 参数透传**，接了 llama.cpp 的 common 层：

- KV cache 可落盘/重载（`nativeSaveKvCache`/`nativeLoadKvCache`）；
- 投机解码、flash attention、K/V cache 量化、mmap、batch/micro-batch、prompt 线程分离；
- **tool_calls 解析**（`set_tool_call_ids`、`json_schema` 约束输出）——这意味着「本地模型做 agent 工具调用」在工程上可行，比我们方案里「小模型工具调用基本不可用」的悲观判断更有余地；
- 后端可选 CPU / OpenCL（Adreno kernels）/ Hexagon NPU / LiteRT。

**对我们的意义**：我们方案的容器路径有三个天生短板——PRoot 里只有 CPU 推理、容器 OOM 会拖死 App、必须手动启停服务。in-process 一次解决三个。

**但不能整搬**，要用「最小子集」并去掉 Hexagon/LiteRT：他们的 CMake 里 `GGML_HEXAGON ON` 且对 `hexagon.sdk.dir` 是硬依赖（`engine/build.gradle.kts` 直接用 `hexagonSdkRoot.get()`，缺了就构建失败），而 Hexagon SDK 是 Qualcomm 注册后才能下载的专有包，且 NOTICE 里写明 QNN 运行时受 Qualcomm 条款约束——不可自由分发。

### P3：PRoot 终端的工程化细节（对照加固）
`terminal/README.md` 里的几条做法比我们现在更严谨，值得逐条比对我们已有实现：
- 归档 SHA-256 固定 + 下载时校验；
- 归档硬链接在 Android 上降级为「独立副本 + 复制源权限」；
- 「删除环境时遍历不跟随符号链接」（我们 `ContainerInstaller.deleteTree` 已用 NOFOLLOW，方向一致）；
- 安装进度/会话「跨页面存活、跨进程死亡不重放」；
- WebView 只从拦截的私有 HTTPS 源加载本地资源，禁外链、禁 file/content URI；
- 进程输出按二进制写 xterm，不当 HTML/JS 解释。

### P4：本地语音（不着急）
whisper.cpp（STT）+ sherpa-onnx（Kokoro/Pocket TTS）。我们已有 Vosk 离线识别（`VoskModelManager`），缺口只在 TTS。除非要做离线朗读，否则不动。

## 三、明确不采纳

| 项 | 理由 |
| --- | --- |
| 整包合并 / fork | 31 个 UI 模块无一处交集；目标用户完全不同 |
| image 模块（10.4 MB） | 本地扩散 + QNN 专有运行时 + 分发条款风险；我们出图走云端 `generateImage` 已够用 |
| Hexagon NPU | 专有 SDK、构建硬依赖、只覆盖骁龙，分发合规不可控 |
| LiteRT 后端 | 多一套 Google 运行时收益不明，增加体积与维护面 |

## 四、硬性不匹配清单

| 项 | 我们（AiCode） | Fancy-Ai | 影响 |
| --- | --- | --- | --- |
| 许可 | GPL-3.0 | Apache-2.0 | 方向兼容（Apache-2.0 可并入 GPL-3.0），但必须在 NOTICE 保留其版权与声明 |
| minSdk | 26 | 33 | 搬代码要下探到 26，部分 API 需替换 |
| compileSdk / targetSdk | 36 / 28 | 37 / 37 | 我们锁 targetSdk 28 是为 PRoot，不能跟随 |
| 构建链 | 纯 Kotlin，无 NDK；native 走预编译 `.so`（`app/src/_armJniLibs/arm64-v8a/libproot*.so`） | NDK 30 + CMake 4.1.2 + JDK 21 + FetchContent 现拉 llama.cpp 源码 | 直接引入会给我们加一条从未走过的 native 构建链 |

## 五、建议动作（三档，按性价比排序）

**A. 立刻做（0.5 天，并入本地模型 P0）**
把「App 内模型下载」范式搬进来：HTTPS/私网校验、Range 续传、hf-mirror 开关、GGUF 魔数 + 大小校验、临时文件原子改名。同步把本地模型方案第三、四步的下载实现从「容器 wget」改成「App 下载」，仍由容器 llama-server 推理。

**B. 做一次 spike（2 天，出数据再决定）**
验证 in-process 引擎是否值得产品化。**先 CPU 基线、再 GPU 对照**，不是「只能用 CPU」：
1. 第一版只编 CPU 后端（`GGML_HEXAGON OFF`、`LLAMA_BUILD_SERVER OFF`、去掉 LiteRT），arm64-v8a，交叉编译一次后把 `.so` 提交进 `_armJniLibs`（与我们 proot 同一种交付方式，避免在容器里跑 NDK 构建）；
2. 只搬 `nativeOpen/nativeGenerate/nativeCancel/nativeCountTokens` + KV cache 四个接口，砍掉全部设置项与 AIDL（先同进程调用）；
3. 真机跑 0.5B Q4_K_M，记录：首 token 延迟、tok/s、峰值内存、10 分钟发热、与容器版对比；
4. 第二版开 `GGML_OPENCL ON` 重编，同一模型同一 prompt 再测一遍，得出 GPU 相对 CPU 的真实倍数；
5. 数据出来再定：若 tok/s ≥ 容器版 2 倍且内存可控 → 产品化为本地模型首选路径；否则保持容器方案，只留 A 档收益。

### 目标设备（已核实）与后端可行性

本机：Qualcomm SM8250（骁龙 865），GPU **Adreno 650**（`/sys/class/kgsl/kgsl-3d0/gpu_model` = `Adreno650v3`），Hexagon HTP **v66**。

| 后端 | Fancy-Ai 配置 | 本机可行性 |
| --- | --- | --- |
| CPU | 默认 | 可用（基线） |
| OpenCL（Adreno kernels） | `GGML_OPENCL ON` + `GGML_OPENCL_USE_ADRENO_KERNELS ON` | **理论可用**，Adreno 650 支持 OpenCL 2.0；但需 `/vendor/lib64/libOpenCL.so` 与 `/vendor/etc/OpenCL/vendors/*.icd` 在宿主暴露且 App 能加载（Android 10+ 链接器命名空间限制），**未验证**——这正是 spike 第一步要探的 |
| Hexagon NPU | `GGML_HEXAGON ON`，skel 只编 v73/v75/v79/v81 | **不可用**：SM8250 是 HTP v66，不在其预编译列表内。即使申请到 Qualcomm SDK 也用不上 |
| Vulkan | `GGML_VULKAN OFF`（显式关闭） | 项目内无此路径；要跨厂商（Mali）才需要自己打开 |

补充：**容器内 llama-server 永远用不上 GPU**（Alpine 的 llama.cpp 包是纯 CPU，容器内没有 OpenCL ICD 访问），所以「想用 Adreno GPU 跑本地模型」这件事本身就要求走 App 内 JNI 路径。

**C. 不做**
image、voice、Hexagon、LiteRT、整包合并。

## 六、未核实项（如实标注）

1. **未实际编译过该项目**：容器内没有 NDK 30 / CMake 4.1.2，且 Hexagon 分支需要专有 SDK；构建耗时与可行性均未验证。以上质量判断基于代码阅读。
2. **未在其真机上实测推理性能**：README 无速度/内存数据，档位表全部未知。
3. **其 tool_calls 在小模型上的实际可用性未验证**：JNI 层有解析与 id 生成，但模型能否稳定产出合规调用取决于模型本身。
4. 其 `image`/`voice` 模块只读了结构，未做深入代码审查（不在采纳范围内）。
