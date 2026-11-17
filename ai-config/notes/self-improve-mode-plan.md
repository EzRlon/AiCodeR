---
name: self-improve-mode-plan
description: 自我改进模式方案（2026-10-12 另一会话拟定）：通过轻量状态标志+提示词指令实现 AI 持续自我改进，改进写入 ~/.aicode/ 实现跨版本遗传，体积增量 < 1.05KB。
---
# 自我改进模式方案（供实施交接）

> **2026-10-13：已实施完成并随批次编译验证。实施增补：核销开关同步点在 AIEditorApp（常驻 collectLatest→ExecutionModeHolder）+ SystemPromptProvider.SelfImproveInstructionsSource + 99-runtime-context.md 的 {{AICODE_SELF_IMPROVE}} 变量位 + 设置页（GeneralSettingsSection/SettingsViewModel/SettingsScreen）。**

## 目标
让 AI 能真正学习与改进（更新 skills 和 prompts.custom），改进可遗传（软件更新时保留 ~/.aicode/ 目录），禁止修改应用源码/资源/配置/工作区，软件体积增量 < 1.05 KB。

## 设计决策
- **不修改 ExecutionMode 枚举**：改用 ExecutionModeHolder 中的轻量级状态标志 `_selfImprove`。
- **提示词驱动行为**：通过 SystemPromptProvider 注入指令引导 AI 反思与改进。
- **遗传性**：写入 `~/.aicode/skills/` 和 `~/.aicode/prompts.custom/`（容器重装不清空）。
- **安全约束**：指令明确禁止修改应用源码、资源、配置文件、工作区文件。
- **体积**：Dex 增量约 350-450 字节，指令常量 300-400 字节，设置页 100-200 字节，总计 < 1.05 KB。

## 文件修改清单

### 1. GeneralSettingsRepository.kt
- 新增偏好键：
  `val SELF_IMPROVE_MODE_KEY = booleanPreferencesKey("self_improve_mode")`
- 新增 Flow：
  `val selfImproveModeFlow: Flow<Boolean> = context.generalDataStore.data.map { it[SELF_IMPROVE_MODE_KEY] ?: false }`
- 新增写入函数：
  `suspend fun setSelfImproveMode(enabled: Boolean) = context.generalDataStore.edit { it[SELF_IMPROVE_MODE_KEY] = enabled }`

### 2. ExecutionModeHolder.kt
- 新增成员：
  `private val _selfImprove = MutableStateFlow(false)`
  `val selfImproveMode: StateFlow<Boolean> = _selfImprove.asStateFlow()`
- 新增函数：
  `fun setSelfImproveMode(enabled: Boolean) { _selfImprove.value = enabled }`
  `fun isSelfImproveMode(): Boolean = _selfImprove.value`

### 3. SystemPromptProvider.kt
- 修改 `EnvironmentSource` 内部类 build 方法：
  ```kotlin
  override fun build(ctx: AgentContext): String =
      when (executionModeHolder.currentMode()) {
          ExecutionMode.LOCAL_PROOT ->
              if (executionModeHolder.isSelfImproveMode()) "自我改进模式"
              else "本地容器（PRoot）"
          ExecutionMode.REMOTE_SSH -> "远程 SSH 服务器"
      }
  ```
