# 交接 · 2026-11-15 晚批次（引擎/签名/专家团/随记）

> 新会话先读本文件，再读 `feedback-batch-2026-11-15-night.md` 与 `engine-process-duplication-2026-11-15.md`。
> 工作区未提交，分支 `feat/feedback-notebook`，HEAD `8a7bebc`（改动均在工作区）。

## 一、交付包（已产出，签名已验证）

- 路径：`/root/workspace/app-armsolo-2.3.0-rc4-dev.40-353.apk`（构建原始产物在 `AiCodeR/app/build/outputs/apk/armsolo/release/app-armsolo-release.apk`）
- versionCode **353** / versionName `2.3.0-rc4-dev.40+8a7bebc-dirty`
- SHA-256：`58d6ebb8578893b780dd2e58000eb56c1ac6c7b1ff31fee6a821f94abdb4f26c`
- 签名：CN=AiCode（与已安装 release 同证书），可直接覆盖安装；安装会重启 App，当前会话会断开。
- `send_file` 因 bridge token mismatch 失败，APK 已复制到工作区根目录，从 App 文件浏览器点击安装。

## 二、本轮反馈与状态

| # | 反馈 | 状态 |
|---|---|---|
| 1 | 专家团始终本土化（含商店新增） | 代码完成，待真机 |
| 2 | 设置「签名与凭据」入口去副标题 | 完成 |
| 3 | 签名页重做：直观、正式包选项、多项目 | 代码完成，待真机 |
| 4 | 「继续」断点续跑、不重复起 CLI | 代码完成，待真机 |
| 5 | 后台发消息不吞不中断、多次发送才强停 | 代码完成，待真机 |
| 6 | 主代理忙时子代理回复/接续 | 只读旁路助手已实现；待真机 |
| 7 | 侧栏子代理运行数/可点入 | 代码完成，待真机 |
| 8 | 随记浮窗 UI 重做 | 代码完成，待真机 |
| 9 | 装包后验收表 | 部分：容器通过；board.json 未生成；UI 待人工 |

## 三、验证

- `:app:compileUniversalDebugKotlin` ✅
- 全量单测 **1868 例 0 失败**（QA 增量复审前最后一次全量；之后只改停止窗口常量/旁路分类/keytool 读取顺序，受影响单测单独通过，正式包编译通过）
- `scripts/check_migrations.py` ✅（8..77）
- 正式包 `BUILD SUCCESSFUL in 9m 18s`，apksigner 验签 ✅

## 四、构建环境（重要，避免重踩）

- aarch64 设备必须覆盖 AAPT2：
  `-Pandroid.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2`
  （Maven 缓存里的 aapt2 是 x86-64，会 `required file not found`）
- Gradle daemon registry 锁在 PRoot 上会 `canonicalize: Function not implemented` 卡死：
  用 `GRADLE_USER_HOME=/tmp/gradle-home-fresh`，软链 `/root/.gradle/caches` 与 `wrapper`，加 `--no-build-cache --no-configuration-cache`。
- 内存紧：`--no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx5g -XX:MaxMetaspaceSize=1024m" -Pkotlin.compiler.execution.strategy=in-process`。
- 同会话重复 `claude -p --resume 9d304073…` 会互相 `gradle --stop`；构建前确认只剩当前引擎，必要时暂停/清理重复子树。

## 五、仍未完成（新会话继续）

1. App 被 LMK 后孤儿 CLI 的 PID 落盘 + 冷启动回收（未做）。
2. Codex/OpenCode 映射跨重启持久化（读取不回填内存、finally 才落盘）。
3. 持久化「上次回合被中断」标记（目前靠用户说「继续」+ `--resume`）。
4. 签名页切项目不自动停任务（确认框/文档已要求先停；未接 stopAll）。
5. 就绪检查 detail/nextStep 仍中文硬编码（英文界面会显示中文）。
6. 旁路助手的回答只在子会话，不自动合并回主会话时间线。
7. `board.json` 未生成：需在 App 外部引擎会话里让专家团实际调用 `orchestration_board` 跑一轮。
8. 真机验收：随记 UI/键盘/缩放、签名多项目、专家团中文、停止/续跑/队列恢复、旁路助手。

## 六、新会话建议第一步

1. 读 `git status` / `git diff`，确认未提交改动仍在。
2. 读 `feedback-batch-2026-11-15-night.md` 的「审查处置」与「构建环境坑」。
3. 装包后按清单真机验证；不要在未验证前继续改引擎状态机。
