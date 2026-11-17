-- 70_add_session_engine.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration69To70，与 67/68/69 先例一致）
ALTER TABLE `chat_sessions` ADD COLUMN `engine` TEXT;
