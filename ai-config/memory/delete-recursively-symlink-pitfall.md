---
name: delete-recursively-symlink-pitfall
description: 踩坑（已验证）：Kotlin File.deleteRecursively 跟随目录符号链接；本项目用 ContainerInstaller.deleteTree（NOFOLLOW）做安全删除
---
# 符号链接安全删除（deleteRecursively 坑）

## 事实（已验证）
- Kotlin `File.deleteRecursively()` 按 `File.isDirectory` 判断、**会跟随目录符号链接下钻**：可能白跑链接目标（大目录慢几倍）、指向祖先时下钻到路径超长、指向目录外时**删掉外部数据**。项目内两处代码注释均有记载（ContainerInstaller 的 rootfs/技能清理 KDoc）。
- 项目已有 NOFOLLOW 实现：`ContainerInstaller.deleteTree(root, startCount, onProgress)`（companion，内部 `isRealDirectory` 用 `LinkOption.NOFOLLOW_LINKS`，符号链接只 unlink 链接本身），返回删除计数并在每 2000 项回调进度。
- 回归测试：`app/src/test/java/com/aicode/feature/agent/domain/container/ContainerInstallerResetTest.kt` 断言含符号链接的 rootfs 删除后链接目标完好。

## 使用约定（2026-10-15 内置技能批确立）
- 删除**与用户内容共享**或**可能含符号链接**的目录，一律用 `deleteTree`（如 `~/.aicode/skills` 的覆盖式清理，`extractBuiltinSkills` 以 `deleteTree(dest, 0) {}` 调用）。
- 例外：`extractDocs` 对 `~/.aicode/docs` 用 `deleteRecursively` 属既有取舍（App 独占重置目录，qa 核过可接受）。
- 新增类似"覆盖式释放"逻辑时，先问：目录是否可能被用户替换成符号链接 / 内含符号链接？是则用 deleteTree。