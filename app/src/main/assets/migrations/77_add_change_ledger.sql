-- 77_add_change_ledger.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration76To77）
-- 改动账本：跨会话记录「改了什么、为什么、怎么验的、现在什么状态」，按工作区隔离。
-- 与 fix_ledger 分工：那张只记来自标注的修复，本表覆盖 feat/fix/docs 等全部改动。
CREATE TABLE IF NOT EXISTS `change_ledger` (
    `id` TEXT NOT NULL,
    `workspacePath` TEXT NOT NULL,
    `title` TEXT NOT NULL,
    `kind` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `humanSummary` TEXT NOT NULL,
    `agentDetail` TEXT NOT NULL,
    `evidence` TEXT NOT NULL,
    `filesJson` TEXT NOT NULL,
    `sessionId` TEXT,
    `sourceAnnotationId` TEXT,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    `verifiedAt` INTEGER,
    PRIMARY KEY(`id`)
);
CREATE INDEX IF NOT EXISTS `index_change_ledger_workspacePath` ON `change_ledger` (`workspacePath`);
CREATE INDEX IF NOT EXISTS `index_change_ledger_updatedAt` ON `change_ledger` (`updatedAt`);
