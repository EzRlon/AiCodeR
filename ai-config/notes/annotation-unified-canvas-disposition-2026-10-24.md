# 标注中心统一画布（反馈 6）· 实施与审查台账（2026-10-24）

## 交付内容（三批，均已本地提交，未 push）

| 批 | 提交 | 内容 |
|---|---|---|
| 批 1 画布合一 | `a719d6d` | 新增 `AnnotationCanvas.kt`（共享绘制层：drawMark/FitRect/手势/撤销重做/双指缩放平移下沉）+ `AnnotationCanvasScreen.kt`（全屏宿主：一条工具架，活标注态透明穿透直接圈画、定格 MediaProjection 底图精画、保存入账本）；MainActivity 全局宿主换线、抽屉入口直开画布；删除 AnnotationEditorDialog / LiveAnnotationOverlay |
| 批 2 账本图层 | `5d48cd0` | 新增 `AnnotationLedgerPanel.kt`（标注+修复账本合流、状态徽章流转、点条目载入画布补画、转入修复账本、手动新建、汇总发给 AI）；`AnnotationRepository/Dao/ViewModel` 新增 `updateMarks`（无 schema 变更、无迁移）；汇总注入接线收进画布宿主 |
| 批 3 删旧入口 | `49d8494` | 删 AnnotationHubScreen / FixLedgerScreen 与路由 `annotations`、`fixledger`；清理 7 个失效字符串；能力地图 `capabilities.md`、指南 `annotations.md`、速查 `65-capabilities.md` 同步统一画布口径 |

用户要求的核心约束已达成：进标注中心 = 一块画布 + 一条工具架，全流程同界面，无任何「点按钮跳旧界面」路径；Repository 两表与状态机、fixledger AI 回写工具未动。

## 机器发现（detekt）

- 改动文件零发现（annotation presentation + data + MainActivity 各跑一轮）。

## 单测

- 全量 1342 用例：批 1-3 期间曾出现 `ScriptsViewModelTest.refresh_dropsRunStateOfScriptsDeletedOutsideThePage` **稳定失败**（隔离复跑两次均失败）。
- 根因（已定位并修复）：`ScriptsViewModel.refresh()` 去重守卫直接丢弃后到的刷新——`run()` 收尾触发的 refresh 在途时，用户手动 refresh（脚本刚被外部删除的清理）被吞，陈旧 run-state 清不掉。属脚本模块既有竞态，机器时序变化暴露，与标注三批无关。
- 修复（未提交，随处置提交）：`previous?.join()` 排队代替直接 return，保证最后一次 refresh 结果一定落地。修复后该用例连续 3 次通过。
- 修复后全量复跑：1342 用例仅剩 `SyncEngineIgnoreTest.upload_skips_custom_ignored_paths` 失败——交接已记录的已知偶发（他用例泄漏协程异常），隔离复跑通过。实质 0 失败。

## qa 审查（子代理 4a3e83b8，BATCH 范围三批 diff）处置

- **P1-1 redo 栈污染**（新增标注后 redoStack 未清，重做会复活已删元素）→ **已修复**：AnnotationCanvas 新增 `onMarkAdded` 回调，落笔成功与文字提交两路新增后清 redoStack/clearedSnapshot。
- **P2-1 arrowStartOverride 未入 pointerInput key**（组件起点陈旧捕获窗口）→ **已修复**：加入 key。
- **P2-2 冻结态缩回 1x 后 panOffset 残留** → **已修复**：scale≤1f 时 panOffset 归零。
- **P2-3 汇总发送后不跳聊天页**（旧流程回归）→ **已修复**：MainActivity onSummaryReady 恢复 `navigate("chat")`。
- **P2-4 账本行滑开后点行误触发载入** → **已修复**：onClick 移交 SwipeToDeleteRow 统一仲裁，行内 Surface 去 clickable。
- **P2-5 返回键不感知子浮层** → **已修复**：BackHandler 分层（文字浮层 → 保存面板 → 账本 → 退出画布）。
- **P2-6 冻结态缩放后选中迷你条锚点脱节**（锚点未乘 scale/pan）→ **记录待排期**：影响面小（仅缩放状态下选中元素时迷你条位置偏差），待真机验证手势时序后与 P2-1 的真机回归一并处理。
- **P2-7 文档措辞歧义**（重定格清空对象描述不清）→ **已修复**：annotations.md 改为「已画标注保留，账本条目编辑上下文清空」。
- qa 已核无误维度：手势坐标与 graphicsLayer 局部坐标一致性、captureInFlight 无遮挡抓帧、穿透成立、宿主层级、删除文件全库零残留、fixledger 回写链路完整、能力登记口径一致。

