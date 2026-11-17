# 交接（2026-10-25 晚终态）：能力全域打通批三 + 批二 已交付，三批全部完成

> 本文件是**当前权威交接**（取代 handoff-2026-10-25-final.md 的队列部分；引擎二期/统一画布等历史结论仍以旧交接为准）。
> 开工顺序：读本文件 → `engine-capability-sync-batch3-disposition-2026-10-25.md`（批三台账）→
> `engine-capability-sync-batch2-disposition-2026-10-25.md`（批二台账）→ `engine-capability-sync-batch1-disposition-2026-10-25.md`（批一台账）。

## 一、状态一句话

仓库 `~/workspace/AiCode`，分支 `dev`，**本地 141 个提交、未 push（用户禁止未经允许 push；本地提交照常）**。
最新提交 `ffb1f62`（批二）。**vCode139 正式包已交付待真机验收**
（`~/workspace/许愿码-正式版-20261025-vCode139-三引擎与能力同步.apk`，versionCode=139 勿再出同号包）；
下一包 versionCode = gitCommitCount()+1 = **142**（出正式包必须 `--no-configuration-cache`，配置缓存会冻结 git 版本号）。
versionName 规则：`gitCommitCount+1`。

## 二、本会话已完成（勿重做）

| 项 | 提交 | 状态 |
|---|---|---|
| 批三：Codex 沙箱档位开关、OpenCode --auto 开关（引擎卡小开关，EngineOptionsRepository DataStore）+ 新会话默认引擎扩展到 Codex/OpenCode | e273f70 | 完，待验收 |
| 批二：App 子代理自动映射为 Claude `--agents` / OpenCode `agent` 段（CliAgentMapping + 两桥 + 单测） | ffb1f62 | 完，待验收 |

两批均过：编译（compileArmsoloReleaseKotlin）、detekt（0 findings）、批二另有 CliAgentMappingTest 单测通过；
qa+optimizer 双审完成且逐条处置（当批修 + 记录，见各台账），能力感知件（65-capabilities.md、docs-site capabilities.md）已同步。

**三期引擎适配（Codex/OpenCode 桥接）代码层全部完成；能力全域打通批一/二/三全部完成。**

## 三、待真机验收（合并出 vCode142）

1. vCode139 既有清单：引擎三态切换、选模型不回退、标注图传递、自装 MCP 可用、recall_search、
   claude image 块 / codex -i / opencode -f 实际行为（完整清单见 release-review 台账与批一台账）。
2. 批三新增：Codex 卡「放开沙箱」开关（开=可读写工作区外）；OpenCode 卡「自动批准权限请求」开关（关=未授权调用被拒、回合可能不完整——qa 已把风险写进 hint）；
   开关全局生效（含定时任务），下一次发送起作用；设置 → 默认模型页设 Codex/OpenCode 为新会话默认引擎后新建会话直接生效。
3. 批二新增：设置 → 子代理建英文自定义子代理 → Claude Code 会话内可被 Task 派发（`@名字` 召唤是否可用待核实，文案已按保守写法）；
   OpenCode 会话内同样可用（App 禁用后下一次发送消失）；中文名/带空格名不映射不报错；
   用户的 `.claude/agents/` 与 opencode.json 不被写动。
4. 引擎登录态测试（用户此前延后）一并补。

## 四、队列（接下来做什么）

1. **优先：真机验收 + 修反馈**（上面清单），验收后按需出 vCode142（发版前三步不可免：detekt 前置 → qa 并行 → optimizer）。
2. 能力全域打通遗留 P2（带行号在各台账）：非 ASCII 名子在代理设置页加提示（qa-P2-5）、
   enabledTools/disabledTools 透传（引擎侧无对应机制）、本体工具选择性桥接（browser 等，P2 逐个评估）、
   旧图片残留清理、映射文件增长上限等。
3. 更大队列以旧交接/笔记为准：用户脚本系统（user-scripts-plan）、在线商店安全层（store-security-plan）、
   存储推荐清理（storage-cleanup-plan）、增强计划批次 0-7（enhancement-program-2026-10-24.md，注意**批次 0 是用户 UI 反馈 F1/F2/F3，F2 引擎切换交互要先出方案交用户拍板**）。

## 五、环境与纪律（必读，均踩坑验证过）

- 构建（musl 补丁前缀，勿并行多构建）：
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew <任务>`
- 出正式包：`assembleArmsoloRelease --no-configuration-cache`，产物核对 output-metadata.json 的 versionCode == rev-list --count HEAD + 1。
- push 一律先问；detekt：`tools/detekt/run-detekt.sh <逗号分隔的文件列表>`（多文件用冒号连接，不是空格）。
- 容器无 rg，用 `grep -rn`；busybox grep 不支持 --include/--exclude；Bash 禁命令替换/子 shell/绝对路径重定向（SYSTEM_DENIED）。
- 单测已知非阻塞：SyncEngineIgnoreTest 全量跑失败/隔离通过（测试间污染，台账有证据）。
- 工作区有他会话产物（如未跟踪的 review-package-2026-10-25-compressed.md、enhancement-program 等笔记），**勿动、勿纳入自己的提交**。
- task 子代理工具：`task create` 的 prompt 传参在当前环境可能失败，可改用对既有子代理 `task send` 追加任务（本会话实测可行）。
- 审查路由/进度心跳/UI 反馈对齐/能力感知等纪律见项目规则（AGENTS.md，已自动注入）。
