-- recall 工具：agent_messages 的 FTS4 外部内容表（66→67）。
-- ⚠️ 本文件仅作编号校验与审计，实际执行由 IdempotentMigrations.kt 的 Migration66To67 完成：
-- SqlScriptSplitter 不识别 CREATE TRIGGER 的 BEGIN…END 块（体内分号会被当语句边界拆断，
-- 覆盖升级首启必崩），触发器只能走 execSQL。DDL 与触发器名与 schemas/67.json 完全一致。
CREATE VIRTUAL TABLE IF NOT EXISTS `agent_messages_fts` USING FTS4(`content` TEXT NOT NULL, content=`agent_messages`);

CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `agent_messages` BEGIN DELETE FROM `agent_messages_fts` WHERE `docid`=OLD.`rowid`; END;
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_BEFORE_DELETE BEFORE DELETE ON `agent_messages` BEGIN DELETE FROM `agent_messages_fts` WHERE `docid`=OLD.`rowid`; END;
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_AFTER_UPDATE AFTER UPDATE ON `agent_messages` BEGIN INSERT INTO `agent_messages_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END;
CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_AFTER_INSERT AFTER INSERT ON `agent_messages` BEGIN INSERT INTO `agent_messages_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END;

-- 存量消息全量回填（FTS4 外部内容表专用 rebuild：按 content 表重建整个索引）：
-- ⚠️ 不在此执行（rebuild 全量 tokenize 大库秒级、迁移在主线程会黑屏）——由 AIEditorApp
-- 启动后在后台协程幂等执行（FTS 行数 < 消息行数即 rebuild）。
