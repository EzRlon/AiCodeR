-- 会话回收站：chat_sessions 增软删标记列（null = 正常；非 null = 入回收站时间戳）
-- 实际执行由 IdempotentMigrations.Migration67To68 的 addColumnIfMissing 幂等完成，本文件作审计与编号校验保留。
ALTER TABLE chat_sessions ADD COLUMN deletedAt INTEGER;
