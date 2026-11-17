# 发版前审查记录（2026-10-22 批次：九条反馈）

范围：悬浮窗面板重构（反馈1-8）+ 标注中心合并（反馈9）。qa（批次）与 optimizer（FULL）两路审查。

## optimizer（FULL）处置

- P0：无。
- P1×2：当批修复（见 git 提交）。
  1. AnnotationHubScreen 详情弹窗主线程全尺寸 Bitmap 解码 → 复用 core/ui/ImageDecode 降采样 + Dispatchers.Default。
  2. FloatBallOverlay 钉住判定在 delay(800) 之前，800ms 窗口内点钉住仍会被自动收起 → 判定挪到 delay 之后。
- P2 待排期（本文件归档）：
  3. FloatBallOverlay.kt:969 消息列表 itemsIndexed 缺 key+contentType（对照 AIChatPanel.kt:1871 惯例）。
  4. AnnotationHubScreen.kt:468 formatTime 每次重组新建 SimpleDateFormat → remember/DateTimeFormatter。
  5. AnnotationHubScreen.kt StatusPill 与 statusLabel 两份 when 状态映射 → 合并为 AnnotationStatus.labelRes() 扩展。
  6. StatusBubbles.kt:608 计时器 100ms 重组 → 放宽 200-300ms 或独立小作用域。
  7. FloatBallOverlay.kt:1012 animateScrollToItem 动画期间新消息插入可能越界（推断）→ 动画前重读 totalItemsCount。
  8. MainActivity.kt:885 popBackStack+delay(350) 硬编码（推断）→ 后续可用 NavBackStackEntry 生命周期回调。
  9. BrowserScreen.kt:554 空 catch → 至少补 FileLogger。

覆盖声明：本批 13 文件 100% 逐行；核心链路抽查约 15%；31 个 >800 行大文件仅特征扫描未命中，深审需另开专项。

## qa（批次）处置

- 结论：可放行，无 P0/P1。
- 已修（当批）：①钉住 800ms 竞态（delay 后复查 !pinned，与 optimizer P1-2 同源）②新消息仅在已在底部时自动滚底（回底按钮语义不冲突）③输入窗收起时空点发送改为展开输入窗 ④钉住时焦点不跳顶 ⑤docs overview.md 旧称残留 ⑥消息流缩进修正。
- 不修（有理由）：禁用态视觉线索——反馈8 就是用户拍板「蓝色空闲态箭头也要白」，加 alpha 与之矛盾；TextField 36dp 最小高度实际生效——静态无法确认，列入真机验收项。
- optimizer P1-1 主线程大图解码已修：decodeSampledBitmap（降采样+OOM 重试）+ Dispatchers.Default。
- 未当批修的 optimizer P2（第 3-9 条）已归档上方。

## 商店加载优化批次 qa 遗留 P2（2026-10-22 审查，延后处理）

1. **同 tab 双在飞请求陈旧覆盖**（StoreViewModel.kt 首页刷新守卫处）：快速「切走再切回」同一 tab 时新旧协程都在飞，旧响应后到会以旧数据覆盖新数据并重复写缓存；数据等价无实害，根治用代次号（generation counter）。
2. **翻译管线重复触发**（kickTranslation 缓存路径+网络路径各一次）：第二次取消首次任务重跑全量缓存读取与过滤；取消语义正确仅浪费，可改为 refresh 替换后只对增量条目 kick。
