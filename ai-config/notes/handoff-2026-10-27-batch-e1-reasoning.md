# 上游 v1.13 合并批次 E1：思考档位学习 + 关闭思考语义（2026-10-27）

## 交付内容

摘上游 v1.13.0-rc1 思考能力子系统的两块独立修复（未搬 resolver）：

1. **400 文案档位白名单学习**（新增 `ReasoningLearning.kt`，字符串版适配我方 `reasoningLevels` 表达，未引入上游 ReasoningCapability/CapabilityTables 模型）：
   - 两种错误格式解析（`valid levels:` / `Supported values are:`），按 `providerId:model` 缓存
   - OpenAI：recall 白名单优先于 models.dev 声明档位，沿用原有向下/向上钳制
   - Gemini/Anthropic：档位映射接 `clampLearned`（向下最近档）
   - 防 400 文案误学：过滤后只剩 none 的白名单不入库（tool_choice 类错误）
   - 三适配器 10 处 enrich 错误路径全部接 `observe`，CancellationException 在 observe 之前重抛
2. **Gemini 关闭思考语义**（原「裸发/恒 400」）：
   - gemini-3 系不可完全关闭 → none 退最低档（flash/minimal 系=minimal、3.7/3.8-flash 与 Pro=low、image=minimal），退档结果过学习钳制
   - 2.5 系：仅 Flash/Flash-Lite 发 budget=0；Pro none→1024（原发 0 直接 400）；minimal→512（真实档位，上游会钳到 low/1024，本处有意按 API 值直发）
   - interactions 路径同步（none 同语义、xhigh/max 统一走钳制）
3. **Anthropic 关闭思考语义**：
   - Fable 全系 + Opus 5.5+（思考恒开）→ adaptive + effort=low（原发 disabled 不报错但不生效）
   - Sonnet 5.5 → between_tools；Opus 5 / Haiku 5 → disabled + effort=high（上限保护）；其余 → disabled
   - `claudeGeneration()` 提取代际解析（兼容 `-`/`.` 分隔符），supportsAdaptiveThinking 委托之

## 审查与处置

- detekt（6 文件）：0 发现
- qa（对照上游逐分支）：P1×2 已修（Fable 5.1 恒开漏网、gemini-3 image 模型退档错误）；P2-3/4/5 已随批修；P2-6 已注释注明取舍
- optimizer：无 P0/P1；P2×3 记录如下
- 单测：ReasoningLearningTest 12 + GeminiAdapterTest 27 + AnthropicAdapterTest 15，全绿（基线 156 用例之外新增）
- dex：64778/65536（本批 +9），余 758

## 待排期（optimizer P2，均为微优化，不构成重构建理由）

1. `AnthropicAdapter.kt:483` claudeGeneration() 每次调用新建 Regex，且 none 分支与 supportsAdaptiveThinking() 重复解析同一模型名——下次触碰该文件时提为 companion 常量并传参复用
2. `ReasoningLearning.kt:17` 缓存无淘汰、生产代码无 clear() 调用点——内存上界 KB 级（key 来自有限配置），无需 LRU；删 provider 后残留条目无害
3. `ReasoningLearning.kt:51` clamp 未命中时的临时 List 分配——收益接近零，可不动

## 其他记录

- qa P2-7：Anthropic ≤4.5 budget 路径未接学习钳制——旧模型错误文案极少带档位白名单，影响小，记录不修
- E2（resolver 全量替换，迁移号需改 74 + 同步 SCHEMA_VERSION，dex 风险高）与 F（子代理模型 tier，与我方路由表重叠）未动，见批次 D 交接笔记
