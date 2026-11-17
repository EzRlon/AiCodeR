---
name: acceptance-and-upstream-rules
description: AiCode 自建分支工作规则：全部完工后一次性真机验收（不每批找用户）；官方上游合并时机（每批最终编译前）；NVIDIA 429 处理流程
---
# 验收节奏与上游合并规则（用户 2026-10-12 指示）

## 真机验收节奏
- **不每批次找用户真机验收**：所有批次（路线图 4 批）全部完工后，一次性交付 APK 让用户验收。
- 每批完成后照常本地编译验证 + qa 审查 + git commit 到本地 dev 分支（不 push，无远端），但不发 APK 不打扰用户。
- **最终版构建前必须先汇报（用户 2026-10-12 指示）**：构建给用户安装验收的「最终交付版」之前，先向用户简要罗列「所有计划/反馈项的完成情况」（逐项标注 完成 / 未完成），经用户确认后才可构建交付。开发中的验证性编译不受此限，但不得把验证构建当作交付版发给用户。

## 构建节奏优化（2026-10-15 用户确认「按照最优方式」）
- **每批必需**：debug 编译（assembleUniversalDebug）+ 单测（错误隔离底线；有错修复后**只复验一次**，不反复跑）。
- **R8（Release+Beta）不再每批跑**：攒到本版功能全部完工 / 交付验证时**统一跑一次**（约 40-50 分钟）；R8 查的是混淆/裁剪类问题，对逻辑小修不敏感，攒批风险低。
- **纯资源任务**（如 App 图标 SVG，不碰 Kotlin 代码）零编译。

## 官方上游合并
- 官方仓库：jieapi/AiCode（GitHub），本地仓库由 tarball 导入、无 remote。
- **每批次最终编译前**，先合并官方最新更新（用户会说什么时候刷到官方更新）。
- 合并方法（2026-10-13 实操定稿）：
  1. `wget -O /tmp/upstream-main.tar.gz "https://gh-proxy.com/https://github.com/jieapi/AiCode/archive/refs/heads/main.tar.gz"` → 解压 /tmp/upstream-src → 临时 `git init && git add -A && git commit` 得快照 commit S0。
  2. **关键**：把 S0 的树重新挂到「官方快照链」上求正确 merge base——`NEW=$(git commit-tree $(git rev-parse 'S0^{tree}') -p <上次官方快照commit> -m "upstream snapshot <date>")`（快照链锚点：首次导入根快照 7833778 → 10-11 快照 a276dc71 → 10-12 快照 13c22f1；直接 merge 无共同祖先的裸快照会 Already up to date/拒绝）。
  3. `git merge --no-commit --no-ff $NEW` → 解决冲突 → 编译验证 → `git commit` 完成 merge（本次 merge commit：a2b49d7）。
  4. 先评审官方改动面：`git diff --stat <上次快照> $NEW`（本次 90 文件 +6176/-378）。
- **迁移编号冲突规则（2026-10-13 确立）**：官方号保持原样（上游主线，避免每次合并再撞）；**我方私有迁移重编号到官方未用号**（本次：annotations 60→62、session_team 61→63，SCHEMA_VERSION=63；同时 schemas json 的 60/61 取官方版）。风险：曾在我方中间开发版执行过重编号迁移的设备会缺官方同号迁移——交付规则是「不交付中间版」，正常用户设备从低版本按序升级无碍；若真需支持该类设备需在重编号文件里做幂等补偿（SQLite 无法条件 ALTER，要另想办法）。
- 用户可能后续把数据源/仓库同步到自己的 GitHub fork（方案待定，先内置化现状保留）。

## NVIDIA 官方模型 429 限流规则
- 触发 NVIDIA 官方模型速率限制 429 时：**立即全部停止所有活动**。
- 等待 1 分钟 → 拉起连通测试：恢复正常则继续开工；未恢复则再等 1 分钟重试，循环。
- 持续 30 分钟仍未恢复才向用户报告出错。