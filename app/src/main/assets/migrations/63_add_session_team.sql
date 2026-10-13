-- 注意：本文件不再直接执行——63 号迁移改由 Migration62To63（core/db/IdempotentMigrations.kt）幂等执行，
-- 以兼容曾装过旧编号 60/61 的中间开发版设备（teamName 列可能已存在）。本文件保留作 SQL 审计与编号校验。
-- 专家团·编队：会话级记录召唤的编队名（对应 teams/<name>.md），NULL 表示未使用编队
ALTER TABLE chat_sessions ADD COLUMN teamName TEXT;
