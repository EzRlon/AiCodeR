-- 73_add_graph_temporal.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration72To73）
ALTER TABLE `graph_nodes` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'PROJECT';
ALTER TABLE `graph_nodes` ADD COLUMN `validFrom` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `graph_nodes` ADD COLUMN `invalidAt` INTEGER;
ALTER TABLE `graph_nodes` ADD COLUMN `sourceSessionId` TEXT;
CREATE INDEX IF NOT EXISTS `index_graph_nodes_invalidAt` ON `graph_nodes` (`invalidAt`);
ALTER TABLE `graph_edges` ADD COLUMN `validAt` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `graph_edges` ADD COLUMN `invalidAt` INTEGER;
CREATE INDEX IF NOT EXISTS `index_graph_edges_invalidAt` ON `graph_edges` (`invalidAt`);