- 新增 `SelfImproveInstructionsSource` 内部类（完整指令文本）：
  ```
  你目前处于自我改进模式。请利用这次机会反思你的表现，识别改进点，并采取行动使自己变得更聪明、更有效。
  你的改进应体现在：
  1. 更新或创建新的技能文件（在 ~/.aicode/skills/ 目录下），每个技能应包含：
     - 名称（skill_name）
     - 描述（何时使用）
     - 详细正文（SKILL.md 内容），包含 frontmatter（name/description）和操作指令
     - 可选：相关脚本或资源
     重要：技能应解决实际问题或提高效率，避免重复现有功能。
  2. 更新或创建新的提示词片段（在 ~/.aicode/prompts.custom/ 目录下），例如：
     - 为常见任务添加新的数字片段（如 00-<名称>.md）
     - 修改现有片段以提高清晰度或添加上下文
     重要：提示词片段应遵循现有格式，使用清晰的标题和可操作的指令。
  安全约束：你绝不得修改以下位置的文件：
  - 应用源代码（app/src/main/java/com/aicode/ 下的任何 .kt 或 .xml 文件）
  - 资源文件（app/src/main/res/ 下的任何文件）
  - 配置文件（如 AndroidManifest.xml、build.gradle.kts 等）
  - 工作区文件（~/workspace/ 下的任何文件，除非是上述允许目录的符号链接，但即使如此也需谨慎）
  - 任何可能影响应用核心功能或安全的文件
  如果你不确定某个文件是否允许修改，请默认禁止，并专注于允许的目录。
  行动步骤：
  a. 花几分钟反思最近的交互：哪里做得好？哪里可以改进？
  b. 基于此反思，决定一个具体的改进项目（例如，创建一个处理常见用户请求的技能，或修改一个提示词片段以减少令牌使用）。
  c. 在允许的目录下创建或更新文件。
  d. 在你的下一次回复中，简要描述你所做的更改及其目的。
  记住：目标不是每次都做巨大的改进，而是通过小的、持续的改进让自己变得更好。你的改进将被保存在 ~/.aicode/ 目录下，因此当用户更新软件版本时，这些改进将被继承。
  ```
- 在 SystemPromptProvider 中：
  - 新增成员：`private val selfImproveInstructionsSource = SelfImproveInstructionsSource()`
  - 在 build 方法中获取各来源基线快照时添加：`val selfImproveContent = selfImproveInstructionsSource.build(agentContext)`
  - 在 renderVariables 调用中加入对应参数（在函数签名中添加参数并执行替换）

### 4. 设置页
- SettingsViewModel.kt 的 init 中收集 `generalSettingsRepository.selfImproveModeFlow` 并更新 UI 状态。
- 设置 UI XML/Compose 新增开关：
  - 标题：启用自我改进模式
  - 描述：允许 AI 反思并改进自身技能/提示词以实现持续学习
  - 关联到 `GeneralSettingsRepository.setSelfImproveMode()`

## 验证方式
1. **偏好持久化**：启用/禁用开关后重启 APP，检查设置保持正确。
2. **提示词显示**：启用后 SystemPromptProvider 输出包含「自我改进模式」及完整指令内容。
3. **指令遵循行为**：
   - 正向：AI 可成功修改 `~/.aicode/skills/` 和 `~/.aicode/prompts.custom/` 下的文件。
   - 负向：AI 拒绝修改应用源码/工作区文件（被指令阻止）。
4. **遗传性**：在 `~/.aicode/` 下创建测试文件，重装容器/升级 APP 后文件仍存在。
5. **体积影响**：APK 分析工具比较基线，增量 < 1.05 KB。

## 执行顺序
1. 用户在设置页启用「自我改进模式」。
2. GeneralSettingsRepository 持久化偏好。
3. SettingsViewModel 检测变化并更新 UI 状态。
4. ExecutionModeHolder 的 selfImproveMode 状态更新。
5. SystemPromptProvider 生成提示词时检查 isSelfImproveMode() 并注入指令。
6. AI 遵循指令进行反思与改进，写入 ~/.aicode/ 目录。
7. 软件更新时该目录不被清除，改进被继承。

## 主要风险与回退
1. AI 可能不遵循指令——指令为可操作步骤；未来版本可优化。
2. AI 可能误改禁止文件——指令明确禁止；容器沙箱隔离。
3. 体积增加超标——已分析可忽略；必要时压缩指令常量。
4. 远程模式下启用——设计为自动切回本地以保证文件访问；禁用时恢复原模式。

## 涉及文件
- GeneralSettingsRepository.kt（新增偏好设置）
- ExecutionModeHolder.kt（新增状态标志）
- SystemPromptProvider.kt（修改环境来源 + 新增指令来源）
- 设置页相关文件（新增开关）