## optimizer 审查（子代理 4bfed946，BATCH，总评风险：低）处置

- **P1 refresh 排队语义失效**（两个重叠 refresh 捕获同一 previous 并行重复扫描，注释与实现不符）→ **已修复**：改为代际去重 + `Mutex.withLock`（`refreshGeneration` 登记，锁内过期代次自行跳过），只有最新一次刷新执行扫描、永不丢弃最新请求；ScriptsViewModelTest 修复后通过。
- **P2-1 hostFit 与画布 fit 双份真值**（同尺寸恒等但改算法会静默错位；附带 select 态闭包捕获 hostFit 的旋转屏边界）→ **记待排期**：下次触碰该文件时把 fit 上提为单一来源。
- **P2-2 pointerInput key 含 marks.size**（增删即重建手势协程，微优化级）→ **记待排期**。
- **P2-3 FullScreenCapture 取帧 Thread.sleep(40) 轮询**（一次性后台线程，非持续功耗）→ **记待排期**：改 `setOnImageAvailableListener` 事件化。
- 已核查无风险：解码/落盘全在后台线程、捕获即释放无持续录屏、大位图生命周期随画布组合、draw 阶段读快照列表为预期失效驱动、SharedPreferences 仅拖动结束异步写、refresh 排队无死锁。

## 待排期汇总（来自双审 P2）

1. 冻结态缩放后选中迷你条锚点未乘 scale/pan（qa P2-6）。
2. hostFit/画布 fit 双份真值收口（optimizer P2-1）。
3. pointerInput key 去掉 marks.size（optimizer P2-2）。
4. FullScreenCapture 取帧事件化（optimizer P2-3）。
以上均带行号详见 qa/optimizer 报告正文（子代理 4a3e83b8 / 4bfed946）。

## 真机验收清单（下次交付包验证）

1. 抽屉进「标注中心」即画布（无跳页）；未定格时触摸穿透：弹窗照弹、按钮照点。
2. 选工具直接在真实界面圈画；「选择」工具点中真实组件虚线框住，可加文字/画箭头。
3. 定格：授权 → 底图出现（含已打开的弹窗）；双指缩放平移、缩回 1x 底图复位。
4. 画 A → 删除 → 画 B → 重做：B 的删除可重做，A 不复活（P1-1 回归）。
5. 「从这里画箭头」后连画两支箭头：第二支起点应为手指落点（P2-1 回归）。
6. 保存入账本：类型+说明入账；同底图可连画多条；未定格时保存有提示。
7. 账本面板：合流列表展示；点标注条目载入重绘、补画后保存为更新；状态流转；转入修复账本；手动新建修复条目；左滑删除。
8. 滑开账本行后点行：先收回不误载入（P2-4 回归）。
9. 汇总发给 AI：文本+图注入聊天输入框并跳回聊天页（P2-3 回归）；fixledger 回写推进状态。
10. 返回键：文字浮层/保存面板/账本面板逐层收起，最后才退画布（P2-5 回归）。
11. 旧入口不存在：设置/侧栏无跳转旧 Hub 或旧编辑器的路径。
