-- 75_add_session_engine_mode.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration74To75）
ALTER TABLE `chat_sessions` ADD COLUMN `engineMode` TEXT;
