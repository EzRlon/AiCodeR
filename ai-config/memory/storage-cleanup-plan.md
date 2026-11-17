---
name: storage-cleanup-plan
description: 存储推荐清理设计（推荐引擎三级判定＋孤儿检测＋白名单硬保护），笔记在 ai-config/notes/storage-recommended-cleanup-plan.md
---
# 存储推荐清理（2026-10-22 设计完成，待用户确认后实施）

- 设计笔记：`AiCode/ai-config/notes/storage-recommended-cleanup-plan.md`。
- 基线：8 类 CleanupKind 手动清理已存在（StorageUsageScanner.clean()，含容器缓存白名单脚本/VACUUM/删会话联动），本设计叠加推荐引擎不推翻。
- 核心：StorageCleanupAdvisor 三级判定（推荐级默认勾选/需确认级默认不勾/禁区白名单 PROTECTED_PATHS 显式常量）＋孤儿检测（checkpoint/ai-log/rootfs_images vs DB 对照，现状为零）＋按 mtime 过期（30 天默认）。
- UI：存储页顶部推荐卡（预计可释放 X·高置信 Y）→ 明细逐条带删除原因 → 一键清除已选 → 升级版逐条预览弹窗 → VACUUM。
- 低频维护：WorkManager 每周只更新推荐池数据绝不自动删。
- 运行健康：现状健康（探索实证）→ 只读审计清单，不做一键杀进程。
- 前置修复：删会话递归子代理会话（现仅一层 SessionUseCase.kt:55）。
- 白名单绝对性是验收硬指标：密钥/工作区/配置目录 0 命中。