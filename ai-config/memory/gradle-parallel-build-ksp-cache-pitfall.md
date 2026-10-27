---
name: gradle-parallel-build-ksp-cache-pitfall
description: 坑（已验证）：多批次并行对同一变体跑 Gradle 会污染构建缓存，KSP 生成物被截断成坏快照反复 from-cache 复现；修法=停并行构建＋删生成目录＋--no-build-cache 重跑
---
# Gradle 并行构建污染 KSP 生成物（2026-10-23 夜间批次实测）

**现象**：多子代理并行实施时各自对同一变体（armsoloRelease）跑 `:app:compileArmsoloReleaseKotlin`，首次竞态把只含 2 个文件的坏快照存进构建缓存；后续编译 from-cache 恢复时 armsoloRelease 的 483 个 KSP 生成物被截断，报 `ScheduledAgentViewModelProvider.kt:108 Unresolved reference 'AIAgentViewModel_Factory'` 这类「找不到 Hilt 生成物」的错，且重试不消失（每次都从坏缓存恢复）。

**判别要点**：报错指向 Hilt/KSP 生成符号（`*_Factory`、`*_Hilt_*`），且错误文件在自己的地盘之外、重试不消失 → 优先怀疑缓存污染，不是代码错。

**修复（已验证）**：①停掉所有并行 Gradle 构建；②删除被截断的生成目录（app/build/generated/ 下对应 variant 的 ksp 产物）；③带 `--no-build-cache` 重跑，KSP 现场重建（486 文件）后通过。

**预防**：多批次并行收尾时错峰跑同一变体编译（一个批次编译时其他批次只写码不编译）；正式 R8 包构建前若夜间有并行编译史，直接带 `--no-build-cache`。