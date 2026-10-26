# 批次 0「导入会话」qa 审查处置台账（2026-10-26）

审查人：qa 子代理（只读，diff+一跳邻居）；实施：本会话。改动提交：bdee912。
功能：会话长按菜单新增「导入会话」，SAF 选单会话包走 BackupManager 既有 prepareImport/previewImport/import 管线。

## 语义裁决落地（与主计划一致）

- 同 ID → 视为恢复，直接 upsert 覆盖（底层 chatSessionDao.upsertAll REPLACE，消息表同 REPLACE，无先清表风险——qa 已核实）。
- 同标题不同 id → 弹「覆盖（旧同名会话软删入回收站后导入）/ 另存副本（并存）」。
- 全量备份 / 加密包 / 旧格式包 → 回调 NOT_SESSION_PACKAGE，提示去「设置 → 备份与恢复」导入；加密包用 BackupCrypto 魔数 AICODEBK 前瞻判定 + openTar 报错文案兜底。

## qa 报告逐条处置

| # | 严重度 | 问题 | 处置 |
|---|---|---|---|
| P1-1 | P1 | 导入单槽位竞态：并发第二次导入覆盖 pendingSessionImportFile，导致误删对方临时文件/泄漏 | **当批修复**：新增 sessionImportMutex，importSession 全程持锁（tryLock，占用回 BUSY 提示），冲突挂起期间也持锁，confirm/cancel 结束时释放；枚举增 BUSY，MainActivity 对应 toast（chat_import_session_busy） |
| P2-2 | P2 | 覆盖路径软删 fire-and-forget：失败仍报成功；旧会话为当前会话时导入后停在新推出的空会话 | **当批修复**：deleteSession 返回 Job，`.map { it }.joinAll()` 等待软删完成再导入 |
| P2-3 | P2 | 双击「覆盖」跑两遍导入，可能把刚导入的同 ID 会话打成回收站态 | **当批修复**：confirmSessionImport 入口同步置空 pendingSessionImportFile 作重入闸；prepared 文件显式传入清理函数防漏删 |
| P2-4 | P2 | 加密包被归为 FAILED「导入会话失败」，误导重试（任务书要求归为引导去备份页） | **当批修复**：读流前 8 字节比对 AICODEBK 魔数 → NOT_SESSION_PACKAGE；旧版无魔数加密格式由 openTar 报错文案含「加密备份」兜底归类 |
| P2-5 | P2 | 冲突/同 ID 判定只看当前工作区根会话：跨工作区同名不弹窗、同 ID 跨区直接覆盖 | **记录不修**：会话列表本按工作区展示，冲突弹窗以用户可见列表为准；同 ID 覆盖是「恢复」语义本意（workspacePath 跟随包内值）。列入待排期，若日后要做全局判定再评估 |
| P2-6 | P2 | 覆盖正在流式输出的会话时，导入分页 REPLACE 与流式追加可能交错 | **记录不修**：低概率（覆盖一个正在跑的会话本身罕见），且回收站可恢复兜底；列入待排期 |

## 验证

- detekt 前置扫描（修复前后各一轮，6 文件）：0 发现。
- `:app:compileArmsoloReleaseKotlin`：通过（3m34s，走锁脚本）。
- 未做真机验证（与既往批次一致，全部完工后一次性真机验收）。

## 能力感知判断

「导入会话」为用户可见的手动操作（入口在会话长按菜单，与「导出会话」对称可见可发现），按收录口径纯用户手动操作不登记能力地图。
