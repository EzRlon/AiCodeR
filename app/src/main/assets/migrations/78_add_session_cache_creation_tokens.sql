-- 78_add_session_cache_creation_tokens.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration77To78）
-- 会话累计缓存写入 token：与缓存读取互不重叠，均已计入 totalInputTokens。
ALTER TABLE `chat_sessions` ADD COLUMN `totalCacheCreationTokens` INTEGER NOT NULL DEFAULT 0;
