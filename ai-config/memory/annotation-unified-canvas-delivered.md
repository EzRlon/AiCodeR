---
name: annotation-unified-canvas-delivered
description: 反馈6统一画布已交付（621eb71，未真机验收）；ScriptsViewModel refresh 竞态坑修复记录；P2 待排期清单位置
---
# 标注中心统一画布（反馈 6）交付状态（2026-10-24）

- 三批实施 + 双审处置全部本地提交至 `621eb71`（a719d6d 画布合一 / 5d48cd0 账本图层 / 49d8494 删旧入口 / 621eb71 双审处置），未 push。待真机验收，清单在 `ai-config/notes/annotation-unified-canvas-disposition-2026-10-24.md`。
- 架构：`AnnotationCanvasScreen`（MainActivity 全局宿主，非路由）+ `AnnotationCanvas`（共享绘制层）+ `AnnotationLedgerPanel`（标注+修复账本合流）；Repository 新增 `updateMarks`（无迁移）；旧 Hub/编辑器/活标注层/FixLedgerScreen 已删。
- 踩坑（已验证修复）：`ScriptsViewModel.refresh()` 原去重守卫 `if (refreshJob?.isActive == true) return` 会**吞掉后到的刷新**——run() 收尾触发的刷新在途时，用户手动刷新被丢，陈旧 run-state 清不掉（ScriptsViewModelTest 稳定失败暴露）。修复 = 代际去重（refreshGeneration）+ Mutex.withLock，锁内过期代次跳过。教训：**「去重」若用直接 return 实现，等于丢弃最新请求；重叠调用应保证最新请求一定执行**。
- 双审 P2 待排期（4 项，带行号见台账）：迷你条锚点未乘 scale/pan、hostFit 双份真值、pointerInput key 含 marks.size、FullScreenCapture 轮询取帧。
- 验证基线：全量 1342 用例实质 0 失败（SyncEngineIgnoreTest 一个已知偶发项隔离复跑通过）；detekt 零发现。