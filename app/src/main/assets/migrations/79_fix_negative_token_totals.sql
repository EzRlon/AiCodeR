-- 79_fix_negative_token_totals.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration78To79）
-- 历史 Int 溢出导致会话累计 token 变成负数（记忆图谱显示 -195776062）。
-- 实体侧已改用 Long 累加；这里把已被写坏的负值归零。
UPDATE `chat_sessions` SET
    `totalInputTokens` = MAX(`totalInputTokens`, 0),
    `totalOutputTokens` = MAX(`totalOutputTokens`, 0),
    `totalCachedInputTokens` = MAX(`totalCachedInputTokens`, 0),
    `totalCacheCreationTokens` = MAX(`totalCacheCreationTokens`, 0);
