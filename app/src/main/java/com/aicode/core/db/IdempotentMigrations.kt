package com.aicode.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.core.util.FileLogger

/**
 * 幂等版 61→62（替代 `62_add_annotations.sql` 执行）。
 *
 * 背景：本项目曾有一批「中间开发版」使用旧编号——60=annotations、61=session_team；
 * 上游合并后编号重排为 62=annotations、63=session_team（官方 60=checkpoint 指纹、61=连接排序）。
 * 曾装过中间版的设备 Room 认为 60、61 已执行，官方 60/61 的两列（fingerprint / sort_order）
 * 会缺失——在这里按列存在性补齐；对正常设备（官方 60/61 已跑过）这两步是 no-op。
 * 最终 schema 与 `app/schemas/.../63.json` 一致。
 */
internal class Migration61To62 : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `annotations` (" +
                "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`imagePath` TEXT NOT NULL, `marksJson` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        addColumnIfMissing(
            db, "checkpoint_file_snapshots", "fingerprint",
            "ALTER TABLE checkpoint_file_snapshots ADD COLUMN fingerprint TEXT"
        )
        addColumnIfMissing(
            db, "remote_connections", "sort_order",
            "ALTER TABLE remote_connections ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * 幂等版 62→63（替代 `63_add_session_team.sql` 执行）：按列存在性补 teamName。
 * 旧编号 61（session_team）已跑过的设备在此为 no-op；正常设备执行一次 ALTER。
 */
internal class Migration62To63 : Migration(62, 63) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db, "chat_sessions", "teamName",
            "ALTER TABLE chat_sessions ADD COLUMN teamName TEXT"
        )
    }
}

/**
 * 幂等版 63→64（替代 `64_add_message_intent_type.sql` 执行）：按列存在性补 intentType。
 */
internal class Migration63To64 : Migration(63, 64) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db, "agent_messages", "intentType",
            "ALTER TABLE agent_messages ADD COLUMN intentType TEXT"
        )
    }
}

/**
 * 幂等版 64→65（替代 `65_add_permission_audit.sql` 执行）：建授权记录表。
 * 纯新建表，天然可重复执行。
 */
internal class Migration64To65 : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS permission_audit (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "timestamp INTEGER NOT NULL, " +
                "sessionId TEXT NOT NULL, " +
                "toolName TEXT NOT NULL, " +
                "summary TEXT NOT NULL, " +
                "decision TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "reason TEXT)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_permission_audit_timestamp ON permission_audit(timestamp)"
        )
    }
}

/**
 * 幂等版 66→67（替代 `67_add_message_fts.sql` 执行）：建 agent_messages 的 FTS4 影子表。
 * 不能用 SQL 文件轨：SqlScriptSplitter 不识别 CREATE TRIGGER 的 BEGIN…END 块，触发器体内的分号
 * 会被当语句边界拆断，覆盖升级首启必崩。execSQL 逐条执行不受影响。
 * 表结构与触发器名、DDL 与编译时 Room 导出的 schemas/67.json 完全一致（触发器是 Room 对
 * contentEntity FTS 表的官方同步触发器，只在全新建库时由 Room 创建，旧库迁移必须显式补建）。
 */
internal class Migration66To67 : Migration(66, 67) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `agent_messages_fts` USING FTS4(`content` TEXT NOT NULL, content=`agent_messages`)"
        )
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_BEFORE_UPDATE BEFORE UPDATE ON `agent_messages` BEGIN DELETE FROM `agent_messages_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_BEFORE_DELETE BEFORE DELETE ON `agent_messages` BEGIN DELETE FROM `agent_messages_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_AFTER_UPDATE AFTER UPDATE ON `agent_messages` BEGIN INSERT INTO `agent_messages_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_agent_messages_fts_AFTER_INSERT AFTER INSERT ON `agent_messages` BEGIN INSERT INTO `agent_messages_fts`(`docid`, `content`) VALUES (NEW.`rowid`, NEW.`content`); END")
        // 存量回填不放迁移内：rebuild 全量 tokenize 大库秒级、迁移在主线程会黑屏——
        // 由 AIEditorApp 启动后在后台协程幂等执行（FTS 行数 < 消息行数即 rebuild）
    }
}

/**
 * 幂等版 67→68（替代 `68_add_session_trash.sql` 执行）：chat_sessions 加回收站标记列。
 * 可空，null = 正常会话；非 null = 入回收站的时间戳（30 天后由彻底清除流程处理）。
 */
internal class Migration67To68 : Migration(67, 68) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db,
            "chat_sessions",
            "deletedAt",
            "ALTER TABLE `chat_sessions` ADD COLUMN `deletedAt` INTEGER"
        )
    }
}

/**
 * 幂等版 68→69（替代 `69_add_fix_ledger.sql` 执行）：新建修复账本表。
 * CREATE TABLE IF NOT EXISTS 本身幂等；列集与 Room 导出的 69.json 一致。
 */
internal class Migration68To69 : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fix_ledger` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, `sourceAnnotationId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}

/**
 * 幂等版 69→70（替代 `70_add_session_engine.sql` 执行）：chat_sessions 加会话级引擎列。
 * 可空，null = 本体内置工作流；"claude_code" = CLI 桥接（引擎切换一期）。
 */
internal class Migration69To70 : Migration(69, 70) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addColumnIfMissing(
            db,
            "chat_sessions",
            "engine",
            "ALTER TABLE `chat_sessions` ADD COLUMN `engine` TEXT"
        )
    }
}

/**
 * 幂等版 70→71（替代 `71_add_memory_graph.sql` 执行）：记忆图谱 v2 节点/边两张新表。
 * CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS 本身幂等；列集与 Room 导出的 71.json 一致。
 */
internal class Migration70To71 : Migration(70, 71) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `graph_nodes` (" +
                "`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, `sourceRefId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`) )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_nodes_workspacePath` ON `graph_nodes` (`workspacePath`)" )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_nodes_workspacePath_type_sourceRefId` ON `graph_nodes` (`workspacePath`, `type`, `sourceRefId`)" )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `graph_edges` (" +
                "`id` TEXT NOT NULL, `workspacePath` TEXT NOT NULL, `fromNodeId` TEXT NOT NULL, `toNodeId` TEXT NOT NULL, " +
                "`relation` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`) )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_workspacePath` ON `graph_edges` (`workspacePath`)" )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_fromNodeId` ON `graph_edges` (`fromNodeId`)" )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_graph_edges_toNodeId` ON `graph_edges` (`toNodeId`)" )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_graph_edges_workspacePath_fromNodeId_toNodeId_relation` ON `graph_edges` (`workspacePath`, `fromNodeId`, `toNodeId`, `relation`)" )
    }
}

/** 71 → 72：运行时失败事件表（1.7 失败埋点）。全新建表，无需列存在性检查。 */
internal class Migration71To72 : Migration(71, 72) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `failure_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, " +
                "`source` TEXT, `detail` TEXT, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_failure_events_createdAt` ON `failure_events` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_failure_events_kind` ON `failure_events` (`kind`)")
    }
}

/** 列不存在才执行 DDL；检查失败按「已存在」处理并记日志（宁可 no-op，也不重复 ALTER 崩库）。 */
private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, ddl: String) {
    val exists = runCatching {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
    }.getOrElse { e ->
        FileLogger.w("MigrationLoader", "列存在性检查失败，跳过补列 $table.$column", e)
        true
    }
    if (!exists) db.execSQL(ddl)
}
