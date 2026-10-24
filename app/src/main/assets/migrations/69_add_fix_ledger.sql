-- 69_add_fix_ledger.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration68To69，与 67/68 先例一致）
CREATE TABLE IF NOT EXISTS `fix_ledger` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `note` TEXT NOT NULL, `sourceAnnotationId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`));
