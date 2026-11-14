-- 76_add_llm_call_reasoning_wire.sql（审计留存；实际执行走 core/db/IdempotentMigrations.kt 的 Migration75To76）
-- 调用记录新增「实际发出的思考参数」与「降级前用户选的档位」：
-- 有了这两列才能在 Token 统计里分辨「选了但服务端没照做」「档位被降级」这类静默问题。
ALTER TABLE `llm_call_records` ADD COLUMN `reasoningWire` TEXT;
ALTER TABLE `llm_call_records` ADD COLUMN `reasoningClampedFrom` TEXT;
