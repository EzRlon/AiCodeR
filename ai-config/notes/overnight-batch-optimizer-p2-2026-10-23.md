# 夜间批次（2026-10-23）optimizer P2 待排期清单

来源：optimizer 审查（BATCH，覆盖夜间批次 6 组改动）。P0 无；P1×1（StoreInstaller commitSkillInstall 主线程内存 zip）已当批修复。以下 P2 按发版纪律不强改、记录待排期：

1. `StorageViewModel.kt:383-385` —— 推荐清除后 vacuum + refresh（全量分类扫描）+ scanRecommendations（dirSize + 容器 du）三趟重 IO；可合并 refresh 与 scanRecommendations 的重复目录遍历（cacheDir/codeCacheDir/tool-output 只走一遍）。用户触发、一次性，低存储设备上偏慢。
2. `StorageViewModel.kt:331` —— scanRecommendations 守卫未查全量 `scanning`，可能与 refresh 并发跑两套磁盘遍历；守卫补 `_state.value.scanning` 即可（一行）。
3. `StorageCleanupAdvisor.kt:241-251` —— isProtected 每条推荐重建 protectedRoots（含 filesDir.listFiles）；根列表提为方法内一次构建循环复用。微开销。
4. `TerminalKeepaliveService.kt:358`（经 StorageViewModel.loadRuntimeHealth 引用）—— getRunningServices(Int.MAX_VALUE) 已废弃且 Android 8+ 只返回本应用服务，健康页可能误报「未运行」；建议换持久化标志位或其它判活方式（正确性面，归 qa 跟进）。
5. `StoreTranslationStore.kt:78-86` —— file() 每次做 legacy 迁移探测（两次 stat）；首次成功后置内存标志位。微优化。
6. `app/build.gradle.kts:120` —— versionCode 偏移 +1 后同 commit 多次构建版本号相同（不随构建次数递增）；可接受，若需严格单调可叠加时间戳低位。
7. `compact-summary.md` —— 九段式净增约 12 行，每次压缩调用 token 增量 ~350-450；仅压缩轮触发、频次低，可接受。

附：设计笔记中「超期检查点（N 天未活跃保留近 2 份）」未实现（实施清单未含，属存储清理后续迭代项）。

# qa 报告（同批）P2 处置记录

qa：无 P0，P1×4 全部已修（存档正则有界化/推荐三类被禁区反向拦截改为按 kind 守卫/rootfs_ 前缀排除 rootfs_images/远端名称落盘安全化）。P2 逐条：

1. REA 导入文件名未排除「.」「..」→ 已修（拒绝首部点号）；静默覆盖同名样本属有意行为，保留。
2. 微压缩注释「跨轮前缀缓存字节稳定」措辞仅对已投影条目成立 → 保留原注释（描述的是投影函数确定性这一必要条件），接受措辞精度。
3. repo-map.sh ctags 字段序假设 → 已在本容器真实 tags（3.9 万符号）实测输出正确，风险解除。
4. codeintel.sh ast-grep 二进制路径假设 → 已在本容器实测 /usr/local/lib/node_modules/@ast-grep/cli-linux-arm64-gnu/ast-grep 存在，风险解除；不符时仍有降级路径。
5. callchain.sh /tmp 固定名临时文件并发覆盖 → 容器单用户，保留。
6. StoreViewModel 翻页失败无条件置错 → 沿用旧行为，保留。
7. .gitignore tags 全局匹配 → 无实害，保留。
8. 高熵计算性能面 → optimizer 已核无新增（有界量词/单遍 O(n)/1MB 上限）。
9. TerminalKeepaliveService getRunningServices 废弃 API（optimizer P2-4 同项）→ 运行健康页可能误报「未运行」，待排期换判活方式。

# optimizer 二轮（新增三批）P2 处置记录

无 P0；P1×1（工作集恢复整文件读入再截断的内存尖峰）已当批修复（fileSize 预判跳过 >1MB 文件＋readLines 逐行预算内累积）。P2：

1. 工作集恢复「构建→注入→落库」块三处近似复制 → 待排期提共享辅助函数。
2. 恢复消息跨压缩累积（旧恢复消息占 preserve-recent 预算）→ 待排期：注入前按标记剔除上一条。
3. 归档行无清理路径（每次压缩约 40KB，仅随整会话删除）→ 待排期：下次压缩顺带删上一条或纳入存储清理。
4. CheckpointDao 新查询 Kotlin 侧 distinctBy+take（千行级整载）→ 待排期 SQL 端 GROUP BY+LIMIT＋复合索引。
5. SummonTeamTool 单次调用查两次 getById → 微优化待排期。
6. localmodel.sh Ollama/模型下载无校验和（第三方代理供应链面）→ 待排期补 checksum；默认不下载故不阻塞。
7. AIChatPanel 团队选择器主线程同步扫描（非本批引入）→ 文件增多后再改异步。

# qa 二轮（新增三批）P2 处置记录

qa：无 P0，P1×4 全部已修（子代理两层禁 summon_team／Ollama 解包路径对齐校验函数／示例模型完整性预检防误删／工作集注入预算护栏含手动路径）。P2：

1. SummonTeamTool 两次 getById → 同 optimizer 二轮第 5，待排期。
2. 工作集恢复整文件读入 → 已当批修复（同 optimizer P1，fileSize 预判＋readLines 预算内累积）。
3. CheckpointDao 新查询无 SQL 端 LIMIT → 同 optimizer 二轮第 4，待排期。
4. 归档行无清理路径 → 同 optimizer 二轮第 3，待排期。
5. take(...) 按字符截断可能切在代理对中间 → 待排期（截断尾部可能一个坏字，LLM 侧无实质影响）。
6. 微压缩滑窗打断 KV 前缀缓存 → 设计权衡非 bug，已在交付说明标注。
7. providers.json localmodel 预设 tags 塞长句可能撑爆 UI 芯片 → 待排期缩短为短语。
