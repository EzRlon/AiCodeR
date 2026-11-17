-- 71_add_memory_graph.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration70To71，与 67/68/69/70 先例一致）
CREATE TABLE IF NOT EXISTS `graph_nodes` (`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `summary` TEXT NOT NULL, `sourceRefId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE INDEX IF NOT EXISTS `index_graph_nodes_workspacePath` ON `graph_nodes` (`workspacePath`);
CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_nodes_workspacePath_type_sourceRefId` ON `graph_nodes` (`workspacePath`, `type`, `sourceRefId`);
CREATE TABLE IF NOT EXISTS `graph_edges` (`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `fromNodeId` TEXT NOT NULL, `toNodeId` TEXT NOT NULL, `relation` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE INDEX IF NOT EXISTS `index_graph_edges_workspacePath` ON `graph_edges` (`workspacePath`);
CREATE INDEX IF NOT EXISTS `index_graph_edges_fromNodeId` ON `graph_edges` (`fromNodeId`);
CREATE INDEX IF NOT EXISTS `index_graph_edges_toNodeId` ON `graph_edges` (`toNodeId`);
CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_edges_workspacePath_fromNodeId_toNodeId_relation` ON `graph_edges` (`workspacePath`, `fromNodeId`, `toNodeId`, `relation`);
