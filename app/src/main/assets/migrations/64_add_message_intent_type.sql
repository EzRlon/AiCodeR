-- 消息类型标签（intent）：仅 USER 行使用，存类型键（如 bug_fix）；NULL=未分类。
-- 幂等执行版见 IdempotentMigrations.Migration63To64（本文件保留作审计与编号校验）。
ALTER TABLE agent_messages ADD COLUMN intentType TEXT
