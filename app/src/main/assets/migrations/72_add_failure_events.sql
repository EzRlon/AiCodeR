-- 71 → 72：运行时失败事件（1.7 失败埋点）
-- 三类最小事件：意图纠正（用户重述/改口/重发）、任务失败（工具报错/权限阻塞）、编译失败。
-- 与 llm_call_records 互补：那张表记「每次模型调用」，本表记「一次交互里的坏信号」，
-- 用于长期量化「更准」：用户纠正率、任务失败率。
CREATE TABLE IF NOT EXISTS `failure_events` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `sessionId` TEXT,
  `kind` TEXT NOT NULL,
  `source` TEXT,
  `detail` TEXT,
  `createdAt` INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS `index_failure_events_createdAt` ON `failure_events` (`createdAt`);
CREATE INDEX IF NOT EXISTS `index_failure_events_kind` ON `failure_events` (`kind`);
