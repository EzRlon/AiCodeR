-- 74_add_session_cached_tokens.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration73To74）
-- 会话累计缓存命中输入：是 totalInputTokens 的子集，只用于展示缓存命中率，不参与总消耗。
ALTER TABLE `chat_sessions` ADD COLUMN `totalCachedInputTokens` INTEGER NOT NULL DEFAULT 0;

-- 回填历史 Claude 会话：旧桥接的 inputTokens 只有未命中缓存部分，缓存命中存在消息行里。
-- 合并成统一口径后，老会话的会话总量与逐轮统计才能与内置引擎比较。
UPDATE `agent_messages` SET `inputTokens` = `inputTokens` + `cachedInputTokens`
WHERE `cachedInputTokens` > 0
  AND `sessionId` IN (SELECT `id` FROM `chat_sessions` WHERE `engine` = 'claude_code');

UPDATE `chat_sessions` SET
  `totalCachedInputTokens` = COALESCE((SELECT SUM(m.`cachedInputTokens`) FROM `agent_messages` m
                                       WHERE m.`sessionId` = `chat_sessions`.`id`), 0),
  `totalInputTokens` = `totalInputTokens` + COALESCE((SELECT SUM(m.`cachedInputTokens`) FROM `agent_messages` m
                                                     WHERE m.`sessionId` = `chat_sessions`.`id`), 0)
WHERE `engine` = 'claude_code';
