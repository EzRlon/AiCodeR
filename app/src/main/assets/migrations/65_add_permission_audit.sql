-- 授权记录（审计）表：用户决策与系统拒绝的授权事件（保留最近 200 条）。
-- 幂等执行版见 IdempotentMigrations.Migration64To65（本文件保留作审计与编号校验）。
CREATE TABLE IF NOT EXISTS permission_audit (
    id TEXT PRIMARY KEY NOT NULL,
    timestamp INTEGER NOT NULL,
    sessionId TEXT NOT NULL,
    toolName TEXT NOT NULL,
    summary TEXT NOT NULL,
    decision TEXT NOT NULL,
    source TEXT NOT NULL,
    reason TEXT
);
CREATE INDEX IF NOT EXISTS index_permission_audit_timestamp ON permission_audit(timestamp);
